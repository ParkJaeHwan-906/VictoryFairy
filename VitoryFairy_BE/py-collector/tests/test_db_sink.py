from types import SimpleNamespace

from kbo_collector.db import DbSink, PLAYERS_UPSERT, REG_UPSERT, MARK_NOT_FIRST, INNINGS_UPSERT
from kbo_collector.dimensions import PlayerRow, TeamRow


class FakeCursor:
    def __init__(self, log): self.log = log
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def execute(self, sql, params=None): self.log.append(("execute", sql, params))
    def executemany(self, sql, rows): self.log.append(("executemany", sql, rows))


class FakeConn:
    def __init__(self): self.log = []; self.commits = 0
    def cursor(self): return FakeCursor(self.log)
    def commit(self): self.commits += 1
    def close(self): pass


def _player(pid="60123"):
    return PlayerRow(pid, "손주영", "1", "투수", "좌투좌타", "1998-05-12", 184, 88)


def test_upsert_players_builds_rows_with_snapshot_date():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_players([_player()], "LG", "2026-07-13")
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == PLAYERS_UPSERT
    assert rows == [("60123", "손주영", "LG", "1", "투수", "좌투좌타",
                     "1998-05-12", 184, 88, "2026-07-13")]
    assert conn.commits == 1


def test_insert_registrations_rows():
    conn = FakeConn()
    DbSink(None, connection=conn).insert_registrations("2026-07-13", [_player()], "LG")
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == REG_UPSERT
    assert rows == [("2026-07-13", "60123", "LG")]


def test_mark_not_first_team_params():
    conn = FakeConn()
    DbSink(None, connection=conn).mark_not_first_team("LG", "2026-07-13")
    kind, sql, params = conn.log[0]
    assert kind == "execute" and sql == MARK_NOT_FIRST
    assert params == ("LG", "2026-07-13")


def test_upsert_teams_rows():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_teams([TeamRow("LG", "LG", "LG 트윈스")])
    kind, sql, rows = conn.log[0]
    assert kind == "executemany"
    assert rows == [("LG", "LG", "LG 트윈스")]


def test_empty_rows_noop():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_players([], "LG", "2026-07-13")
    assert conn.log == [] and conn.commits == 0


def test_upsert_innings_expands_1indexed_skipping_none():
    conn = FakeConn()
    game = SimpleNamespace(game_id="G1",
                           inn_scores={"away": [2, 0, 1], "home": [0, None, 3]})
    DbSink(None, connection=conn).upsert_innings(game)
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == INNINGS_UPSERT
    assert rows == [("G1", 1, False, 2), ("G1", 2, False, 0), ("G1", 3, False, 1),
                    ("G1", 1, True, 0), ("G1", 3, True, 3)]  # home 2회(None) 스킵
