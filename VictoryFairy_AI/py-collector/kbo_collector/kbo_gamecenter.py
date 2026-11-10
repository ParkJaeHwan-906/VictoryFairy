"""KBO 게임센터 경기 목록(Main.asmx/GetKboGameList) — games_sync 의 네이버 대체 원천.

네이버 스포츠 API 가 2026-10-26 17:35 KST 부터 AWS 대역(서울 NAT, Lambda 공용 IP,
us-east-1)에서 오는 요청에 응답하지 않는다 — 연결은 맺어지고 read timeout 이 난다.
같은 출구에서 KBO 공식 사이트는 열려서, 네이버가 실패한 날짜만 여기로 넘어온다.

games 의 자연키는 네이버 gameId 다. 이미 적재된 행은 (날짜, 홈, 원정)으로 찾아 갱신하고,
없는 경기는 G_ID 에서 네이버 id 를 만들어 **새 행으로 넣는다**(`naver_game_id`). 처음에는
기존 행만 갱신했는데, 차단이 선적재 기간(+7일)을 넘기자 새 날짜의 경기가 하나도 생기지 않아
2026-11-03 한국시리즈 1차전이 앱에서 통째로 빠졌다.

네이버 id 규칙(2026-10 실측): 연도 자리만 다르고 MMDD·팀·더블헤더 번호 배치는 G_ID 와 같다.
정규시즌은 G_ID + 시즌(20261006KTWO0 ↔ 20261006KTWO02026), 포스트시즌은 연도 자리에 시리즈
번호(SR_ID)를 네 번 쓴다(준PO SR_ID=3 → 33331020HTLG02026, PO SR_ID=5 → 55551025HTSS02026).
와일드카드(4)·한국시리즈(7)는 같은 규칙으로 추정한 값이라 실측이 아니다 — 틀렸다면 네이버가
돌아온 뒤 games_sync 의 사라진 경기 판정이 우리가 만든 행을 removed 로 걸러 준다.

사라진 경기 판정은 이 경로에서도 돈다. 시리즈가 일찍 끝나면 미리 만든 예비 경기가 KBO 목록에서
빠지는데, 판정을 끄면 그 행이 예정 상태로 앱에 남는다(2026-11-10 한국시리즈 6·7차전).

상태 코드(2026-10-26 실측, 3월~10월 표본): GAME_STATE_SC 1=경기 전, 2=진행 중,
3=종료, 4=취소(CANCEL_SC_ID 가 0 이 아님). 연장은 GAME_INN_NO 가 10·11 로 온다.
"""
import json

from .dimensions import TEAM_CODES

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.86 Safari/537.36"
)
# 게임센터 화면이 보내는 값 그대로다. 정규시즌·포스트시즌이 모두 들어 있다.
_SERIES = "0,1,3,4,5,6,7,8,9"

# 네이버 statusCode 로 옮겨 game_records.map_status 를 그대로 쓴다. 4(취소)는 cancel 로 간다.
_STATUS_CODE = {"1": "BEFORE", "2": "STARTED", "3": "RESULT"}
_HALF = {"T": "초", "B": "말"}
# 정규시즌(0)과 포스트시즌(3·4·5·7)만 id 를 만든다. 시범경기 등 나머지는 네이버 규칙을 본 적이
# 없어 행을 만들지 않는다 — 잘못 만든 id 는 네이버가 돌아와도 같은 경기로 이어지지 않는다.
_POSTSEASON_SR_IDS = {"3", "4", "5", "7"}


def list_url(settings) -> str:
    return f"{settings.kbo_base_url}/ws/Main.asmx/GetKboGameList"


def fetch_day(date: str, *, settings, client) -> list[dict] | None:
    """date('YYYY-MM-DD') 하루의 경기 목록 원본. 팀을 모르는 경기(올스타 등)는 버린다.

    응답에 game 배열이 없으면 None 이다 — 경기가 없는 날은 빈 배열로 온다(2026-11-10 실측
    {"game": [], "code": "100"}). 둘을 섞으면 깨진 응답 하나에 그날 경기가 전부 사라진 경기가 된다.
    """
    base = settings.kbo_base_url
    body = f"leId=1&srId={_SERIES}&date={date.replace('-', '')}"
    resp = client.post(list_url(settings), content=body.encode("utf-8"), headers={
        "User-Agent": _UA,
        "Referer": f"{base}/Schedule/GameCenter/Main.aspx",
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    resp.raise_for_status()
    games = json.loads(resp.content.decode("utf-8-sig")).get("game")
    if not isinstance(games, list):
        return None
    return [g for g in games if g.get("HOME_ID") in TEAM_CODES and g.get("AWAY_ID") in TEAM_CODES]


def _score(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def to_schedule_game(kg: dict, game_id: str) -> dict:
    """KBO 경기 1건 -> 네이버 schedule 경기 모양(games_sync 루프가 읽는 키만)."""
    state = str(kg.get("GAME_STATE_SC") or "")
    cancel = state == "4" or str(kg.get("CANCEL_SC_ID") or "0") != "0"
    inning, half = kg.get("GAME_INN_NO"), _HALF.get(kg.get("GAME_TB_SC"))
    # 네이버처럼 종료 경기도 마지막 이닝을 싣는다 — last_inning 이 이 값으로 채워진다.
    status_info = f"{inning}회{half}" if state in ("2", "3") and inning and half else ""
    d, tm = kg.get("G_DT") or "", kg.get("G_TM") or ""
    dt = f"{d[:4]}-{d[4:6]}-{d[6:8]}T{tm}:00" if len(d) == 8 and tm else ""
    return {
        "gameId": game_id,
        "statusCode": _STATUS_CODE.get(state),
        "cancel": cancel,
        "statusInfo": status_info,
        "homeTeamCode": kg.get("HOME_ID"),
        "awayTeamCode": kg.get("AWAY_ID"),
        "homeTeamScore": _score(kg.get("B_SCORE_CN")),
        "awayTeamScore": _score(kg.get("T_SCORE_CN")),
        "gameDateTime": dt,
        # 구장 짧은 이름(수원·잠실…)은 네이버 상세의 stadium 과 같은 표기다(2026-10 실측, 9개 구장).
        "stadium": (kg.get("S_NM") or "").strip() or None,
    }


def naver_game_id(kg: dict) -> str | None:
    """KBO 경기 1건의 네이버 gameId(모듈 주석의 규칙). 규칙을 모르는 시리즈면 None."""
    g_id, sr_id = kg.get("G_ID") or "", str(kg.get("SR_ID", ""))  # 응답은 숫자, 테스트는 문자열
    if len(g_id) != 13 or not g_id[:8].isdigit():
        return None
    season = g_id[:4]
    if sr_id == "0":
        return g_id + season
    if sr_id in _POSTSEASON_SR_IDS:
        return sr_id * 4 + g_id[4:] + season
    return None


def has_matchup(kg: dict, rows, team_ids: dict) -> bool:
    """그날 같은 대진의 행이 하나라도 있나. 있는데 match_game_id 가 None 이면 짝이 모호한 것이다."""
    home, away = team_ids.get(kg.get("HOME_ID")), team_ids.get(kg.get("AWAY_ID"))
    return any((h, a) == (home, away) for _, h, a in rows)


def match_game_id(kg: dict, rows, team_ids: dict) -> str | None:
    """KBO 경기에 해당하는 games 행의 네이버 gameId. 못 찾거나 둘 이상이면 None.

    rows 는 그날의 (naver_game_id, home_team_id, away_team_id) 목록이다. 같은 대진이 둘인
    날(더블헤더)은 13번째 글자(더블헤더 번호)로 가른다 — 네이버 id 도 연도 자리만 다를 뿐
    MMDD·팀·번호 배치는 G_ID 와 같다.
    """
    home, away = team_ids.get(kg.get("HOME_ID")), team_ids.get(kg.get("AWAY_ID"))
    same = [nid for nid, h, a in rows if (h, a) == (home, away)]
    if len(same) > 1:
        dh = (kg.get("G_ID") or "")[12:13]
        same = [nid for nid in same if nid[12:13] == dh]
    return same[0] if len(same) == 1 else None
