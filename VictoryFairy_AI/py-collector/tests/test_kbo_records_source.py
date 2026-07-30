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


def test_collect_page_with_no_tables_is_isolated_as_no_tables_parsed():
    """실측(라이브 스모크)에서 나온 실제 실패 모드: 응답은 200이지만 페이지가
    <table>이 아닌 마크업(예: top5의 div 리스트)이라 parse_tables가 []를 반환하는
    경우. 이때도 그 페이지만 "no tables parsed" 사유로 failed에 들어가고 나머지
    페이지는 정상 적재돼야 한다 — client.get이 예외를 던지는 경로만 다루던 기존
    격리 테스트로는 이 경로가 전혀 커버되지 않았다."""
    no_table_html = "<html><body><div class=\"list\">표 없음</div></body></html>"

    class MixedClient:
        def get(self, url, headers=None):
            if PAGES["top5"] in url:
                return SimpleNamespace(text=no_table_html)
            return SimpleNamespace(text=FIXTURE)

    src = source_base.get_source("kbo_records")
    sink = FakeSink()
    ctx = source_base.CollectContext(
        settings=SimpleNamespace(kbo_base_url="https://www.koreabaseball.com"),
        client=MixedClient(), sink=sink, date="2026-07-30")
    res = src.collect(ctx)
    assert res.loaded == len(PAGES) - 1
    assert res.failed == ["top5: no tables parsed"]
    keys_written = {k for k, _ in sink.puts}
    assert "kbo-records/top5/2026-07-30.json" not in keys_written
