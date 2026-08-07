import re
import urllib.parse

from bs4 import BeautifulSoup

from .dimensions import PLAYER_SECTIONS, PlayerRow, parse_physique

_CTL = "ctl00$ctl00$ctl00$cphContents$cphContents$cphContents$"
_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


def parse_register(html: str) -> list[PlayerRow]:
    """Register.aspx 응답 → 1군 선수 목록. 감독/코치 섹션 제외."""
    soup = BeautifulSoup(html, "lxml")
    players: list[PlayerRow] = []
    for table in soup.select("table"):
        ths = table.select("tr th")
        if len(ths) < 2:
            continue
        section = ths[1].get_text(strip=True)  # 2번째 th = 역할/포지션
        if section not in PLAYER_SECTIONS:
            continue
        for tr in table.select("tr"):
            tds = tr.find_all("td")
            if len(tds) < 5:
                continue
            a = tds[1].find("a")
            if a is None:
                continue
            m = re.search(r"playerId=(\d+)", a.get("href", ""))
            if not m:
                continue
            h, w = parse_physique(tds[4].get_text(strip=True))
            birth = tds[3].get_text(strip=True) or None
            players.append(PlayerRow(
                player_id=m.group(1),
                name=a.get_text(strip=True),
                back_number=tds[0].get_text(strip=True),
                position=section,
                throw_bat=tds[2].get_text(strip=True),
                birth_date=birth,
                height_cm=h,
                weight_kg=w,
            ))
    return players


def _hidden(html: str, name: str) -> str:
    m = re.search(r'name="' + re.escape(name) + r'"[^>]*value="([^"]*)"', html)
    return m.group(1) if m else ""


def _headers(base: str) -> dict:
    return {"User-Agent": _UA, "Referer": f"{base}/Player/Register.aspx",
            "Content-Type": "application/x-www-form-urlencoded"}


def current_date(settings, client) -> str:
    """사이트 기본(=현재) 등록일. 'YYYY-MM-DD'."""
    url = f"{settings.kbo_base_url}/Player/Register.aspx"
    html = client.get(url, headers={"User-Agent": _UA}).text
    d = _hidden(html, _CTL + "hfSearchDate") or ""
    return f"{d[0:4]}-{d[4:6]}-{d[6:8]}" if len(d) == 8 else d


def fetch_register_html(team_code: str, date_compact: str, *, settings, client) -> str:
    """팀+날짜(YYYYMMDD)의 1군 등록명단 HTML. EUC-KR POST."""
    url = f"{settings.kbo_base_url}/Player/Register.aspx"
    seed = client.get(url, headers={"User-Agent": _UA}).text
    form = {
        "__EVENTTARGET": _CTL + "btnCalendarSelect",
        "__EVENTARGUMENT": "",
        "__VIEWSTATE": _hidden(seed, "__VIEWSTATE"),
        "__VIEWSTATEGENERATOR": _hidden(seed, "__VIEWSTATEGENERATOR"),
        "__EVENTVALIDATION": _hidden(seed, "__EVENTVALIDATION"),
        _CTL + "hfSearchTeam": team_code,
        _CTL + "hfSearchDate": date_compact,
    }
    body = urllib.parse.urlencode(form)
    resp = client.post(url, content=body.encode("utf-8"), headers=_headers(settings.kbo_base_url))
    resp.encoding = "utf-8"  # KBO 응답은 UTF-8
    return resp.text
