"""사용자 정의 퀴즈방 데모 봇 (2026-11-10 ~ 11-15 발표용).

1) quizzes.json 의 방을 release 날짜에 페르소나 한 명이 만든다(제목이 이미 있으면 그 방을 쓴다 — 재시작 안전).
2) '풀이 슬롯' SOLVERS 개가 페르소나를 바꿔 가며 방에 들어오고(SSE 구독 → 풀이 중 인원 +1) 나간다(DELETE).
   방마다 인기 가중치가 달라 인원이 고르게 나뉘지 않고, 가끔 몰려 들어오기/빠지기 물결이 인다.
3) 실제 풀이(문항별 답 제출 → SSE votes 분포가 움직임)는 활동 시간 내내 방마다 SOLVE_CONCURRENT 명이 늘 하고 있다.
   서버는 (계정, 문항)당 답 하나라(409) 안 푼 페르소나가 바닥나면, 가장 오래전에 푼 페르소나의 답을 DB 에서 지우고
   다시 풀게 한다(recycle). 총 투표 수는 페르소나 수 근처에서 유지되고 분포만 계속 움직인다.
   문제마다 고정 난이도와 '함정 보기'가 있어 문제별 분포 모양이 다르다.
4) 좋아요는 풀이 여부와 상관없이 일부 계정이 누른다(작성자 본인은 누르지도 풀지도 않는다 — 주간 점수에서 빠지기도 한다).

주의: 인기 탭(GET /popular)은 매주 월요일 00:10 확정분이라 이 기간에 만든 방은 11/16 전에 인기 탭에 안 뜬다.
      화면에서는 전체 목록·상세의 likeCount·solvingCount 가 오르내리는 것으로 보인다.
"""
import base64, calendar, hashlib, hmac, http.client, json, os, random, ssl, threading, time, urllib.error, urllib.request, uuid
from urllib.parse import urlparse

import pymysql

BASE = os.environ.get("BASE_URL", "https://victoryfairy.com").rstrip("/")
URL = urlparse(BASE)
API = "/rt/quiz-rooms"
KST = 9 * 3600
END = calendar.timegm(time.strptime(os.environ.get("END_AT", "2026-11-15 23:59"), "%Y-%m-%d %H:%M")) - KST
ACTIVE_FROM, ACTIVE_TO = (int(x) for x in os.environ.get("ACTIVE_HOURS", "8-24").split("-"))
SOLVERS = int(os.environ.get("SOLVERS", "16"))
BROWSE = [float(x) for x in os.environ.get("BROWSE_SEC", "20,140").split(",")]
REST = [float(x) for x in os.environ.get("REST_SEC", "10,60").split(",")]
# 문항 사이 고민 시간 — 길수록 풀이 한 번이 몇 분에 걸쳐 투표율을 조금씩 움직인다
ANSWER_GAP = [float(x) for x in os.environ.get("ANSWER_GAP_SEC", "15,60").split(",")]
# 활동 시간 내내 방마다 이만큼이 늘 풀고 있다 — 투표율이 끊기지 않고 움직인다
SOLVE_CONCURRENT = int(os.environ.get("SOLVE_CONCURRENT", "2"))
# 발표 시간대 "2026-11-12 13:00-16:00;…" 에는 FOCUS_CONCURRENT 명으로 늘린다(선택)
FOCUS = []
for _w in filter(None, (x.strip() for x in os.environ.get("FOCUS_WINDOWS", "").split(";"))):
    _d, _hm = _w.split(" ")
    _a, _b = _hm.split("-")
    FOCUS.append(tuple(calendar.timegm(time.strptime(f"{_d} {x}", "%Y-%m-%d %H:%M")) - KST for x in (_a, _b)))
FOCUS_CONCURRENT = int(os.environ.get("FOCUS_CONCURRENT", "4"))
LIKE_P = float(os.environ.get("LIKE_P", "0.12"))        # 방문 한 번에 좋아요를 누를 확률(아직 안 눌렀으면)
RELEASE_HOUR = int(os.environ.get("RELEASE_HOUR", "10"))  # 둘째 날부터 새 방을 여는 시각(KST)
CONTENT = os.environ.get("CONTENT", "/bot/quizzes.json")
SECRET = os.environ["JWT_SECRET"].encode()
PERSONA = "%@persona.victoryfairy.internal"

lock = threading.Lock()
stats = {}


def log(*a):
    print(time.strftime("%m-%d %H:%M:%S", time.gmtime(time.time() + KST)), *a, flush=True)


def kst_now():
    return time.gmtime(time.time() + KST)


def active():
    return ACTIVE_FROM <= kst_now().tm_hour < ACTIVE_TO


def in_focus(t=None):
    t = t or time.time()
    return any(a <= t < b for a, b in FOCUS)




def count(k):
    with lock:
        stats[k] = stats.get(k, 0) + 1


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def token(uid, ttl=3600):
    now = int(time.time())
    h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    p = b64(json.dumps({"jti": str(uuid.uuid4()), "sub": uid, "type": "access", "iat": now, "exp": now + ttl}).encode())
    return f"{h}.{p}.{b64(hmac.new(SECRET, f'{h}.{p}'.encode(), hashlib.sha256).digest())}"


def call(method, path, uid, body=None, tag=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + API + path, data=data, method=method)
    r.add_header("Authorization", "Bearer " + token(uid))
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            st, txt = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        st, txt = e.code, e.read()
    except Exception as e:
        st, txt = 0, str(e).encode()
    if tag:
        count(f"{tag}:{st}" if st >= 300 or st == 0 else tag)
    try:
        return st, (json.loads(txt).get("data") if txt else None)
    except Exception:
        return st, None


def new_conn(timeout):
    if URL.scheme == "https":
        return http.client.HTTPSConnection(URL.netloc, timeout=timeout, context=ssl.create_default_context())
    return http.client.HTTPConnection(URL.netloc, timeout=timeout)


# ---------------------------------------------------------------- 내용

def build_request(room):
    """객관식은 options[0] 이 정답 — 문제별 고정 시드로 섞어 answer 번호를 정한다(재시작해도 같은 순서)."""
    items, key = [], {}
    for qz in room["quizzes"]:
        if qz["type"] == "O/X":
            items.append({"type": "O/X", "question": qz["q"], "options": ["O", "X"], "answer": 0 if qz["answer"] == "O" else 1})
            key[qz["q"]] = qz["answer"]
        else:
            opts = list(qz["options"])
            random.Random(qz["q"]).shuffle(opts)
            items.append({"type": "객관식", "question": qz["q"], "options": opts, "answer": opts.index(qz["options"][0])})
            key[qz["q"]] = qz["options"][0]
    return {"title": room["title"], "description": room["description"], "quizzes": items}, key


class Room:
    def __init__(self, spec, rid, owner, key, weight):
        self.spec, self.rid, self.owner, self.key, self.weight = spec, rid, owner, key, weight
        self.title = spec["title"]
        self.quizzes = None            # GET /quizzes 캐시
        self.solved, self.liked = set(), set()
        self.solved_at = {}            # uid -> 마지막으로 다 푼 시각(재활용 순서)
        self.wave_until = self.ebb_until = 0
        self.online = 0
        self.solving = 0               # 지금 풀고 있는 계정 수

    def solve_due(self):
        return self.solving < (FOCUS_CONCURRENT if in_focus() else SOLVE_CONCURRENT)

    def may_solve(self, uid):
        """안 푼 계정은 언제나, 푼 계정은 안 푼 계정이 바닥났을 때 가장 오래전에 푼 순서로 다시 푼다."""
        if uid not in self.solved:
            return True
        unsolved = len(PERSONAS) - 1 - len(self.solved)
        if unsolved > 0:
            return False
        oldest = sorted(self.solved_at.items(), key=lambda kv: kv[1])[:max(3, len(self.solved_at) // 4)]
        return uid in {u for u, _ in oldest} or uid not in self.solved_at


# ---------------------------------------------------------------- DB

def db():
    return pymysql.connect(host=os.environ.get("DB_HOST", "mysql"), user=os.environ["DB_USERNAME"],
                           password=os.environ["DB_PASSWORD"], database="victoryfairy", charset="utf8mb4")


def load_personas():
    conn = db()
    with conn.cursor() as cur:
        cur.execute("""SELECT ua.id, ua.uid, ua.nickname FROM users u JOIN users_account ua ON ua.user_id = u.id
                       WHERE u.email LIKE %s AND ua.exit_at IS NULL""", (PERSONA,))
        rows = cur.fetchall()
    conn.close()
    return rows


def load_progress(rooms, uid_by_id):
    """이미 푼 계정(문항 수만큼 답이 있는 계정)과 좋아요 누른 계정 — 재시작 뒤에도 409 를 덜 맞게."""
    ids = [r.rid for r in rooms.values()]
    if not ids:
        return
    conn = db()
    with conn.cursor() as cur:
        marks = ",".join(["%s"] * len(ids))
        cur.execute(f"""SELECT a.room_id, a.user_account_id, COUNT(*) FROM quiz_room_answers a
                        WHERE a.room_id IN ({marks}) GROUP BY a.room_id, a.user_account_id""", ids)
        answers = cur.fetchall()
        cur.execute(f"SELECT room_id, user_account_id FROM quiz_room_likes WHERE room_id IN ({marks})", ids)
        likes = cur.fetchall()
    conn.close()
    by_id = {r.rid: r for r in rooms.values()}
    for rid, acc, _ in answers:   # 한 문항이라도 답했으면 이어 풀지 않고 푼 것으로 친다
        if acc in uid_by_id:
            by_id[rid].solved.add(uid_by_id[acc])
    for rid, acc in likes:
        if acc in uid_by_id:
            by_id[rid].liked.add(uid_by_id[acc])


def recycle(room, uid):
    """이 페르소나가 이 방에 낸 답을 지워 다시 풀 수 있게 한다 -> 지운 행 수.

    투표 분포는 DB 의 답 행을 그때그때 세서 내므로(QuizRoomVoteReader) 지운 뒤 다시 내면 분포만 바뀌고
    총 투표 수는 그대로다. 페르소나 계정의 답만, 봇이 만든 방에서만 지운다 — 실제 사용자 답은 건드리지 않는다.
    """
    conn = db()
    try:
        with conn.cursor() as cur:
            n = cur.execute("""DELETE a FROM quiz_room_answers a
                               JOIN users_account ua ON ua.id = a.user_account_id
                               JOIN users u ON u.id = ua.user_id
                               WHERE a.room_id = %s AND ua.uid = %s AND u.email LIKE %s""", (room.rid, uid, PERSONA))
        conn.commit()
        return n
    finally:
        conn.close()


# ---------------------------------------------------------------- 방 만들기

def existing_rooms():
    out, page = {}, 0
    while True:
        st, data = call("GET", f"?page={page}&size=50", PERSONAS[0][1])
        if st != 200 or not data:
            break
        for r in data["content"]:
            out.setdefault(r["title"], r)
        if not data.get("hasNext"):
            break
        page += 1
    return out


def ensure_rooms(rooms, specs, owners):
    today = time.strftime("%Y-%m-%d", kst_now())
    first_day = min(s["release"] for s in specs)
    have = None
    for i, spec in enumerate(specs):
        if spec["title"] in rooms:
            continue
        due = spec["release"] < today or (spec["release"] == today and
                                          (spec["release"] == first_day or kst_now().tm_hour >= RELEASE_HOUR))
        if not due:
            continue
        if have is None:
            have = existing_rooms()
        body, key = build_request(spec)
        owner = owners[i % len(owners)]
        weight = random.uniform(0.6, 1.8)
        if spec["title"] in have:
            rid = have[spec["title"]]["roomId"]
            log(f"🧩 기존 방 사용 #{rid} {spec['title']}")
        else:
            st, data = call("POST", "", owner[1], body=body, tag="방생성")
            if st != 201:
                log(f"🧩 방 생성 실패 {st} {spec['title']} {data}")
                continue
            rid = data["roomId"]
            weight *= 1.6   # 새로 연 방은 한동안 사람이 몰린다
            log(f"🧩 새 방 #{rid} '{spec['title']}' by {owner[2]} — 문항 {len(body['quizzes'])}개")
        rooms[spec["title"]] = Room(spec, rid, owner[1], key, weight)


# ---------------------------------------------------------------- 풀이 슬롯

PERSONAS = []
busy = set()


def subscribe(room, uid, leave):
    conn = None
    try:
        conn = new_conn(10)
        conn.request("GET", f"{API}/{room.rid}/subscribe",
                     headers={"Authorization": "Bearer " + token(uid, 7200), "Accept": "text/event-stream"})
        resp = conn.getresponse()
        if resp.status != 200:
            count(f"구독:{resp.status}")
            return
        count("구독")
        conn.sock.settimeout(None)   # 퇴장 DELETE 가 서버 쪽에서 스트림을 닫아 readline 이 풀린다
        while not leave.is_set():
            if not resp.readline():
                break
    except Exception as e:
        if not leave.is_set():
            count(f"구독:{type(e).__name__}")
    finally:
        if conn:
            conn.close()


def choose(qz, right):
    """문제마다 고정된 난이도(정답률)와 '함정 보기'로 고른다 — 문제마다 투표율 모양이 달라진다."""
    nos = [o["no"] for o in qz["options"]]
    rnd = random.Random("difficulty:" + qz["question"])
    p = rnd.uniform(0.45, 0.88) if len(nos) == 2 else rnd.uniform(0.3, 0.9)
    wrong = [n for n in nos if n not in right]
    weights = [rnd.uniform(0.3, 1.2) for _ in wrong]
    if wrong:
        weights[rnd.randrange(len(wrong))] += 2.5   # 헷갈리는 보기 하나에 오답이 몰린다
    if right and (random.random() < p or not wrong):
        return right[0]
    return random.choices(wrong, weights=weights)[0] if wrong else random.choice(nos)


def solve(room, uid, nick):
    if room.quizzes is None:
        st, qs = call("GET", f"/{room.rid}/quizzes", uid, tag="문항조회")
        if st != 200:
            return
        room.quizzes = qs
    time.sleep(random.uniform(4, 15))   # 소개 읽는 시간
    correct = 0
    for i, qz in enumerate(room.quizzes):
        answer_text = room.key.get(qz["question"])
        right = [o["no"] for o in qz["options"] if o["text"] == answer_text]
        pick = choose(qz, right)
        correct += int(bool(right) and pick == right[0])
        st, data = call("POST", f"/{room.rid}/quizzes/{qz['quizId']}/answer", uid, body={"option": pick}, tag="답제출")
        if st == 409:
            break
        if st != 200:
            return
        time.sleep(random.uniform(*ANSWER_GAP))   # 다음 문제 고민
    room.solved.add(uid)
    room.solved_at[uid] = time.time()
    log(f"✅ #{room.rid} {room.title} — {nick} 풀이 완료 ({correct}/{len(room.quizzes)})")


def pick_room(rooms):
    rs = list(rooms.values())   # ticker 가 방을 추가하는 중에도 안전하게 스냅샷
    if not rs:
        return None
    w = []
    for r in rs:
        x = r.weight
        if time.time() < r.wave_until:
            x *= 3
        if time.time() < r.ebb_until:
            x *= 0.3
        # 사람이 몰린 방일수록 덜 고른다 — 인기 차이는 남기되 한 방이 텅 비어 보이지 않게
        x /= 1 + max(0, r.online) / 6
        w.append(x)
    return random.choices(rs, weights=w)[0]


def slot(rooms):
    while time.time() < END:
        if not active() or not rooms:
            time.sleep(60)
            continue
        with lock:
            free = [p for p in PERSONAS if p[1] not in busy]
            if not free:
                person = None
            else:
                person = random.choice(free)
                busy.add(person[1])
        if person is None:
            time.sleep(10)
            continue
        _, uid, nick = person
        try:
            room = pick_room(rooms)
            if room is None or uid == room.owner:
                continue
            leave = threading.Event()
            sse = threading.Thread(target=subscribe, args=(room, uid, leave), daemon=True)
            sse.start()
            room.online += 1
            time.sleep(random.uniform(2, 8))
            if uid not in room.liked and random.random() < LIKE_P:
                st, data = call("PUT", f"/{room.rid}/like", uid, body={"liked": True}, tag="좋아요")
                if st == 200:
                    room.liked.add(uid)
                    log(f"❤ #{room.rid} {room.title} — {nick} (좋아요 {data and data.get('likeCount')})")
            if room.solve_due() and room.may_solve(uid):
                room.solving += 1
                try:
                    if uid in room.solved:
                        n = recycle(room, uid)
                        room.solved.discard(uid)
                        count("재활용")
                        log(f"♻ #{room.rid} {room.title} — {nick} 이전 답 {n}건 지우고 다시 풂")
                    solve(room, uid, nick)
                finally:
                    room.solving -= 1
                time.sleep(random.uniform(10, 40))   # 결과 화면
            else:
                until = time.time() + random.uniform(*BROWSE)
                while time.time() < until:
                    if time.time() < room.ebb_until and random.random() < 0.1:
                        break
                    time.sleep(2)
            leave.set()
            call("DELETE", f"/{room.rid}/subscribe", uid, tag="퇴장")
            room.online -= 1
            sse.join(timeout=6)
        except Exception as e:
            log("슬롯 오류", repr(e))
        finally:
            with lock:
                busy.discard(uid)
        time.sleep(random.uniform(*REST))


def ticker(rooms, specs, owners):
    """새 방 공개 + 물결 + 1분 요약."""
    n = 0
    while time.time() < END:
        time.sleep(30)
        n += 1
        try:
            ensure_rooms(rooms, specs, owners)
        except Exception as e:
            log("방 확인 오류", repr(e))
        for r in list(rooms.values()):
            roll = random.random()
            if roll < 0.07:
                r.wave_until = time.time() + 60
            elif roll < 0.12:
                r.ebb_until = time.time() + 45
        if n % 2 == 0:
            with lock:
                snap = dict(stats)
                stats.clear()
            on = " ".join(f"#{r.rid}:{r.online}명/푸는중{r.solving}/풀이{len(r.solved)}/♥{len(r.liked)}"
                          for r in list(rooms.values()))
            log(f"📊 {on} | " + " ".join(f"{k}={v}" for k, v in sorted(snap.items())))


def main():
    with open(CONTENT, encoding="utf-8") as f:
        specs = json.load(f)["rooms"]
    PERSONAS.extend(load_personas())
    if len(PERSONAS) < 5:
        raise SystemExit(f"페르소나가 너무 적다({len(PERSONAS)}명)")
    owners = random.Random(20261110).sample(PERSONAS, min(len(specs), len(PERSONAS)))
    rooms = {}
    ensure_rooms(rooms, specs, owners)
    load_progress(rooms, {p[0]: p[1] for p in PERSONAS})
    log(f"시작 — 페르소나 {len(PERSONAS)}명, 풀이 슬롯 {SOLVERS}, 활동 {ACTIVE_FROM}~{ACTIVE_TO}시, 방 {len(rooms)}/{len(specs)}개 공개, "
        f"방마다 동시 풀이 {SOLVE_CONCURRENT}명(집중 시간대 {os.environ.get('FOCUS_WINDOWS') or '없음'} → {FOCUS_CONCURRENT}명)")
    for r in list(rooms.values()):
        log(f"   #{r.rid} {r.title}: 이미 푼 계정 {len(r.solved)}, 좋아요 {len(r.liked)}")
    threading.Thread(target=ticker, args=(rooms, specs, owners), daemon=True).start()
    for _ in range(SOLVERS):
        threading.Thread(target=slot, args=(rooms,), daemon=True).start()
        time.sleep(random.uniform(1, 5))
    while time.time() < END:
        time.sleep(30)
    log("END_AT 도달 — 종료")


if __name__ == "__main__":
    main()
