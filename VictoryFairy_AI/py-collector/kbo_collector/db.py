"""운영 서비스 스키마(MySQL) 적재 싱크.

테이블 구조의 유일한 원천은 domain 모듈 JPA 엔티티다(teams/players/stadiums/
game_statuses/games/game_lineups/registrations — dev_be 브랜치 VictoryFairy_BE/domain
참고. 이 리포에는 DDL 사본을 두지 않으며 스키마 변경·생성은 dev_be 소관). 수집기는
소스 자연키(teams.code, players.kbo_player_id, games.naver_game_id)로
upsert 해 재실행에도 멱등이다. PK 는 전부 서비스 소유의 AUTO_INCREMENT id.
"""
import logging

import pymysql

from .dimensions import POSITION_GROUPS

log = logging.getLogger("db")

# created_at/updated_at: 전 테이블 NOT NULL·DB 기본값 없음(JPA @CreationTimestamp/
# @UpdateTimestamp 가 앱에서 채우는 컬럼). 직접 SQL 을 쓰는 수집기는 NOW(6)으로 공급하고,
# 갱신 경로에서는 updated_at 만 밀어준다(dev_be infra/sql 시드와 동일한 규약).
TEAMS_UPSERT = (
    "INSERT INTO teams (code, name, created_at, updated_at) "
    "VALUES (%s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), updated_at=NOW(6)"
)

TEAM_IDS_SQL = "SELECT code, id FROM teams WHERE code IS NOT NULL"

# KBO 공식 로스터: kbo_player_id 자연키. 팀 이적 시 team_id 전진, 신규는 average=0.
# uniform_number/position_group 은 등록명단이 주는 값으로 매일 최신화(등번호 변경 반영).
ROSTER_PLAYER_UPSERT = (
    "INSERT INTO players (kbo_player_id, name, team_id, uniform_number, position_group, "
    " average, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, %s, 0, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), team_id=VALUES(team_id), "
    "  uniform_number=VALUES(uniform_number), position_group=VALUES(position_group), "
    "  updated_at=NOW(6)"
)

# 일별 등록 스냅샷: (registration_date, player_id) 자연키. 같은 날 재실행이면 team 만 갱신.
PLAYER_IDS_BY_KBO = "SELECT kbo_player_id, id FROM players WHERE kbo_player_id IN ({ph})"
REGISTRATION_UPSERT = (
    "INSERT INTO registrations (registration_date, team_id, player_id, created_at, updated_at) "
    "VALUES (%s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), updated_at=NOW(6)"
)

# 이동현황 반영: playerId 가 없어 (이름, 팀) 유일 매칭일 때만 손댄다.
PLAYER_IDS_BY_NAME_TEAM = "SELECT id FROM players WHERE name=%s AND team_id=%s"
PLAYER_SET_TEAM = "UPDATE players SET team_id=%s, updated_at=NOW(6) WHERE id=%s"
PLAYER_SET_NAME = "UPDATE players SET name=%s, updated_at=NOW(6) WHERE id=%s"
PLAYER_SET_UNIFORM_NUMBER = "UPDATE players SET uniform_number=%s, updated_at=NOW(6) WHERE id=%s"

# 상태/구장 lookup-or-insert (name 에 UNIQUE 없음 → SELECT 후 INSERT, 크론 단일 실행 전제)
STATUS_SELECT = "SELECT id FROM game_statuses WHERE name=%s"
STATUS_INSERT = (
    "INSERT INTO game_statuses (name, created_at, updated_at) VALUES (%s, NOW(6), NOW(6))"
)
STADIUM_SELECT = "SELECT id FROM stadiums WHERE name=%s"
STADIUM_INSERT = (
    "INSERT INTO stadiums (name, created_at, updated_at) VALUES (%s, NOW(6), NOW(6))"
)
POSITION_SELECT = "SELECT id FROM positions WHERE name=%s"
POSITION_INSERT = (
    "INSERT INTO positions (name, created_at, updated_at) VALUES (%s, NOW(6), NOW(6))"
)

# 네이버 박스스코어 pos 표기 → 화면 표시용 정식 명칭 (사용자 결정: positions.name 이
# 그대로 API `positionName` 으로 나가므로 DB 에 읽을 수 있는 값을 저장한다).
# "타"/"주"는 수비 위치가 아닌 출전 형태(대타/대주자) — 구분 보존.
# 1·2·3루는 네이버가 한자(一/二/三)로 보낸다.
POSITION_NAMES = {
    "투": "투수", "포": "포수", "一": "1루수", "二": "2루수", "三": "3루수",
    "유": "유격수", "좌": "좌익수", "중": "중견수", "우": "우익수", "지": "지명타자",
    "타": "대타", "주": "대주자",
}


def position_name(raw):
    """네이버 pos 표기 → 정식 명칭.

    경기 중 수비 위치를 바꾼 선수는 표기가 이어붙어 온다("중좌"=중견수→좌익수,
    "타二"=대타→2루수). 라인업은 "이 선수가 어디로 나왔나"를 보여주는 화면이므로
    첫 글자(= 그 경기 시작 위치)로 접는다. 이 분해가 없으면 조합이 12×12 까지
    늘어나 positions 코드테이블이 계속 불어난다.
    미지 표기는 warning 후 원문 그대로 적재해 수집이 깨지지 않게 한다.
    """
    if raw is None:
        return None
    name = POSITION_NAMES.get(raw) or POSITION_NAMES.get(raw[:1])
    if name is None:
        log.warning("unknown position notation %r — storing raw", raw)
        return raw
    return name


# 박스스코어 선수 해소: kbo_player_id 일괄 조회 → 신규 INSERT
# 실측상 네이버 pcode == KBO playerId (2026-07 박스스코어·로스터 교집합 228명 전수
# 일치) — 이 동치를 스키마 전제로 승격해 kbo_player_id 단일 자연키로 해소한다.
# 전제가 깨지면 이름 불일치 warning 이 급증하므로 그때 재설계한다.
PLAYER_BY_KBO_ID = (
    "SELECT kbo_player_id, id, name FROM players WHERE kbo_player_id IN ({ph})"
)
PLAYER_INSERT = (
    "INSERT INTO players (kbo_player_id, name, team_id, average, created_at, updated_at) "
    "VALUES (%s, %s, %s, 0, NOW(6), NOW(6))"
)

# id=LAST_INSERT_ID(id): 갱신 경로에서도 lastrowid 로 기존 PK 를 돌려받는 MySQL 관용구.
GAME_UPSERT = (
    "INSERT INTO games (naver_game_id, game_date, home_team_id, away_team_id, "
    " stadium_id, home_score, away_score, game_status_id, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), game_date=VALUES(game_date), "
    "  home_team_id=VALUES(home_team_id), away_team_id=VALUES(away_team_id), "
    "  stadium_id=VALUES(stadium_id), home_score=VALUES(home_score), "
    "  away_score=VALUES(away_score), game_status_id=VALUES(game_status_id), "
    "  updated_at=NOW(6)"
)

# games_sync 전용: 점수·구장은 제공될 때만 갱신(COALESCE).
# stadium_id 의 최종 진실은 여전히 records 잡이다 — GAME_UPSERT 는 무조건 덮어쓰고
# 여기선 NULL 일 때만 메운다. 경기 전에는 records 가 아직 안 돌아 구장이 비는데,
# 일정을 미리 적재하는 잡(games_sync 의 +N일 윈도)이 그 구멍을 채우기 위한 것.
#
# id=LAST_INSERT_ID(id) 는 GAME_UPSERT 와 같은 관용구다(설명은 그쪽 주석). 여기서는
# 뒤이은 선발 라인업 적재가 이 반환 PK 를 그대로 쓰기 때문에 붙였다.
# 다만 "없으면 엉뚱한 PK 가 온다"는 아니다 — MySQL 8.0.46 실측상 갱신 경로(rowcount=2)
# 에서는 관용구 없이도 서버가 OK 패킷에 기존 PK 를 실어 준다. 차이가 나는 건 완전
# 무변경(rowcount=0) 뿐이고 그때 lastrowid 는 0 이다(남의 PK 가 아니다). 이 문장엔
# updated_at=NOW(6) 이 있어 무변경 경로 자체가 안 생긴다. 즉 이건 **보장에 기대지 않기
# 위한 명시**이지 관측된 사고의 수정이 아니다 — 버전·설정에 의존하는 동작에 PK 를
# 맡기지 않으려는 것.
# ⚠ 이닝 두 컬럼만 COALESCE 가 아니다 — 의도된 비대칭이다.
# 점수·구장은 "한 번 알면 계속 참"이라 COALESCE 로 기존 값을 지킨다. 이닝은 반대로
# **진행 중일 때만 참**인 값이라 경기가 끝나면 지워져야 한다(BE 계약: IN_PROGRESS 가
# 아닌 상태는 항상 null). COALESCE 를 붙이면 종료된 경기에 마지막 이닝이 영원히
# 남아 "9회말 진행 중"으로 보인다. 일관성 명목으로 COALESCE 를 더하지 말 것.
GAME_SYNC_UPSERT = (
    "INSERT INTO games (naver_game_id, game_date, home_team_id, away_team_id, "
    " stadium_id, home_score, away_score, game_status_id, current_inning, inning_half, "
    " created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), game_date=VALUES(game_date), "
    "  stadium_id=COALESCE(VALUES(stadium_id), stadium_id), "
    "  home_score=COALESCE(VALUES(home_score), home_score), "
    "  away_score=COALESCE(VALUES(away_score), away_score), "
    "  game_status_id=VALUES(game_status_id), "
    "  current_inning=VALUES(current_inning), inning_half=VALUES(inning_half), "
    "  updated_at=NOW(6)"
)

# 선발 라인업이 '완성'된 경기 판정. 완성 = 선발 타순 1~9 가 두 팀에 걸쳐 18행.
# game 단위 COUNT>0 으로 보면 한 팀만 먼저 공시된 경기에서 나머지 한 팀이 영영
# 안 들어온다(공시는 팀별로 따로 뜬다).
LINEUP_DONE_GAMES = (
    "SELECT game_id FROM game_lineups "
    "WHERE game_id IN ({ph}) AND is_starter = 1 AND bat_order BETWEEN 1 AND 9 "
    "GROUP BY game_id HAVING COUNT(DISTINCT team_id) = 2 AND COUNT(*) >= 18"
)

# 구장을 이미 아는 경기. 구장은 스케줄 목록에 없어 경기마다 상세 API 를 한 번 더
# 불러야 하는데, 한 번 채우면 변하지 않는다 — 폴링이 1분이면 같은 값을 하루 수백 번
# 다시 받게 되므로 아는 경기는 건너뛴다.
GAMES_WITH_STADIUM = (
    "SELECT naver_game_id FROM games "
    "WHERE naver_game_id IN ({ph}) AND stadium_id IS NOT NULL"
)

# preview(경기 전 공시)로 적재해 놓고 실제로는 출전하지 않은 선수를 걷어낸다.
# records 잡이 박스스코어로 확정 적재한 뒤 부르며, 박스스코어에 없는 행이 곧 유령이다.
LINEUP_DELETE_EXCEPT = (
    "DELETE FROM game_lineups WHERE game_id = %s AND player_id NOT IN ({ph})"
)

# 취소된 경기의 preview 라인업 정리. 취소 경기는 RESULT 가 아니라 records 잡이
# 영영 돌지 않으므로 위 정리 경로가 닿지 않는다 — 놔두면 유령이 영구히 남는다.
# batter_records 가 없을 때만 지우는 이유: 서스펜디드처럼 '경기는 치렀는데 나중에
# 취소로 표시'되는 경우 records 가 적재한 확정 라인업까지 날리면 안 된다.
LINEUP_DELETE_UNPLAYED = (
    "DELETE FROM game_lineups WHERE game_id = %s "
    "AND NOT EXISTS (SELECT 1 FROM batter_records b WHERE b.game_id = %s)"
)

# 취소 사유는 KBO 공식 일정표에만 있어(네이버는 "경기취소"로 뭉뚱그린다) 네이버 자연키
# naver_game_id 로 행을 못 집는다 — 취소 경기엔 KBO 쪽 gameId 자체가 비어 있다. 그래서
# (날짜, 대진) 으로 UPDATE 한다. games.game_date 는 시각을 포함하는 DATETIME 이라
# 등치 비교가 늘 0건이므로 반개구간으로 잡는다(domain 규약).
#
# 마지막 조건(사유가 실제로 다를 때만)이 이 잡을 멱등하게 만든다. 없으면 값이 같아도
# updated_at=NOW(6) 때문에 행이 매번 "변경"되어 두 가지가 어긋난다:
#   1. 매일 같은 행을 다시 쓴다 — 바뀐 게 없는데 updated_at 만 흔들린다.
#   2. 반환하는 rowcount 가 "새로 채운 행"이 아니라 "매칭된 행"이 된다. 그래서 재실행
#      해도 같은 수가 찍혀 "매일 30건씩 갱신되네?"로 읽힌다(2026-08-10 실측).
CANCEL_REASON_UPDATE = (
    "UPDATE games SET cancel_reason=%s, updated_at=NOW(6) "
    "WHERE game_date >= %s AND game_date < DATE_ADD(%s, INTERVAL 1 DAY) "
    "  AND home_team_id=%s AND away_team_id=%s "
    "  AND (cancel_reason IS NULL OR cancel_reason <> %s)"
)

LINEUP_UPSERT = (
    "INSERT INTO game_lineups (game_id, team_id, player_id, bat_order, position_id, "
    " is_starter, decision, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), bat_order=VALUES(bat_order), "
    "  position_id=VALUES(position_id), is_starter=VALUES(is_starter), decision=VALUES(decision), "
    "  updated_at=NOW(6)"
)

# 경기×선수 1행 집계 기록 (UNIQUE(game_id, player_id)). BattingRow/PitchingRow
# (game_records.py) 필드 순서와 컬럼 순서가 정확히 대응해야 한다.
BATTER_UPSERT = (
    "INSERT INTO batter_records (game_id, player_id, at_bats, runs, hits, home_runs, "
    " rbi, walks, strikeouts, stolen_bases, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(6),NOW(6)) "
    "ON DUPLICATE KEY UPDATE at_bats=VALUES(at_bats), runs=VALUES(runs), "
    "  hits=VALUES(hits), home_runs=VALUES(home_runs), rbi=VALUES(rbi), "
    "  walks=VALUES(walks), strikeouts=VALUES(strikeouts), "
    "  stolen_bases=VALUES(stolen_bases), updated_at=NOW(6)"
)
PITCHER_UPSERT = (
    "INSERT INTO pitcher_records (game_id, player_id, seq, ip_display, ip_outs, "
    " batters_faced, at_bats, hits, runs, earned_runs, home_runs, walks_hbp, "
    " strikeouts, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(6),NOW(6)) "
    "ON DUPLICATE KEY UPDATE seq=VALUES(seq), ip_display=VALUES(ip_display), "
    "  ip_outs=VALUES(ip_outs), batters_faced=VALUES(batters_faced), "
    "  at_bats=VALUES(at_bats), hits=VALUES(hits), runs=VALUES(runs), "
    "  earned_runs=VALUES(earned_runs), home_runs=VALUES(home_runs), "
    "  walks_hbp=VALUES(walks_hbp), strikeouts=VALUES(strikeouts), updated_at=NOW(6)"
)


class DbSink:
    def __init__(self, settings, connection=None):
        self._conn = connection or pymysql.connect(
            host=settings.db_host, port=settings.db_port, user=settings.db_user,
            password=settings.db_password, database=settings.db_name,
            charset="utf8mb4", autocommit=False,
        )

    # ---------- 팀 ----------
    def upsert_teams(self, teams) -> dict:
        """dimensions.TeamRow 목록 upsert 후 {team_code: teams.id} 반환."""
        self._many(TEAMS_UPSERT, [(t.team_code, t.name) for t in teams])
        return self.team_ids()

    def team_ids(self) -> dict:
        return {code: tid for code, tid in self.fetch_all(TEAM_IDS_SQL)}

    # ---------- 선수 (KBO 로스터) ----------
    def upsert_roster_players(self, players, team_id) -> None:
        self._many(ROSTER_PLAYER_UPSERT,
                   [(p.player_id, p.name, team_id, p.back_number or None,
                     POSITION_GROUPS.get(p.position)) for p in players])

    # ---------- 일별 등록 스냅샷 ----------
    def upsert_registrations(self, snapshot_date, players, team_id) -> int:
        """PlayerRow 목록 -> registrations(snapshot_date, team, player FK) upsert.

        players 행이 선행돼 있어야 한다(upsert_roster_players 직후 호출 전제).
        미해소 kbo_player_id 는 스킵하고 적재 건수를 반환한다.
        """
        codes = [p.player_id for p in players]
        if not codes:
            return 0
        ph = ",".join(["%s"] * len(codes))
        ids = {c: i for c, i in self.fetch_all(PLAYER_IDS_BY_KBO.format(ph=ph), codes)}
        rows = [(snapshot_date, team_id, ids[p.player_id])
                for p in players if p.player_id in ids]
        self._many(REGISTRATION_UPSERT, rows)
        return len(rows)

    # ---------- 이동현황 (Trade.aspx) ----------
    def apply_trades(self, trades, team_ids_by_name) -> dict:
        """TradeRow 목록을 players 에 보조 반영. 반환: 항목별 적용 건수.

        등록명단(playerId 보유)이 팀 배정의 원천이므로, 여기서는 명단이 못 잡는
        갭만 메운다 — 미등록(2군) 선수의 트레이드/개명/등번호 변경. playerId 가
        없는 피드라 (이름, 팀) 매칭이 유일할 때만 UPDATE 하고, 대상이 players 에
        없으면(1군 이력 없음) 스킵이 정상이다. 재적용은 매칭 실패로 자연 멱등.
        """
        from . import kbo_trade  # 순환 없음: kbo_trade 는 dimensions 만 본다

        applied: dict[str, int] = {}
        with self._conn.cursor() as cur:
            for t in trades:
                if t.category in ("트레이드", "트레이드(웨이버)"):
                    move = kbo_trade.split_arrow(t.note)
                    if not move:
                        continue
                    src, dst = (team_ids_by_name.get(move[0]),
                                team_ids_by_name.get(move[1]))
                    pid = self._sole_player(cur, t.player_name, src)
                    if pid and dst:
                        cur.execute(PLAYER_SET_TEAM, (dst, pid))
                        applied[t.category] = applied.get(t.category, 0) + 1
                elif t.category == "개명":
                    old = kbo_trade.renamed_from(t.note)
                    pid = old and self._sole_player(
                        cur, old, team_ids_by_name.get(t.team_name))
                    if pid:
                        cur.execute(PLAYER_SET_NAME, (t.player_name, pid))
                        applied[t.category] = applied.get(t.category, 0) + 1
                elif t.category == "등번호 변경":
                    change = kbo_trade.split_arrow(t.note)
                    pid = change and self._sole_player(
                        cur, t.player_name, team_ids_by_name.get(t.team_name))
                    if pid:
                        cur.execute(PLAYER_SET_UNIFORM_NUMBER, (change[1], pid))
                        applied[t.category] = applied.get(t.category, 0) + 1
        self._conn.commit()
        return applied

    def _sole_player(self, cur, name, team_id):
        """(이름, 팀) 매칭이 정확히 1건일 때만 players.id. 동명이인이면 경고 후 포기."""
        if not name or team_id is None:
            return None
        cur.execute(PLAYER_IDS_BY_NAME_TEAM, (name, team_id))
        rows = cur.fetchall()
        if len(rows) > 1:
            log.warning("apply_trades: ambiguous player %s (team_id=%s)", name, team_id)
        return rows[0][0] if len(rows) == 1 else None

    # ---------- 상태 / 구장 ----------
    def status_id(self, name) -> int:
        return self._lookup_or_insert(STATUS_SELECT, STATUS_INSERT, name)

    def stadium_id(self, name):
        if not name:
            return None
        return self._lookup_or_insert(STADIUM_SELECT, STADIUM_INSERT, name)

    def position_id(self, name):
        if not name:
            return None
        return self._lookup_or_insert(POSITION_SELECT, POSITION_INSERT, name)

    # ---------- 선수 (박스스코어 kbo_player_id 해소) ----------
    def resolve_players(self, refs, team_ids) -> dict:
        """PlayerRef 목록 -> {pcode: players.id}. pcode == kbo_player_id 전제.

        1) kbo_player_id 로 일괄 조회 — DB 이름과 API 이름이 다르면 동치 전제
           훼손 신호라 warning. 2) 없으면 신규 행 INSERT (이후 로스터 잡이 같은
           키로 upsert 하므로 자연 병합). 팀 코드가 미지(비표준 팀)면 스킵.
        """
        uniq = {r.pcode: r for r in refs}
        if not uniq:
            return {}
        codes = list(uniq)
        ph = ",".join(["%s"] * len(codes))
        out = {}
        for kbo_id, pk, db_name in self.fetch_all(PLAYER_BY_KBO_ID.format(ph=ph), codes):
            out[kbo_id] = pk
            if db_name != uniq[kbo_id].name:
                log.warning("resolve_players: name mismatch pcode=%s db=%s api=%s",
                            kbo_id, db_name, uniq[kbo_id].name)
        with self._conn.cursor() as cur:
            for pcode, ref in uniq.items():
                if pcode in out:
                    continue
                team_id = team_ids.get(ref.team_code)
                if team_id is None:
                    log.warning("resolve_players: unknown team %s (pcode=%s %s)",
                                ref.team_code, pcode, ref.name)
                    continue
                cur.execute(PLAYER_INSERT, (pcode, ref.name, team_id))
                out[pcode] = cur.lastrowid
        self._conn.commit()
        return out

    # ---------- 경기 / 라인업 ----------
    def upsert_game(self, game, *, team_ids, stadium_id, status_id) -> int:
        """GameRow upsert 후 games.id 반환. game_date 는 시작시각 포함 DATETIME."""
        g = game
        dt = f"{g.game_date} {g.start_time}:00" if g.start_time else f"{g.game_date} 00:00:00"
        with self._conn.cursor() as cur:
            cur.execute(GAME_UPSERT, (
                g.game_id, dt, team_ids[g.home_team_code], team_ids[g.away_team_code],
                stadium_id, g.home_score, g.away_score, status_id,
            ))
            pk = cur.lastrowid
        self._conn.commit()
        return pk

    def sync_game(self, *, naver_game_id, game_dt, home_team_id, away_team_id,
                 home_score, away_score, status_id, stadium_id=None,
                 current_inning=None, inning_half=None) -> int:
        """games_sync 잡 전용 upsert. GAME_SYNC_UPSERT 를 써서 점수·구장은
        COALESCE로 기존 값을 지킨다(구장의 최종 진실은 records 잡).

        `current_inning`/`inning_half` 는 그와 달리 매번 덮어쓴다 — 진행 중이
        아닌 경기는 None 을 받아 NULL 로 지워져야 한다(GAME_SYNC_UPSERT 주석 참고).
        """
        with self._conn.cursor() as cur:
            cur.execute(GAME_SYNC_UPSERT, (
                naver_game_id, game_dt, home_team_id, away_team_id,
                stadium_id, home_score, away_score, status_id,
                current_inning, inning_half,
            ))
            pk = cur.lastrowid
        self._conn.commit()
        return pk

    def set_cancel_reason(self, *, date, home_team_id, away_team_id, reason) -> int:
        """(날짜, 대진) 의 games 행에 취소 사유를 기록하고 **실제로 바뀐** 행 수를 반환.

        0 이 정상인 경우가 둘이고, 둘의 의미가 다르다:
          - 이미 같은 사유가 적혀 있다 (매일 재실행 — 정상, 아무것도 안 쓴다)
          - 매칭될 행이 없다 (games_sync 가 그 날짜를 아직 안 적재했거나 games 에
            없는 경기). 사유는 경기 행이 있어야 붙으므로 이쪽은 순서 문제다.
        둘을 구분해야 하면 호출자가 별도 조회로 확인한다 — 이 반환값만으로는 못 나눈다.
        """
        with self._conn.cursor() as cur:
            cur.execute(CANCEL_REASON_UPDATE,
                        (reason, date, date, home_team_id, away_team_id, reason))
            changed = cur.rowcount
        self._conn.commit()
        return changed

    def lineup_done_games(self, game_pks) -> set:
        """선발 라인업 적재가 끝난 game PK 집합 (LINEUP_DONE_GAMES 판정).

        games_sync 의 라인업 단계가 매 폴링마다 preview 를 다시 긁지 않게 하는
        스킵 근거다. Lambda /tmp 나 프로세스 메모리가 아니라 DB 를 근거로 삼는
        이유는 콜드스타트·동시 실행·재배포에도 판정이 유지돼야 하기 때문.
        """
        pks = [pk for pk in game_pks if pk]
        if not pks:
            return set()
        ph = ",".join(["%s"] * len(pks))
        return {row[0] for row in self.fetch_all(LINEUP_DONE_GAMES.format(ph=ph), pks)}

    def games_with_stadium(self, naver_game_ids) -> set:
        """구장이 이미 채워진 naver_game_id 집합 (GAMES_WITH_STADIUM 판정)."""
        ids = [gid for gid in naver_game_ids if gid]
        if not ids:
            return set()
        ph = ",".join(["%s"] * len(ids))
        return {row[0] for row in self.fetch_all(GAMES_WITH_STADIUM.format(ph=ph), ids)}

    def delete_lineups_except(self, game_pk, player_ids) -> int:
        """박스스코어에 없는 라인업 행 삭제 -> 지운 행 수.

        preview 로 미리 적재한 선발이 경기 직전 교체되면 그 선수는 박스스코어에
        없는 채로 is_starter=1 인 유령으로 남는다. records 잡이 확정 적재한 뒤
        불러 걷어낸다.

        player_ids 가 비면 아무것도 하지 않는다. 빈 목록으로 만든 NOT IN () 은
        MySQL 이 문법 오류로 거부하므로(실측) 통째 삭제가 나지는 않지만, 그 예외가
        records 잡의 그 경기 처리를 통째로 실패시킨다 — 파싱이 빈 결과를 내는 것과
        적재를 실패시키는 것은 다른 문제다.
        """
        ids = [pk for pk in set(player_ids or ()) if pk]
        if not ids:
            return 0
        ph = ",".join(["%s"] * len(ids))
        with self._conn.cursor() as cur:
            cur.execute(LINEUP_DELETE_EXCEPT.format(ph=ph), [game_pk, *ids])
            deleted = cur.rowcount
        self._conn.commit()
        return deleted

    def delete_lineups_if_unplayed(self, game_pk) -> int:
        """치르지 않은 경기(박스스코어 없음)의 라인업 삭제 -> 지운 행 수.

        취소 경기 전용. 우천 취소는 라인업 공시 뒤에 나는 일이 잦은데, 취소 경기엔
        records 잡이 영영 돌지 않아 delete_lineups_except 가 닿지 않는다.
        """
        with self._conn.cursor() as cur:
            cur.execute(LINEUP_DELETE_UNPLAYED, (game_pk, game_pk))
            deleted = cur.rowcount
        self._conn.commit()
        return deleted

    def upsert_lineups(self, game_pk, lineups, player_map, team_ids) -> None:
        """LineupRow 목록 upsert. position(네이버 원문 표기) -> 정식 명칭으로
        변환한 뒤 position_id 는 호출당 memo dict로 해소한다 (포지션은 ~10종이라
        중복 lookup 을 막는 게 목적. memo 키도 변환 후 값 기준)."""
        rows = [r for r in lineups if r.pcode in player_map and r.team_code in team_ids]
        position_memo: dict = {}

        def resolved_position_id(raw):
            name = position_name(raw)
            if name not in position_memo:
                position_memo[name] = self.position_id(name)
            return position_memo[name]

        self._many(LINEUP_UPSERT, [(
            game_pk, team_ids[r.team_code], player_map[r.pcode],
            r.bat_order, resolved_position_id(r.position), r.is_starter, r.decision,
        ) for r in rows])

    def upsert_batting(self, game_pk, rows, player_map) -> None:
        """BattingRow 목록 upsert. player_map 에 없는 pcode(미해소 선수)는 스킵."""
        self._many(BATTER_UPSERT, [(
            game_pk, player_map[r.pcode], r.at_bats, r.runs, r.hits, r.home_runs,
            r.rbi, r.walks, r.strikeouts, r.stolen_bases,
        ) for r in rows if r.pcode in player_map])

    def upsert_pitching(self, game_pk, rows, player_map) -> None:
        """PitchingRow 목록 upsert. player_map 에 없는 pcode(미해소 선수)는 스킵."""
        self._many(PITCHER_UPSERT, [(
            game_pk, player_map[r.pcode], r.seq, r.ip_display, r.ip_outs,
            r.batters_faced, r.at_bats, r.hits, r.runs, r.earned_runs,
            r.home_runs, r.walks_hbp, r.strikeouts,
        ) for r in rows if r.pcode in player_map])

    # ---------- 공통 ----------
    def _lookup_or_insert(self, select_sql, insert_sql, name) -> int:
        with self._conn.cursor() as cur:
            cur.execute(select_sql, (name,))
            row = cur.fetchone()
            if row:
                return row[0]
            cur.execute(insert_sql, (name,))
            pk = cur.lastrowid
        self._conn.commit()
        return pk

    def _many(self, sql, rows) -> None:
        if not rows:
            return
        with self._conn.cursor() as cur:
            cur.executemany(sql, rows)
        self._conn.commit()

    def fetch_all(self, sql, params=()) -> list:
        """읽기 헬퍼 (exporter·엔티티 해소용)."""
        with self._conn.cursor() as cur:
            cur.execute(sql, params)
            return list(cur.fetchall())

    def close(self) -> None:
        self._conn.close()
