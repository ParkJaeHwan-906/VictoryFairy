"""커뮤니티 데모 봇 (2026-11-10 ~ 11-15 발표용). 페르소나 계정으로 글(야구 사진 포함)을 올리고 댓글·좋아요를 단다.

10/13 판과 다른 점
  - 더미 유저는 삭제됐다 → 페르소나 계정(@persona.victoryfairy.internal).
  - 이미지는 picsum(무작위 사진)이 아니라 /img 아래에 마운트한 **야구 사진만** 쓴다(위키미디어 공용, CREDITS.tsv).
    사진을 못 읽으면 이미지 없이 올린다 — 무관한 그림으로 대체하지 않는다.
  - 글감은 11월 기준(삼성 한국시리즈 우승·스토브리그·시즌 기록)으로 바꿨다.
  - '버즈' 스레드: 최근 글 몇 개에 몇십 초 간격으로 댓글·좋아요가 붙어, 상세 화면을 보고 있으면 실시간으로 늘어난다.

MODE=run | cleanup(이 봇이 START_DATE 이후 페르소나로 쓴 글 삭제)
"""
import base64, calendar, hashlib, hmac, json, os, random, threading, time, urllib.error, urllib.request, uuid

import pymysql

BASE = os.environ.get("BASE_URL", "https://victoryfairy.com")
KST = 9 * 3600
SECRET = os.environ["JWT_SECRET"].encode()
MODE = os.environ.get("MODE", "run")
END = calendar.timegm(time.strptime(os.environ.get("END_AT", "2026-11-15 23:59"), "%Y-%m-%d %H:%M")) - KST
START_DATE = os.environ.get("START_DATE", "2026-11-10")
ACTIVE_FROM, ACTIVE_TO = (int(x) for x in os.environ.get("ACTIVE_HOURS", "8-24").split("-"))
SEED_POSTS = int(os.environ.get("SEED_POSTS", "40"))
READERS = int(os.environ.get("READERS", "10"))
IMAGE_RATIO = float(os.environ.get("IMAGE_RATIO", "0.6"))
MAX_IMAGES = int(os.environ.get("MAX_IMAGES", "3"))
NEW_POST_SEC = [int(x) for x in os.environ.get("NEW_POST_SEC", "180,600").split(",")]
READ_PAUSE_SEC = [int(x) for x in os.environ.get("READ_PAUSE_SEC", "5,30").split(",")]
BUZZ_SEC = [int(x) for x in os.environ.get("BUZZ_SEC", "8,35").split(",")]
IMG_DIR = os.environ.get("IMG_DIR", "/img")
PERSONA = "%@persona.victoryfairy.internal"

lock = threading.Lock()
stats = {}
recent = []          # 이 실행에서 쓴 글 id(최근 순) — 버즈 대상


def log(*a):
    print(time.strftime("%m-%d %H:%M:%S", time.gmtime(time.time() + KST)), *a, flush=True)


def active():
    return ACTIVE_FROM <= time.gmtime(time.time() + KST).tm_hour < ACTIVE_TO


def idle_until_active():
    while not active() and time.time() < END:
        time.sleep(60)


def count(key):
    with lock:
        stats[key] = stats.get(key, 0) + 1


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def token(uid):
    now = int(time.time())
    h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    p = b64(json.dumps({"jti": str(uuid.uuid4()), "sub": uid, "type": "access", "iat": now, "exp": now + 3600}).encode())
    return f"{h}.{p}.{b64(hmac.new(SECRET, f'{h}.{p}'.encode(), hashlib.sha256).digest())}"


def call(method, path, uid, body=None, raw=None, ctype=None, tag=None):
    headers = {"Authorization": "Bearer " + token(uid)}
    data = None
    if body is not None:
        data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
    elif raw is not None:
        data, headers["Content-Type"] = raw, ctype
    req = urllib.request.Request(BASE + "/api" + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            st, txt = r.status, r.read()
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


# ---------- 이미지: 마운트된 야구 사진만 ----------

def load_images():
    files = []
    for root, dirs, names in os.walk(IMG_DIR):
        # ConfigMap 마운트에는 실제 파일이 든 '..2026_…' 디렉터리와 그쪽을 가리키는 심볼릭 링크가 함께 있다 — 링크만 센다
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        files += [os.path.join(root, n) for n in names if n.lower().endswith(".jpg") and not n.startswith(".")]
    return sorted(files)


IMAGES = []


def upload_image(uid, path):
    with open(path, "rb") as f:
        img = f.read()
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{os.path.basename(path)}\"\r\n"
            f"Content-Type: image/jpeg\r\n\r\n").encode() + img + f"\r\n--{boundary}--\r\n".encode()
    st, data = call("POST", "/community/images", uid, raw=body, ctype=f"multipart/form-data; boundary={boundary}", tag="이미지")
    return data["imageUrl"] if st == 200 and data else None


# ---------- 글감 (2026-11 기준. 사실 문장은 여러 보도로 확인한 것만) ----------

FREE = [
    ("삼성 12년 만의 우승 다들 보셨나요", ["한국시리즈 4승 1패, 이렇게 깔끔하게 끝날 줄 몰랐어요.", "라팍 분위기 영상으로만 봐도 소름이었습니다."]),
    ("구자욱 KS MVP 인정하시죠?", ["시리즈 내내 타석에서 존재감이 대단했어요.", "주장이 MVP까지 받으니까 더 의미 있네요."]),
    ("강민호 선수 첫 우승 반지", ["프로 23년 차에 첫 우승이라니 보는 제가 다 울컥했어요.", "베테랑의 눈물은 언제 봐도 뭉클합니다."]),
    ("올해 관중 1,200만 넘은 거 실화인가요", ["역대 최다 관중 기록이라네요.", "예매 전쟁이 괜히 있던 게 아니었어요."]),
    ("김도영 45홈런 마지막 타석", ["시즌 마지막 타석에서 단독 홈런왕 확정이라니 드라마 그 자체.", "장타율까지 2관왕이에요."]),
    ("레이예스 211안타 신기록", ["최종전에서 기록 갈아치우는 거 보고 감탄했습니다.", "꾸준함의 끝판왕."]),
    ("시즌 끝나니까 너무 허전해요", ["주말마다 야구 보던 낙이 사라졌어요ㅠ", "스토브리그 소식으로 버텨봅니다."]),
    ("스토브리그 FA 누가 움직일까요", ["올해 FA 시장 꽤 뜨거울 것 같아요.", "우리 팀은 불펜 보강이 1순위라고 봅니다."]),
    ("내년 시즌 기대되는 팀", ["저는 올해 가을에 아쉽게 떨어진 팀들이 칼을 갈 것 같아요.", "여러분 생각은 어떠세요?"]),
    ("야구 비시즌에 뭐 하시나요", ["하이라이트 다시 보기 중입니다ㅋㅋ", "MLB 오프시즌 소식도 챙겨 보고 있어요."]),
    ("올해 직관 승률 공유해요", ["저는 직관 12번에 8승! 승리요정 맞죠?", "기록 남겨두니까 재밌네요."]),
    ("야구장 먹거리 베스트", ["구장마다 시그니처 메뉴 정리해 보고 싶네요.", "추천 받아요!"]),
    ("승리요정 퀴즈방 만들어 봤어요", ["올해 이슈로 문제 내봤는데 한 번씩 풀어주세요.", "생각보다 어렵다는 반응이 많네요ㅋㅋ"]),
    ("밀워키 월드시리즈 첫 우승", ["브루어스 창단 첫 우승이라 현지 분위기 대단했다더라고요.", "MVP는 포수 콘트레라스."]),
    ("WBC 베네수엘라 우승 다시 생각나네요", ["결승 미국전 3-2 접전 진짜 명경기였죠.", "내년 대표팀도 응원합니다."]),
]
TEAM_TITLES = ["{t} 올 시즌 총평", "{t} 내년 선발 로테이션 예상", "{t} 불펜 보강 필요하다", "{t} 스토브리그 희망사항",
               "{t} 팬분들 올해 직관 인증", "{t} 응원가 떼창 그립다", "{t} 유망주 기대됩니다", "{t} 올해 최고의 경기는?",
               "{t} 내년 캡틴 누가 할까요", "{t} 유니폼 새로 샀어요", "{t} 마무리캠프 소식"]
TEAM_LINES = ["올해 진짜 롤러코스터 같은 시즌이었어요.", "그래도 끝까지 응원한 보람이 있었습니다.", "내년엔 꼭 더 높이 갑시다.",
              "어린 선수들 성장한 게 제일 큰 수확이라고 봐요.", "구장 분위기는 올해가 최고였어요.", "사진 몇 장 올려봅니다.",
              "여러분은 어떻게 보세요?", "비시즌 동안 몸 잘 만들어서 돌아오길!", "응원가 생각나서 혼자 흥얼거리는 중ㅋㅋ"]
COMMENTS = ["공감합니다!", "ㅋㅋㅋㅋ 저도요", "사진 잘 찍으셨네요", "와 부럽다", "인정합니다", "저도 같은 생각이에요",
            "내년엔 꼭 같이 가요", "좋은 글 감사합니다", "오 꿀팁 감사해요", "그 장면 진짜 소름이었죠", "저는 좀 다르게 봤어요",
            "올해 고생 많았죠", "응원합니다!", "ㅠㅠ 벌써 그립다", "이 글 추천 누릅니다", "명경기였죠", "구장 사진 예쁘네요"]
REPLIES = ["맞아요ㅋㅋ", "그쵸!", "감사합니다~", "저도요!", "ㅇㅈ", "다음에 꼭이요", "동의합니다"]


def compose(cat):
    if cat["teamId"]:
        t = (cat["name"] or "").split()[0]
        return random.choice(TEAM_TITLES).format(t=t), " ".join(random.sample(TEAM_LINES, random.randint(2, 4)))
    title, lines = random.choice(FREE)
    return title, " ".join(lines) + ("\n\n" + random.choice(TEAM_LINES) if random.random() < 0.4 else "")


# ---------- 유저 ----------

def db():
    return pymysql.connect(host=os.environ.get("DB_HOST", "mysql"), user=os.environ["DB_USERNAME"],
                           password=os.environ["DB_PASSWORD"], database="victoryfairy", charset="utf8mb4")


def load_users():
    conn = db()
    with conn.cursor() as cur:
        cur.execute("""SELECT ua.uid, ust.team_id FROM users u JOIN users_account ua ON ua.user_id = u.id
                       LEFT JOIN user_support_team ust ON ust.user_account_id = ua.id
                       WHERE u.email LIKE %s AND ua.exit_at IS NULL""", (PERSONA,))
        rows = cur.fetchall()
        cur.execute("""SELECT COUNT(*) FROM community_posts p JOIN users_account ua ON ua.id = p.user_account_id
                       JOIN users u ON u.id = ua.user_id
                       WHERE u.email LIKE %s AND p.deleted_at IS NULL AND p.created_at >= %s""", (PERSONA, START_DATE))
        existing = cur.fetchone()[0]
    conn.close()
    by_team, all_uids = {}, set()
    for uid, team in rows:
        all_uids.add(uid)
        if team:
            by_team.setdefault(team, []).append(uid)
    return list(all_uids), by_team, existing


def react(pid, uid):
    call("PUT", f"/community/posts/{pid}/reaction", uid,
         body={"reaction": "LIKE" if random.random() < 0.92 else "DISLIKE"}, tag="반응")


def comment(pid, uid, cpage=None):
    live = [c for c in ((cpage or {}).get("content") or []) if c.get("status") == "VISIBLE"]
    if live and random.random() < 0.35:
        call("POST", f"/community/posts/{pid}/comments", uid,
             body={"content": random.choice(REPLIES), "parentCommentId": random.choice(live)["commentId"]}, tag="답글")
    else:
        call("POST", f"/community/posts/{pid}/comments", uid, body={"content": random.choice(COMMENTS)}, tag="댓글")


def write_post(cats, uids, by_team):
    cat = random.choice(cats)
    uid = random.choice(by_team.get(cat["teamId"]) or uids)
    images = []
    if IMAGES and random.random() < IMAGE_RATIO:
        for path in random.sample(IMAGES, min(len(IMAGES), random.randint(1, MAX_IMAGES))):
            ep = upload_image(uid, path)
            if ep:
                images.append(ep)
    title, content = compose(cat)
    st, data = call("POST", "/community/posts", uid,
                    body={"categoryId": cat["id"], "title": title, "content": content, "imageUrls": images}, tag="글작성")
    if st == 201:
        pid = data["postId"]
        log(f"✍ #{pid} [{cat['name']}] {title} (이미지 {len(images)})")
        with lock:
            recent.insert(0, pid)
            del recent[12:]
        return pid
    log(f"✍ 실패 {st} [{cat['name']}] {data}")
    return None


# ---------- 열람·버즈 ----------

def reader(cats, uids):
    """열람 슬롯 — 방문할 때마다 다른 페르소나. 최신 글 위주로 훑으며 좋아요·댓글."""
    while time.time() < END:
        idle_until_active()
        uid = random.choice(uids)
        cat = random.choice([None, None] + cats)
        q = f"?sort={random.choice(['latest'] * 3 + ['likes', 'views'])}&page={random.choice([0, 0, 0, 0, 1])}&size=20"
        if cat:
            q += f"&categoryId={cat['id']}"
        if random.random() < 0.3:
            call("GET", "/community/posts/popular" + (f"?categoryId={cat['id']}" if cat else ""), uid, tag="인기목록")
        st, page = call("GET", "/community/posts" + q, uid, tag="목록")
        posts = (page or {}).get("content") or []
        for p in random.sample(posts, min(len(posts), random.randint(1, 4))):
            pid = p["postId"]
            st, _ = call("GET", f"/community/posts/{pid}", uid, tag="상세")
            if st != 200:
                continue
            time.sleep(random.uniform(1, 6))
            st, cpage = call("GET", f"/community/posts/{pid}/comments?page=0&size=20", uid, tag="댓글목록")
            if random.random() < 0.4:
                react(pid, uid)
            if random.random() < 0.18:
                comment(pid, uid, cpage)
        time.sleep(random.uniform(*READ_PAUSE_SEC))


def buzz(uids):
    """최근 글에 짧은 간격으로 반응이 붙게 — 상세 화면에서 댓글·좋아요가 실시간으로 늘어나는 게 보인다."""
    while time.time() < END:
        idle_until_active()
        time.sleep(random.uniform(*BUZZ_SEC))
        with lock:
            targets = list(recent[:5])
        if not targets:
            continue
        # 가장 최근 글일수록 자주
        pid = random.choices(targets, weights=[5, 3, 2, 1, 1][:len(targets)])[0]
        uid = random.choice(uids)
        if random.random() < 0.55:
            react(pid, uid)
        else:
            st, cpage = call("GET", f"/community/posts/{pid}/comments?page=0&size=20", uid, tag="댓글목록")
            comment(pid, uid, cpage)


def stats_loop():
    while time.time() < END:
        time.sleep(60)
        with lock:
            snap = dict(stats)
            stats.clear()
        log("📊 " + " ".join(f"{k}={v}" for k, v in sorted(snap.items())))


def cleanup():
    conn = db()
    with conn.cursor() as cur:
        cur.execute("""SELECT p.id, ua.uid FROM community_posts p JOIN users_account ua ON ua.id = p.user_account_id
                       JOIN users u ON u.id = ua.user_id
                       WHERE u.email LIKE %s AND p.deleted_at IS NULL AND p.created_at >= %s""", (PERSONA, START_DATE))
        rows = cur.fetchall()
    conn.close()
    log(f"삭제 대상 {len(rows)}건")
    for pid, uid in rows:
        call("DELETE", f"/community/posts/{pid}", uid, tag="삭제")
    log("📊 " + " ".join(f"{k}={v}" for k, v in sorted(stats.items())))


def main():
    if MODE == "cleanup":
        return cleanup()
    IMAGES.extend(load_images())
    uids, by_team, existing = load_users()
    if not uids:
        raise SystemExit("페르소나 계정이 없다")
    st, cats = call("GET", "/community/categories", uids[0])
    if st != 200 or not cats:
        raise SystemExit(f"카테고리 조회 실패 {st}")
    log(f"페르소나 {len(uids)}명, 야구 사진 {len(IMAGES)}장, 카테고리 {len(cats)}개: "
        + ", ".join(f"{c['id']}:{c['name']}" for c in cats))
    threading.Thread(target=stats_loop, daemon=True).start()
    todo = max(0, SEED_POSTS - existing)   # 재시작해도 시드를 또 쌓지 않게
    log(f"{START_DATE} 이후 페르소나 글 {existing}건 → 시드 {todo}건 작성")
    for _ in range(todo):
        write_post(cats, uids, by_team)
        time.sleep(random.uniform(1, 4))
    log("시드 완료 — 열람·버즈 시작")
    for _ in range(READERS):
        threading.Thread(target=reader, args=(cats, uids), daemon=True).start()
        time.sleep(random.uniform(0.5, 3))
    threading.Thread(target=buzz, args=(uids,), daemon=True).start()
    while time.time() < END:
        time.sleep(random.uniform(*NEW_POST_SEC))
        if active():
            write_post(cats, uids, by_team)
    log("END_AT 도달 — 종료")


main()
