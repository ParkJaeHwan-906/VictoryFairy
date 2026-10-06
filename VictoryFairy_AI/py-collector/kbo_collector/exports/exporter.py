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


# 종료 경기만 export — games_sync 가 만드는 SCHEDULED/IN_PROGRESS/CANCELED 행이
# 섞이면 점수 NULL 경기가 draw 판정(NULL==NULL)에 걸려 "무승부로 끝났다" 가짜
# envelope 이 나간다. 이 필터가 games_sync 크론 등록의 선행 조건이었다.
_GAMES_SQL = (
    "SELECT g.id, g.naver_game_id, DATE(g.game_date), "
    " TIME_FORMAT(g.game_date, '%%H:%%i'), s.name, "
    " ta.code, ta.name, th.code, th.name, g.away_score, g.home_score, gs.name "
    "FROM games g JOIN teams ta ON ta.id=g.away_team_id "
    " JOIN teams th ON th.id=g.home_team_id "
    " JOIN game_statuses gs ON gs.id=g.game_status_id "
    " LEFT JOIN stadiums s ON s.id=g.stadium_id "
    "WHERE g.naver_game_id IS NOT NULL AND gs.name IN ('FINISHED','DRAW') "
    " AND (%s IS NULL OR DATE(g.game_date)=%s)"
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


# 운영 players 는 이름/팀에 더해 uniform_number·position_group 을 가진다(등록명단발).
# 투타·생년월일 등 나머지 상세는 미저장 — 필요해지면 players 확장이 선행돼야 한다.
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


# 시즌 개인 타/투 누적 기록 (2026-10-06 신설). batter_records·pitcher_records는
# records 잡이 "그 경기에 뛴 선수마다" 적재하는 박스스코어 원자료라 — 위키
# 서사(CAREER_PATH 등)나 kbo-official 공식 기록실 랭킹 표(타율 상위 30명 등 —
# 자격 타석수 미달 선수는 애초에 표에 안 뜸)와 달리 **자격 하한이 전혀 없다**.
# 1군에서 단 1경기라도 타석/투구 기록을 남긴 선수는 전부 집계된다 — 실측
# 검증(2026-10-06, KBO 공식 사이트 Record/Player/{Hitter,Pitcher}Basic 팀필터
# 교차조회, 이 레포 바깥 조사)으로 롯데 자이언츠 1군 출전 선수가 62명(타자 39·
# 투수 31·겸업 8 중복 제거) 확인됐다 — 로스터 78명 기준 커버리지 ~79%로,
# 위키 서사 기반 PLAYER scope 템플릿의 13%(동일 팀 기준)를 크게 웃돈다.
_SEASON_BATTING_SQL = (
    "SELECT p.id, p.kbo_player_id, p.name, t.code, t.name, COUNT(*), "
    " SUM(br.at_bats), SUM(br.hits), SUM(br.home_runs), SUM(br.rbi), "
    " SUM(br.walks), SUM(br.strikeouts), SUM(br.stolen_bases) "
    "FROM batter_records br "
    "JOIN games g ON g.id = br.game_id "
    "JOIN players p ON p.id = br.player_id "
    "JOIN teams t ON t.id = p.team_id "
    "WHERE p.kbo_player_id IS NOT NULL AND YEAR(g.game_date) = YEAR(CURDATE()) "
    "GROUP BY p.id, p.kbo_player_id, p.name, t.code, t.name"
)
_SEASON_PITCHING_SQL = (
    "SELECT p.id, p.kbo_player_id, p.name, t.code, t.name, COUNT(*), "
    " SUM(pr.ip_outs), SUM(pr.earned_runs), SUM(pr.strikeouts), SUM(pr.hits), "
    " SUM(pr.walks_hbp), SUM(pr.home_runs) "
    "FROM pitcher_records pr "
    "JOIN games g ON g.id = pr.game_id "
    "JOIN players p ON p.id = pr.player_id "
    "JOIN teams t ON t.id = p.team_id "
    "WHERE p.kbo_player_id IS NOT NULL AND YEAR(g.game_date) = YEAR(CURDATE()) "
    "GROUP BY p.id, p.kbo_player_id, p.name, t.code, t.name"
)


@reader("player_season_stat")
def read_player_season_stats(db, date=None, sink=None):
    """선수별 시즌(올해) 타/투 누적 기록. player_profile과 같은 "최신 파티션
    1개" 스냅샷 패턴 — 날짜 창이 아니라 매 실행마다 시즌 전체를 다시 합산한다
    (date 인자는 S3 파티션 경로에만 쓰이고 쿼리에는 영향 없음, player_profile과
    동일). 시즌 경계는 YEAR(game_date)=YEAR(CURDATE())로 DB가 직접 판정한다
    (KBO 시즌이 연말을 걸치지 않아 서버 로컬 타임존 차이는 무해).

    타자·투수 겸업 선수(투수가 타석에 선 경우 등)는 batting·pitching 양쪽 다
    채운 envelope 하나로 합친다(player_records.py의 batting+pitching 병합과
    같은 원칙). at_bats=0인 타자, ip_outs=0인 투수는 평균(avg/era)을 None으로
    남긴다(0으로 나누지 않음 — 소비자가 "집계 불가"로 처리할 근거).
    """
    now = _now()
    season = datetime.now(timezone.utc).year

    batting: dict = {}
    for (pk, kbo_id, name, code, tname, games, ab, h, hr, rbi, bb, so, sb) in \
            db.fetch_all(_SEASON_BATTING_SQL):
        if not kbo_id:
            continue
        batting[kbo_id] = {
            "pk": pk, "name": name, "code": code, "tname": tname, "games": games,
            "atBats": int(ab or 0), "hits": int(h or 0), "homeRuns": int(hr or 0),
            "rbi": int(rbi or 0), "walks": int(bb or 0), "strikeouts": int(so or 0),
            "stolenBases": int(sb or 0),
        }

    pitching: dict = {}
    for (pk, kbo_id, name, code, tname, games, ip_outs, er, so, h, bbhp, hr) in \
            db.fetch_all(_SEASON_PITCHING_SQL):
        if not kbo_id:
            continue
        pitching[kbo_id] = {
            "pk": pk, "name": name, "code": code, "tname": tname, "games": games,
            "ipOuts": int(ip_outs or 0), "earnedRuns": int(er or 0),
            "strikeouts": int(so or 0), "hits": int(h or 0),
            "walksHbp": int(bbhp or 0), "homeRuns": int(hr or 0),
        }

    for kbo_id in sorted(set(batting) | set(pitching)):
        b, p = batting.get(kbo_id), pitching.get(kbo_id)
        base = b or p
        name, code, tname, pk = base["name"], base["code"], base["tname"], base["pk"]
        sentences = []
        payload = {"playerId": kbo_id, "season": season}

        if b:
            avg = round(b["hits"] / b["atBats"], 3) if b["atBats"] else None
            avg_txt = f"{avg:.3f}" if avg is not None else "집계불가(무타수)"
            sentences.append(
                f"{tname} {name}은(는) {season}시즌 {b['games']}경기 {b['atBats']}타수 "
                f"{b['hits']}안타(타율 {avg_txt}) {b['homeRuns']}홈런 {b['rbi']}타점 "
                f"{b['stolenBases']}도루를 기록했다."
            )
            payload["batting"] = {k: v for k, v in b.items() if k not in ("pk", "name", "code", "tname")}
            payload["batting"]["avg"] = avg

        if p:
            era = round(p["earnedRuns"] * 27 / p["ipOuts"], 2) if p["ipOuts"] else None
            era_txt = f"{era:.2f}" if era is not None else "집계불가(무이닝)"
            ip_whole, ip_frac = divmod(p["ipOuts"], 3)
            ip_txt = f"{ip_whole}{['', ' ⅓', ' ⅔'][ip_frac]}"
            sentences.append(
                f"{tname} {name}은(는) {season}시즌 {p['games']}경기 {ip_txt}이닝 "
                f"평균자책점 {era_txt} {p['strikeouts']}탈삼진을 기록했다."
            )
            payload["pitching"] = {k: v for k, v in p.items() if k not in ("pk", "name", "code", "tname")}
            payload["pitching"]["era"] = era

        entities = empty_entities()
        entities["teamCodes"] = [code]
        entities["playerUids"] = [pk]
        yield Envelope(
            doc_id=f"player_season_stat:{kbo_id}",
            doc_type="player_season_stat",
            source="naver",
            source_ref=f"mysql://batter_records,pitcher_records/{kbo_id}",
            collected_at=now,
            title=f"{tname} {name} {season}시즌 기록",
            content=" ".join(sentences),
            tags=["개인기록", "시즌통계"],
            entities=entities,
            payload=payload,
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
