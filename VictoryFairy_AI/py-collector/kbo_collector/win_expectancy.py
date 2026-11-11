"""경기 상황 -> 홈·원정 승률 (승리 기대값). 네이버 승률의 대체 원천.

네이버 차단 뒤 KBO 게임센터에는 승률이 없다. 대신 경기 목록(GetKboGameList)이 이닝·초말·아웃·
주자·점수를 실시간으로 주므로, 그 상황에서 홈이 이길 확률을 직접 계산한다.

방법(Tango 식 승리 기대값): (아웃, 주자) 24상황별로 "그 이닝에 남은 득점" 분포(`_run_dist.DIST`)를
두고, 남은 이닝을 이 분포로 굴려 점수차가 어떻게 끝나는지 동적 계획법으로 센다. 다음 이닝은 무사
주자 없음 분포를 쓴다. 팀 전력·투수는 보지 않는다.

- 9회 이후 초가 끝났을 때 홈이 앞서면 말은 없다. 9회 이후 말이 끝나 점수가 다르면 끝난다.
- 정규시즌은 11회, 포스트시즌은 15회까지 하고 그래도 같으면 무승부다(KBO 규정).
- 무승부 확률은 반씩 나눠 홈+원정 = 100 으로 맞춘다 — 네이버 승률도 늘 합이 100 이었다.

평가(2026-11-03): 9월 이전 160경기로 분포를 만들고 이후 146경기 11,359타석을 실제 승패로 채점하면
Brier 0.1450(네이버 0.1418), 같은 타석의 네이버 승률과는 평균 3.4%p 차이였다.
"""
import json
import os
import sys
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal
from functools import lru_cache

from ._run_dist import DIST

_START = DIST[(0, 0)]
_DIFF_CAP = 20  # 이 이상 점수차는 결과가 같다 — 메모이즈 상태 수를 묶는다
REGULAR_MAX_INNING = 11
POSTSEASON_MAX_INNING = 15


def _clip(diff: int) -> int:
    return max(-_DIFF_CAP, min(_DIFF_CAP, diff))


@lru_cache(maxsize=None)
def _within(inn: int, half: int, outs: int, bases: int, diff: int, max_inn: int):
    """(P 홈 승, P 무) — inn 회 half(0 초·1 말), outs·bases 상황, 홈 기준 점수차 diff."""
    ph = pd = 0.0
    for runs, p in enumerate(DIST.get((outs, bases)) or _START):
        if p:
            h, d = _after_half(inn, half, _clip(diff + (runs if half else -runs)), max_inn)
            ph += p * h
            pd += p * d
    return ph, pd


@lru_cache(maxsize=None)
def _after_half(inn: int, half: int, diff: int, max_inn: int):
    if half == 0:
        if inn >= 9 and diff > 0:
            return 1.0, 0.0
        return _within(inn, 1, 0, 0, diff, max_inn)
    if inn >= 9 and diff != 0:
        return (1.0, 0.0) if diff > 0 else (0.0, 0.0)
    if inn >= max_inn:
        return 0.0, 1.0
    return _within(inn + 1, 0, 0, 0, diff, max_inn)


def probabilities(inning: int, half: int, outs: int, bases: int, home: int, away: int,
                  max_inning: int = REGULAR_MAX_INNING) -> tuple[float, float]:
    """(P 홈 승, P 무승부). half 0=초·1=말, bases 비트 1루=1·2루=2·3루=4."""
    if half == 1 and inning >= 9 and home > away:
        return 1.0, 0.0  # 끝내기
    if outs >= 3:
        return _after_half(inning, half, _clip(home - away), max_inning)
    return _within(inning, half, outs, bases, _clip(home - away), max_inning)


def win_rate(inning, half, outs, bases, home, away, max_inning=REGULAR_MAX_INNING):
    """(홈 승률, 원정 승률) — 백분율 Decimal 소수 첫째 자리, 합 100. games.*_win_rate 형식."""
    ph, pd = probabilities(inning, half, outs, bases, home, away, max_inning)
    h = (Decimal(str(ph + pd / 2)) * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return h, Decimal("100.0") - h


_HALF = {"T": 0, "B": 1}


def from_kbo_game(kg: dict):
    """KBO 경기 목록 1건(진행 중)의 (홈, 원정) 승률. 상황을 못 읽으면 None(= 모름, 직전 값 유지)."""
    try:
        inning, half = int(kg["GAME_INN_NO"]), _HALF[kg["GAME_TB_SC"]]
        outs = int(kg.get("OUT_CN") or 0)
        bases = sum(bit for key, bit in (("B1_BAT_ORDER_NO", 1), ("B2_BAT_ORDER_NO", 2),
                                         ("B3_BAT_ORDER_NO", 4)) if int(kg.get(key) or 0))
        home, away = int(kg["B_SCORE_CN"]), int(kg["T_SCORE_CN"])
    except (KeyError, TypeError, ValueError):
        return None
    if inning < 1 or not 0 <= outs <= 3:
        return None
    max_inn = REGULAR_MAX_INNING if str(kg.get("SR_ID")) == "0" else POSTSEASON_MAX_INNING
    return win_rate(inning, half, outs, bases, home, away, max_inn)


# ---------------------------------------------------------------- 분포 재생성 (오프라인)
KMAX = 10


def _game_states(gdir):
    """relay 디렉터리(이닝별 JSON) -> (타석 시작 상태 목록, (홈 최종, 원정 최종))."""
    pas, final = [], None
    for f in sorted((f for f in os.listdir(gdir) if f.endswith(".json")), key=lambda f: int(f[:-5])):
        try:
            d = json.load(open(os.path.join(gdir, f), encoding="utf-8"))["result"]["textRelayData"]
        except (OSError, ValueError, KeyError, TypeError):
            continue
        g = d.get("currentGameState") or {}
        final = (int(g.get("homeScore") or 0), int(g.get("awayScore") or 0))
        for t in d.get("textRelays") or []:
            start = next((o for o in sorted(t.get("textOptions") or [], key=lambda o: o.get("seqno") or 0)
                          if o.get("type") == 8), None)  # type 8 = 타석 시작 줄
            if start is None:
                continue
            s = start["currentGameState"]
            bases = sum(bit for key, bit in (("base1", 1), ("base2", 2), ("base3", 4)) if s[key] != "0")
            pas.append((int(t["inn"]), int(t["homeOrAway"]), int(s["out"]), bases,
                        int(s["homeScore"]), int(s["awayScore"]), t["no"]))
    pas.sort(key=lambda x: x[-1])
    return pas, final


def count_runs(relay_dir) -> tuple[dict, int]:
    """{(아웃, 주자): [남은 득점 0..KMAX 빈도]}, 경기 수."""
    cnt = defaultdict(lambda: [0] * (KMAX + 1))
    games = 0
    for gid in sorted(os.listdir(relay_dir)):
        gdir = os.path.join(relay_dir, gid)
        if not os.path.isdir(gdir):
            continue
        pas, final = _game_states(gdir)
        if len(pas) < 40 or final is None:
            continue  # 중계가 끊긴 경기
        games += 1
        halves = defaultdict(list)
        for p in pas:
            halves[(p[0], p[1])].append(p)
        keys = sorted(halves)
        for i, k in enumerate(keys):
            bat = 4 if k[1] == 1 else 5
            end = halves[keys[i + 1]][0][bat] if i + 1 < len(keys) else final[bat - 4]
            for p in halves[k]:
                if p[2] < 3:
                    cnt[(p[2], p[3])][min(max(0, end - p[bat]), KMAX)] += 1
    return cnt, games


def _write_dist(cnt, games, path):
    lines = [
        '"""(아웃, 주자) 별 "그 이닝에 남은 득점" 분포 — win_expectancy 의 입력. 생성물이니 손으로 고치지 말 것.',
        "",
        f"2026 정규·포스트시즌 {games}경기(네이버 relay 아카이브, S3 raw-json/relay/, 2026-07-08~10-14)의 타석 시작",
        "상태에서 셌다. 재생성: python -m kbo_collector.win_expectancy build <relay 디렉터리>.",
        "키 (아웃, 주자 비트: 1루=1·2루=2·3루=4), 값 [P(0점), P(1점), …, P(10점 이상)].",
        '"""',
        "",
        f"GAMES = {games}",
        "DIST = {",
    ]
    for outs in range(3):
        for bases in range(8):
            c = cnt[(outs, bases)]
            n = sum(c)
            probs = ", ".join(f"{x / n:.5f}" for x in c)
            lines.append(f"    ({outs}, {bases}): [{probs}],  # n={n}")
    lines.append("}")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":  # python -m kbo_collector.win_expectancy build <relay_dir>
    if len(sys.argv) != 3 or sys.argv[1] != "build":
        sys.exit("usage: python -m kbo_collector.win_expectancy build <relay_dir>")
    counts, n_games = count_runs(sys.argv[2])
    _write_dist(counts, n_games, os.path.join(os.path.dirname(__file__), "_run_dist.py"))
    print(f"wrote _run_dist.py from {n_games} games")
