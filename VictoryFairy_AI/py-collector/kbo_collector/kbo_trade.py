"""KBO 공식 선수 이동현황(Player/Trade.aspx) 수집.

페이지는 AJAX 셸이고 데이터는 /ws/Player.asmx/GetTradeList JSON POST 로 내려온다.
- Trade.aspx GET 으로 세션 쿠키를 선발급받아야 한다(동일 client 재사용, 쿠키는
  httpx.Client 가 유지).
- bdSc(항목 필터)는 서버가 int 로 파싱하므로 빈 문자열 불가 → 전체 조회는 0.
- 응답 본문은 UTF-8 BOM 포함 JSON(utf-8-sig 디코드 필요).
- 선수 셀은 '이름(포지션)' 텍스트뿐이고 playerId 가 없다 → 소비자는 이름+팀
  매칭만 가능하다(동명이인이면 매칭 포기가 안전).
"""
import json
import re

from .dimensions import TradeRow

_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# '이형범(투수)' -> ('이형범', '투수'). 괄호 없으면 전체가 이름.
_NAME_POS = re.compile(r"^(.*?)\(([^()]*)\)$")
# 'KIA→한화'·'140→62' 공용 화살표 비고.
_ARROW = re.compile(r"^(.*?)→(.*)$")
_RENAME_PREFIX = "개명전:"


def split_player(text: str) -> tuple[str, str | None]:
    m = _NAME_POS.match((text or "").strip())
    return (m.group(1), m.group(2)) if m else ((text or "").strip(), None)


def split_arrow(note: str) -> tuple[str, str] | None:
    m = _ARROW.match((note or "").strip())
    return (m.group(1).strip(), m.group(2).strip()) if m else None


def renamed_from(note: str) -> str | None:
    note = (note or "").strip()
    return note[len(_RENAME_PREFIX):].strip() if note.startswith(_RENAME_PREFIX) else None


def parse_rows(payload: dict) -> list[TradeRow]:
    """GetTradeList 응답 dict -> TradeRow 목록. 셀 순서: 날짜/항목/팀/선수/비고."""
    out: list[TradeRow] = []
    for row in payload.get("rows") or []:
        cells = [(c.get("Text") or "").strip() for c in row.get("row") or []]
        if len(cells) < 5:
            continue
        name, position = split_player(cells[3])
        out.append(TradeRow(
            date=cells[0], category=cells[1], team_name=cells[2],
            player_name=name, position=position, note=cells[4],
        ))
    return out


def fetch_trades(season: str, *, settings, client, list_count: int = 500) -> list[TradeRow]:
    """시즌 전체 이동현황. totalCnt 까지 페이지를 이어붙인다(최신순)."""
    base = settings.kbo_base_url
    client.get(f"{base}/Player/Trade.aspx", headers={"User-Agent": _UA})  # 쿠키 선발급
    headers = {
        "User-Agent": _UA,
        "Referer": f"{base}/Player/Trade.aspx",
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded",
    }
    rows: list[TradeRow] = []
    page = 1
    while True:
        body = (f"seasonId={season}&monthId=0&bdSc=0&teamName=&searchIf="
                f"&pageNo={page}&listCount={list_count}")
        resp = client.post(f"{base}/ws/Player.asmx/GetTradeList",
                           content=body.encode("utf-8"), headers=headers)
        payload = json.loads(resp.content.decode("utf-8-sig"))
        rows.extend(parse_rows(payload))
        total = int(payload.get("totalCnt") or 0)
        if len(rows) >= total or not payload.get("rows"):
            return rows
        page += 1
