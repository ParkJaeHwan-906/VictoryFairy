from types import SimpleNamespace

from kbo_collector.db import (
    DbSink, TEAMS_UPSERT, ROSTER_PLAYER_UPSERT, GAME_UPSERT, LINEUP_UPSERT,
    PLAYER_INSERT, BATTER_UPSERT, PITCHER_UPSERT, POSITION_SELECT, POSITION_CODES,
)
from kbo_collector.dimensions import PlayerRow, TeamRow
from kbo_collector.game_records import LineupRow, PlayerRef, BattingRow, PitchingRow


class FakeCursor:
    """SELECT 는 fetch_results 큐에서 꺼내 답하고, 나머지는 로그만 남긴다."""

    def __init__(self, conn):
        self.conn = conn

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql, params=None):
        self.conn.log.append(("execute", sql, params))
        if sql.lstrip().upper().startswith("SELECT"):
            self._rows = self.conn.fetch_results.pop(0) if self.conn.fetch_results else []
        else:
            self._rows = []
            self.conn.last_id += 1

    def executemany(self, sql, rows):
        self.conn.log.append(("executemany", sql, rows))

    def fetchone(self):
        return self._rows[0] if self._rows else None

    def fetchall(self):
        return list(self._rows)

    @property
    def lastrowid(self):
        return self.conn.last_id


class FakeConn:
    def __init__(self, fetch_results=None):
        self.log = []
        self.commits = 0
        self.fetch_results = list(fetch_results or [])
        self.last_id = 100  # INSERT 마다 1씩 증가

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commits += 1

    def close(self):
        pass


def _roster_player(pid="60123"):
    return PlayerRow(pid, "손주영", "1", "투수", "좌투좌타", "1998-05-12", 184, 88)


def test_upsert_teams_returns_code_to_id_map():
    conn = FakeConn(fetch_results=[[("LG", 2), ("OB", 1)]])
    ids = DbSink(None, connection=conn).upsert_teams(
        [TeamRow("LG", "LG", "LG 트윈스"), TeamRow("OB", "두산", "두산 베어스")])
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == TEAMS_UPSERT
    assert rows == [("LG", "LG"), ("OB", "두산")]
    assert ids == {"LG": 2, "OB": 1}


def test_upsert_roster_players_uses_kbo_id_and_team_pk():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_roster_players([_roster_player()], 2)
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == ROSTER_PLAYER_UPSERT
    assert rows == [("60123", "손주영", 2)]
    assert conn.commits == 1


def test_status_id_lookup_hits_existing_row():
    conn = FakeConn(fetch_results=[[(7,)]])
    assert DbSink(None, connection=conn).status_id("FINISHED") == 7
    assert len(conn.log) == 1  # INSERT 없음


def test_stadium_id_inserts_when_missing_and_none_passthrough():
    conn = FakeConn(fetch_results=[[]])
    sink = DbSink(None, connection=conn)
    assert sink.stadium_id("잠실") == 101   # last_id 100 -> INSERT 후 101
    assert sink.stadium_id(None) is None


def test_position_id_lookup_hits_existing_row():
    conn = FakeConn(fetch_results=[[(7,)]])
    assert DbSink(None, connection=conn).position_id("중") == 7
    assert len(conn.log) == 1  # INSERT 없음


def test_position_id_inserts_when_missing_and_none_passthrough():
    conn = FakeConn(fetch_results=[[]])
    sink = DbSink(None, connection=conn)
    assert sink.position_id("포") == 101   # last_id 100 -> INSERT 후 101
    assert sink.position_id(None) is None


def test_lineup_upsert_sql_targets_position_id_column():
    assert "position_id" in LINEUP_UPSERT
    assert "position_id=VALUES(position_id)" in LINEUP_UPSERT


def test_resolve_players_kbo_id_hit_and_insert(caplog):
    refs = [PlayerRef("P1", "기존선수", "LG"), PlayerRef("P2", "개명선수", "LG"),
            PlayerRef("P3", "신규선수", "OB")]
    conn = FakeConn(fetch_results=[
        [("P1", 11, "기존선수"), ("P2", 22, "옛이름")],  # 일괄 조회 (P3 미존재)
    ])
    with caplog.at_level("WARNING", logger="db"):
        out = DbSink(None, connection=conn).resolve_players(refs, {"LG": 2, "OB": 1})
    # P3 는 유일한 INSERT — FakeCursor last_id 100 -> 101
    assert out == {"P1": 11, "P2": 22, "P3": 101}
    sqls = [s for _, s, _ in conn.log]
    assert PLAYER_INSERT in sqls
    # 이름 불일치는 pcode==kbo_player_id 동치 전제 훼손 신호 — warning 1건
    assert "name mismatch" in caplog.text and "P2" in caplog.text


def test_resolve_players_skips_unknown_team():
    refs = [PlayerRef("P9", "올스타", "DR")]
    conn = FakeConn(fetch_results=[[]])
    out = DbSink(None, connection=conn).resolve_players(refs, {"LG": 2})
    assert out == {}


def test_upsert_game_combines_datetime_and_returns_pk():
    game = SimpleNamespace(game_id="20260708LGSS02026", game_date="2026-07-08",
                           start_time="18:30", home_team_code="SS",
                           away_team_code="LG", home_score=3, away_score=5)
    conn = FakeConn()
    pk = DbSink(None, connection=conn).upsert_game(
        game, team_ids={"LG": 2, "SS": 3}, stadium_id=9, status_id=7)
    kind, sql, params = conn.log[0]
    assert sql == GAME_UPSERT
    assert params == ("20260708LGSS02026", "2026-07-08 18:30:00", 3, 2, 9, 3, 5, 7)
    assert pk == 101


def test_upsert_lineups_maps_ids_and_drops_unresolved():
    rows = [
        LineupRow("P1", "LG", False, 1, "중", True, None),
        LineupRow("P2", "LG", False, None, "투", True, "W"),
        LineupRow("PX", "LG", False, 9, "포", False, None),  # 미해소 pcode -> 제외
    ]
    # position lookup: "중"->CF->30, "투"->P->40 (PX 는 제외되므로 "포" lookup 없음)
    conn = FakeConn(fetch_results=[[(30,)], [(40,)]])
    DbSink(None, connection=conn).upsert_lineups(
        55, rows, {"P1": 11, "P2": 12}, {"LG": 2})
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == LINEUP_UPSERT
    assert params == [(55, 2, 11, 1, 30, True, None),
                      (55, 2, 12, None, 40, True, "W")]
    selects = [(s, p) for k, s, p in conn.log if k == "execute" and s.startswith("SELECT")]
    assert len(selects) == 2  # 포지션 2종 각 1회 lookup, "포" 는 미조회
    # POSITION_SELECT 바인딩 값은 네이버 원문이 아니라 자체 영문 약어여야 한다.
    assert selects == [(POSITION_SELECT, ("CF",)), (POSITION_SELECT, ("P",))]


def test_upsert_lineups_memoizes_position_lookup_per_call():
    rows = [
        LineupRow("P1", "LG", False, 1, "중", True, None),
        LineupRow("P2", "LG", False, 2, "중", True, None),  # 같은 포지션 재사용
        LineupRow("P3", "LG", False, 3, "투", True, None),
    ]
    conn = FakeConn(fetch_results=[[(50,)], [(60,)]])  # "중"->50, "투"->60, 각 1회만
    DbSink(None, connection=conn).upsert_lineups(
        55, rows, {"P1": 11, "P2": 12, "P3": 13}, {"LG": 2})
    selects = [(s, p) for k, s, p in conn.log if k == "execute" and s.startswith("SELECT")]
    assert len(selects) == 2  # memo 적중 -> "중" 은 한 번만 조회 (memo 키도 변환 후 값 기준)
    assert selects == [(POSITION_SELECT, ("CF",)), (POSITION_SELECT, ("P",))]
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == LINEUP_UPSERT
    assert params == [(55, 2, 11, 1, 50, True, None),
                      (55, 2, 12, 2, 50, True, None),
                      (55, 2, 13, 3, 60, True, None)]


def test_upsert_lineups_unknown_position_notation_falls_back_to_raw(caplog):
    """매핑에 없는 표기(예: 미지 신설 표기)는 warning 후 원문 그대로 바인딩한다
    (수집이 절대 깨지지 않는 기존 lookup-or-insert 설계 유지)."""
    rows = [LineupRow("P1", "LG", False, 1, "겸", True, None)]
    conn = FakeConn(fetch_results=[[(99,)]])
    with caplog.at_level("WARNING", logger="db"):
        DbSink(None, connection=conn).upsert_lineups(55, rows, {"P1": 11}, {"LG": 2})
    selects = [(s, p) for k, s, p in conn.log if k == "execute" and s.startswith("SELECT")]
    assert selects == [(POSITION_SELECT, ("겸",))]
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == LINEUP_UPSERT
    assert params == [(55, 2, 11, 1, 99, True, None)]
    assert "unknown position notation" in caplog.text and "겸" in caplog.text


def test_position_codes_covers_all_twelve_naver_notations():
    """네이버 박스스코어 pos 표기 12종 -> 자체 영문 약어 확정 매핑 전수 단언."""
    assert POSITION_CODES == {
        "투": "P", "포": "C", "一": "1B", "二": "2B", "三": "3B",
        "유": "SS", "좌": "LF", "중": "CF", "우": "RF", "지": "DH",
        "타": "PH", "주": "PR",
    }


def test_upsert_lineups_binds_null_for_missing_position():
    rows = [LineupRow("P1", "LG", False, None, None, True, None)]  # 대타/교체 등 미표기
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_lineups(
        55, rows, {"P1": 11}, {"LG": 2})
    selects = [s for k, s, _ in conn.log if k == "execute" and s.startswith("SELECT")]
    assert selects == []  # position=None -> lookup 자체를 안 함
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == LINEUP_UPSERT
    assert params == [(55, 2, 11, None, None, True, None)]


def _batting_row(pcode="P1", **overrides):
    base = dict(pcode=pcode, team_code="LG", is_home=False, bat_order=3, position="중",
               at_bats=4, runs=1, hits=2, home_runs=1, rbi=2, walks=0, strikeouts=1,
               stolen_bases=0)
    base.update(overrides)
    return BattingRow(**base)


def _pitching_row(pcode="P1", **overrides):
    base = dict(pcode=pcode, team_code="LG", is_home=False, seq=0, decision="W",
               ip_display="6", ip_outs=18, batters_faced=25, at_bats=22, hits=5,
               runs=2, earned_runs=2, home_runs=1, walks_hbp=1, strikeouts=7)
    base.update(overrides)
    return PitchingRow(**base)


def test_upsert_batting_maps_ids_and_drops_unresolved():
    rows = [_batting_row("P1"), _batting_row("PX", bat_order=4, position="포")]  # PX 미해소
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_batting(55, rows, {"P1": 11})
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == BATTER_UPSERT
    assert params == [(55, 11, 4, 1, 2, 1, 2, 0, 1, 0)]


def test_upsert_batting_all_unresolved_is_noop():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_batting(55, [_batting_row("PX")], {})
    assert conn.log == [] and conn.commits == 0


def test_upsert_pitching_maps_ids_and_drops_unresolved():
    rows = [_pitching_row("P1"), _pitching_row("PX", seq=1, decision=None)]  # PX 미해소
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_pitching(55, rows, {"P1": 11})
    kind, sql, params = conn.log[-1]
    assert kind == "executemany" and sql == PITCHER_UPSERT
    assert params == [(55, 11, 0, "6", 18, 25, 22, 5, 2, 2, 1, 1, 7)]


def test_upsert_pitching_all_unresolved_is_noop():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_pitching(55, [_pitching_row("PX")], {})
    assert conn.log == [] and conn.commits == 0


def test_empty_rows_noop():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_roster_players([], 2)
    assert conn.log == [] and conn.commits == 0


# --------------------------------------------------------------------------- games_sync (Task 8)
# GAME_SYNC_UPSERT / sync_game don't exist yet -> imported lazily inside each test
# so the rest of this file still collects cleanly while these are RED.
def test_game_sync_upsert_sql_inserts_null_stadium_and_coalesces_scores():
    from kbo_collector.db import GAME_SYNC_UPSERT
    assert "VALUES (%s, %s, %s, %s, NULL, %s, %s, %s, NOW(6), NOW(6))" in GAME_SYNC_UPSERT
    assert "home_score=COALESCE(VALUES(home_score), home_score)" in GAME_SYNC_UPSERT
    assert "away_score=COALESCE(VALUES(away_score), away_score)" in GAME_SYNC_UPSERT


def test_game_sync_upsert_sql_excludes_stadium_id_from_update_clause():
    from kbo_collector.db import GAME_SYNC_UPSERT
    # stadium_id 는 records 잡 소유 — games_sync 의 UPDATE 목록에 있으면 records가
    # 적재한 구장을 games_sync가 NULL로 덮어써버린다.
    update_clause = GAME_SYNC_UPSERT.split("ON DUPLICATE KEY UPDATE", 1)[1]
    assert "stadium_id" not in update_clause


def test_sync_game_upserts_with_null_stadium_and_commits():
    from kbo_collector.db import GAME_SYNC_UPSERT
    conn = FakeConn()
    pk = DbSink(None, connection=conn).sync_game(
        naver_game_id="20260708LGSS02026", game_dt="2026-07-08 18:30:00",
        home_team_id=3, away_team_id=2, home_score=5, away_score=3, status_id=7)
    kind, sql, params = conn.log[0]
    assert kind == "execute" and sql == GAME_SYNC_UPSERT
    assert params == ("20260708LGSS02026", "2026-07-08 18:30:00", 3, 2, 5, 3, 7)
    assert pk == 101
    assert conn.commits == 1


def test_sync_game_passes_none_scores_through_for_scheduled_or_cancelled():
    conn = FakeConn()
    DbSink(None, connection=conn).sync_game(
        naver_game_id="g1", game_dt="2026-07-08 00:00:00",
        home_team_id=3, away_team_id=2, home_score=None, away_score=None, status_id=1)
    _, _, params = conn.log[0]
    assert params[4] is None and params[5] is None  # home_score, away_score
