from pathlib import Path
from types import SimpleNamespace

from kbo_collector.sources import base as source_base
from kbo_collector.sources.kbo_records import PAGES, parse_tables
from kbo_collector import keys

FIXTURE = (Path(__file__).parent / "fixtures" / "kbo_team_rank_daily.html").read_text(encoding="utf-8")


def test_parse_tables_extracts_headers_and_rows():
    tables = parse_tables(FIXTURE)
    assert tables, "픽스처에서 테이블을 하나도 못 뽑음"
    t = tables[0]
    assert t["headers"] and t["rows"]
    assert all(isinstance(r, list) for r in t["rows"])


def test_kbo_records_key():
    assert keys.kbo_records_key("team-rank-daily", "2026-07-30") == \
        "kbo-records/team-rank-daily/2026-07-30.json"


class FakeClient:
    def get(self, url, headers=None):
        return SimpleNamespace(text=FIXTURE)


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))


def test_collect_snapshots_every_page():
    src = source_base.get_source("kbo_records")
    assert getattr(src, "needs_db", True) is False
    sink = FakeSink()
    ctx = source_base.CollectContext(
        settings=SimpleNamespace(kbo_base_url="https://www.koreabaseball.com"),
        client=FakeClient(), sink=sink, date="2026-07-30")
    res = src.collect(ctx)
    assert res.loaded == len(PAGES) and not res.failed
    keys_written = {k for k, _ in sink.puts}
    assert "kbo-records/team-rank-daily/2026-07-30.json" in keys_written
    _, obj = sink.puts[0]
    assert set(obj) == {"page", "url", "date", "fetchedAt", "tables"}


def test_collect_page_failure_is_isolated():
    class FailingClient:
        def __init__(self):
            self.n = 0

        def get(self, url, headers=None):
            self.n += 1
            if self.n == 1:
                raise RuntimeError("boom")
            return SimpleNamespace(text=FIXTURE)

    src = source_base.get_source("kbo_records")
    sink = FakeSink()
    ctx = source_base.CollectContext(
        settings=SimpleNamespace(kbo_base_url="https://x"),
        client=FailingClient(), sink=sink, date="2026-07-30")
    res = src.collect(ctx)
    assert res.loaded == len(PAGES) - 1 and len(res.failed) == 1
