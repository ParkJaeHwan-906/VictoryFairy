import pymysql

TEAMS_UPSERT = (
    "INSERT INTO teams (team_code, name, full_name) VALUES (%s, %s, %s) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), full_name=VALUES(full_name)"
)

# 현재상태 필드는 D >= last_registered_on 일 때만 전진. 신원(name/birth)은 항상.
PLAYERS_UPSERT = (
    "INSERT INTO players "
    "(player_id, name, team_code, back_number, position, throw_bat, "
    " birth_date, height_cm, weight_kg, is_first_team, last_registered_on) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, TRUE, %s) "
    "ON DUPLICATE KEY UPDATE "
    "  name=VALUES(name), birth_date=VALUES(birth_date), "
    "  team_code   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(team_code), team_code), "
    "  back_number = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(back_number), back_number), "
    "  position    = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(position), position), "
    "  throw_bat   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(throw_bat), throw_bat), "
    "  height_cm   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(height_cm), height_cm), "
    "  weight_kg   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(weight_kg), weight_kg), "
    "  is_first_team = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), TRUE, is_first_team), "
    "  last_registered_on = GREATEST(COALESCE(last_registered_on,'0001-01-01'), VALUES(last_registered_on))"
)

REG_UPSERT = (
    "INSERT INTO player_registrations (snapshot_date, player_id, team_code) "
    "VALUES (%s, %s, %s) ON DUPLICATE KEY UPDATE team_code=VALUES(team_code)"
)

# 그날 명단에 없던(=last_registered_on이 D로 갱신되지 않은) 이 팀 선수를 1군에서 내림.
MARK_NOT_FIRST = (
    "UPDATE players SET is_first_team=FALSE "
    "WHERE team_code=%s AND is_first_team=TRUE "
    "AND (last_registered_on IS NULL OR last_registered_on < %s)"
)

# --- 경기 기록 ---
GAME_PLAYER_UPSERT = (
    "INSERT INTO game_players (naver_pcode, name, team_code) VALUES (%s, %s, %s) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), "
    "  team_code=COALESCE(VALUES(team_code), team_code)"
)

# name+team_code가 players에서 유일할 때만 kbo_player_id 링크(동명이인 방지).
LINK_KBO_IDS = (
    "UPDATE game_players gp JOIN ("
    "  SELECT name, team_code, MIN(player_id) pid, COUNT(*) c"
    "  FROM players GROUP BY name, team_code"
    ") p ON gp.name=p.name AND gp.team_code=p.team_code AND p.c=1 "
    "SET gp.kbo_player_id=p.pid WHERE gp.kbo_player_id IS NULL"
)

GAME_UPSERT = (
    "INSERT INTO games (game_id, game_date, game_type, round_no, stadium, start_time, "
    " away_team_code, home_team_code, away_score, home_score, away_hits, home_hits, "
    " away_errors, home_errors, away_bb, home_bb, winner, away_starter_uid, "
    " home_starter_uid) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
    "ON DUPLICATE KEY UPDATE "
    "  game_date=VALUES(game_date), game_type=VALUES(game_type), round_no=VALUES(round_no), "
    "  stadium=VALUES(stadium), start_time=VALUES(start_time), "
    "  away_team_code=VALUES(away_team_code), home_team_code=VALUES(home_team_code), "
    "  away_score=VALUES(away_score), home_score=VALUES(home_score), "
    "  away_hits=VALUES(away_hits), home_hits=VALUES(home_hits), "
    "  away_errors=VALUES(away_errors), home_errors=VALUES(home_errors), "
    "  away_bb=VALUES(away_bb), home_bb=VALUES(home_bb), winner=VALUES(winner), "
    "  away_starter_uid=VALUES(away_starter_uid), home_starter_uid=VALUES(home_starter_uid)"
)

INNINGS_UPSERT = (
    "INSERT INTO game_innings (game_id, inning, is_home, runs) VALUES (%s, %s, %s, %s) "
    "ON DUPLICATE KEY UPDATE runs=VALUES(runs)"
)

PITCHING_UPSERT = (
    "INSERT INTO game_pitching (game_id, player_uid, team_code, is_home, seq, decision, "
    " ip_display, ip_outs, batters_faced, at_bats, hits, runs, earned_runs, home_runs, "
    " walks_hbp, strikeouts) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
    "ON DUPLICATE KEY UPDATE team_code=VALUES(team_code), is_home=VALUES(is_home), "
    "  seq=VALUES(seq), decision=VALUES(decision), ip_display=VALUES(ip_display), "
    "  ip_outs=VALUES(ip_outs), batters_faced=VALUES(batters_faced), at_bats=VALUES(at_bats), "
    "  hits=VALUES(hits), runs=VALUES(runs), earned_runs=VALUES(earned_runs), "
    "  home_runs=VALUES(home_runs), walks_hbp=VALUES(walks_hbp), strikeouts=VALUES(strikeouts)"
)

BATTING_UPSERT = (
    "INSERT INTO game_batting (game_id, player_uid, team_code, is_home, bat_order, position, "
    " at_bats, runs, hits, home_runs, rbi, walks, strikeouts, stolen_bases) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
    "ON DUPLICATE KEY UPDATE team_code=VALUES(team_code), is_home=VALUES(is_home), "
    "  bat_order=VALUES(bat_order), position=VALUES(position), at_bats=VALUES(at_bats), "
    "  runs=VALUES(runs), hits=VALUES(hits), home_runs=VALUES(home_runs), rbi=VALUES(rbi), "
    "  walks=VALUES(walks), strikeouts=VALUES(strikeouts), stolen_bases=VALUES(stolen_bases)"
)


class DbSink:
    def __init__(self, settings, connection=None):
        self._conn = connection or pymysql.connect(
            host=settings.db_host, port=settings.db_port, user=settings.db_user,
            password=settings.db_password, database=settings.db_name,
            charset="utf8mb4", autocommit=False,
        )

    def upsert_teams(self, teams) -> None:
        self._many(TEAMS_UPSERT, [(t.team_code, t.name, t.full_name) for t in teams])

    def upsert_players(self, players, team_code, snapshot_date) -> None:
        self._many(PLAYERS_UPSERT, [
            (p.player_id, p.name, team_code, p.back_number, p.position, p.throw_bat,
             p.birth_date, p.height_cm, p.weight_kg, snapshot_date) for p in players])

    def insert_registrations(self, snapshot_date, players, team_code) -> None:
        self._many(REG_UPSERT, [(snapshot_date, p.player_id, team_code) for p in players])

    def mark_not_first_team(self, team_code, snapshot_date) -> None:
        with self._conn.cursor() as cur:
            cur.execute(MARK_NOT_FIRST, (team_code, snapshot_date))
        self._conn.commit()

    # ---------- 경기 기록 ----------
    def upsert_game_players(self, refs) -> dict:
        """PlayerRef 목록을 game_players에 upsert하고 {pcode: player_uid} 반환."""
        uniq = {r.pcode: r for r in refs}
        self._many(GAME_PLAYER_UPSERT,
                   [(r.pcode, r.name, r.team_code) for r in uniq.values()])
        if not uniq:
            return {}
        codes = list(uniq)
        placeholders = ",".join(["%s"] * len(codes))
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT naver_pcode, player_uid FROM game_players "
                f"WHERE naver_pcode IN ({placeholders})", codes)
            return {code: uid for code, uid in cur.fetchall()}

    def upsert_game(self, game, uid_map) -> None:
        g = game
        self._many(GAME_UPSERT, [(
            g.game_id, g.game_date, g.game_type, g.round_no, g.stadium, g.start_time,
            g.away_team_code, g.home_team_code, g.away_score, g.home_score,
            g.away_hits, g.home_hits, g.away_errors, g.home_errors, g.away_bb, g.home_bb,
            g.winner, uid_map.get(g.away_starter_pcode), uid_map.get(g.home_starter_pcode),
        )])

    def upsert_innings(self, game) -> None:
        rows = []
        for side, is_home in (("away", False), ("home", True)):
            for i, runs in enumerate(game.inn_scores.get(side) or [], start=1):
                if runs is None:
                    continue
                rows.append((game.game_id, i, is_home, int(runs)))
        self._many(INNINGS_UPSERT, rows)

    def upsert_pitching(self, game_id, rows, uid_map) -> None:
        self._many(PITCHING_UPSERT, [(
            game_id, uid_map[r.pcode], r.team_code, r.is_home, r.seq, r.decision,
            r.ip_display, r.ip_outs, r.batters_faced, r.at_bats, r.hits, r.runs,
            r.earned_runs, r.home_runs, r.walks_hbp, r.strikeouts,
        ) for r in rows if r.pcode in uid_map])

    def upsert_batting(self, game_id, rows, uid_map) -> None:
        self._many(BATTING_UPSERT, [(
            game_id, uid_map[r.pcode], r.team_code, r.is_home, r.bat_order, r.position,
            r.at_bats, r.runs, r.hits, r.home_runs, r.rbi, r.walks, r.strikeouts,
            r.stolen_bases,
        ) for r in rows if r.pcode in uid_map])

    def link_kbo_player_ids(self) -> None:
        with self._conn.cursor() as cur:
            cur.execute(LINK_KBO_IDS)
        self._conn.commit()

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
