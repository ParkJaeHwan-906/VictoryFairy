"""KBO 공식 기록실 페이지 스냅샷 → S3 kbo-records/{page}/{date}.json (브론즈).

envelope 재포장 없음 — 소비자는 통계 집계·시드 생성 스크립트뿐(스펙 4.4).
파싱은 '페이지의 모든 <table>을 헤더+행 그대로' 뜨는 제네릭 방식이라 페이지별
스키마 해석은 소비자 몫이고, 사이트 개편 시엔 여기가 아니라 소비자가 덜 다친다.
페이지 하나 실패는 그 페이지만 건너뛴다(이전 날짜 스냅샷이 남아 있으므로, 스펙 §5).

실측(2026-07-30) 결과 `top5`·`record-correct` 두 페이지는 이 제네릭 <table> 파서로
상시 실패한다(일시 장애가 아님) — 상세 사유는 PAGES 항목 주석 참고. 두 슬러그는
S3에 스냅샷이 생성되지 않으므로 소비자는 이 결측을 전제해야 한다.
"""
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from .. import keys
from .base import CollectResult, register

_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# 스펙 4.4의 9개 대상 페이지 (+ history-top의 hitter 페이지). 슬러그가 S3 prefix.
#
# 주의(실측 2026-07-30, curl -sI로 리다이렉트 아님 확인 — PAGES 경로 수정으로는
# 해결 불가): 아래 두 페이지는 응답이 HTTP 200이어도 <table> 기반 데이터가 없어
# parse_tables가 항상 []를 반환 -> collect()가 "no tables parsed"로 상시 failed
# 처리한다(일시 장애 아님, 스냅샷 미생성). 브론즈 원칙상 페이지별 파싱은 추가하지
# 않았다 — Task 5/9 등 소비자는 이 두 슬러그의 결측을 전제해야 한다.
PAGES = {
    "team-rank-daily":       "/Record/TeamRank/TeamRankDaily.aspx",
    "hitter-basic":          "/Record/Player/HitterBasic/Basic1.aspx",
    "pitcher-basic":         "/Record/Player/PitcherBasic/Basic1.aspx",
    # 상시 실패: TOP5 랭킹이 <table>이 아니라 <div class="record_list">...
    # <div class="list"> 구조로 서버 렌더됨 -> parse_tables가 표를 하나도 못 찾음.
    "top5":                  "/Record/Ranking/Top5.aspx",
    "history-top-hitter":    "/Record/History/Top/Hitter.aspx",
    "history-player-hitter": "/Record/History/Player/Hitter.aspx",
    "history-player-pitcher": "/Record/History/Player/Pitcher.aspx",
    "history-team":          "/Record/History/Team/Record.aspx",
    "expectation-week":      "/Record/Expectation/WeekList.aspx",
    # 상시 실패: <table id="recordTbl">는 있지만 <tbody>가 항상 비어 있고 주변
    # <select> 필터들도 옵션이 없음 -> 데이터가 JS/AJAX로 채워지는 구조라
    # 정적 GET만으로는 행이 절대 생기지 않음(parse_tables가 빈 표는 제외).
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
