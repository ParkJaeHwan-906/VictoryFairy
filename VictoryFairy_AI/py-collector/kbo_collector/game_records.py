"""네이버 스포츠 record API에서 경기 박스스코어를 긁고 파싱한다.

소스: {base}/schedule/games?...  (일자별 경기 목록)
      {base}/schedule/games/{gameId}/record  (경기 기록)
경기 종료 후에도 영구 제공되므로 과거 시즌 백필에 사용한다.
"""
from dataclasses import dataclass, field

from .dimensions import TEAM_CODES

_UA = "Mozilla/5.0"

_GAME_TYPE = {"0": "regular", "1": "preseason"}
_FRACTIONS = (("⅓", 1), ("⅔", 2), ("1/3", 1), ("2/3", 2))


@dataclass
class PlayerRef:
    pcode: str
    name: str
    team_code: str


@dataclass
class PitchingRow:
    pcode: str
    team_code: str
    is_home: bool
    seq: int
    decision: str | None
    ip_display: str
    ip_outs: int
    batters_faced: int | None
    at_bats: int | None
    hits: int | None
    runs: int | None
    earned_runs: int | None
    home_runs: int | None
    walks_hbp: int | None
    strikeouts: int | None


@dataclass
class BattingRow:
    pcode: str
    team_code: str
    is_home: bool
    bat_order: int | None
    position: str | None
    at_bats: int | None
    runs: int | None
    hits: int | None
    home_runs: int | None
    rbi: int | None
    walks: int | None
    strikeouts: int | None
    stolen_bases: int | None


@dataclass
class LineupRow:
    """경기별 출전 1행 (game_lineups 테이블과 1:1). 타자·투수 겸출은 한 행으로 병합."""
    pcode: str
    team_code: str
    is_home: bool
    bat_order: int | None       # 타순 1~9, 타석 없는 투수는 None
    position: str | None        # 포지션 표기(중/포/지/투 …), 대타=타/대주자=주
    is_starter: bool
    decision: str | None        # 투수 한정 W/L/S/H


@dataclass
class GameRow:
    game_id: str
    game_date: str          # 'YYYY-MM-DD'
    game_type: str
    round_no: int | None
    stadium: str | None
    start_time: str | None
    away_team_code: str
    home_team_code: str
    away_score: int | None
    home_score: int | None
    away_hits: int | None
    home_hits: int | None
    away_errors: int | None
    home_errors: int | None
    away_bb: int | None
    home_bb: int | None
    winner: str | None
    away_starter_pcode: str | None
    home_starter_pcode: str | None
    inn_scores: dict
    pitching: list[PitchingRow] = field(default_factory=list)
    batting: list[BattingRow] = field(default_factory=list)
    players: list[PlayerRef] = field(default_factory=list)


def innings_to_outs(text: str | None) -> int:
    """'6' -> 18, '6 ⅓' -> 19, '⅔' -> 2, '' -> 0."""
    s = (text or "").strip()
    if not s:
        return 0
    frac = 0
    for token, val in _FRACTIONS:
        if token in s:
            frac = val
            s = s.replace(token, "")
    s = s.strip()
    whole = int(s) if s.isdigit() else 0
    return whole * 3 + frac


def _i(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def schedule_url(settings, date: str) -> str:
    # 라이브 API는 대시 날짜 필수 (YYYY-MM-DD).
    return (f"{settings.naver_base_url}/schedule/games?fields=basic,statusNum,statusInfo"
            f"&upperCategoryId=kbaseball&fromDate={date}&toDate={date}")


def record_url(settings, game_id: str) -> str:
    return f"{settings.naver_base_url}/schedule/games/{game_id}/record"


def list_finished_games(schedule_json: dict) -> list[dict]:
    """일자별 스케줄 JSON -> 완료(RESULT)된 KBO 정규 팀 경기만.

    올스타전 등 비표준 팀코드, 취소(BEFORE) 경기는 제외.
    """
    games = (schedule_json.get("result") or {}).get("games") or []
    out = []
    for g in games:
        if g.get("categoryId") != "kbo":
            continue
        if g.get("statusCode") != "RESULT" or g.get("cancel"):
            continue
        if g.get("awayTeamCode") not in TEAM_CODES or g.get("homeTeamCode") not in TEAM_CODES:
            continue
        out.append(g)
    return out


def list_kbo_games(schedule_json: dict) -> list[dict]:
    """일자별 스케줄 JSON -> 상태 무관 KBO 정규 팀 경기 전부 (games_sync용).

    list_finished_games 와 달리 취소·예정·진행 경기를 포함한다.
    """
    games = (schedule_json.get("result") or {}).get("games") or []
    return [g for g in games
            if g.get("categoryId") == "kbo"
            and g.get("awayTeamCode") in TEAM_CODES
            and g.get("homeTeamCode") in TEAM_CODES]


def map_status(g: dict) -> str | None:
    """schedule 경기 1건 -> game_statuses.name (미지 상태는 None).

    취소는 cancel 플래그 최우선 — 취소 경기는 statusCode "BEFORE" + winner
    "DRAW" 껍데기로 오므로(2026-07-08 NCHH 실측) 다른 필드로 판정하면 오답.
    진행 중 statusCode 는 "STARTED"(2026-08-04 실황 3경기 실측). "LIVE" 는
    초기 가정값인데 반례가 없어 호환으로 남겨 둔다.

    미시작은 코드가 둘이고 **경기 당일에 갈린다**: 먼 날짜는 "BEFORE"(statusNum=0)
    지만 당일 경기는 첫 투구 전까지 "READY"(statusNum=1, statusInfo="경기전")로
    온다 — 2026-08-12 18:25 KST 에 19:00 시작 5경기 전수 실측. 둘 다 아직 안 한
    경기이므로 SCHEDULED 로 접는다.

    READY 를 빠뜨리면 **경기 당일 낮~경기 직전 구간의 경기가 통째로 스킵**된다
    (상태 동기화도, preview 선발 라인업 적재도 일어나지 않는다). 하필 그 구간이
    선발 공시가 뜨는 때라, 라인업 수집이 조용히 무력화된다.
    """
    if g.get("cancel"):
        return "CANCELED"
    sc = g.get("statusCode")
    if sc in ("BEFORE", "READY"):
        return "SCHEDULED"
    if sc in ("STARTED", "LIVE"):
        return "IN_PROGRESS"
    if sc == "RESULT":
        draw = g.get("homeTeamScore") == g.get("awayTeamScore")
        return "DRAW" if draw else "FINISHED"
    return None


def _decisions(record: dict) -> dict:
    """pcode -> 'W'/'L'/'S'/'H' (pitchingResult)."""
    out = {}
    for r in record.get("pitchingResult") or []:
        wls = (r.get("wls") or "").strip().upper()
        code = str(r.get("pCode") or r.get("pcode") or "")
        if wls in ("W", "L", "S", "H") and code:
            out[code] = wls
    return out


def _pitchers(record: dict, decisions: dict) -> tuple[list[PitchingRow], list[PlayerRef]]:
    rows, refs = [], []
    box = record.get("pitchersBoxscore") or {}
    for side, is_home, tcode in (("away", False, None), ("home", True, None)):
        team = _team_of(record, side)
        for seq, p in enumerate(box.get(side) or []):
            code = str(p.get("pcode") or p.get("pCode") or "")
            if not code:
                continue
            name = p.get("name") or ""
            refs.append(PlayerRef(code, name, team))
            rows.append(PitchingRow(
                pcode=code, team_code=team, is_home=is_home, seq=seq,
                decision=decisions.get(code),
                ip_display=(p.get("inn") or "").strip(),
                ip_outs=innings_to_outs(p.get("inn")),
                batters_faced=_i(p.get("bf")), at_bats=_i(p.get("ab")),
                hits=_i(p.get("hit")), runs=_i(p.get("r")), earned_runs=_i(p.get("er")),
                home_runs=_i(p.get("hr")), walks_hbp=_i(p.get("bbhp")),
                strikeouts=_i(p.get("kk")),
            ))
    return rows, refs


def _batters(record: dict) -> tuple[list[BattingRow], list[PlayerRef]]:
    rows, refs = [], []
    box = record.get("battersBoxscore") or {}
    for side, is_home in (("away", False), ("home", True)):
        team = _team_of(record, side)
        for b in box.get(side) or []:
            code = str(b.get("playerCode") or b.get("pcode") or "")
            if not code:
                continue
            refs.append(PlayerRef(code, b.get("name") or "", team))
            rows.append(BattingRow(
                pcode=code, team_code=team, is_home=is_home,
                bat_order=_i(b.get("batOrder")), position=b.get("pos") or None,
                at_bats=_i(b.get("ab")), runs=_i(b.get("run")), hits=_i(b.get("hit")),
                home_runs=_i(b.get("hr")), rbi=_i(b.get("rbi")), walks=_i(b.get("bb")),
                strikeouts=_i(b.get("kk")), stolen_bases=_i(b.get("sb")),
            ))
    return rows, refs


def _team_of(record: dict, side: str) -> str:
    gi = record.get("gameInfo") or {}
    return gi.get("aCode") if side == "away" else gi.get("hCode")


def parse_record(game_id: str, record: dict) -> GameRow:
    """recordData -> GameRow (하위 pitching/batting/players 포함)."""
    gi = record.get("gameInfo") or {}
    sb = record.get("scoreBoard") or {}
    rheb = sb.get("rheb") or {}
    a, h = rheb.get("away") or {}, rheb.get("home") or {}
    ar, hr = _i(a.get("r")), _i(h.get("r"))
    winner = None
    if ar is not None and hr is not None:
        winner = "away" if ar > hr else "home" if hr > ar else "draw"

    gdate = str(gi.get("gdate") or "")
    date = f"{gdate[0:4]}-{gdate[4:6]}-{gdate[6:8]}" if len(gdate) == 8 else None

    decisions = _decisions(record)
    pit, pref = _pitchers(record, decisions)
    bat, bref = _batters(record)

    # dedup PlayerRef by pcode (마지막 관측 이름/팀 유지)
    seen = {}
    for r in pref + bref:
        seen[r.pcode] = r

    return GameRow(
        game_id=game_id, game_date=date,
        game_type=_GAME_TYPE.get(str(gi.get("gameFlag")), "other"),
        round_no=_i(gi.get("round")), stadium=gi.get("stadium") or None,
        start_time=gi.get("gtime") or None,
        away_team_code=gi.get("aCode"), home_team_code=gi.get("hCode"),
        away_score=ar, home_score=hr,
        away_hits=_i(a.get("h")), home_hits=_i(h.get("h")),
        away_errors=_i(a.get("e")), home_errors=_i(h.get("e")),
        away_bb=_i(a.get("b")), home_bb=_i(h.get("b")),
        winner=winner,
        away_starter_pcode=str(gi.get("aPCode")) if gi.get("aPCode") else None,
        home_starter_pcode=str(gi.get("hPCode")) if gi.get("hPCode") else None,
        inn_scores=sb.get("inn") or {},
        pitching=pit, batting=bat, players=list(seen.values()),
    )


def build_lineups(game: GameRow) -> list[LineupRow]:
    """GameRow -> 출전 명단(교체 포함).

    선발 판정:
    - 타자: 박스스코어는 타순별로 선발 → 교체 순서로 나열되므로, (홈/원정, 타순)별
      첫 등장 행이 선발. 타순 없는 행(집계 꼬리 등)은 선발 아님.
    - 투수: gameInfo 의 선발투수 pcode(aPCode/hPCode)와 일치하면 선발,
      메타가 없으면 등판 순서 0(seq==0)으로 대체 판정. 포지션은 '투'.
    같은 선수가 타자·투수 양쪽에 있으면(지명타자 해제 등) 한 행으로 병합한다.
    """
    rows: dict[str, LineupRow] = {}
    seen_order: set[tuple[bool, int]] = set()
    for b in game.batting:
        starter = b.bat_order is not None and (b.is_home, b.bat_order) not in seen_order
        if starter:
            seen_order.add((b.is_home, b.bat_order))
        rows[b.pcode] = LineupRow(
            pcode=b.pcode, team_code=b.team_code, is_home=b.is_home,
            bat_order=b.bat_order, position=b.position,
            is_starter=starter, decision=None,
        )
    for p in game.pitching:
        meta = game.home_starter_pcode if p.is_home else game.away_starter_pcode
        starter = p.pcode == meta if meta else p.seq == 0
        prev = rows.get(p.pcode)
        if prev:
            rows[p.pcode] = LineupRow(
                pcode=p.pcode, team_code=p.team_code, is_home=p.is_home,
                bat_order=prev.bat_order, position=prev.position,
                is_starter=prev.is_starter or starter, decision=p.decision,
            )
        else:
            rows[p.pcode] = LineupRow(
                pcode=p.pcode, team_code=p.team_code, is_home=p.is_home,
                bat_order=None, position="투",
                is_starter=starter, decision=p.decision,
            )
    return list(rows.values())
