"""운영 서비스 스키마(MySQL) 적재 싱크.

테이블 구조의 유일한 원천은 domain 모듈 JPA 엔티티다(teams/players/stadiums/
game_statuses/games/game_lineups — dev_be 브랜치 VictoryFairy_BE/domain 참고.
이 리포에는 DDL 사본을 두지 않으며 스키마 변경·생성은 dev_be 소관). 수집기는 소스
자연키(teams.code, players.kbo_player_id, games.naver_game_id)로
upsert 해 재실행에도 멱등이다. PK 는 전부 서비스 소유의 AUTO_INCREMENT id.
"""
import logging

import pymysql

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
ROSTER_PLAYER_UPSERT = (
    "INSERT INTO players (kbo_player_id, name, team_id, average, created_at, updated_at) "
    "VALUES (%s, %s, %s, 0, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), team_id=VALUES(team_id), updated_at=NOW(6)"
)

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

# games_sync 전용: 점수는 제공될 때만 갱신(COALESCE), stadium_id 는 records 잡
# 소유라 건드리지 않는다(INSERT 시 NULL, UPDATE 목록에서 제외).
GAME_SYNC_UPSERT = (
    "INSERT INTO games (naver_game_id, game_date, home_team_id, away_team_id, "
    " stadium_id, home_score, away_score, game_status_id, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, NULL, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE game_date=VALUES(game_date), "
    "  home_score=COALESCE(VALUES(home_score), home_score), "
    "  away_score=COALESCE(VALUES(away_score), away_score), "
    "  game_status_id=VALUES(game_status_id), updated_at=NOW(6)"
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
                   [(p.player_id, p.name, team_id) for p in players])

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
                 home_score, away_score, status_id) -> int:
        """games_sync 잡 전용 upsert. GAME_SYNC_UPSERT 를 써서 stadium_id 는
        건드리지 않고(records 잡 소유), 점수는 COALESCE로 기존 값을 지킨다."""
        with self._conn.cursor() as cur:
            cur.execute(GAME_SYNC_UPSERT, (
                naver_game_id, game_dt, home_team_id, away_team_id,
                home_score, away_score, status_id,
            ))
            pk = cur.lastrowid
        self._conn.commit()
        return pk

    def upsert_lineups(self, game_pk, lineups, player_map, team_ids) -> None:
        """LineupRow 목록 upsert. position(명) -> position_id 는 호출당 memo dict로
        해소한다 (포지션은 ~10종이라 중복 lookup 을 막는 게 목적)."""
        rows = [r for r in lineups if r.pcode in player_map and r.team_code in team_ids]
        position_memo: dict = {}

        def resolved_position_id(name):
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
