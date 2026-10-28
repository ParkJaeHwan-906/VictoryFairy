"""quiz-candidates 결정적 검증 스크립트 — LLM 미사용 2차 방어선.

퀴즈 생성기 routine(LLM)이 만든 quiz-candidates JSON(스펙 4.3 계약)을 S3
업로드 직전에 마지막으로 게이트 검사한다. 같은 입력이면 항상 같은 결과가
나오는 순수 검사만 수행하며(랜덤·시각 의존 없음), LLM 기반 1차 검증 패스와
독립적인 별개 방어선이다.

`question-gen/config/question-templates.yaml` 카탈로그를 로드해 templateId가
실재하는지, 활성 상태인지, kind/format이 선언과 일치하는지 확인하고,
`question-gen/config/banned-topics.txt`로 안전 규칙(스펙 4.2, 사건사고·법적
논란·사생활·건강 소재 금지)의 결정적 부분(키워드 부분 문자열 매칭)을 검사한다.
로스터 기반 템플릿(CAREER_PATH·MEME_ORIGIN·RELATION_LINK)이 경기 유닛에
섞여 들어가 팀 특화 유닛과 교차 중복을 일으키는 것도 막는다(check 10).
PREDICTION 지표가 `BATTER_HIT_IN_INNING`(이닝 트리거 기반 실시간 정산)이면
settlement.inning·half도 형식에 맞는지 검사한다(check 11).
`TEAMMATE_STAT_COMPARE`(비교형 PLAYER scope, 2026-10-06 신설)는 보기에 실제
수치가 노출되면 비교형 설계(암기형 유출 방지)가 무너지므로 그걸 하드로
막고, 비교 대상 playerIds가 정확히 2명인지·evidence가 실제 집계 수치를
인용하는지도 검사한다(check 12).

stdlib + PyYAML만 사용(boto3 금지).
"""

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml

# ── 상수 ────────────────────────────────────────────────

#: 포인트 기준표. pointReward는 difficulty로 결정된다(check 6). 값의 정본은
#: `question-gen/config/scoring.yaml` 하나뿐이며 여기서는 숫자를 적지 않는다 —
#: import 시 그 파일에서 채운다(`--scoring`으로 다른 파일 지정 가능).
POINTS: dict = {}

#: BQ 기준표. bqReward도 pointReward와 똑같이 difficulty로 결정된다(check 6).
#: 정본·주입 방식 모두 POINTS와 같다(scoring.yaml의 `bq` 표).
BQ: dict = {}

#: BE가 RDB로 정산 가능한 예측 지표만 허용(check 4). 스펙 6. 리스크 참조 —
#: 선수 퍼포먼스 등 상세 스탯 예측은 정산 불가라 카탈로그에도 없음.
#: `BATTER_HIT_IN_INNING`(2026-10-04 추가)은 예외다 — py-collector가 S3
#: inning-events/*에 적재하는 이닝별 안타 사실로 BE가 나중에 정산하는 이닝
#: 트리거 기반 지표라, 나머지 넷(경기 전 확정 가능한 승패/점수)과 달리 settlement에
#: inning·half가 추가로 필요하다(check 11).
METRICS = {"WIN_TEAM", "TOTAL_RUNS", "SCORE_GAP", "PITCHER_DECISION",
           "BATTER_HIT_IN_INNING"}

#: BATTER_HIT_IN_INNING 전용 settlement.inning 상한(check 11). 정본은 py-collector
#: `kbo_collector/game_records.py`의 `INNING_MAX = 11`(연장 포함 KBO 경기 이닝
#: 파서 상한)이고 여기는 그 사본이다 — TEAM_CODE_NAMES와 같은 하드코드 미러 패턴.
#: 그쪽 값이 바뀌면 여기도 맞출 것.
INNING_MAX = 11

#: BATTER_HIT_IN_INNING 전용 settlement.half 허용값(check 11). BE
#: `com.skhynix.domain.game.entity.InningHalf`의 enum 이름 문자열이다(TOP=초,
#: BOTTOM=말). DB 컬럼(`quiz.settlement_half`)에는 ordinal(0/1)로 저장되지만,
#: candidate JSON 계약은 **이름 문자열**이다 — `QuizCandidate.Settlement.half()`를
#: BE가 `InningHalf.valueOf(...)`로 역직렬화한다(VictoryFairy_BE 커밋 3e2f30f0
#: 실측). 0/1 정수를 그대로 쓰면 그 역직렬화가 깨진다 — 흔한 오해이므로 명시한다.
INNING_HALVES = {"TOP", "BOTTOM"}

#: format별 필수 option 개수(check 2). 주관식은 계약에 없다.
FORMAT_OPTION_COUNTS = {"OX": 2, "BINARY": 2, "MULTI4": 4}

#: quiz-candidates 계약(스펙 4.3)의 필수 필드(check 1). answer/evidence/
#: settlement/gameId는 kind에 따라 있거나 없어야 하므로 여기 포함하지 않는다
#: (check 3/4에서 별도 검사). subject(주제 축, v2)는 optional이라 여기 넣지
#: 않는다 — 구계약(v1) 후보와 공존해야 하므로 부재는 candidate_warnings()의
#: 경고로만 알리고, '있는데 틀린' 것만 check 9가 하드 실패로 잡는다.
REQUIRED_FIELDS = (
    "quizId", "kind", "type", "templateId", "format", "question",
    "options", "difficulty", "pointReward", "bqReward", "status",
    "createdAt", "deadlineAt", "createdBy",
)

#: 실존 KBO 10개 구단 코드 → 팀 이름(check 9). 정본은 BE 리포의 teams 시드
#: (VictoryFairy_BE `infra/sql/teams-init.sql` — py-collector
#: `kbo_collector/dimensions.py`의 TEAMS와 같은 축)이고 여기는 그 사본을
#: 하드코드한 것이다. 구단 증감·코드 변경 시 BE 시드와 함께 맞출 것.
#: top-level `teamCodes`(귀속 축)와 `subject.teamCodes`(주제 축) 둘 다 이
#: 화이트리스트로 검사하고, 이름 쪽은 정답 유출 검사(check 9)에 쓴다.
TEAM_CODE_NAMES = {
    "OB": "두산", "LG": "LG", "SS": "삼성", "KT": "KT", "WO": "키움",
    "HT": "KIA", "HH": "한화", "NC": "NC", "LT": "롯데", "SK": "SSG",
}

#: subject.scope 허용값(check 9, 스펙 4.3 v2). scope는 문항별 판단이 아니라
#: 카탈로그 템플릿의 `subjectScope` 선언을 그대로 따라야 한다.
SUBJECT_SCOPES = {"PLAYER", "TEAM", "MATCHUP", "LEAGUE", "GAME"}

#: 경기 유닛(gameId 있음)에서 금지하는 템플릿(check 10, 2026-10-03 신설).
#: 2026-10-03 실행에서 경기 유닛과 팀 특화 유닛이 서로 모른 채 같은 선수의
#: CAREER_PATH·MEME_ORIGIN·RELATION_LINK를 독립적으로 뽑아 39건 중 28건이
#: 교차 중복으로 폐기됐다(`wiki/_meta/casebook/bad.md` #102). 이 세 템플릿은
#: 로스터 기반이라 경기 당일 여부와 무관하고(perTeam이 이미 매일 전담), 경기
#: 유닛에는 애초에 재료 자격이 없어야 한다 — 프롬프트 설명만으로는 다시 샐 수
#: 있어 결정적 게이트로 못박는다. 소속은 `subjectScope`(PLAYER)로도 추정
#: 가능하지만, 이 세 템플릿만 명시적으로 금지하는 것이 과거 이력과 더 안전하게
#: 호환된다(RECORD_OX 등 다른 PLAYER scope 템플릿까지 건드리지 않음).
#: INTERNATIONAL_CALLUP·FRANCHISE_RECORD(2026-10-08 신설)도 같은 이유로
#: 추가한다 — 위키 '커리어 이력' 섹션 기반이라 똑같이 로스터 기반·경기 당일
#: 무관이다(subjectScope는 TEAM이라 PLAYER 추정 휴리스틱으로도 안 걸려서,
#: 명시 등재가 더더욱 필요하다).
GAME_UNIT_FORBIDDEN_TEMPLATES = {
    "CAREER_PATH", "MEME_ORIGIN", "RELATION_LINK",
    "INTERNATIONAL_CALLUP", "FRANCHISE_RECORD",
}

#: TEAMMATE_STAT_COMPARE 전용(check 12, 2026-10-06 신설). 비교형 설계 보호 —
#: 보기 문면에 실제 수치(타율·ERA 등)가 노출되면 "암기형 유출"이 된다. 두
#: 선수 이름만 보기로 허용한다.
TEAMMATE_STAT_COMPARE_ID = "TEAMMATE_STAT_COMPARE"
_HAS_DIGIT_RE = re.compile(r"\d")
_NUMBER_RE = re.compile(r"\d+(?:\.\d+)?")


# ── 로더 ────────────────────────────────────────────────

def load_scoring(path) -> tuple:
    """`(points, bq)`를 낸다. 파일이 없거나 둘 중 하나가 비면 예외를 낸다 —
    기본값으로 조용히 되돌아가면 정본 파일과 실제 검사 기준이 갈라지기
    때문이다(fail-closed).

    난이도 키 집합이 두 표에서 다르면 그것도 예외다. check 6은 difficulty 하나로
    두 표를 함께 조회하므로, 한쪽에만 난이도가 있으면 그 난이도 후보에서
    KeyError로 죽거나(조회 실패) 검사가 조용히 빠진다."""
    with open(path, "r", encoding="utf-8") as f:
        doc = yaml.safe_load(f) or {}
    points = doc.get("points") or {}
    bq = doc.get("bq") or {}
    if not points:
        raise ValueError(f"scoring.yaml에 points가 비어 있음: {path}")
    if not bq:
        raise ValueError(f"scoring.yaml에 bq가 비어 있음: {path}")
    points = {str(k): int(v) for k, v in points.items()}
    bq = {str(k): int(v) for k, v in bq.items()}
    if set(points) != set(bq):
        raise ValueError(
            f"scoring.yaml의 points·bq 난이도 키가 불일치: "
            f"points={sorted(points)}, bq={sorted(bq)} ({path})")
    return points, bq


def load_catalog(path) -> dict:
    """항목에 `enabled` 키가 없으면 기본값 True를 채운다(카탈로그 주석 규칙)."""
    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or []
    catalog = {}
    for entry in raw:
        entry = dict(entry)
        entry.setdefault("enabled", True)
        catalog[entry["id"]] = entry
    return catalog


def load_banned(path) -> list:
    keywords = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            keywords.append(line)
    return keywords


# ── 검사 ────────────────────────────────────────────────

def _kst_date_deadline_bounds(yyyymmdd: str):
    """이 경계는 "그 날짜에 열리는 경기의 마감이 상식적으로 그 날 안에 있는가"만
    보는 보수적 sanity 검사용이다 — candidate JSON에는 경기 실제 시작시각이 없어
    (game_schedule payload.startTime과 대조하려면 별도 파일이 필요) 정확한 "시작
    2시간 전" 대조는 여기서 하지 않는다(generation-rules.md의 deadlineAt 산정
    규칙과 LLM 검증 패스가 그 정밀 검증을 맡는다)."""
    d = datetime.strptime(yyyymmdd, "%Y%m%d").replace(tzinfo=timezone.utc)
    lo = d - timedelta(hours=9)                             # 그날 00:00 KST → UTC
    hi = d + timedelta(hours=14, minutes=59, seconds=59)     # 그날 23:59:59 KST → UTC
    return lo.strftime("%Y-%m-%dT%H:%M:%SZ"), hi.strftime("%Y-%m-%dT%H:%M:%SZ")


def validate_candidate(c: dict, catalog: dict, banned: list) -> list:
    """각 필드가 아예 없거나 타입이 다른 경우에도 예외를 던지지 않고 위반으로
    기록한 뒤 나머지 검사를 계속한다(부분 실패로 전체 검사가 죽지 않도록)."""
    violations = []

    # 1. 필수 필드 존재
    for field in REQUIRED_FIELDS:
        if field not in c or c[field] is None:
            violations.append(f"필수 필드 누락: {field}")

    # 2. format·options 규칙
    fmt = c.get("format")
    options = c.get("options")
    if not isinstance(options, list):
        options = []
    if fmt not in FORMAT_OPTION_COUNTS:
        violations.append(f"format 값이 올바르지 않음(OX/BINARY/MULTI4만 허용): {fmt}")
    else:
        expected_count = FORMAT_OPTION_COUNTS[fmt]
        if len(options) != expected_count:
            violations.append(
                f"options 개수가 format({fmt})과 불일치: 기대 {expected_count}개, "
                f"실제 {len(options)}개")
        expected_ids = [chr(ord("A") + i) for i in range(len(options))]
        actual_ids = [o.get("id") if isinstance(o, dict) else None for o in options]
        if actual_ids != expected_ids:
            violations.append(f"option id가 A부터 순서대로 유니크하지 않음: {actual_ids}")
    for o in options:
        text = o.get("text") if isinstance(o, dict) else None
        if not (text or "").strip():
            oid = o.get("id") if isinstance(o, dict) else "?"
            violations.append(f"option text가 비어있음: id={oid}")

    option_ids = {o.get("id") for o in options if isinstance(o, dict)}

    # 3/4. kind별 answer/evidence/settlement 규칙
    kind = c.get("kind")
    if kind == "KNOWLEDGE":
        answer = c.get("answer")
        if answer not in option_ids:
            violations.append(f"KNOWLEDGE 문항은 answer가 option id 중 하나여야 함: {answer}")
        evidence = c.get("evidence")
        source = evidence.get("source") if isinstance(evidence, dict) else None
        if not (source or "").strip():
            violations.append("KNOWLEDGE 문항은 evidence.source가 비어있지 않아야 함")
        if c.get("settlement") is not None:
            violations.append("KNOWLEDGE 문항은 settlement이 None이어야 함")
    elif kind == "PREDICTION":
        settlement = c.get("settlement")
        game_id = settlement.get("gameId") if isinstance(settlement, dict) else None
        if not (game_id or "").strip():
            violations.append("PREDICTION 문항은 settlement.gameId가 존재해야 함")
        metric = settlement.get("metric") if isinstance(settlement, dict) else None
        if metric not in METRICS:
            violations.append(f"PREDICTION 문항의 정산 지표가 허용 목록에 없음: {metric}")
        if c.get("answer") is not None or c.get("evidence") is not None:
            violations.append("PREDICTION 문항은 answer·evidence가 모두 None이어야 함")

        # 8(a). top-level gameId와 settlement.gameId 일치
        top_game_id = c.get("gameId")
        if isinstance(game_id, str) and game_id.strip() and top_game_id != game_id:
            violations.append(
                f"PREDICTION 문항의 gameId가 settlement.gameId와 불일치: "
                f"gameId={top_game_id!r}, settlement.gameId={game_id!r}")

        # 8(b). deadlineAt 보수적 sanity 검사 — gameId 날짜(KST)의 하루 범위 안인지만
        # 본다. 정확한 "경기 시작 2시간 전" 대조는 candidate에 시작시각이 없어
        # 여기서 하지 않는다(LLM 검증 패스 몫, _kst_date_deadline_bounds 독스트링 참고).
        deadline_at = c.get("deadlineAt")
        if (isinstance(game_id, str) and len(game_id) >= 8
                and isinstance(deadline_at, str) and deadline_at):
            gid_date = game_id[:8]
            try:
                lo, hi = _kst_date_deadline_bounds(gid_date)
            except ValueError:
                violations.append(f"settlement.gameId의 날짜 형식이 올바르지 않음: {game_id}")
            else:
                if not (lo <= deadline_at <= hi):
                    violations.append(
                        f"PREDICTION 문항의 deadlineAt이 gameId 날짜({gid_date})의 KST "
                        f"유효 범위를 벗어남: deadlineAt={deadline_at}, 허용 범위 [{lo}, {hi}]")
    else:
        # 필수 필드 검사(check 1)로 kind 누락은 잡히지만, 잘못된 값(오탈자 등)
        # 은 3/4 어느 분기에도 안 걸려 조용히 통과할 수 있어 안전장치로 추가.
        violations.append(f"kind 값이 KNOWLEDGE/PREDICTION이 아님: {kind}")

    # 5. templateId 카탈로그 검증
    template_id = c.get("templateId")
    if template_id not in catalog:
        violations.append(f"templateId가 카탈로그에 없음: {template_id}")
    else:
        template = catalog[template_id]
        if not template.get("enabled", True):
            violations.append(f"templateId가 비활성 상태(enabled: false)임: {template_id}")
        if kind != template.get("kind"):
            violations.append(
                f"kind가 카탈로그 템플릿과 불일치: candidate={kind}, "
                f"catalog={template.get('kind')}")
        if fmt != template.get("format"):
            violations.append(
                f"format이 카탈로그 템플릿과 불일치: candidate={fmt}, "
                f"catalog={template.get('format')}")

    # 6. 보상 기준표 일치(포인트·BQ)
    difficulty = c.get("difficulty")
    point_reward = c.get("pointReward")
    bq_reward = c.get("bqReward")
    if difficulty not in POINTS:
        violations.append(f"difficulty 값이 올바르지 않음: {difficulty}")
    else:
        if point_reward != POINTS[difficulty]:
            violations.append(
                f"pointReward가 difficulty({difficulty}) 기준 포인트와 불일치: "
                f"기대 {POINTS[difficulty]}, 실제 {point_reward}")
        # BQ는 POINTS와 난이도 키 집합이 같음이 load_scoring에서 보장된다.
        if bq_reward != BQ[difficulty]:
            violations.append(
                f"bqReward가 difficulty({difficulty}) 기준 BQ와 불일치: "
                f"기대 {BQ[difficulty]}, 실제 {bq_reward}")

    # 7. 금지 소재 키워드(부분 문자열 매칭)
    texts = [c.get("question") or ""]
    texts += [o.get("text") or "" for o in options if isinstance(o, dict)]
    for keyword in banned:
        for text in texts:
            if keyword in text:
                violations.append(f"금지 소재 키워드 포함: '{keyword}' in \"{text}\"")
                break

    # 9. 팀코드 화이트리스트 + subject(주제 축, 스펙 4.3 v2)
    # top-level teamCodes(귀속 축 — 이 문항을 어느 팀 팬에게 보여줄지)는 subject와
    # 무관하게 화이트리스트만 검사한다(구계약 후보도 [] 또는 실존 코드라 통과).
    top_team_codes = c.get("teamCodes")
    if top_team_codes is not None and not isinstance(top_team_codes, list):
        violations.append(f"teamCodes(귀속 축)는 배열이어야 함: {top_team_codes!r}")
    for code in top_team_codes if isinstance(top_team_codes, list) else []:
        if code not in TEAM_CODE_NAMES:
            violations.append(f"teamCodes(귀속 축)에 실존하지 않는 구단 코드: {code!r}")

    subject = c.get("subject")
    if subject is not None and not isinstance(subject, dict):
        violations.append(f"subject는 object(dict)여야 함: {subject!r}")
    elif isinstance(subject, dict):
        scope = subject.get("scope")
        if scope not in SUBJECT_SCOPES:
            violations.append(
                f"subject.scope 값이 올바르지 않음(PLAYER/TEAM/MATCHUP/LEAGUE/GAME만 "
                f"허용): {scope}")
        # scope는 템플릿 단위 선언(카탈로그 subjectScope)을 그대로 따라야 한다 —
        # 문항별 LLM 재량 금지. 카탈로그에 선언이 없는 템플릿은 대조 생략(이행기).
        if template_id in catalog:
            declared = catalog[template_id].get("subjectScope")
            if declared and scope != declared:
                violations.append(
                    f"subject.scope가 카탈로그 subjectScope 선언과 불일치: "
                    f"candidate={scope}, catalog={declared}")

        player_ids = subject.get("playerIds")
        if player_ids is None:
            player_ids = []
        if not isinstance(player_ids, list) or any(
                isinstance(p, bool) or not isinstance(p, int) for p in player_ids):
            violations.append(
                f"subject.playerIds는 KBO playerId 정수 배열이어야 함: {player_ids!r}")
            player_ids = []
        team_codes = subject.get("teamCodes")
        if team_codes is None:
            team_codes = []
        if not isinstance(team_codes, list):
            violations.append(f"subject.teamCodes는 배열이어야 함: {team_codes!r}")
            team_codes = []
        for code in team_codes:
            if code not in TEAM_CODE_NAMES:
                violations.append(f"subject.teamCodes에 실존하지 않는 구단 코드: {code!r}")

        # scope별 카디널리티(스펙 4.3 v2 — subject에는 문제가 '전제'하는 엔티티만)
        subj_game_id = subject.get("gameId")
        if scope == "PLAYER" and len(player_ids) < 1:
            violations.append("subject.scope=PLAYER면 playerIds가 1개 이상이어야 함")
        elif scope == "TEAM" and (len(team_codes) != 1 or player_ids):
            violations.append(
                f"subject.scope=TEAM이면 teamCodes 정확히 1개·playerIds는 비어야 함: "
                f"teamCodes={team_codes!r}, playerIds={player_ids!r}")
        elif scope == "MATCHUP" and len(team_codes) != 2:
            violations.append(
                f"subject.scope=MATCHUP이면 teamCodes가 정확히 2개여야 함: {team_codes!r}")
        elif scope == "LEAGUE" and (player_ids or team_codes):
            violations.append(
                f"subject.scope=LEAGUE면 playerIds·teamCodes가 모두 비어야 함: "
                f"playerIds={player_ids!r}, teamCodes={team_codes!r}")
        if scope == "GAME":
            if not (isinstance(subj_game_id, str) and subj_game_id.strip()):
                violations.append("subject.scope=GAME이면 subject.gameId가 필수임")
        elif scope in SUBJECT_SCOPES and subj_game_id is not None:
            violations.append(
                f"subject.gameId는 scope=GAME일 때만 채운다(그 외 null): scope={scope}")

        # 정답 유출 결정적 검사(팀 한정): subject는 문제가 '전제'하는 엔티티만
        # 담아야 하므로, 전제로 선언된 팀 이름이 정답 보기 문면에 그대로 있으면
        # 주제 메타데이터가 정답을 시사한 것이다. 오답 보기는 허용(전제 팀이
        # 오답 후보로 등장하는 건 정상 — 예: 승리투수 문제의 양 팀 선수 보기).
        # PREDICTION은 answer가 None이라 자연히 검사 대상이 아니다.
        answer_text = next(
            (o.get("text") or "" for o in options
             if isinstance(o, dict) and o.get("id") == c.get("answer")), "")
        for code in team_codes:
            name = TEAM_CODE_NAMES.get(code)
            if name and name in answer_text:
                violations.append(
                    f"subject.teamCodes의 팀({code}={name})이 정답 보기 문면에 등장"
                    f"(정답 유출): \"{answer_text}\"")

    # 10. 경기 유닛 전용 템플릿 금지(교차 중복 방지, 2026-10-03 신설)
    if c.get("gameId") is not None and template_id in GAME_UNIT_FORBIDDEN_TEMPLATES:
        violations.append(
            f"{template_id}는 경기 유닛(gameId 있음)에 쓸 수 없음 — 로스터 기반이라 "
            f"팀 특화 유닛(perTeam) 전용(교차 중복 방지, check 10)")

    # 11. PREDICTION BATTER_HIT_IN_INNING 전용 — inning·half 필수(2026-10-04 신설)
    # BE QuizIngestService.ingestPrediction()이 이 metric일 때만 settlement.inning/
    # half로 Quiz.settlementInning/settlementHalf를 채운다(그 외 metric은 이
    # 필드를 전혀 보지 않으므로 검사하지 않는다 — 기존 WIN_TEAM 등 후보는 이 필드가
    # 없어도/있어도 그대로 통과한다, 기존 동작 유지). kind!="PREDICTION"이거나
    # settlement가 dict가 아니면 이미 위 3/4에서 걸리므로 여기선 재검사하지 않는다.
    settlement = c.get("settlement")
    if (kind == "PREDICTION" and isinstance(settlement, dict)
            and settlement.get("metric") == "BATTER_HIT_IN_INNING"):
        inning = settlement.get("inning")
        if not (isinstance(inning, int) and not isinstance(inning, bool)
                and 1 <= inning <= INNING_MAX):
            violations.append(
                f"BATTER_HIT_IN_INNING 문항은 settlement.inning이 1~{INNING_MAX} "
                f"사이 정수여야 함: {inning!r}")
        half = settlement.get("half")
        if half not in INNING_HALVES:
            violations.append(
                f"BATTER_HIT_IN_INNING 문항은 settlement.half가 "
                f"{sorted(INNING_HALVES)!r} 중 하나여야 함(BE InningHalf#name() "
                f"문자열 — 0/1 정수가 아님): {half!r}")

    # 12. TEAMMATE_STAT_COMPARE 전용 — 비교형 설계 보호(2026-10-06 신설)
    # 보기 문면에 실제 수치가 노출되면 비교형 설계(암기형 유출 방지, 카탈로그
    # intent)가 무너진다. subject.playerIds도 "정확히 2명"이어야 한다(비교
    # 대상 둘 — 일반 PLAYER scope 하한인 "1개 이상"보다 이 템플릿만 더
    # 엄격하다, ROUTINE.md §3-2). evidence는 기존 계약대로 정답 선수 한 명의
    # envelope만 가리키지만(source 1개·quote 1개, check_evidence/_resolve
    # 재사용), 그 quote가 실제 집계 수치(숫자 2개 이상 — 그 선수의 결정적으로
    # 렌더된 기록 문장에는 경기수·안타·타율 등 여러 수치가 이미 들어있다)를
    # 인용하는지는 검사한다 — 뭉뚱그린 서술만으로는 원문 대조의 의미가 없다.
    # 상대 선수와의 비교 정확성 자체는 이 결정적 검사 범위 밖이고 검증 패스
    # (6단계)의 책임이다(ROUTINE.md §3-2 참고).
    if template_id == TEAMMATE_STAT_COMPARE_ID:
        for o in options:
            text = (o.get("text") if isinstance(o, dict) else None) or ""
            if _HAS_DIGIT_RE.search(text):
                violations.append(
                    f"{TEAMMATE_STAT_COMPARE_ID}는 보기에 수치를 노출할 수 없음"
                    f"(비교형 설계 — 선수 이름만 허용): \"{text}\"")
        if isinstance(subject, dict) and subject.get("scope") == "PLAYER":
            pids = subject.get("playerIds")
            if not (isinstance(pids, list) and len(pids) == 2):
                violations.append(
                    f"{TEAMMATE_STAT_COMPARE_ID}는 subject.playerIds가 정확히 "
                    f"2명(비교 대상)이어야 함: {pids!r}")
        if isinstance(top_team_codes, list) and len(top_team_codes) != 1:
            violations.append(
                f"{TEAMMATE_STAT_COMPARE_ID}는 top-level teamCodes가 정확히 1개 "
                f"팀이어야 함(같은 팀 두 선수 비교, 다른 팀 섞기 금지, §3): "
                f"{top_team_codes!r}")
        evidence = c.get("evidence")
        quote = evidence.get("quote") if isinstance(evidence, dict) else None
        numbers = _NUMBER_RE.findall(quote or "")
        if len(numbers) < 2:
            violations.append(
                f"{TEAMMATE_STAT_COMPARE_ID}는 evidence.quote가 실제 집계 수치를 "
                f"인용해야 함(숫자 2개 이상 필요, 실제 {len(numbers)}개): {quote!r}")

    return violations


def candidate_warnings(c: dict, catalog: dict) -> list:
    """subject(주제 축)는 v2에서 추가된 optional 필드다 — 부재를 위반으로 만들면
    S3에 이미 쌓인 구계약(v1) 후보가 전부 죽으므로, 카탈로그 subjectScope 선언
    여부와 무관하게 부재는 경고만 출력하고 exit code에는 반영하지 않는다(이행기
    완화 — 생성 루틴이 generation-rules.md §11을 따르기 시작하면 자연히
    사라진다). subject가 '있는데' 틀린 것은 validate_candidate check 9가 하드
    실패로 잡는다."""
    warnings = []
    if c.get("subject") is None:
        template = catalog.get(c.get("templateId"))
        declared = template.get("subjectScope") if isinstance(template, dict) else None
        hint = f" (카탈로그 선언: subjectScope={declared})" if declared else ""
        warnings.append(f"subject 부재 — v2 계약은 subject 기록을 요구함{hint}")
    return warnings


# ── CLI ──────────────────────────────────────────────────

def _default_config_path(name: str) -> str:
    return str(Path(__file__).resolve().parent.parent / "config" / name)


#: 보상 기준표를 import 시점에 정본(scoring.yaml)에서 채운다 — 이 모듈을 CLI가
#: 아니라 직접 import해 쓰는 경로(테스트 등)에서도 같은 값이 보이도록.
_POINTS, _BQ = load_scoring(_default_config_path("scoring.yaml"))
POINTS.update(_POINTS)
BQ.update(_BQ)


def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="quiz-candidates JSON 디렉토리를 결정적으로 검사한다"
                    "(S3 업로드 직전 마지막 게이트).")
    p.add_argument("--dir", required=True, help="quiz-candidates *.json이 있는 디렉토리")
    p.add_argument("--catalog", default=None,
                   help="질문 템플릿 카탈로그 YAML 경로(기본: question-gen/config/"
                       "question-templates.yaml)")
    p.add_argument("--banned", default=None,
                   help="banned-topics.txt 경로(기본: question-gen/config/banned-topics.txt)")
    p.add_argument("--scoring", default=None,
                   help="보상 기준표(points·bq) YAML 경로"
                        "(기본: question-gen/config/scoring.yaml)")
    return p


def main(argv=None) -> None:
    """위반이 하나라도 있으면 `sys.exit(1)`,
    없으면 `sys.exit(0)`(routine이 이 exit code로 업로드 여부를 결정한다)."""
    args = _build_arg_parser().parse_args(argv)

    catalog_path = args.catalog or _default_config_path("question-templates.yaml")
    banned_path = args.banned or _default_config_path("banned-topics.txt")
    catalog = load_catalog(catalog_path)
    banned = load_banned(banned_path)
    if args.scoring:
        points, bq = load_scoring(args.scoring)
        POINTS.clear()
        POINTS.update(points)
        BQ.clear()
        BQ.update(bq)

    files = sorted(Path(args.dir).glob("*.json"))
    file_violations = {}
    file_warnings = {}
    quiz_id_files = {}

    for f in files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            file_violations[f] = [f"JSON 파싱 실패: {e}"]
            continue
        if not isinstance(data, dict):
            file_violations[f] = ["최상위 JSON이 object(dict)가 아님"]
            continue

        violations = validate_candidate(data, catalog, banned)
        warnings = candidate_warnings(data, catalog)
        quiz_id = data.get("quizId")
        if quiz_id is not None:
            quiz_id_files.setdefault(quiz_id, []).append(f)
        if violations:
            file_violations[f] = violations
        if warnings:
            file_warnings[f] = warnings

    for quiz_id, paths in quiz_id_files.items():
        if len(paths) > 1:
            names = ", ".join(p.name for p in paths)
            msg = f"quizId 중복: {quiz_id} ({names})"
            for p in paths:
                file_violations.setdefault(p, []).append(msg)

    for f in files:
        violations = file_violations.get(f)
        if violations:
            print(f"[FAIL] {f.name}")
            for v in violations:
                print(f"  - {v}")
        else:
            print(f"[OK] {f.name}")
        for w in file_warnings.get(f, []):
            print(f"  ! 경고: {w}")

    fail_count = len(file_violations)
    warn_count = sum(len(ws) for ws in file_warnings.values())
    print(f"\n총 {len(files)}개 파일 중 {fail_count}개 위반, "
          f"{len(files) - fail_count}개 통과 (경고 {warn_count}건 — exit code 미반영)")

    sys.exit(1 if file_violations else 0)


if __name__ == "__main__":
    main()
