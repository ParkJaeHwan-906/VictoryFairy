"""KBO 게임센터 박스스코어·라인업 — records 잡과 경기 전 라인업의 네이버 대체 원천.

네이버 스포츠 API 가 AWS 대역 요청에 응답하지 않게 된 뒤(2026-10-26, kbo_gamecenter 모듈 주석)
경기 기록과 선발 라인업도 KBO 게임센터 화면이 쓰는 엔드포인트로 받는다.

  /ws/Schedule.asmx/GetBoxScoreScroll   타자(이닝별 결과 포함)·투수·비고(도루 등)
  /ws/Schedule.asmx/GetScoreBoardScroll 팀 R·H·E·B, 이닝 점수
  /ws/Schedule.asmx/GetLineUpAnalysis   경기 전 공시 타순 9명

네이버와 달리 **선수 ID 가 없다**(이름만 온다, 2026-11-03 실측). 그래서 (구단, 이름)을
players.kbo_player_id 로 바꾸는 표(`codes`)를 호출자가 넘기고, 여기서 못 찾은 선수는 행에서
빼고 이름을 돌려준다 — 엉뚱한 선수에 기록을 붙이느니 비우는 편이 낫다(동명이인은 호출자가
표에서 이미 뺐다). 선발투수만은 경기 목록(GetKboGameList)의 T_PIT_P_ID/B_PIT_P_ID 로 ID 가 온다.

응답 표 모양(2026-11-01 PO5 실측):
- arrHitter[0]=원정, [1]=홈. table1 = [타순, 포지션, 이름], table2 = 이닝별 타석 결과,
  table3 = [타수, 안타, 타점, 득점, 타율]. 교체 선수는 같은 타순 번호로 이어 나온다.
- 포지션은 네이버 pos 와 같은 표기(중·一·타…, 이동하면 이어 붙음 "좌중")라 db.position_name 을
  그대로 탄다.
- 타자 홈런·볼넷·삼진은 표에 열이 없어 이닝별 결과 칸에서 센다. 도루는 비고의 '도루' 줄이다.
- arrPitcher[0]=원정, [1]=홈. 열은 헤더 그대로(선수명·등판·결과·…·자책·평균자책점).
- 스코어보드 table3 = [R, H, E, B] 원정·홈 순.
"""
import json
import re

from .game_records import (BattingRow, GameRow, LineupRow, PitchingRow, PlayerRef,
                           innings_to_outs)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.86 Safari/537.36"
)
_BLANK = {"", "&nbsp;"}
_DECISION = {"승": "W", "패": "L", "세": "S", "홀": "H"}
# 라인업 화면은 포지션을 정식 명칭으로 준다 -> 박스스코어 표기로 되돌려 position_name 을 한 길로 태운다.
_LINEUP_POSITION = {"투수": "투", "포수": "포", "1루수": "一", "2루수": "二", "3루수": "三",
                    "유격수": "유", "좌익수": "좌", "중견수": "중", "우익수": "우",
                    "지명타자": "지"}
_SIDES = ((0, False), (1, True))  # (배열 인덱스, is_home)
_STEAL = re.compile(r"([^\s(),]+?)(\d*)\(")
# 한 이닝에 두 번 나온 타석은 한 칸에 "<br />" 로 이어 오고, 둘째 타석 앞에 "/ " 가 붙기도 한다
# (2026 시즌 60경기 실측: '1땅<br />…', '/ 4구'). 결과 표기 자체에는 '/' 가 없다.
_PLATE_SEP = re.compile(r"<br\s*/?>|/")


def _post(path: str, body: str, *, settings, client) -> dict:
    base = settings.kbo_base_url
    resp = client.post(f"{base}{path}", content=body.encode("utf-8"), headers={
        "User-Agent": _UA,
        "Referer": f"{base}/Schedule/GameCenter/Main.aspx",
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    resp.raise_for_status()
    return json.loads(resp.content.decode("utf-8-sig"))


def _body(kg: dict) -> str:
    g_id = kg["G_ID"]
    return f"leId=1&srId={kg.get('SR_ID')}&seasonId={g_id[:4]}&gameId={g_id}"


def fetch_boxscore(kg: dict, *, settings, client) -> tuple[dict, dict]:
    """경기 목록 1건(kg) -> (박스스코어, 스코어보드) 원본."""
    box = _post("/ws/Schedule.asmx/GetBoxScoreScroll", _body(kg), settings=settings, client=client)
    board = _post("/ws/Schedule.asmx/GetScoreBoardScroll", _body(kg), settings=settings,
                  client=client)
    return box, board


def fetch_lineup(kg: dict, *, settings, client) -> list:
    return _post("/ws/Schedule.asmx/GetLineUpAnalysis", _body(kg), settings=settings,
                 client=client)


def _table(raw) -> list[list[str]]:
    t = json.loads(raw) if isinstance(raw, str) else (raw or {})
    return [[(c.get("Text") or "").strip() for c in r.get("row") or []] for r in t.get("rows") or []]


def _i(v):
    try:
        return int(str(v).replace(",", ""))
    except (TypeError, ValueError):
        return None


def _plate_results(cells) -> list[str]:
    """이닝별 결과 칸 -> 타석 결과 목록(_PLATE_SEP 주석)."""
    out = []
    for c in cells:
        out.extend(p.strip() for p in _PLATE_SEP.split(c) if p.strip() not in _BLANK)
    return out


def _is_walk(r: str) -> bool:
    return r in ("4구", "고4")  # 사구(몸에 맞는 공)는 볼넷이 아니다 — 네이버 bb 와 같은 기준


def _is_strikeout(r: str) -> bool:
    return "삼진" in r or "낫" in r  # 스트라이크 낫아웃도 삼진이다


def _steals(etc_rows) -> dict:
    """비고 '도루' 줄 -> {이름: 개수}. '김지찬(2회)' / '김지찬2(2회 5회)' 두 모양을 다 센다."""
    out: dict = {}
    for row in etc_rows:
        if len(row) >= 2 and row[0] == "도루":
            for name, n in _STEAL.findall(row[1]):
                out[name] = out.get(name, 0) + (int(n) if n else 1)
    return out


def parse_boxscore(kg: dict, box: dict, board: dict, game_id: str, codes: dict):
    """KBO 박스스코어 -> (GameRow, 이름을 못 푼 선수 목록).

    codes 는 {(구단 코드, 이름): kbo_player_id}. 못 푼 선수는 GameRow 에서 빠진다.
    """
    away, home = kg["AWAY_ID"], kg["HOME_ID"]
    teams = {False: away, True: home}
    unresolved: list[str] = []

    def code(team, name):
        c = codes.get((team, name))
        if c is None:
            unresolved.append(f"{team}:{name}")
        return c

    steals = _steals(_table(box.get("tableEtc")))
    batting, refs = [], {}
    for idx, is_home in _SIDES:
        team = teams[is_home]
        side = (box.get("arrHitter") or [])[idx]
        for t1, t2, t3 in zip(_table(side["table1"]), _table(side["table2"]), _table(side["table3"])):
            if len(t1) < 3 or len(t3) < 4 or not t1[2] or t1[2] in _BLANK:
                continue
            pcode = code(team, t1[2])
            if pcode is None:
                continue
            plates = _plate_results(t2)
            refs[pcode] = PlayerRef(pcode, t1[2], team)
            batting.append(BattingRow(
                pcode=pcode, team_code=team, is_home=is_home,
                bat_order=_i(t1[0]), position=t1[1] or None,
                at_bats=_i(t3[0]), hits=_i(t3[1]), rbi=_i(t3[2]), runs=_i(t3[3]),
                home_runs=sum("홈" in r for r in plates),
                walks=sum(_is_walk(r) for r in plates),
                strikeouts=sum(_is_strikeout(r) for r in plates),
                stolen_bases=steals.get(t1[2], 0),
            ))

    pitching, starters = [], {False: None, True: None}
    for idx, is_home in _SIDES:
        team = teams[is_home]
        side = (box.get("arrPitcher") or [])[idx]
        for seq, r in enumerate(_table(side["table"])):
            if len(r) < 16 or not r[0]:
                continue
            pcode = code(team, r[0])
            if pcode is None:
                continue
            refs[pcode] = PlayerRef(pcode, r[0], team)
            if r[1] == "선발":
                starters[is_home] = pcode
            ip = r[6] if r[6] not in _BLANK else ""
            pitching.append(PitchingRow(
                pcode=pcode, team_code=team, is_home=is_home, seq=seq,
                decision=_DECISION.get(r[2]),
                ip_display=ip, ip_outs=innings_to_outs(ip),
                batters_faced=_i(r[7]), at_bats=_i(r[9]), hits=_i(r[10]),
                home_runs=_i(r[11]), walks_hbp=_i(r[12]), strikeouts=_i(r[13]),
                runs=_i(r[14]), earned_runs=_i(r[15]),
            ))

    rheb = _table(board.get("table3"))
    a = rheb[0] if len(rheb) > 0 else []
    h = rheb[1] if len(rheb) > 1 else []
    ar, hr = _i(kg.get("T_SCORE_CN")), _i(kg.get("B_SCORE_CN"))
    winner = None
    if ar is not None and hr is not None:
        winner = "away" if ar > hr else "home" if hr > ar else "draw"
    d = kg.get("G_DT") or ""
    game = GameRow(
        game_id=game_id, game_date=f"{d[:4]}-{d[4:6]}-{d[6:8]}",
        game_type="regular" if str(kg.get("SR_ID")) == "0" else "postseason",
        round_no=None, stadium=(kg.get("S_NM") or "").strip() or None,
        start_time=kg.get("G_TM") or None,
        away_team_code=away, home_team_code=home, away_score=ar, home_score=hr,
        away_hits=_i(a[1]) if len(a) > 1 else None, home_hits=_i(h[1]) if len(h) > 1 else None,
        away_errors=_i(a[2]) if len(a) > 2 else None, home_errors=_i(h[2]) if len(h) > 2 else None,
        away_bb=_i(a[3]) if len(a) > 3 else None, home_bb=_i(h[3]) if len(h) > 3 else None,
        winner=winner,
        away_starter_pcode=starters[False], home_starter_pcode=starters[True],
        inn_scores={}, pitching=pitching, batting=batting, players=list(refs.values()),
    )
    return game, unresolved


def parse_lineup(kg: dict, lineup: list, codes: dict):
    """GetLineUpAnalysis -> (LineupRow, PlayerRef, 못 푼 이름). 미공시면 빈 목록.

    공시 전에도 표는 차 있다 — **각 팀의 직전 경기 라인업**이다(2026-11-03 실측, T_ID 옆 G_ID 가
    오늘 경기가 아님). 그래서 LINEUP_CK 와 표마다 붙은 G_ID 둘 다 오늘 경기일 때만 쓴다.
    선발투수는 이 화면에 없어 경기 목록의 T_PIT_P_ID/B_PIT_P_ID 로 채운다(이쪽은 ID 가 온다).
    """
    rows, refs, unresolved = [], [], []
    if not lineup or not (lineup[0] or [{}])[0].get("LINEUP_CK"):
        return rows, refs, unresolved
    sides = {kg["AWAY_ID"]: False, kg["HOME_ID"]: True}
    for meta, table in ((lineup[1], lineup[3]), (lineup[2], lineup[4])):
        info = (meta or [{}])[0]
        team = info.get("T_ID")
        if team not in sides or info.get("G_ID") != kg["G_ID"]:
            continue
        batters = []
        for r in _table((table or [None])[0]):
            if len(r) < 3:
                continue
            pcode = codes.get((team, r[2]))
            if pcode is None:
                unresolved.append(f"{team}:{r[2]}")
                continue
            batters.append((pcode, r))
        if len(batters) < 9:
            continue  # 한 명이라도 못 풀면 반쪽 라인업이 나가므로 이 팀은 통째로 미룬다
        for pcode, r in batters:
            refs.append(PlayerRef(pcode, r[2], team))
            rows.append(LineupRow(pcode=pcode, team_code=team, is_home=sides[team],
                                  bat_order=_i(r[0]), position=_LINEUP_POSITION.get(r[1], r[1]),
                                  is_starter=True, decision=None))
        pit_id = kg.get("B_PIT_P_ID" if sides[team] else "T_PIT_P_ID")
        pit_nm = (kg.get("B_PIT_P_NM" if sides[team] else "T_PIT_P_NM") or "").strip()
        if pit_id and str(pit_id) not in {p for p, _ in batters}:
            refs.append(PlayerRef(str(pit_id), pit_nm, team))
            rows.append(LineupRow(pcode=str(pit_id), team_code=team, is_home=sides[team],
                                  bat_order=None, position="투", is_starter=True, decision=None))
    return rows, refs, unresolved
