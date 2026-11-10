"""데모 경기(naver_game_id DEMO…) 중계 시뮬레이터 — 점수·이닝·실시간 승률을 움직이고 SSE game-update 를 쏜다.

실경기는 수집기가 DB 를 갱신하고 S3→SQS 알림을 남기면 user-app 이 Redis `game:events` 로 팬아웃한다.
데모 경기는 수집기가 모르므로 이 스크립트가 그 두 단계를 대신한다:
  1) games 행(점수·이닝·승률) UPDATE   2) 공개 GET /api/games 의 서버 응답을 그대로 실어 `game:events` 에 PUBLISH
상태 전이(시작·종료)는 MySQL EVENT 가 하고, 이 스크립트는 그 전이를 감지해 status 이벤트만 쏜다.

10/29 판과 다른 점: 경기마다 끝나는 시각이 다르다. 경기별 종료 이벤트 `demo_finish_<ngid>` 의 EXECUTE_AT 과
최종 스코어를 읽어 [경기 시작(game_date), 종료 - FINISH_LEAD_MIN] 구간에 1회초~9회를 고르게 펼친다.
그래서 일찍 끝나는 경기도 9회까지 정상적으로 흐른 뒤 종료된다. 득점 배치는 경기별 시드 고정(재시작해도 같은 흐름).
"""
import calendar, datetime, json, math, os, random, re, time, urllib.request

import pymysql
import redis

BASE = os.environ.get("BASE_URL", "https://victoryfairy.com")
KST = 9 * 3600
END = calendar.timegm(time.strptime(os.environ.get("END_AT", "2026-11-15 23:59"), "%Y-%m-%d %H:%M")) - KST
TICK = [int(x) for x in os.environ.get("TICK_SEC", "12,30").split(",")]
NOISE = float(os.environ.get("NOISE", "2.5"))
SWING_P = float(os.environ.get("SWING_P", "0.12"))
SWING = [float(x) for x in os.environ.get("SWING", "5,14").split(",")]
# 9회 마지막 아웃과 종료 EVENT 사이 여유 — 화면에 "9회말" 이 잠깐 보인 뒤 FINISHED 로 넘어가게
FINISH_LEAD = int(os.environ.get("FINISH_LEAD_MIN", "6")) * 60
CHANNEL = "game:events"
PREFIX = os.environ.get("DEMO_PREFIX", "DEMO")
EXCLUDE = {g.strip() for g in os.environ.get("EXCLUDE_GAME_IDS", "").split(",") if g.strip()}

r = redis.Redis(host=os.environ.get("REDIS_HOST", "redis"), port=int(os.environ.get("REDIS_PORT", "6379")))


def log(*a):
    print(time.strftime("%m-%d %H:%M:%S", time.gmtime(time.time() + KST)), *a, flush=True)


def kst_date():
    return time.strftime("%Y-%m-%d", time.gmtime(time.time() + KST))


def to_ts(dt):
    """DB 의 naive DATETIME(KST) → epoch."""
    return calendar.timegm(dt.timetuple()) - KST


def db():
    return pymysql.connect(host=os.environ.get("DB_HOST", "mysql"), user=os.environ["DB_USERNAME"],
                           password=os.environ["DB_PASSWORD"], database="victoryfairy", charset="utf8mb4",
                           autocommit=True, init_command="SET time_zone = '+09:00'")


def finish_events(cur, date):
    """{ngid: (종료 epoch, (away, home))} — 그날 경기별 종료 EVENT 에서 읽는다."""
    cur.execute("""SELECT EVENT_NAME, EXECUTE_AT, EVENT_DEFINITION FROM information_schema.EVENTS
                   WHERE EVENT_SCHEMA = DATABASE() AND EVENT_NAME LIKE %s""",
                (f"demo\\_finish\\_{PREFIX}{date[5:7]}{date[8:10]}%",))
    out = {}
    for name, at, body in cur.fetchall():
        m = re.search(r"away_score\s*=\s*(\d+).*?home_score\s*=\s*(\d+)", body, re.S)
        if m:
            out[name[len("demo_finish_"):]] = (to_ts(at), (int(m.group(1)), int(m.group(2))))
    return out


def plan(gid, final):
    """반이닝별 득점 계획 [(half, frac, side)] — 경기별 시드 고정."""
    rnd = random.Random(gid)
    if final is None:
        a, h = rnd.randint(0, 8), rnd.randint(0, 8)
        if a == h:
            h += 1
        final = (a, h)
    a, h = final
    last = 16 if h > a else 17          # 홈이 이기면 9회말은 없다
    # 홈 끝내기 승리가 아니면 마지막 득점이 9회말에 몰리지 않게, 홈 득점 반이닝은 last 이하에서만 고른다
    runs = []
    for side, n, halves in (("away", a, range(0, 17, 2)), ("home", h, range(1, last + 1, 2))):
        halves = list(halves)
        for _ in range(n):
            # 빅이닝이 보이도록 같은 반이닝에 몰아 넣을 확률을 둔다
            hh = runs[-1][0] if runs and runs[-1][2] == side and rnd.random() < 0.35 else rnd.choice(halves)
            runs.append((hh, rnd.random(), side))
    runs.sort()
    return runs, last, final


def snapshot(runs, last, t0, t1, now):
    """지금 시각의 (반이닝 index, away, home, 남은 비율)."""
    pos = max(0.0, min(1.0, (now - t0) / max(1, t1 - t0))) * (last + 1)
    half = min(int(pos), last)
    frac = pos - int(pos) if pos < last + 1 else 1.0
    away = sum(1 for h, f, s in runs if s == "away" and (h < half or (h == half and f <= frac)))
    home = sum(1 for h, f, s in runs if s == "home" and (h < half or (h == half and f <= frac)))
    return half, away, home, 1 - pos / 18


def model_wp(away, home, remaining):
    """홈 승리확률(%) — 점수차가 남은 경기에 비해 클수록 극단으로."""
    k = 0.8 / math.sqrt(max(remaining, 0.05))
    return 100 / (1 + math.exp(-(0.08 + k * (home - away) * 0.4)))


state = {}


def publish(date, changes):
    if not changes:
        return
    with urllib.request.urlopen(f"{BASE}/api/games?date={date}", timeout=10) as resp:
        games = {g["gameId"]: g for g in json.load(resp)["data"]}
    observed = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for gid, changed in changes.items():
        if gid in games:
            g = games[gid]
            n = r.publish(CHANNEL, json.dumps({"game": g, "changed": changed, "observedAt": observed}, ensure_ascii=False))
            log(f"📡 {g['awayTeam']}@{g['homeTeam']} {g['gameState']} {g['inning']}회{g['inningHalf'] or ''} "
                f"{g['awayTeamScore']}:{g['homeTeamScore']} 승률 원정 {g['awayTeamWinRate']} / 홈 {g['homeTeamWinRate']} "
                f"changed={changed} (수신 파드 {n})")


def tick(conn):
    date = kst_date()
    now = time.time()
    changes = {}
    with conn.cursor() as cur:
        cur.execute("""SELECT g.naver_game_id, s.name, g.away_score, g.home_score, g.current_inning, g.inning_half,
                              g.home_win_rate, g.game_date
                       FROM games g JOIN game_statuses s ON s.id = g.game_status_id
                       WHERE g.naver_game_id LIKE %s""", (f"{PREFIX}{date[5:7]}{date[8:10]}%",))
        rows = cur.fetchall()
        finals = None
        for gid, status, a0, h0, inn0, half0, wr0, gdate in rows:
            if gid in EXCLUDE:
                continue
            st = state.setdefault(gid, {"noise": 0.0, "next": 0, "status": status})
            if st["status"] != status:
                log(f"상태 전이 감지 {gid}: {st['status']} -> {status}")
                changes[gid] = ["status", "inning", "homeScore", "awayScore", "winRate"]
                st["status"] = status
            if status != "IN_PROGRESS":
                if wr0 is not None:
                    cur.execute("UPDATE games SET home_win_rate = NULL, away_win_rate = NULL WHERE naver_game_id = %s "
                                "AND naver_game_id LIKE %s", (gid, PREFIX + "%"))
                continue
            if now < st["next"]:
                continue
            st["next"] = now + random.uniform(*TICK)
            if "plan" not in st:
                if finals is None:
                    finals = finish_events(cur, date)
                end_ts, final = finals.get(gid, (to_ts(gdate) + 5 * 3600, None))
                st["window"] = (to_ts(gdate), end_ts - FINISH_LEAD)
                st["plan"] = plan(gid, final)
                log(f"경기 계획 {gid}: 최종 {st['plan'][2][0]}:{st['plan'][2][1]} (원정:홈), "
                    f"9회 종료 {time.strftime('%H:%M', time.gmtime(st['window'][1] + KST))}, "
                    f"득점 반이닝 {[(h, s[0]) for h, _, s in st['plan'][0]]}")
            runs, last, _ = st["plan"]
            half, away, home, remaining = snapshot(runs, last, *st["window"], now)
            scored = (away, home) != (a0, h0)
            st["noise"] = st["noise"] * (0.5 if scored else 0.8) + random.gauss(0, NOISE)
            if random.random() < SWING_P:
                st["noise"] += random.choice([-1, 1]) * random.uniform(*SWING)
            wp = model_wp(away, home, remaining) + st["noise"] * (0.4 + remaining)
            wp = round(max(2.0, min(98.0, wp)), 1)
            inning, ih = half // 2 + 1, half % 2
            changed = []
            if (inning, ih) != (inn0, half0):
                changed.append("inning")
            if home != h0:
                changed.append("homeScore")
            if away != a0:
                changed.append("awayScore")
            if wr0 is None or abs(float(wr0) - wp) >= 0.1:
                changed.append("winRate")
            if not changed:
                continue
            cur.execute("""UPDATE games SET away_score = %s, home_score = %s, current_inning = %s, inning_half = %s,
                                  home_win_rate = %s, away_win_rate = %s, updated_at = NOW(6)
                           WHERE naver_game_id = %s AND naver_game_id LIKE %s
                             AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS')""",
                        (away, home, inning, ih, wp, round(100 - wp, 1), gid, PREFIX + "%"))
            changes[gid] = sorted(set(changes.get(gid, []) + changed))
    publish(date, changes)


def main():
    log(f"시작 — 접두사 {PREFIX}, 제외 {sorted(EXCLUDE)}, 틱 {TICK}s, 9회 종료 = 종료 이벤트 {FINISH_LEAD // 60}분 전")
    r.ping()
    conn = db()
    while time.time() < END:
        try:
            conn.ping()
            tick(conn)
        except Exception as e:
            log("오류", repr(e))
        time.sleep(3)
    log("END_AT 도달 — 종료")


main()
