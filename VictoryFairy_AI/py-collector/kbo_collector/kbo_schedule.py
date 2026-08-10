"""KBO 공식 일정(Schedule/Schedule.aspx) 수집 — 취소 사유 전용.

경기 일정 자체는 네이버에서 받는다(games_sync). 여기서 KBO 를 따로 긁는 이유는
**취소 사유** 하나뿐이다 — 네이버는 취소를 `statusInfo: "경기취소"` 로만 알려주고
경기 상세에도 사유 필드가 없는 반면(2026-08-10 실측), KBO 일정표 비고 열에는
'폭염취소'·'우천취소'가 그대로 적혀 있다.

응답 구조는 Player/Trade.aspx 와 같은 계열이다(AJAX 셸 + /ws/*.asmx JSON POST,
UTF-8 BOM). 다만 표가 훨씬 험하다:

- 날짜 셀은 그날 **첫 경기 행에만** 붙는다(RowSpan). 이후 행은 열이 한 칸씩 당겨져
  같은 의미의 셀이 다른 인덱스에 온다 — 그래서 앞에서 세면 안 되고, 날짜 셀을
  떼어낸 뒤 세거나 뒤에서 세야 한다(실측: 130행 중 26행이 9칸, 104행이 8칸).
- 구장·비고 셀에는 **Class 가 없다.** 앞쪽 day/time/play/relay 만 이름이 있고
  나머지는 전부 None 이라 위치로 잡을 수밖에 없다. 뒤에서 두 번째가 구장,
  마지막이 비고다.
- 팀 이름은 코드가 아니라 한글 표기('두산'·'키움')다 → dimensions.TEAMS 로 해소.
- 점수가 <em> 안에 span 으로 박혀 있어 span 을 전부 긁으면 팀 사이에 숫자가 낀다.
  **첫 span 과 마지막 span** 만 취하면 경기 전/후 어느 쪽이든 원정·홈이 나온다.
- 취소 경기에는 리뷰·하이라이트 링크가 없어 **gameId 가 비어 있다**(실측: 8월
  취소 30건 전부 결측). 그래서 이 모듈은 gameId 에 기대지 않는다 — 소비자는
  (날짜, 원정, 홈) 으로 매칭한다.
"""
import json
import re

from .dimensions import TEAMS

_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# 비고 열의 '경기 없음' 표시. 이 값이면 정상 편성이다.
_NO_NOTE = "-"
# '08.01(토)' -> '08', '01'
_DAY = re.compile(r"^(\d{2})\.(\d{2})")
_TAG = re.compile(r"<[^>]+>")
_SPAN = re.compile(r"<span[^>]*>([^<]*)</span>")

_TEAM_CODE_BY_NAME = {t.name: t.team_code for t in TEAMS}


def _text(cell) -> str:
    return _TAG.sub("", (cell or {}).get("Text") or "").strip()


def parse_rows(payload: dict, season: str) -> list[dict]:
    """GetScheduleList 응답 -> 경기 행 목록.

    반환 항목: date('YYYY-MM-DD') / away_code / home_code / stadium / note.
    팀 코드를 해소하지 못한 행(올스타전·이벤트 경기 등)은 버린다.
    """
    out: list[dict] = []
    day_md = None
    for row in payload.get("rows") or []:
        cells = row.get("row") or []
        if not cells:
            continue
        if (cells[0].get("Class") or "") == "day":
            m = _DAY.match(_text(cells[0]))
            day_md = (m.group(1), m.group(2)) if m else None
            cells = cells[1:]
        if day_md is None or len(cells) < 4:
            continue
        spans = [s.strip() for s in _SPAN.findall((cells[1] or {}).get("Text") or "")]
        if len(spans) < 2:
            continue
        away = _TEAM_CODE_BY_NAME.get(spans[0])
        home = _TEAM_CODE_BY_NAME.get(spans[-1])
        if not away or not home:
            continue
        out.append({
            "date": f"{season}-{day_md[0]}-{day_md[1]}",
            "away_code": away,
            "home_code": home,
            "stadium": _text(cells[-2]),
            "note": _text(cells[-1]),
        })
    return out


def cancelled_rows(rows) -> list[dict]:
    """비고가 채워진 행(= 취소)만. '-' 는 정상 편성이라 제외한다."""
    return [r for r in rows if r["note"] and r["note"] != _NO_NOTE]


def schedule_url(settings) -> str:
    return f"{settings.kbo_base_url}/ws/Schedule.asmx/GetScheduleList"


def fetch_month(season: str, month: str, *, settings, client) -> list[dict]:
    """해당 월(1회 호출)의 경기 행 목록. season/month 는 'YYYY'/'MM'."""
    base = settings.kbo_base_url
    client.get(f"{base}/Schedule/Schedule.aspx", headers={"User-Agent": _UA})  # 쿠키 선발급
    body = (f"leId=1&srIdList=0,9,6&seasonId={season}&gameMonth={month}&teamId=")
    resp = client.post(schedule_url(settings), content=body.encode("utf-8"), headers={
        "User-Agent": _UA,
        "Referer": f"{base}/Schedule/Schedule.aspx",
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    return parse_rows(json.loads(resp.content.decode("utf-8-sig")), season)
