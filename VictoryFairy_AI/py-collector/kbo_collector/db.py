"""운영 서비스 스키마(MySQL) 적재 싱크.

테이블 구조의 유일한 원천은 domain 모듈 JPA 엔티티다(teams/players/stadiums/
game_statuses/games/game_lineups — dev_be 브랜치 VictoryFairy_BE/domain 참고.
이 리포에는 DDL 사본을 두지 않으며 스키마 변경·생성은 dev_be 소관). 수집기는 소스
자연키(teams.code, players.kbo_player_id/naver_pcode, games.naver_game_id)로
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

# 박스스코어 선수 해소: pcode 일괄 조회 → kbo_player_id 동치 백필 → (이름, 팀)
# 유일 매칭 백필 → 신규 INSERT
PLAYER_BY_PCODE = "SELECT naver_pcode, id FROM players WHERE naver_pcode IN ({ph})"
# 실측상 네이버 pcode == KBO playerId (2026-07 박스스코어·로스터 교집합 228명 전수 일치).
# 동명이인도 이 경로로 안전하게 붙고, 이름+팀 휴리스틱은 폴백으로만 쓴다.
PLAYER_BY_KBO_ID_EQ = (
    "SELECT id FROM players WHERE kbo_player_id=%s AND naver_pcode IS NULL"
)
PLAYER_BY_NAME_TEAM = (
    "SELECT id FROM players WHERE name=%s AND team_id=%s AND naver_pcode IS NULL"
)
PLAYER_SET_PCODE = "UPDATE players SET naver_pcode=%s WHERE id=%s"
PLAYER_INSERT_PCODE = (
    "INSERT INTO players (naver_pcode, name, team_id, average, created_at, updated_at) "
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

LINEUP_UPSERT = (
    "INSERT INTO game_lineups (game_id, team_id, player_id, bat_order, position, "
    " is_starter, decision, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), bat_order=VALUES(bat_order), "
    "  position=VALUES(position), is_starter=VALUES(is_starter), decision=VALUES(decision), "
    "  updated_at=NOW(6)"
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

    # ---------- 선수 (박스스코어 pcode 해소) ----------
    def resolve_players(self, refs, team_ids) -> dict:
        """PlayerRef 목록 -> {pcode: players.id}.

        1) naver_pcode 로 일괄 조회. 2) kbo_player_id == pcode 인 로스터 행에 백필
        (실측상 두 ID 는 같은 값 — 동명이인도 정확히 붙는다). 3) 미등록 pcode 는
        (이름, 팀) 유일 매칭이면 로스터 행에 pcode 백필(동명이인 2건 이상이면 매칭
        포기). 4) 그래도 없으면 신규 행 INSERT. 팀 코드가 미지(비표준 팀)면 스킵.
        """
        uniq = {r.pcode: r for r in refs}
        if not uniq:
            return {}
        codes = list(uniq)
        ph = ",".join(["%s"] * len(codes))
        out = {p: i for p, i in self.fetch_all(PLAYER_BY_PCODE.format(ph=ph), codes)}
        with self._conn.cursor() as cur:
            for pcode, ref in uniq.items():
                if pcode in out:
                    continue
                team_id = team_ids.get(ref.team_code)
                if team_id is None:
                    log.warning("resolve_players: unknown team %s (pcode=%s %s)",
                                ref.team_code, pcode, ref.name)
                    continue
                cur.execute(PLAYER_BY_KBO_ID_EQ, (pcode,))
                rows = cur.fetchall()
                if len(rows) == 1:
                    cur.execute(PLAYER_SET_PCODE, (pcode, rows[0][0]))
                    out[pcode] = rows[0][0]
                    continue
                cur.execute(PLAYER_BY_NAME_TEAM, (ref.name, team_id))
                rows = cur.fetchall()
                if len(rows) == 1:
                    cur.execute(PLAYER_SET_PCODE, (pcode, rows[0][0]))
                    out[pcode] = rows[0][0]
                else:
                    cur.execute(PLAYER_INSERT_PCODE, (pcode, ref.name, team_id))
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

    def upsert_lineups(self, game_pk, lineups, player_map, team_ids) -> None:
        self._many(LINEUP_UPSERT, [(
            game_pk, team_ids[r.team_code], player_map[r.pcode],
            r.bat_order, r.position, r.is_starter, r.decision,
        ) for r in lineups if r.pcode in player_map and r.team_code in team_ids])

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
