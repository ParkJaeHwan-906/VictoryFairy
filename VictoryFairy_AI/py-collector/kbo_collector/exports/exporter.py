"""docType별 reader → envelope → S3 question-source/ 적재.

reader 추가 = 함수 1개 + @reader 1줄. reader가 없는 docType은
그 docType을 방출하는 소스의 collect로 위임한다(예: player_meme → meme_dict).
"""
import logging
from datetime import datetime, timezone

from ..sources import base as source_base
from .envelope import Envelope, empty_entities, s3_key

READERS: dict = {}

# DB 없이 export 가능한 docType (reader가 db 인자를 무시하거나, collect가 곧
# export인 소스가 needs_db=False 인 경우). run.py/handler.py 가 이 집합을 보고
# DbSink 생성을 건너뛴다.
DB_FREE = {"game_schedule", "community_post"}


def reader(doc_type: str):
    def deco(fn):
        READERS[doc_type] = fn
        return fn
    return deco


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def export(doc_type: str, *, settings, db, sink, date=None) -> int:
    """docType의 envelope들을 S3에 적재하고 건수 반환.

    파티션 키(S3 경로의 날짜)는 date 인자가 있으면 그 날짜를, 없으면 실행일(UTC)을
    쓴다. 이전엔 date 인자와 무관하게 항상 실행일을 썼는데, date를 명시해 호출하는
    잡(예: game_schedule)까지 실행일 파티션에 쌓이면서 매일 재실행마다 같은 데이터가
    새 날짜 밑에 중복 적재되는 문제가 있었다(리뷰 C1-1 — 시즌 통계 오염 원인 중 하나).
    """
    if doc_type in READERS:
        partition_date = date or _now()[:10]
        count = 0
        for env in READERS[doc_type](db, date=date, sink=sink):
            try:
                env.validate()
                sink.put_json(s3_key(env.doc_type, partition_date, env.doc_id), env.to_dict())
            except Exception as exc:
                logging.getLogger("export").warning("skip %s: %s", env.doc_id, exc)
                continue
            count += 1
        return count
    owners = source_base.sources_for(doc_type)
    if owners:  # collect가 곧 export인 소스 (스펙 3-6)
        ctx = source_base.CollectContext(settings=settings, db=db, sink=sink, date=date)
        return owners[0].collect(ctx).loaded
    known = sorted(set(READERS) | {d for s in source_base.REGISTRY.values()
                                   for d in s.doc_types})
    raise KeyError(f"unknown docType '{doc_type}' (known: {', '.join(known)})")


_GAMES_SQL = (
    "SELECT g.id, g.naver_game_id, DATE(g.game_date), "
    " TIME_FORMAT(g.game_date, '%%H:%%i'), s.name, "
    " ta.code, ta.name, th.code, th.name, g.away_score, g.home_score, gs.name "
    "FROM games g JOIN teams ta ON ta.id=g.away_team_id "
    " JOIN teams th ON th.id=g.home_team_id "
    " JOIN game_statuses gs ON gs.id=g.game_status_id "
    " LEFT JOIN stadiums s ON s.id=g.stadium_id "
    "WHERE g.naver_game_id IS NOT NULL AND (%s IS NULL OR DATE(g.game_date)=%s)"
)
_DECISION_SQL = (
    "SELECT gl.decision, p.name FROM game_lineups gl "
    "JOIN players p ON p.id=gl.player_id "
    "WHERE gl.game_id=%s AND gl.decision IS NOT NULL"
)


@reader("game_result")
def read_game_results(db, date=None, sink=None):
    now = _now()
    for (pk, gid, gdate, gtime, stadium, a_code, a_name, h_code, h_name,
         a_score, h_score, status) in db.fetch_all(_GAMES_SQL, (date, date)):
        decisions = dict(db.fetch_all(_DECISION_SQL, (pk,)))
        win_name = decisions.get("W")
        lose_name = decisions.get("L")
        draw = status == "DRAW" or a_score == h_score
        if draw:
            winner = "draw"
            outcome = "무승부로 끝났다"
        else:
            winner = "home" if (h_score or 0) > (a_score or 0) else "away"
            winner_name = h_name if winner == "home" else a_name
            outcome = f"{winner_name}의 승리로 끝났다"
        parts = [f"{gdate} {stadium}에서 열린 {a_name} 대 {h_name} 경기는 "
                 f"{a_score}:{h_score}, {outcome}."]
        if win_name:
            parts.append(f"승리투수 {win_name}.")
        if lose_name:
            parts.append(f"패전투수 {lose_name}.")
        if decisions.get("S"):
            parts.append(f"세이브 {decisions['S']}.")
        entities = empty_entities()
        entities["gameId"] = gid
        entities["teamCodes"] = [a_code, h_code]
        tags = ["박스스코어", "경기결과"]
        if draw:
            tags.append("무승부")
        yield Envelope(
            doc_id=f"game_result:{gid}",
            doc_type="game_result",
            source="naver",
            source_ref=f"mysql://games/{gid}",
            collected_at=now,
            title=f"{gdate} {a_name} {a_score}:{h_score} {h_name}",
            content=" ".join(parts),
            tags=tags,
            entities=entities,
            payload={"gameId": gid, "awayScore": a_score, "homeScore": h_score,
                     "winner": winner, "stadium": stadium, "startTime": gtime},
        )


# 운영 players 는 상세정보(등번호·포지션·투타·생년월일) 없이 이름/팀만 가진다.
# 상세가 다시 필요해지면 players 테이블 확장이 선행돼야 한다.
_PLAYERS_SQL = (
    "SELECT p.kbo_player_id, p.id, p.name, t.code, t.name "
    "FROM players p JOIN teams t ON t.id=p.team_id "
    "WHERE p.kbo_player_id IS NOT NULL"
)


@reader("player_profile")
def read_player_profiles(db, date=None, sink=None):
    now = _now()
    for (pid, uid, name, team_code, team_name) in db.fetch_all(_PLAYERS_SQL):
        entities = empty_entities()
        entities["teamCodes"] = [team_code]
        entities["playerUids"] = [uid]
        content = f"{name}은(는) {team_name} 소속 선수다."
        yield Envelope(
            doc_id=f"player_profile:{pid}",
            doc_type="player_profile",
            source="kbo_official",
            source_ref=f"mysql://players/{uid}",
            collected_at=now,
            title=f"{team_name} {name} 프로필",
            content=content,
            tags=["프로필", "선수"],
            entities=entities,
            payload={"playerId": pid},
        )


@reader("community_post")
def read_community_posts(db, date=None, sink=None):
    """S3 RawPost 재포장(본문 재크롤 없음). date 필수."""
    if not date:
        raise ValueError("community_post export requires --date")
    now = _now()
    for source_dir in ("dcinside", "fmkorea"):
        for key in sink.iter_keys(f"community/{source_dir}/{date}/"):
            post = sink.get_json(key)
            entities = empty_entities()
            team = post.get("team")
            tags = ["커뮤니티", "여론"]
            if team:
                tags.append(team)
            title = post.get("title") or "(제목 없음)"
            body = post.get("body") or ""
            yield Envelope(
                doc_id=f"community_post:{post['source']}:{post['postExternalId']}",
                doc_type="community_post",
                source=post["source"].lower(),
                source_ref=post.get("sourceUrl") or key,
                collected_at=now,
                title=title,
                content=f"{title}\n{body}".strip(),   # 원문 통과, 요약 없음
                tags=tags,
                entities=entities,
                payload={"engagement": post.get("engagement"),
                         "crawledAt": post.get("crawledAt")},
                pii={"masked": True},
            )


@reader("game_schedule")
def read_game_schedules(db, date=None, sink=None):
    """raw-json/schedule/{date} → 예정(BEFORE) 경기 envelope. date 필수, db 미사용.

    선발 라인업(타자)은 경기 전 데이터 소스가 없어 v1은 일정+선발투수만 담는다.
    네이버 스케줄 API(fields=basic,statusNum,statusInfo, 운영 schedule 잡이
    실제 쓰는 필드셋) 응답에는 선발투수 필드가 없어 실제로는 항상 None —
    Task 10에서 PRED_SP_WIN 템플릿 비활성화로 대응. 같은 이유로 stadium도
    이 응답에 없어 payload의 stadium은 실제로는 항상 빈 문자열("")이다.
    """
    if not date:
        raise ValueError("game_schedule export requires --date")
    from .. import keys as raw_keys
    from ..dimensions import TEAM_CODES
    key = raw_keys.schedule_key(date)
    if not sink.exists(key):
        raise ValueError(f"raw schedule 없음: {key} — 'schedule' 잡을 먼저 실행하세요")
    now = _now()
    games = ((sink.get_json(key) or {}).get("result") or {}).get("games") or []
    for g in games:
        if g.get("categoryId") != "kbo" or g.get("cancel"):
            continue
        if g.get("statusCode") != "BEFORE":
            continue
        if g.get("awayTeamCode") not in TEAM_CODES or g.get("homeTeamCode") not in TEAM_CODES:
            continue
        gid = g["gameId"]
        a_name = g.get("awayTeamName") or g["awayTeamCode"]
        h_name = g.get("homeTeamName") or g["homeTeamCode"]
        gtime = (g.get("gameDateTime") or "")[11:16]   # 'YYYY-MM-DDTHH:MM:SS' -> 'HH:MM'
        stadium = g.get("stadium") or ""
        a_sp, h_sp = g.get("awayStarterName"), g.get("homeStarterName")
        parts = [f"{date} {gtime} {stadium}에서 {a_name} 대 {h_name} 경기가 예정되어 있다."]
        if a_sp or h_sp:
            parts.append(f"선발투수는 {a_name} {a_sp or '미정'}, {h_name} {h_sp or '미정'}.")
        entities = empty_entities()
        entities["gameId"] = gid
        entities["teamCodes"] = [g["awayTeamCode"], g["homeTeamCode"]]
        yield Envelope(
            doc_id=f"game_schedule:{gid}", doc_type="game_schedule", source="naver",
            source_ref=key, collected_at=now,
            title=f"{date} {a_name} vs {h_name} 경기 예정",
            content=" ".join(parts), tags=["일정", "예정경기"],
            entities=entities,
            payload={"gameId": gid, "startTime": gtime, "stadium": stadium,
                     "awayStarter": a_sp, "homeStarter": h_sp},
        )
