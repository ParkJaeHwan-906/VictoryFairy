"""⓪ 전처리 — 결정적 통계 재집계. LLM 미사용(스펙 Global 제약).

game_result envelope들을 읽어 시즌 통계(상대전적·순위·연승연패·홈원정·월별·
순위추이·최근득점·전년동기)를 순수 함수로 계산한다. 같은 입력이면 항상 같은
출력(랜덤·시각 의존 없음) — 사실 통계의 자연어화 단계에서 LLM이 환각을
일으키지 않도록, 수치 자체는 이 파일에서 결정적으로 확정한다.

입력은 로컬 디렉토리(routine이 aws s3 sync로 미리 준비), 출력도 로컬 디렉토리
→ routine이 다시 S3로 업로드한다. 이 스크립트는 S3에 직접 접근하지 않는다
(boto3 미사용, stdlib + PyYAML만).

파일 구성: Game dataclass·parse_game → 순수 집계 함수(Task 4) 다음에
kbo-records 스냅샷 로더·추출 → build_season_stats(조립) → 마크다운 렌더
→ CLI(main)를 이어붙인다(Task 5). 렌더는 f-string만 쓰는 결정적 변환이고,
LLM은 여기서 호출하지 않는다 — 자연어 서술은 다음 단계(퀴즈 생성기 routine)
의 몫이다.
"""

import argparse
import json
from dataclasses import dataclass
from collections import defaultdict
from datetime import date as date_cls, datetime, timedelta, timezone
from pathlib import Path


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


# ── 로더 ────────────────────────────────────────────────

def load_envelopes_dir(path) -> list:
    """디렉토리를 재귀로 훑어 `*.json` envelope를 읽고 `docType=="game_result"`
    인 것만 리스트로 반환한다. 디렉토리가 없으면 빈 리스트, 개별 파일이 JSON이
    아니면 그 파일만 건너뛴다(수집 파이프라인의 부분 실패를 여기서 다시 죽이지
    않는다는 원칙 — 스펙 §5와 동일한 태도)."""
    root = Path(path)
    if not root.exists():
        return []
    out = []
    for p in sorted(root.rglob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(data, dict) and data.get("docType") == "game_result":
            out.append(data)
    return out


def load_snapshots_dir(path) -> dict:
    """`{kbo-dir}/{page}/{date}.json` 구조(Task 2 스냅샷 계약)에서 페이지별로
    가장 최신 날짜의 스냅샷 하나만 골라 `{page_slug: snapshot_dict}`로 반환한다.

    디렉토리 부재·빈 디렉토리·개별 페이지 결측(예: top5·record-correct는
    kbo_records 소스에서 상시 미생성)은 모두 정상 케이스로 보고 빈 dict나
    해당 페이지 키 부재로 처리한다 — extract_kbo_official이 이를 그대로
    None으로 넘긴다."""
    root = Path(path)
    if not root.exists():
        return {}
    out = {}
    for page_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        best_date, best_snap = None, None
        for f in sorted(page_dir.glob("*.json")):
            try:
                snap = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            snap_date = snap.get("date") or f.stem
            if best_date is None or snap_date > best_date:
                best_date, best_snap = snap_date, snap
        if best_snap is not None:
            out[page_dir.name] = best_snap
    return out


# ── 추출(kbo-records 스냅샷 → 통계 렌더용 부분) ──────────────────

def _wrap_snapshot(snap):
    """스냅샷 dict를 `{"asOf","tables"}`로, 없으면 None으로."""
    if not snap:
        return None
    return {"asOf": snap.get("date"), "tables": snap.get("tables")}


def extract_kbo_official(snapshots: dict) -> dict:
    """kbo-records 스냅샷 딕셔너리(`{page_slug: snapshot_dict}`)에서 통계
    렌더에 쓸 부분만 추출한다.

    top5·record-correct 페이지는 마크업이 <table>이 아니라(Task 2 kbo_records.py
    PAGES 주석) 상시 미생성이므로, 이 함수는 해당 슬러그의 부재를 에러 없이
    None으로 처리하는 게 정상 경로다."""
    team_rank_daily = _wrap_snapshot(snapshots.get("team-rank-daily"))
    hitter_basic = _wrap_snapshot(snapshots.get("hitter-basic"))
    pitcher_basic = _wrap_snapshot(snapshots.get("pitcher-basic"))
    top5 = _wrap_snapshot(snapshots.get("top5"))
    season_leaders = None
    if hitter_basic is not None or pitcher_basic is not None or top5 is not None:
        season_leaders = {"hitterBasic": hitter_basic, "pitcherBasic": pitcher_basic, "top5": top5}
    milestone_watch = _wrap_snapshot(snapshots.get("expectation-week"))
    team_history = _wrap_snapshot(snapshots.get("history-team"))
    record_correct = _wrap_snapshot(snapshots.get("record-correct"))
    return {
        "teamRankDaily": team_rank_daily,
        "seasonLeaders": season_leaders,
        "milestoneWatch": milestone_watch,
        "teamHistory": team_history,
        "recordCorrect": record_correct,
    }


# ── 조립(시즌 통계 하나로) ─────────────────────────────────────

def build_season_stats(games, today: str) -> dict:
    """Task 4 집계 함수를 전부 호출해 시즌 통계를 하나로 조립한다.

    `today`(=asOf) 연도를 현재 시즌, 그 전 연도를 전년 시즌으로 보고
    `season_games`로 나눈다 — yoy 비교의 두 시즌 게임 목록도 여기서 갈라낸다.
    `generatedAt`만 호출 시각(UTC) 의존적이고, 그 외 값은 games/today로부터
    결정적으로 계산된다."""
    year = int(today[:4])
    cur = season_games(games, year)
    prev = season_games(games, year - 1)
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "asOf": today,
        "headToHead": head_to_head(cur),
        "standings": standings(cur),
        "streaks": streaks(cur),
        "homeAway": home_away(cur),
        "monthly": monthly(cur),
        "standingsTrend": standings_trend(cur),
        "recentScoring": recent_scoring(cur, end_date=today),
        "yoy": yoy(cur, prev, as_of=today),
    }


# ── 렌더(결정적 마크다운, LLM 미사용) ──────────────────────────

_WINNER_LABEL = {"away": "원정팀 승", "home": "홈팀 승", "draw": "무승부"}


def _render_standings_section(rows: list, as_of: str) -> str:
    lines = [f"## 팀 순위 (기준일 {as_of})", "",
             "| 순위 | 팀 | 승 | 패 | 무 | 승률 |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['rank']} | {r['team']} | {r['wins']} | {r['losses']} | "
                     f"{r['draws']} | {r['winPct']:.3f} |")
    return "\n".join(lines)


def _render_head_to_head_section(h2h: dict, as_of: str) -> str:
    lines = [f"## 상대전적 (기준일 {as_of})"]
    if not h2h:
        lines.append("데이터 없음")
        return "\n".join(lines)
    for key in sorted(h2h):
        v = h2h[key]
        a, b = key.split("|")
        wins_txt = " ".join(f"{t} {n}승" for t, n in v["wins"].items())
        last = v["last"]
        score = f'{last["score"]["away"]}:{last["score"]["home"]}'
        winner = _WINNER_LABEL.get(last["winner"], last["winner"])
        lines.append(f"- **{a} vs {b}**: {wins_txt} · {v['draws']}무 "
                     f"(최근 {last['date']} {score}, {winner})")
    return "\n".join(lines)


def _render_streaks_section(streaks_map: dict, as_of: str) -> str:
    lines = [f"## 연승·연패 (기준일 {as_of})"]
    if not streaks_map:
        lines.append("데이터 없음")
        return "\n".join(lines)
    for team in sorted(streaks_map):
        s = streaks_map[team]
        if s["kind"] is None:
            lines.append(f"- {team}: 직전 경기 무승부 — 연속 기록 없음")
        elif s["kind"] == "W":
            lines.append(f"- {team}: {s['length']}연승")
        else:
            lines.append(f"- {team}: {s['length']}연패")
    return "\n".join(lines)


def _render_home_away_section(ha: dict, as_of: str) -> str:
    lines = [f"## 홈/원정 (기준일 {as_of})", "",
             "| 팀 | 홈 승-패-무 | 원정 승-패-무 |", "|---|---|---|"]
    for team in sorted(ha):
        h, a = ha[team]["home"], ha[team]["away"]
        lines.append(f"| {team} | {h['wins']}-{h['losses']}-{h['draws']} | "
                     f"{a['wins']}-{a['losses']}-{a['draws']} |")
    return "\n".join(lines)


def _render_monthly_section(monthly_map: dict, as_of: str) -> str:
    lines = [f"## 월별 성적 (기준일 {as_of})"]
    if not monthly_map:
        lines.append("데이터 없음")
        return "\n".join(lines)
    for team in sorted(monthly_map):
        lines.append(f"### {team}")
        for ym in sorted(monthly_map[team]):
            b = monthly_map[team][ym]
            lines.append(f"- {ym}: {b['wins']}승 {b['losses']}패 {b['draws']}무 "
                         f"(승률 {b['winPct']:.3f})")
    return "\n".join(lines)


def _render_standings_trend_section(trend: dict, as_of: str) -> str:
    lines = [f"## 시즌 초 대비 순위 변동 (기준일 {as_of})"]
    early_as_of = trend.get("earlyAsOf")
    if early_as_of is None:
        lines.append("데이터 없음")
        return "\n".join(lines)
    lines.append(f"(개막 후 {early_as_of} 시점 순위 대비)")
    now_rank, delta = trend["now"], trend["delta"]
    for team in sorted(now_rank):
        n = now_rank[team]
        if team not in delta:
            lines.append(f"- {team}: 초반 데이터 없음 → 현재 {n}위")
            continue
        d = delta[team]
        early_rank = trend["early"][team]
        if d > 0:
            change = f"{d}단계 상승"
        elif d < 0:
            change = f"{abs(d)}단계 하락"
        else:
            change = "변동 없음"
        lines.append(f"- {team}: {early_rank}위 → {n}위 ({change})")
    return "\n".join(lines)


def _render_recent_scoring_section(rs: dict, as_of: str) -> str:
    lines = [f"## 최근 7일 득실 (기준일 {as_of})"]
    if not rs:
        lines.append("데이터 없음")
        return "\n".join(lines)
    for team in sorted(rs):
        v = rs[team]
        diff = v["runsFor"] - v["runsAgainst"]
        sign = "+" if diff >= 0 else ""
        lines.append(f"- {team}: {v['games']}경기 {v['runsFor']}득 {v['runsAgainst']}실 "
                     f"(득실차 {sign}{diff})")
    return "\n".join(lines)


def _render_yoy_section(yoy_map, as_of: str) -> str:
    lines = [f"## 전년 대비 (기준일 {as_of})"]
    if yoy_map is None:
        prev_year = int(as_of[:4]) - 1
        lines.append(f"{prev_year} 데이터 없음 — 비활성")
        return "\n".join(lines)
    if not yoy_map:
        lines.append("비교 가능한 팀 없음(양쪽 시즌에 겹치는 팀 없음)")
        return "\n".join(lines)
    for team in sorted(yoy_map):
        v = yoy_map[team]
        sign = "+" if v["delta"] >= 0 else ""
        lines.append(f"- {team}: 전년 {v['prev']:.3f} → 올해 {v['cur']:.3f} "
                     f"({sign}{v['delta']:.3f})")
    return "\n".join(lines)


def render_season_md(stats: dict) -> str:
    """build_season_stats 출력을 사람·LLM이 읽을 마크다운으로 렌더한다.

    수치를 그대로 옮기기만 하고 해석·추론은 하지 않는다(다음 단계 LLM의
    환각을 막는 게 이 파일 전체의 목적). 모든 섹션 제목에 `(기준일 {asOf})`를
    넣는다."""
    as_of = stats["asOf"]
    sections = [
        f"# 시즌 통계 요약 (기준일 {as_of})",
        _render_standings_section(stats["standings"], as_of),
        _render_head_to_head_section(stats["headToHead"], as_of),
        _render_streaks_section(stats["streaks"], as_of),
        _render_home_away_section(stats["homeAway"], as_of),
        _render_monthly_section(stats["monthly"], as_of),
        _render_standings_trend_section(stats["standingsTrend"], as_of),
        _render_recent_scoring_section(stats["recentScoring"], as_of),
        _render_yoy_section(stats["yoy"], as_of),
    ]
    return "\n\n".join(sections)


def _render_table_md(table: dict) -> str:
    """스냅샷 표 하나(`{"headers","rows"}`)를 마크다운 표로."""
    headers = table.get("headers") or []
    rows = table.get("rows") or []
    if not headers:
        width = len(rows[0]) if rows else 0
        headers = [""] * width
    header_line = "| " + " | ".join(headers) + " |"
    sep_line = "|" + "|".join(["---"] * len(headers)) + "|"
    row_lines = ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join([header_line, sep_line] + row_lines)


def _render_tables_md(tables) -> str:
    if not tables:
        return "데이터 없음"
    return "\n\n".join(_render_table_md(t) for t in tables)


def _kbo_asof(entry) -> str:
    return entry["asOf"] if entry else "미확보"


def _render_kbo_simple_section(title: str, entry, missing_reason: str = "스냅샷 없음",
                               level: int = 2) -> str:
    header = f"{'#' * level} {title} (기준일 {_kbo_asof(entry)})"
    if not entry:
        return f"{header}\n\n{missing_reason}"
    return f"{header}\n\n{_render_tables_md(entry.get('tables') or [])}"


def _render_season_leaders_section(leaders) -> str:
    title = "타/투 시즌 리더"
    if not leaders:
        return (f"## {title} (기준일 미확보)\n\n"
                "스냅샷 없음(hitter-basic/pitcher-basic/top5 모두 미수집)")
    sub_asof = next((_kbo_asof(leaders[k]) for k in ("hitterBasic", "pitcherBasic", "top5")
                     if leaders.get(k)), "미확보")
    parts = [f"## {title} (기준일 {sub_asof})"]
    labels = (("hitterBasic", "타자 기본기록"), ("pitcherBasic", "투수 기본기록"),
              ("top5", "TOP5 랭킹"))
    for key, label in labels:
        entry = leaders.get(key)
        reason = ("top5 페이지는 마크업이 <table>이 아니라 상시 미생성"
                 if key == "top5" else "스냅샷 없음")
        parts.append(_render_kbo_simple_section(label, entry, reason, level=3))
    return "\n\n".join(parts)


def render_kbo_md(kbo: dict) -> str:
    """extract_kbo_official 출력을 마크다운으로 렌더한다.

    각 섹션은 자기 스냅샷의 날짜를 `(기준일 …)`로 표기한다(kbo-official 전체가
    공유하는 단일 asOf가 없음 — 페이지마다 수집 시각이 다를 수 있어서다).
    top5·record-correct는 상시 미생성(Task 2)이라 None이 정상 경로다."""
    sections = [
        "# KBO 공식 기록실 스냅샷",
        _render_kbo_simple_section("팀 순위(공식)", kbo.get("teamRankDaily")),
        _render_season_leaders_section(kbo.get("seasonLeaders")),
        _render_kbo_simple_section("주간 마일스톤 워치", kbo.get("milestoneWatch")),
        _render_kbo_simple_section("역대 팀 기록", kbo.get("teamHistory")),
        _render_kbo_simple_section("정정 기록", kbo.get("recordCorrect"),
                                   "스냅샷 없음(record-correct는 상시 미생성 — Task 2)"),
    ]
    return "\n\n".join(sections)


# ── CLI ──────────────────────────────────────────────────

def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="game_result·kbo-records 스냅샷을 읽어 시즌 통계를 결정적으로 "
                    "재집계·렌더한다(LLM 미사용).")
    p.add_argument("--envelopes-dir", required=True, help="game_result envelope 디렉토리(재귀 탐색)")
    p.add_argument("--kbo-dir", required=True, help="kbo-records 스냅샷 디렉토리({page}/{date}.json)")
    p.add_argument("--out-dir", required=True, help="산출물(season/kbo-official *.json/*.md) 디렉토리")
    p.add_argument("--date", required=True, help="기준일 YYYY-MM-DD (asOf)")
    return p


def main(argv=None) -> None:
    """CLI 진입점. envelopes-dir·kbo-dir를 읽어 out-dir에 4개 파일을 쓴다:
    season.json, season.md, kbo-official.json, kbo-official.md.
    kbo-dir가 없거나 비어 있어도(스냅샷 미수집) kbo-official 파일은 만들되
    내부 값은 전부 None으로 채운다."""
    args = _build_arg_parser().parse_args(argv)

    envelopes = load_envelopes_dir(args.envelopes_dir)
    games = [g for g in (parse_game(e) for e in envelopes) if g is not None]
    stats = build_season_stats(games, today=args.date)

    snapshots = load_snapshots_dir(args.kbo_dir)
    kbo = extract_kbo_official(snapshots)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "season.json").write_text(
        json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "season.md").write_text(render_season_md(stats), encoding="utf-8")
    (out_dir / "kbo-official.json").write_text(
        json.dumps(kbo, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "kbo-official.md").write_text(render_kbo_md(kbo), encoding="utf-8")


if __name__ == "__main__":
    main()
