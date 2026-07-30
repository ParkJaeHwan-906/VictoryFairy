"""KBO 공식 기록실 페이지 스냅샷 → S3 kbo-records/{page}/{date}.json (브론즈).

envelope 재포장 없음 — 소비자는 통계 집계·시드 생성 스크립트뿐(스펙 4.4).
파싱은 '페이지의 모든 <table>을 헤더+행 그대로' 뜨는 제네릭 방식이라 페이지별
스키마 해석은 소비자 몫이고, 사이트 개편 시엔 여기가 아니라 소비자가 덜 다친다.
페이지 하나 실패는 그 페이지만 건너뛴다(이전 날짜 스냅샷이 남아 있으므로, 스펙 §5).
"""
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from .. import keys
from .base import CollectResult, register

_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# 스펙 4.4의 9개 대상 페이지 (+ history-top의 hitter 페이지). 슬러그가 S3 prefix.
PAGES = {
    "team-rank-daily":       "/Record/TeamRank/TeamRankDaily.aspx",
    "hitter-basic":          "/Record/Player/HitterBasic/Basic1.aspx",
    "pitcher-basic":         "/Record/Player/PitcherBasic/Basic1.aspx",
    "top5":                  "/Record/Ranking/Top5.aspx",
    "history-top-hitter":    "/Record/History/Top/Hitter.aspx",
    "history-player-hitter": "/Record/History/Player/Hitter.aspx",
    "history-player-pitcher": "/Record/History/Player/Pitcher.aspx",
    "history-team":          "/Record/History/Team/Record.aspx",
    "expectation-week":      "/Record/Expectation/WeekList.aspx",
    "record-correct":        "/Record/RecordCorrect/RecordCorrect.aspx",
}


def parse_tables(html: str) -> list:
    """모든 <table> → [{"headers": [...], "rows": [[...]]}]. 행 없는 표는 제외."""
    soup = BeautifulSoup(html, "lxml")
    out = []
    for table in soup.select("table"):
        headers = [th.get_text(" ", strip=True) for th in table.select("tr th")]
        rows = [[td.get_text(" ", strip=True) for td in tr.find_all("td")]
                for tr in table.select("tr") if tr.find_all("td")]
        if rows:
            out.append({"headers": headers, "rows": rows})
    return out


@register
class KboRecords:
    source_id = "kbo_records"
    doc_types = ("kbo_records",)
    needs_db = False    # run.py collect 잡이 DbSink 생성을 건너뛴다

    def collect(self, ctx) -> CollectResult:
        date = ctx.date or datetime.now(timezone.utc).strftime("%Y-%m-%d")
        fetched_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        loaded, failed = 0, []
        for page, path in PAGES.items():
            url = f"{ctx.settings.kbo_base_url}{path}"
            try:
                html = ctx.client.get(url, headers={"User-Agent": _UA}).text
                tables = parse_tables(html)
                if not tables:
                    raise ValueError("no tables parsed")
                ctx.sink.put_json(keys.kbo_records_key(page, date), {
                    "page": page, "url": url, "date": date,
                    "fetchedAt": fetched_at, "tables": tables,
                })
                loaded += 1
            except Exception as exc:   # 페이지 단위 격리
                failed.append(f"{page}: {exc}")
        return CollectResult(loaded=loaded, failed=failed)
