"""docType별 reader → envelope → S3 question-source/ 적재.

reader 추가 = 함수 1개 + @reader 1줄. reader가 없는 docType은
그 docType을 방출하는 소스의 collect로 위임한다(예: player_meme → meme_dict).
"""
import logging
from datetime import datetime, timezone

from ..sources import base as source_base
from .envelope import Envelope, empty_entities, s3_key

READERS: dict = {}


def reader(doc_type: str):
    def deco(fn):
        READERS[doc_type] = fn
        return fn
    return deco


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def export(doc_type: str, *, settings, db, sink, date=None) -> int:
    """docType의 envelope들을 S3에 적재하고 건수 반환."""
    if doc_type in READERS:
        today = _now()[:10]
        count = 0
        for env in READERS[doc_type](db, date=date, sink=sink):
            try:
                env.validate()
                sink.put_json(s3_key(env.doc_type, today, env.doc_id), env.to_dict())
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
    "SELECT g.game_id, g.game_date, g.game_type, g.stadium, g.start_time, "
    " g.away_team_code, ta.name, g.home_team_code, th.name, "
    " g.away_score, g.home_score, g.winner "
    "FROM games g JOIN teams ta ON ta.team_code=g.away_team_code "
    " JOIN teams th ON th.team_code=g.home_team_code "
    "WHERE (%s IS NULL OR g.game_date=%s)"
)
_DECISION_SQL = (
    "SELECT gp.decision, p.name FROM game_pitching gp "
    "JOIN game_players p ON p.player_uid=gp.player_uid "
    "WHERE gp.game_id=%s AND gp.decision IS NOT NULL"
)


@reader("game_result")
def read_game_results(db, date=None, sink=None):
    now = _now()
    for (gid, gdate, gtype, stadium, gtime, a_code, a_name, h_code, h_name,
         a_score, h_score, winner) in db.fetch_all(_GAMES_SQL, (date, date)):
        decisions = dict(db.fetch_all(_DECISION_SQL, (gid,)))
        win_name = decisions.get("W")
        lose_name = decisions.get("L")
        if winner == "draw":
            outcome = "무승부로 끝났다"
        else:
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
        if gtype == "preseason":
            tags.append("시범경기")
        if winner == "draw":
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


_PLAYERS_SQL = (
    "SELECT p.player_id, p.name, p.team_code, t.name, p.back_number, "
    " p.position, p.throw_bat, p.birth_date, p.is_first_team, gp.player_uid "
    "FROM players p JOIN teams t ON t.team_code=p.team_code "
    "LEFT JOIN game_players gp ON gp.kbo_player_id=p.player_id"
)


@reader("player_profile")
def read_player_profiles(db, date=None, sink=None):
    now = _now()
    for (pid, name, team_code, team_name, back_no, position, throw_bat,
         birth, is_first, uid) in db.fetch_all(_PLAYERS_SQL):
        entities = empty_entities()
        entities["teamCodes"] = [team_code]
        if uid is not None:
            entities["playerUids"] = [uid]
        else:
            entities["unresolved"] = [
                {"kind": "player", "name": name, "reason": "no-game-uid"}]
        first = "1군 등록" if is_first else "1군 미등록"
        content = (f"{team_name} {name}은(는) {position}로, 등번호 {back_no}번, "
                   f"{throw_bat}이다. 생년월일 {birth}. 현재 {first} 상태다.")
        yield Envelope(
            doc_id=f"player_profile:{pid}",
            doc_type="player_profile",
            source="kbo_official",
            source_ref=f"mysql://players/{pid}",
            collected_at=now,
            title=f"{team_name} {name} 프로필",
            content=content,
            tags=["프로필", "선수"],
            entities=entities,
            payload={"playerId": pid, "backNumber": back_no,
                     "position": position, "throwBat": throw_bat,
                     "isFirstTeam": bool(is_first)},
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
