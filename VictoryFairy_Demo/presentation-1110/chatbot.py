"""경기별 채팅 데모 봇 (2026-11-10 ~ 11-15 발표용).

10/29 판(bot.py)과 다른 점
  - 더미 유저(@dummy.victoryfairy.com)는 2026-11-06 기준 삭제됐다. 페르소나 계정(@persona.victoryfairy.internal)을 쓴다.
    계정 수가 적어(40명대) 한 계정은 하루 한 방에만 배정한다 — 채팅 구독은 계정당 하나라 두 방에 겹치면 서로 끊는다.
  - 접속 인원이 눈에 띄게 오르내리도록 체류·휴식을 짧게, 가끔 여러 명이 몰려 들어오거나 빠지는 '물결'을 둔다.
  - 대사가 경기 상황(시작 전·진행 중·종료 후, 점수)을 따른다. 상태는 공개 /api/games 를 30초마다 읽는다.
  - ACTIVE_HOURS 밖에서는 아무도 접속하지 않는다(밤새 응원 연타를 돌리지 않으려고).

환경변수: BASE_URL, END_AT(KST), ACTIVE_HOURS("8-24"), STAY_SEC("25,150"), REST_SEC("15,110"), MSG_INTERVAL(방 평균 초),
         LIKES_PER_SEC, LIKE_BURST_SEC, LIKE_REST_SEC, GAME_IDS(비우면 그날 경기 전부), EXCLUDE_GAME_IDS
"""
import base64, calendar, hashlib, hmac, http.client, json, os, random, ssl, threading, time, urllib.request, uuid
from urllib.parse import urlparse

import pymysql

BASE = os.environ.get("BASE_URL", "https://victoryfairy.com").rstrip("/")
URL = urlparse(BASE)
KST = 9 * 3600
END = calendar.timegm(time.strptime(os.environ.get("END_AT", "2026-11-15 23:59"), "%Y-%m-%d %H:%M")) - KST
ACTIVE_FROM, ACTIVE_TO = (int(x) for x in os.environ.get("ACTIVE_HOURS", "8-24").split("-"))
STAY = [float(x) for x in os.environ.get("STAY_SEC", "25,150").split(",")]
REST = [float(x) for x in os.environ.get("REST_SEC", "15,110").split(",")]
MSG_INTERVAL = float(os.environ.get("MSG_INTERVAL", "4"))
LIKES_PER_SEC = float(os.environ.get("LIKES_PER_SEC", "5"))
LIKE_BURST = [float(x) for x in os.environ.get("LIKE_BURST_SEC", "2,8").split(",")]
LIKE_REST = [float(x) for x in os.environ.get("LIKE_REST_SEC", "3,20").split(",")]
SECRET = os.environ["JWT_SECRET"].encode("utf-8")
PERSONA = "%@persona.victoryfairy.internal"
stop = threading.Event()

stats_lock = threading.Lock()
stats = {"like_ok": 0, "like_err": {}, "msg_ok": 0, "msg_err": {}}


def log(*a):
    print(time.strftime("%m-%d %H:%M:%S", time.gmtime(time.time() + KST)), *a, flush=True)


def kst_date():
    return time.strftime("%Y-%m-%d", time.gmtime(time.time() + KST))


def kst_hour():
    return time.gmtime(time.time() + KST).tm_hour


def active():
    return ACTIVE_FROM <= kst_hour() < ACTIVE_TO


def next_kst_midnight():
    now = time.time()
    return now - (now + KST) % 86400 + 86400


def count(kind, status):
    with stats_lock:
        if status == 202:
            stats[kind + "_ok"] += 1
        else:
            stats[kind + "_err"][str(status)] = stats[kind + "_err"].get(str(status), 0) + 1


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def token(uid, ttl):
    now = int(time.time())
    h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    p = b64(json.dumps({"jti": str(uuid.uuid4()), "sub": uid, "type": "access", "iat": now, "exp": now + ttl}).encode())
    return f"{h}.{p}.{b64(hmac.new(SECRET, f'{h}.{p}'.encode(), hashlib.sha256).digest())}"


def new_conn(timeout):
    if URL.scheme == "https":
        return http.client.HTTPSConnection(URL.netloc, timeout=timeout, context=ssl.create_default_context())
    return http.client.HTTPConnection(URL.netloc, timeout=timeout)


def req(method, path, tok, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Authorization", "Bearer " + tok)
    if data:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        return type(e).__name__


# ---------------------------------------------------------------- 대사
PRE = ["오늘 선발 누구예요?", "곧 시작이네요", "자리 잡았습니다", "오늘 라인업 괜찮다", "정오 경기라 햇빛 장난 아님ㅋㅋ",
       "점심 먹고 야구 보러 왔어요", "오늘 무조건 이긴다", "직관 왔습니다!!", "집관 준비 완료", "치킨 시켰습니다"]
LIVE = ["ㅋㅋㅋㅋㅋㅋ", "아 이걸 못 잡네", "와 방금 그거 봤어요?", "수비 좋다", "볼넷은 진짜 아니다", "투수 교체 좀...",
        "삼진!!!", "나이스 플레이", "아 아깝다", "이번 이닝 중요하다", "응원가 나올 때 소름", "심판 존 왜 저래",
        "홈런 가자!!", "ㄹㅇ", "제발 한 점만", "병살은 안 돼", "불펜 버텨라", "ㅠㅠㅠㅠ", "와 이걸 막네", "안타!!",
        "끝까지 간다", "{inning}회인데 벌써 투구수 많네", "주자 나갔다 가자", "초구 공략 좋다"]
CLOSE = ["{score} 실화냐", "{score} 지키자!!", "한 점 차 미치겠다", "마무리 올라온다", "여기서 뒤집으면 레전드",
         "{inning}회 {half} 집중!!", "심장 터질 것 같아요"]
BLOWOUT = ["오늘 타선 터졌다", "{score} 이건 끝났다", "편하게 보는 중ㅋㅋ", "벤치 멤버들 기회 주자", "점수 차 너무 난다ㅠ"]
WIN = ["이겼다!!!!", "오늘 경기 최고", "{team} 승리!!", "MVP는 오늘 선발이지", "퇴근길 행복하다", "{score} 승리 기념 출석"]
LOSE = ["아 졌다ㅠㅠ", "내일 이기면 돼", "그래도 끝까지 잘 싸웠다", "불펜이 아쉬웠다", "다음 경기 가자"]
POST = ["오늘 경기 다들 고생하셨어요", "하이라이트 다시 보러 갑니다", "오늘 수훈 선수 누구라고 생각해요?", "내일도 여기서 봬요"]
TEAM = {
    "LG": ["무적LG!", "LG 트윈스 승리를 위하여", "잠실은 우리 홈", "엘린이 출석"],
    "롯데": ["부산 갈매기~", "롯데 자이언츠 가자!", "마!!", "사직 출석"],
    "삼성": ["최강삼성!", "라팍 분위기 최고", "V9 기운 이어가자", "푸른 피 흐른다"],
    "KT": ["KT 위즈 화이팅!", "마법사들 가자", "수원 출석이요", "위즈 승리 기원"],
    "한화": ["최강한화!", "나는 행복합니다~", "보살 출석", "대전 분위기 미쳤다"],
    "두산": ["최강두산!", "허슬두 가자", "베어스 승리", "두린이 왔어요"],
    "KIA": ["최강기아!", "광주 출석", "타이거즈 가즈아", "기아 오늘 이긴다"],
    "NC": ["NC 다이노스 화이팅", "창원에서 응원합니다", "공룡군단 가자", "엔씨 승리!"],
    "SSG": ["SSG 랜더스 화이팅!", "문학 직관 왔어요", "랜더스 가자", "쓱 승리"],
    "키움": ["키움 화이팅!", "영웅들 가자", "고척 출석", "히어로즈 승리!"],
}
# 경기 이벤트에 대한 즉각 반응 — refresh_loop 가 점수·이닝·상태 변화를 감지하면 방 전체가 몇 초 안에 반응한다
EV_SCORE_MINE = ["와아아아 득점!!!", "{team} 가자!!!", "점수 났다!!!", "이 맛에 야구 보지", "타점 감사합니다ㅠㅠ",
                 "{score} 좋아좋아", "흐름 왔다!!", "한 점 더!!", "소리 질러!!!"]
EV_SCORE_THEIRS = ["아 실점...", "투수 바꿔야 한다", "괜찮아 아직 안 끝났다", "수비 집중!!", "ㅠㅠ 맞았다", "{score} 따라가자",
                   "아 그걸 맞네", "분위기 넘어간다ㅠ"]
EV_TIE = ["동점!!!!", "다시 원점이다", "{score} 동점 ㄷㄷ", "이제부터 진짜 경기다", "와 동점 미쳤다"]
EV_LEAD_MINE = ["역전!!!!!!", "역전이다아아아", "드라마 쓴다", "뒤집었다!!!", "이게 야구지!!!"]
EV_LEAD_THEIRS = ["역전 당했네ㅠㅠ", "다시 뒤집자", "아직 기회 있다", "하...역전이라니"]
EV_INNING = ["{inning}회{half} 시작", "이번 이닝 중요해요", "클린업 나온다", "삼자범퇴 가자", "{inning}회 집중!!"]
EV_WIN = ["이겼다!!!!", "승리!!!! 오늘 최고", "{team} 승리 축하합니다", "퇴근길 행복하다ㅠㅠ", "{score} 승!! 내일도 가자"]
EV_LOSE = ["졌다ㅠㅠ", "내일 이기면 된다", "그래도 잘 싸웠다", "아쉽다 진짜", "다음 경기 가자"]

REPLY = ["ㅋㅋㅋ ㄹㅇ", "인정", "ㅇㅈ", "그니까요", "{n}님 말이 맞음", "{n}님 ㅋㅋㅋ", "ㄴㄴ 아직 몰라요", "동의합니다"]


class Room:
    def __init__(self, game):
        self.gid = game["gameId"]
        self.label = f'{game["awayTeam"]}@{game["homeTeam"]}'
        self.game = game
        self.last_nick = None
        self.lock = threading.Lock()
        self.online = 0
        self.size = 1             # 배정된 봇 수 — assign 이 채운다
        self.event = None         # (id, kind, scoring_side, until) — 반응할 경기 이벤트
        self.event_seq = 0
        self.wave_until = 0       # 이 시각까지 '몰려 들어오기' — 휴식 중인 봇이 일찍 들어온다
        self.ebb_until = 0        # 이 시각까지 '빠지기' — 체류 중인 봇이 일찍 나간다

    def event_line(self, team):
        """지금 이벤트에 대한 이 봇의 반응. 우리 팀 쪽이면 환호, 상대 쪽이면 탄식."""
        _, kind, side, _ = self.event
        g = self.game
        a, h = g.get("awayTeamScore") or 0, g.get("homeTeamScore") or 0
        mine = side == ("home" if team == g["homeTeam"] else "away")
        fmt = {"score": f"{a}:{h}", "inning": g.get("inning") or 1, "team": team,
               "half": "말" if g.get("inningHalf") in ("말", "BOTTOM", 1) else "초"}
        pool = {"score": EV_SCORE_MINE if mine else EV_SCORE_THEIRS,
                "lead": EV_LEAD_MINE if mine else EV_LEAD_THEIRS,
                "tie": EV_TIE,
                "inning": EV_INNING,
                "final": EV_WIN if mine else EV_LOSE}[kind]
        return random.choice(pool).format(**fmt)

    def event_active(self):
        return self.event is not None and time.time() < self.event[3]

    def line(self, team):
        g = self.game
        state, a, h = g.get("gameState"), g.get("awayTeamScore") or 0, g.get("homeTeamScore") or 0
        mine, other = (h, a) if team == g["homeTeam"] else (a, h)
        fmt = {"score": f"{a}:{h}", "inning": g.get("inning") or 1, "team": team,
               "half": "말" if g.get("inningHalf") in ("말", "BOTTOM", 1) else "초"}
        r = random.random()
        if r < 0.2 and team in TEAM:
            pool = TEAM[team]
        elif state == "SCHEDULED":
            pool = PRE
        elif state == "FINISHED":
            pool = (WIN if mine > other else LOSE) if r < 0.7 else POST
        elif abs(a - h) <= 1 and (g.get("inning") or 1) >= 6:
            pool = CLOSE if r < 0.6 else LIVE
        elif abs(a - h) >= 5:
            pool = BLOWOUT if r < 0.45 else LIVE
        else:
            pool = LIVE
        return random.choice(pool).format(**fmt)


class Bot(threading.Thread):
    def __init__(self, room, uid, nick, team_name, sess_stop, sess_end):
        super().__init__(daemon=True)
        self.room, self.uid, self.nick, self.team = room, uid, nick, team_name
        self.sess_stop, self.sess_end = sess_stop, sess_end
        self.tok = token(uid, int(sess_end - time.time()) + 3600)
        self.reacted = None

    def alive(self):
        return not stop.is_set() and not self.sess_stop.is_set() and time.time() < self.sess_end

    def _sse(self, leave_evt):
        conn = None
        try:
            conn = new_conn(5)
            conn.request("GET", f"/chat/rooms/{self.room.gid}/subscribe",
                         headers={"Authorization": "Bearer " + self.tok, "Accept": "text/event-stream"})
            resp = conn.getresponse()
            if resp.status != 200:
                log(f"[{self.room.label}] {self.nick} 구독 실패 {resp.status} {resp.read(200)!r}")
                return
            # 퇴장 DELETE 가 서버 쪽에서 스트림을 닫아 readline 이 풀린다
            conn.sock.settimeout(None)
            while not leave_evt.is_set():
                if not resp.readline():
                    break
        except Exception as e:
            if not leave_evt.is_set():
                log(f"[{self.room.label}] {self.nick} 구독 오류 {e}")
        finally:
            if conn:
                conn.close()

    def _likes(self, leave_evt):
        if LIKES_PER_SEC <= 0:
            return
        path = f"/chat/rooms/{self.room.gid}/likes"
        headers = {"Authorization": "Bearer " + self.tok, "Content-Length": "0"}
        conn = None
        leave_evt.wait(random.uniform(*LIKE_REST))
        while not leave_evt.is_set():
            # 경기 중엔 더 자주·길게 누른다
            live = self.room.game.get("gameState") == "IN_PROGRESS"
            hot = self.room.event_active() and self.room.event[1] != "inning"
            rate = random.randint(1, max(1, int(LIKES_PER_SEC if live else LIKES_PER_SEC / 2)))
            if hot:
                rate = max(rate, int(LIKES_PER_SEC) + 2)   # 이벤트 순간엔 서버 제한(10/s) 아래에서 최대로
            burst_end = time.time() + random.uniform(*LIKE_BURST) * (1 if live else 0.5) * (2 if hot else 1)
            nxt = time.time()
            while not leave_evt.is_set() and time.time() < burst_end:
                try:
                    if conn is None:
                        conn = new_conn(10)
                    conn.request("POST", path, headers=headers)
                    resp = conn.getresponse()
                    resp.read()
                    count("like", resp.status)
                except Exception as e:
                    count("like", type(e).__name__)
                    if conn:
                        conn.close()
                    conn = None
                    break
                nxt += 1 / rate
                leave_evt.wait(max(0.0, nxt - time.time()))
            if conn:   # 쉬는 동안 ALB 유휴 타임아웃에 끊기니 미리 닫는다
                conn.close()
                conn = None
            # 쉬는 중에도 득점·역전 같은 이벤트가 나면 바로 연타로 돌아온다
            rest_until = time.time() + random.uniform(*LIKE_REST) * (1 if live else 2)
            while not leave_evt.is_set() and time.time() < rest_until:
                if self.room.event_active() and self.room.event[1] != "inning":
                    break
                leave_evt.wait(0.5)

    def say(self, text=None):
        if text is None and random.random() < 0.15 and self.room.last_nick and self.room.last_nick != self.nick:
            text = random.choice(REPLY).format(n=self.room.last_nick)
        elif text is None:
            text = self.room.line(self.team)
        st = req("POST", f"/chat/rooms/{self.room.gid}/messages", self.tok,
                 {"content": text, "clientMsgId": str(uuid.uuid4())})
        count("msg", st)
        self.room.last_nick = self.nick
        log(f"[{self.room.label}] 💬 {self.nick}({self.team}): {text}  -> {st}")

    def wait_rest(self, sec):
        """휴식. 방에 '물결'이 일면 일찍 들어간다."""
        until = time.time() + sec
        while self.alive() and time.time() < until:
            if time.time() < self.room.wave_until and random.random() < 0.25:
                return
            self.sess_stop.wait(2)

    def run(self):
        self.sess_stop.wait(random.uniform(0, 30))
        while self.alive():
            if not active():
                self.sess_stop.wait(60)
                continue
            leave = threading.Event()
            sse = threading.Thread(target=self._sse, args=(leave,), daemon=True)
            likes = threading.Thread(target=self._likes, args=(leave,), daemon=True)
            sse.start()
            likes.start()
            with self.room.lock:
                self.room.online += 1
                on = self.room.online
            log(f"[{self.room.label}] ▶ {self.nick} 입장 (봇 접속 {on})")
            stay_until = time.time() + random.uniform(*STAY)
            # 방 평균 MSG_INTERVAL 초에 한 마디 — 접속률 ~60% 가정으로 봇 한 명의 평균 간격을 잡는다
            per_bot = MSG_INTERVAL * max(1, self.room.size) * 0.6
            next_msg = time.time() + random.expovariate(1 / per_bot)
            while self.alive() and active() and time.time() < stay_until:
                if time.time() < self.room.ebb_until and random.random() < 0.15 and not self.room.event_active():
                    break
                ev = self.room.event
                if self.room.event_active() and ev[0] != self.reacted:
                    self.reacted = ev[0]
                    # 득점·역전·종료엔 대부분, 이닝 교대엔 일부만 — 1~8초 사이에 흩어져 반응한다
                    if random.random() < (0.25 if ev[1] == "inning" else 0.75):
                        self.sess_stop.wait(random.uniform(1, 8))
                        if self.alive():
                            self.say(self.room.event_line(self.team))
                if time.time() >= next_msg:
                    self.say()
                    next_msg = time.time() + random.expovariate(1 / per_bot)
                self.sess_stop.wait(1)
            leave.set()
            st = req("DELETE", f"/chat/rooms/{self.room.gid}/subscribe", self.tok)
            with self.room.lock:
                self.room.online -= 1
                on = self.room.online
            log(f"[{self.room.label}] ◀ {self.nick} 퇴장 -> {st} (봇 접속 {on})")
            sse.join(timeout=6)
            likes.join(timeout=3)
            if self.alive():
                self.wait_rest(random.uniform(*REST))


def fetch_games(date):
    with urllib.request.urlopen(f"{BASE}/api/games?date={date}", timeout=10) as r:
        return json.load(r)["data"]


def detect(old, new):
    """이전·지금 경기 응답 -> (kind, 득점 쪽) 또는 None. 우선순위: 종료 > 역전 > 동점 > 득점 > 이닝 교대."""
    if old.get("gameState") != "FINISHED" and new.get("gameState") == "FINISHED":
        a, h = new.get("awayTeamScore") or 0, new.get("homeTeamScore") or 0
        return "final", "home" if h > a else "away"
    if new.get("gameState") != "IN_PROGRESS" or old.get("gameState") != "IN_PROGRESS":
        return None
    oa, oh = old.get("awayTeamScore") or 0, old.get("homeTeamScore") or 0
    a, h = new.get("awayTeamScore") or 0, new.get("homeTeamScore") or 0
    if (a, h) != (oa, oh):
        side = "home" if h > oh else "away"
        if (oh - oa) * (h - a) < 0:
            return "lead", side
        if a == h:
            return "tie", side
        return "score", side
    if (old.get("inning"), old.get("inningHalf")) != (new.get("inning"), new.get("inningHalf")):
        return "inning", None
    return None


def refresh_loop(rooms, date, sess_stop):
    """10초마다 경기 상태·점수를 읽어 이벤트를 만들고, 30초마다 몰려 들어오기/빠지기 물결을 만든다."""
    n = 0
    while not sess_stop.wait(10):
        n += 1
        try:
            for g in fetch_games(date):
                room = rooms.get(g["gameId"])
                if room is None:
                    continue
                ev = detect(room.game, g)
                room.game = g
                if ev:
                    room.event_seq += 1
                    room.event = (room.event_seq, ev[0], ev[1], time.time() + (20 if ev[0] == "inning" else 35))
                    if ev[0] != "inning":
                        room.wave_until = time.time() + 40    # 큰 장면엔 사람이 몰려온다
                    log(f"[{room.label}] ⚡ {ev[0]} ({ev[1] or '-'}) {g.get('awayTeamScore')}:{g.get('homeTeamScore')} "
                        f"{g.get('inning')}회{g.get('inningHalf') or ''}")
        except Exception as e:
            log(f"경기 상태 갱신 실패 {e}")
        if n % 3:
            continue
        for room in rooms.values():
            roll = random.random()
            if roll < 0.08:
                room.wave_until = time.time() + 40
                log(f"[{room.label}] 🌊 몰려 들어오기")
            elif roll < 0.14:
                room.ebb_until = time.time() + 40
                log(f"[{room.label}] 🍃 빠지기")


def stats_loop():
    while not stop.wait(60):
        with stats_lock:
            s = {k: (dict(v) if isinstance(v, dict) else v) for k, v in stats.items()}
            stats.update(like_ok=0, like_err={}, msg_ok=0, msg_err={})
        log(f"📊 최근 1분 — 응원 202 {s['like_ok']}건({s['like_ok'] / 60:.0f}/s) 실패 {s['like_err'] or '없음'}"
            f" · 채팅 202 {s['msg_ok']}건 실패 {s['msg_err'] or '없음'}")


def load_personas():
    db = pymysql.connect(host=os.environ.get("DB_HOST", "mysql"), user=os.environ["DB_USERNAME"],
                         password=os.environ["DB_PASSWORD"], database="victoryfairy", charset="utf8mb4")
    try:
        with db.cursor() as cur:
            cur.execute("""SELECT ua.uid, ua.nickname, MIN(ust.team_id) FROM users u
                           JOIN users_account ua ON ua.user_id = u.id
                           LEFT JOIN user_support_team ust ON ust.user_account_id = ua.id
                           WHERE u.email LIKE %s AND ua.exit_at IS NULL GROUP BY ua.uid, ua.nickname""", (PERSONA,))
            return cur.fetchall()
    finally:
        db.close()


def assign(games, personas, sess_stop, sess_end):
    """한 계정은 한 방에만. 응원 구단이 그 경기 팀인 계정을 먼저, 남는 계정은 인원이 적은 방부터 채운다."""
    rooms = {g["gameId"]: Room(g) for g in games}
    members = {gid: [] for gid in rooms}
    pool = list(personas)
    random.shuffle(pool)
    rest = []
    for p in pool:
        fans = [gid for gid, room in rooms.items() if p[2] in (room.game["homeTeamId"], room.game["awayTeamId"])]
        if fans:
            members[min(fans, key=lambda k: len(members[k]))].append(p)
        else:
            rest.append(p)
    for p in rest:
        gid = min(members, key=lambda k: len(members[k]))
        members[gid].append(p)
    # 한 방에 너무 몰리면(같은 팀 팬이 많을 때) 고르게 덜어 낸다
    total = sum(len(v) for v in members.values())
    cap = -(-total // max(1, len(members))) + 2
    spill = []
    for gid in members:
        while len(members[gid]) > cap:
            spill.append(members[gid].pop())
    for p in spill:
        members[min(members, key=lambda k: len(members[k]))].append(p)
    bots = []
    for gid, ms in members.items():
        room = rooms[gid]
        room.size = len(ms)
        g = room.game
        for uid, nick, team_id in ms:
            team = g["homeTeam"] if team_id == g["homeTeamId"] else g["awayTeam"] if team_id == g["awayTeamId"] \
                else random.choice([g["homeTeam"], g["awayTeam"]])
            bots.append(Bot(room, uid, nick, team, sess_stop, sess_end))
        log(f"{room.label} ({gid}) 봇 {len(ms)}명")
    return rooms, bots


def main():
    want = [g.strip() for g in os.environ.get("GAME_IDS", "").split(",") if g.strip()]
    exclude = {g.strip() for g in os.environ.get("EXCLUDE_GAME_IDS", "").split(",") if g.strip()}
    threading.Thread(target=stats_loop, daemon=True).start()
    personas = load_personas()
    log(f"시작 — 페르소나 {len(personas)}명, 활동 {ACTIVE_FROM}~{ACTIVE_TO}시, 체류 {STAY}s 휴식 {REST}s, 응원 {LIKES_PER_SEC}/s/명")
    if not personas:
        raise SystemExit("페르소나 계정이 없다 — users.email LIKE '%@persona.victoryfairy.internal' 확인")
    while not stop.is_set() and time.time() < END:
        date = kst_date()
        sess_end = min(END, next_kst_midnight() - 30)   # 자정 방 정리와 겹치지 않게
        try:
            games = [g for g in fetch_games(date) if (not want or g["gameId"] in want) and g["gameId"] not in exclude]
        except Exception as e:
            log(f"경기 조회 실패 {e} — 1분 뒤 재시도")
            time.sleep(60)
            continue
        if not games:
            log(f"{date} 경기 없음 — 10분 뒤 다시 확인")
            time.sleep(min(600, max(1, next_kst_midnight() + 60 - time.time())))
            continue
        sess_stop = threading.Event()
        rooms, bots = assign(games, personas, sess_stop, sess_end)
        log(f"{date} 세션 시작 — 방 {len(rooms)}개, 봇 {len(bots)}명, "
            f"{time.strftime('%m-%d %H:%M', time.gmtime(sess_end + KST))} KST 까지")
        threading.Thread(target=refresh_loop, args=(rooms, date, sess_stop), daemon=True).start()
        for b in bots:
            b.start()
        while time.time() < sess_end and not stop.is_set():
            time.sleep(1)
        sess_stop.set()
        log(f"{date} 세션 종료 — 남은 봇 퇴장 처리")
        for b in bots:
            req("DELETE", f"/chat/rooms/{b.room.gid}/subscribe", b.tok)
        for b in bots:
            b.join(timeout=10)
        if time.time() < END:
            time.sleep(max(0, next_kst_midnight() + 60 - time.time()))
    stop.set()
    log("끝")


if __name__ == "__main__":
    main()
