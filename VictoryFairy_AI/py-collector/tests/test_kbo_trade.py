import json
from pathlib import Path
from types import SimpleNamespace

from kbo_collector import kbo_trade
from kbo_collector.dimensions import TradeRow

FIX = Path(__file__).parent / "fixtures" / "kbo"


def _payload():
    return json.loads((FIX / "trade_list.json").read_text(encoding="utf-8"))


def test_parse_rows_splits_name_position_and_keeps_note():
    rows = kbo_trade.parse_rows(_payload())
    assert len(rows) == 5
    trade = next(r for r in rows if r.category == "트레이드")
    assert trade == TradeRow("2026-07-24", "트레이드", "한화", "이형범", "투수", "KIA→한화")
    rename = next(r for r in rows if r.category == "개명")
    assert rename.player_name == "박하늘" and rename.note == "개명전:박건"


def test_parse_rows_tolerates_short_and_parenless_rows():
    payload = {"rows": [
        {"row": [{"Text": "2026-01-01"}, {"Text": "웨이버"}]},  # 셀 부족 -> 스킵
        {"row": [{"Text": "2026-01-01"}, {"Text": "웨이버"}, {"Text": "KT"},
                 {"Text": "괄호없는선수"}, {"Text": ""}]},
    ]}
    rows = kbo_trade.parse_rows(payload)
    assert rows == [TradeRow("2026-01-01", "웨이버", "KT", "괄호없는선수", None, "")]


def test_note_helpers():
    assert kbo_trade.split_arrow("KIA→한화") == ("KIA", "한화")
    assert kbo_trade.split_arrow("140→62") == ("140", "62")
    assert kbo_trade.split_arrow("10일") is None
    assert kbo_trade.renamed_from("개명전:박건") == "박건"
    assert kbo_trade.renamed_from("KIA→한화") is None


class FakeResp:
    def __init__(self, content):
        self.content = content
        self.text = ""


class FakeClient:
    """GET 은 쿠키 선발급용 셸, POST 는 큐 순서대로 응답하며 폼 본문을 기록한다."""

    def __init__(self, posts):
        self.posts = list(posts)
        self.gets = []
        self.forms = []

    def get(self, url, headers=None):
        self.gets.append(url)
        return FakeResp(b"<html/>")

    def post(self, url, content=None, headers=None):
        self.forms.append(content.decode())
        return FakeResp(self.posts.pop(0))


def _page(dates, total):
    rows = [{"row": [{"Text": d}, {"Text": "웨이버"}, {"Text": "KT"},
                     {"Text": f"선수{i}(투수)"}, {"Text": ""}]}
            for i, d in enumerate(dates)]
    # 실서버처럼 UTF-8 BOM 을 붙인다
    return b"\xef\xbb\xbf" + json.dumps({"totalCnt": total, "rows": rows}).encode()


def test_fetch_trades_seeds_cookie_and_chains_pages():
    client = FakeClient([_page(["2026-08-04", "2026-08-03"], 3),
                         _page(["2026-08-02"], 3)])
    settings = SimpleNamespace(kbo_base_url="https://kbo.example")
    rows = kbo_trade.fetch_trades("2026", settings=settings, client=client, list_count=2)
    assert [r.date for r in rows] == ["2026-08-04", "2026-08-03", "2026-08-02"]
    assert client.gets == ["https://kbo.example/Player/Trade.aspx"]  # 쿠키 선발급 1회
    assert "seasonId=2026" in client.forms[0] and "bdSc=0" in client.forms[0]
    assert "pageNo=1" in client.forms[0] and "pageNo=2" in client.forms[1]


def test_fetch_trades_stops_on_empty_rows():
    client = FakeClient([b"\xef\xbb\xbf" + json.dumps({"totalCnt": 9, "rows": []}).encode()])
    settings = SimpleNamespace(kbo_base_url="https://kbo.example")
    assert kbo_trade.fetch_trades("2026", settings=settings, client=client) == []
