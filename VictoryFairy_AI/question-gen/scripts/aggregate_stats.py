"""⓪ 전처리 — 결정적 통계 재집계. LLM 미사용(스펙 Global 제약).

game_result envelope들을 읽어 시즌 통계(상대전적·순위·연승연패·홈원정·월별·
순위추이·최근득점·전년동기)를 순수 함수로 계산한다. 같은 입력이면 항상 같은
출력(랜덤·시각 의존 없음) — 사실 통계의 자연어화 단계(Task 5)에서 LLM이
환각을 일으키지 않도록, 수치 자체는 이 파일에서 결정적으로 확정한다.

입력은 로컬 디렉토리(routine이 aws s3 sync로 미리 준비), 출력도 로컬 디렉토리
→ routine이 다시 S3로 업로드한다. 이 스크립트는 S3에 직접 접근하지 않는다
(boto3 미사용, stdlib + PyYAML만).

이 파일은 순수 집계 함수만 담는다. 렌더링·CLI는 Task 5에서 같은 파일에 추가된다.
"""

from dataclasses import dataclass
from collections import defaultdict
from datetime import date as date_cls, timedelta


@dataclass(frozen=True)
class Game:
    game_id: str
    date: str
    away: str
    home: str
    away_score: int
    home_score: int
    winner: str   # away|home|draw


def parse_game(env: dict) -> "Game | None":
    """game_result envelope 하나를 Game으로 변환한다. 필드 결손 시 None."""
    p = (env or {}).get("payload") or {}
    codes = ((env or {}).get("entities") or {}).get("teamCodes") or []
    gid = p.get("gameId") or ""
    if len(gid) < 8 or len(codes) != 2 or p.get("winner") not in ("away", "home", "draw"):
        return None
    if p.get("awayScore") is None or p.get("homeScore") is None:
        return None
    d = f"{gid[0:4]}-{gid[4:6]}-{gid[6:8]}"
    return Game(gid, d, codes[0], codes[1], p["awayScore"], p["homeScore"], p["winner"])


# ── 내부 헬퍼 ────────────────────────────────────────────────

def _win_pct(w: int, l: int) -> float:
    """KBO 승률 규칙: 무승부는 분모(승+패)에서 제외."""
    if w + l == 0:
        return 0.0
    return round(w / (w + l), 3)


def _wld(team: str, g: Game) -> "str | None":
    """게임 g에서 team의 결과. "W"/"L"/"D", team이 무관한 경기면 None."""
    if g.away == team:
        side = "away"
    elif g.home == team:
        side = "home"
    else:
        return None
    if g.winner == "draw":
        return "D"
    return "W" if g.winner == side else "L"


def _teams_in(games) -> list:
    """games에 등장하는 팀 코드를 알파벳순으로. (set 이터레이션은 해시시드에
    따라 순서가 달라질 수 있어 결정성을 위해 반드시 sorted()로 마무리한다.)"""
    codes = set()
    for g in games:
        codes.add(g.away)
        codes.add(g.home)
    return sorted(codes)


def _parse_date(s: str) -> date_cls:
    y, m, d = s.split("-")
    return date_cls(int(y), int(m), int(d))


def _shift_date(s: str, days: int) -> str:
    return (_parse_date(s) + timedelta(days=days)).isoformat()


# ── 집계 함수 ────────────────────────────────────────────────

def head_to_head(games) -> dict:
    """두 팀 간 상대전적. 키는 "A|B"(코드 사전순)."""
    pairs = defaultdict(list)
    for g in games:
        key = "|".join(sorted((g.away, g.home)))
        pairs[key].append(g)

    result = {}
    for key in sorted(pairs):
        gs = sorted(pairs[key], key=lambda g: (g.date, g.game_id))
        wins = {t: 0 for t in key.split("|")}
        draws = 0
        for g in gs:
            if g.winner == "draw":
                draws += 1
            else:
                winner_team = g.away if g.winner == "away" else g.home
                wins[winner_team] += 1
        last = gs[-1]
        result[key] = {
            "wins": wins,
            "draws": draws,
            "last": {
                "date": last.date,
                "gameId": last.game_id,
                "score": {"away": last.away_score, "home": last.home_score},
                "winner": last.winner,
            },
        }
    return result


def standings(games) -> list:
    """승률 내림차순 순위표. winPct는 (승+패) 기준 소수 3자리.

    v1 단순화: 동률(winPct 같음) 처리 없이 순서대로 rank를 부여한다
    (동순위 표기 없음). 정렬은 winPct desc, 팀코드 asc로 안정적/결정적이다.
    """
    teams = _teams_in(games)
    tally = {t: {"wins": 0, "losses": 0, "draws": 0} for t in teams}
    for g in games:
        for team in (g.away, g.home):
            r = _wld(team, g)
            if r == "W":
                tally[team]["wins"] += 1
            elif r == "L":
                tally[team]["losses"] += 1
            else:
                tally[team]["draws"] += 1

    rows = []
    for t in teams:
        s = tally[t]
        rows.append({
            "team": t,
            "wins": s["wins"],
            "losses": s["losses"],
            "draws": s["draws"],
            "winPct": _win_pct(s["wins"], s["losses"]),
        })
    rows.sort(key=lambda r: (-r["winPct"], r["team"]))
    for i, r in enumerate(rows, start=1):
        r["rank"] = i
    return rows


def streaks(games) -> dict:
    """팀별 마지막 연속 W/L. 날짜순(동일 날짜면 gameId순)으로 봤을 때
    가장 최근 경기부터 같은 결과가 이어진 길이. 무승부는 연속을 끊는다.

    마지막 경기 자체가 무승부인 팀은 "직전 연속"이 끊긴 상태이므로
    {"kind": None, "length": 0}으로 명시한다(소비자가 구분 가능하도록).
    """
    by_team = defaultdict(list)
    for g in games:
        by_team[g.away].append(g)
        by_team[g.home].append(g)

    out = {}
    for t in _teams_in(games):
        gs = sorted(by_team[t], key=lambda g: (g.date, g.game_id))
        results = [_wld(t, g) for g in gs]
        last = results[-1]
        if last == "D":
            out[t] = {"kind": None, "length": 0}
            continue
        length = 0
        for r in reversed(results):
            if r == last:
                length += 1
            else:
                break
        out[t] = {"kind": last, "length": length}
    return out


def home_away(games) -> dict:
    """홈/원정 분리 승패무. {team: {"home": {...}, "away": {...}}}."""
    teams = _teams_in(games)
    out = {t: {"home": {"wins": 0, "losses": 0, "draws": 0},
               "away": {"wins": 0, "losses": 0, "draws": 0}} for t in teams}
    label = {"W": "wins", "L": "losses", "D": "draws"}
    for g in games:
        for team, side in ((g.away, "away"), (g.home, "home")):
            r = _wld(team, g)
            out[team][side][label[r]] += 1
    return out


def monthly(games) -> dict:
    """{team: {"YYYY-MM": {"wins","losses","draws","winPct"}}}."""
    teams = _teams_in(games)
    out = {t: {} for t in teams}
    label = {"W": "wins", "L": "losses", "D": "draws"}
    for g in games:
        ym = g.date[:7]
        for team in (g.away, g.home):
            bucket = out[team].setdefault(ym, {"wins": 0, "losses": 0, "draws": 0})
            r = _wld(team, g)
            bucket[label[r]] += 1
    for t in teams:
        for b in out[t].values():
            b["winPct"] = _win_pct(b["wins"], b["losses"])
    return out


def standings_trend(games) -> dict:
    """개막일(min date) + 27일 시점까지의 순위 vs 전체 경기 기준 현재 순위.

    delta = early_rank - now_rank (양수면 순위 상승 = 랭크 숫자 감소).
    early 시점에 경기가 없었던 팀(그 기간 무경기)은 delta에서 제외한다.
    """
    if not games:
        return {"earlyAsOf": None, "early": {}, "now": {}, "delta": {}}

    min_date = min(g.date for g in games)
    early_as_of = _shift_date(min_date, 27)
    early_games = [g for g in games if g.date <= early_as_of]

    early_rank = {r["team"]: r["rank"] for r in standings(early_games)}
    now_rank = {r["team"]: r["rank"] for r in standings(games)}
    delta = {t: early_rank[t] - now_rank[t] for t in early_rank if t in now_rank}

    return {"earlyAsOf": early_as_of, "early": early_rank, "now": now_rank, "delta": delta}


def recent_scoring(games, end_date: str, days: int = 7) -> dict:
    """end_date를 포함한 직전 days일 윈도([end_date-days+1, end_date])의 득실점."""
    start = _shift_date(end_date, -(days - 1))
    window = [g for g in games if start <= g.date <= end_date]

    out = {t: {"games": 0, "runsFor": 0, "runsAgainst": 0} for t in _teams_in(window)}
    for g in window:
        out[g.away]["games"] += 1
        out[g.away]["runsFor"] += g.away_score
        out[g.away]["runsAgainst"] += g.home_score
        out[g.home]["games"] += 1
        out[g.home]["runsFor"] += g.home_score
        out[g.home]["runsAgainst"] += g.away_score
    return out


def yoy(cur_games, prev_games, as_of: str) -> "dict | None":
    """전년 동일 월-일(as_of의 MM-DD) 컷오프로 두 시즌 승률을 비교한다.

    prev_games가 비면 (전년 데이터 없음) None. 두 시즌 모두에 존재하는
    팀만 결과에 포함한다(한쪽에만 있는 팀은 비교 불가라 제외).
    """
    if not prev_games:
        return None

    cutoff = as_of[5:10]  # "MM-DD"
    cur_filtered = [g for g in cur_games if g.date[5:10] <= cutoff]
    prev_filtered = [g for g in prev_games if g.date[5:10] <= cutoff]

    cur_rows = {r["team"]: r["winPct"] for r in standings(cur_filtered)}
    prev_rows = {r["team"]: r["winPct"] for r in standings(prev_filtered)}

    out = {}
    for t in sorted(set(cur_rows) & set(prev_rows)):
        out[t] = {
            "prev": prev_rows[t],
            "cur": cur_rows[t],
            "delta": round(cur_rows[t] - prev_rows[t], 3),
        }
    return out


def season_games(games, year: int) -> list:
    """date가 해당 연도인 게임만 필터(입력 리스트는 변형하지 않음)."""
    prefix = str(year)
    return [g for g in games if g.date[:4] == prefix]
