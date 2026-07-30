"""quiz-candidates 결정적 검증 스크립트 — LLM 미사용 2차 방어선.

퀴즈 생성기 routine(LLM)이 만든 quiz-candidates JSON(스펙 4.3 계약)을 S3
업로드 직전에 마지막으로 게이트 검사한다. 같은 입력이면 항상 같은 결과가
나오는 순수 검사만 수행하며(랜덤·시각 의존 없음), LLM 기반 1차 검증 패스와
독립적인 별개 방어선이다.

`question-gen/config/question-templates.yaml` 카탈로그를 로드해 templateId가
실재하는지, 활성 상태인지, kind/format이 선언과 일치하는지 확인하고,
`question-gen/config/banned-topics.txt`로 안전 규칙(스펙 4.2, 사건사고·법적
논란·사생활·건강 소재 금지)의 결정적 부분(키워드 부분 문자열 매칭)을 검사한다.

파일 구성: 상수(POINTS/METRICS/그 외) → 로더 2개(load_catalog/load_banned) →
validate_candidate(검사 7항목) → CLI(main). stdlib + PyYAML만 사용(boto3 금지).
"""

import argparse
import json
import sys
from pathlib import Path

import yaml

# ── 상수 ────────────────────────────────────────────────

#: 기능명세서 포인트 기준표. pointReward는 difficulty로 결정된다(check 6).
POINTS = {"EASY": 30, "MEDIUM": 50, "HARD": 80, "EXPERT": 120}

#: BE가 RDB로 정산 가능한 예측 지표만 허용(check 4). 스펙 6. 리스크 참조 —
#: 선수 퍼포먼스 등 상세 스탯 예측은 정산 불가라 카탈로그에도 없음.
METRICS = {"WIN_TEAM", "TOTAL_RUNS", "SCORE_GAP", "PITCHER_DECISION"}

#: format별 필수 option 개수(check 2). 주관식은 계약에 없다.
FORMAT_OPTION_COUNTS = {"OX": 2, "BINARY": 2, "MULTI4": 4}

#: quiz-candidates 계약(스펙 4.3)의 필수 필드(check 1). answer/evidence/
#: settlement/gameId는 kind에 따라 있거나 없어야 하므로 여기 포함하지 않는다
#: (check 3/4에서 별도 검사).
REQUIRED_FIELDS = (
    "quizId", "kind", "type", "templateId", "format", "question",
    "options", "difficulty", "pointReward", "status",
    "createdAt", "deadlineAt", "createdBy",
)


# ── 로더 ────────────────────────────────────────────────

def load_catalog(path) -> dict:
    """질문 템플릿 카탈로그 YAML(리스트)을 읽어 `{id: 항목dict}`로 반환한다.

    항목에 `enabled` 키가 없으면 기본값 True를 채운다(카탈로그 주석 규칙)."""
    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or []
    catalog = {}
    for entry in raw:
        entry = dict(entry)
        entry.setdefault("enabled", True)
        catalog[entry["id"]] = entry
    return catalog


def load_banned(path) -> list:
    """banned-topics.txt(줄당 1키워드)를 읽어 키워드 리스트로 반환한다.

    빈 줄과 `#` 주석 줄은 제거한다."""
    keywords = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            keywords.append(line)
    return keywords


# ── 검사 ────────────────────────────────────────────────

def validate_candidate(c: dict, catalog: dict, banned: list) -> list:
    """quiz-candidates 항목 하나를 검사해 위반 메시지 리스트를 반환한다.
    빈 리스트면 통과. 검사 7항목:

    1. 필수 필드 존재
    2. format이 OX/BINARY/MULTI4 중 하나 + options 개수·id·text 규칙
    3. KNOWLEDGE → answer/evidence 필수 + settlement은 None
    4. PREDICTION → settlement 필수(gameId·정산 가능 metric) + answer/evidence는 None
    5. templateId가 카탈로그에 존재 + enabled + kind/format이 카탈로그와 일치
    6. pointReward가 POINTS[difficulty]와 일치
    7. question·모든 option text에 banned 키워드가 없음

    각 필드가 아예 없거나 타입이 다른 경우에도 예외를 던지지 않고 위반으로
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

    # 6. 포인트 기준표 일치
    difficulty = c.get("difficulty")
    point_reward = c.get("pointReward")
    if difficulty not in POINTS:
        violations.append(f"difficulty 값이 올바르지 않음: {difficulty}")
    elif point_reward != POINTS[difficulty]:
        violations.append(
            f"pointReward가 difficulty({difficulty}) 기준 포인트와 불일치: "
            f"기대 {POINTS[difficulty]}, 실제 {point_reward}")

    # 7. 금지 소재 키워드(부분 문자열 매칭)
    texts = [c.get("question") or ""]
    texts += [o.get("text") or "" for o in options if isinstance(o, dict)]
    for keyword in banned:
        for text in texts:
            if keyword in text:
                violations.append(f"금지 소재 키워드 포함: '{keyword}' in \"{text}\"")
                break

    return violations


# ── CLI ──────────────────────────────────────────────────

def _default_config_path(name: str) -> str:
    """이 스크립트 기준(`question-gen/scripts/`) 상대경로로 config 파일을 찾는다."""
    return str(Path(__file__).resolve().parent.parent / "config" / name)


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
    return p


def main(argv=None) -> None:
    """CLI 진입점. `--dir`의 `*.json`을 전부 로드해 검사하고 파일별 위반을
    출력한 뒤 요약을 찍는다. JSON 파싱 실패도 위반으로 취급하고, 같은
    디렉토리 내 quizId 중복도 검사한다. 위반이 하나라도 있으면 `sys.exit(1)`,
    없으면 `sys.exit(0)`(routine이 이 exit code로 업로드 여부를 결정한다)."""
    args = _build_arg_parser().parse_args(argv)

    catalog_path = args.catalog or _default_config_path("question-templates.yaml")
    banned_path = args.banned or _default_config_path("banned-topics.txt")
    catalog = load_catalog(catalog_path)
    banned = load_banned(banned_path)

    files = sorted(Path(args.dir).glob("*.json"))
    file_violations = {}
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
        quiz_id = data.get("quizId")
        if quiz_id is not None:
            quiz_id_files.setdefault(quiz_id, []).append(f)
        if violations:
            file_violations[f] = violations

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

    fail_count = len(file_violations)
    print(f"\n총 {len(files)}개 파일 중 {fail_count}개 위반, {len(files) - fail_count}개 통과")

    sys.exit(1 if file_violations else 0)


if __name__ == "__main__":
    main()
