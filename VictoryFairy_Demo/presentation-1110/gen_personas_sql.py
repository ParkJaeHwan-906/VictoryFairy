"""페르소나 계정 추가 SQL 생성기. python gen_personas_sql.py  (personas-add-60.sql 을 쓴다)

봇 세 개(chatbot·communitybot·quizbot)는 users.email LIKE '%@persona.victoryfairy.internal' 을 시작할 때 읽는다 —
같은 도메인으로 넣으면 봇을 다시 띄우는 것만으로 함께 쓰인다. 2026-09 더미 시드와 같은 4개 테이블만 채운다.
비밀번호는 BCrypt 가 아닌 고정 문자열이라 로그인할 수 없다(봇은 JWT 를 직접 서명한다).
닉네임은 NicknamePolicy(1~10자, 한글·영문·숫자) 안에서 짓고, DB 에 UNIQUE 가 없어 이미 있는 닉네임은 건너뛴다.
"""
import random, re

TEAMS = {  # team_id: 닉네임 6개
    17: ["잠실의밤", "트윈스사랑", "엘지직관러", "무적엘지팬", "쌍둥이응원단", "잠실외야석"],
    16: ["허슬두최고", "곰돌이팬", "베어스직관", "두산내야석", "잠실곰", "미라클두산"],
    18: ["라팍직관러", "사자군단", "블루윙즈", "대구사자팬", "삼성V9", "푸른피사나이"],
    19: ["수원마법사", "위즈파크", "케이티팬", "마법사군단", "위즈응원단", "수원직관러"],
    20: ["고척돔지기", "영웅본색", "버건디팬", "히어로즈짱", "고척외야", "키움직관러"],
    21: ["챔피언스필드", "호랑이굴", "광주직관러", "타이거즈팬", "갸팬입니다", "기아응원단"],
    22: ["보살의마음", "이글스둥지", "대전직관러", "독수리군단", "한화행복야구", "주황피"],
    23: ["공룡발자국", "창원직관러", "다이노스팬", "엔팍외야석", "공룡군단장", "마산아재"],
    24: ["사직갈매기", "부산직관러", "롯데자이언츠", "거인군단", "봉다리응원", "마린스타"],
    25: ["랜더스필드", "인천짠물", "쓱직관러", "문학야구장", "상륙작전", "랜더스팬"],
}
rnd = random.Random(20261110)
rows = []
n = 0
for team, nicks in TEAMS.items():
    for nick in nicks:
        assert 1 <= len(nick) <= 10 and re.fullmatch(r"[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+", nick), nick
        n += 1
        bq = 0 if rnd.random() < 0.2 else int(rnd.lognormvariate(2.2, 0.9))
        rows.append((f"persona2-{n:03d}@persona.victoryfairy.internal", nick, team, min(bq, 250),
                     rnd.choice([0, 0, 100, 300, 500, 800, 1200, 2000])))
assert len({r[1] for r in rows}) == len(rows)

vals = ",\n".join(f"  ('{e}', '{k}', {t}, {b}, {p})" for e, k, t, b, p in rows)
emails = ",".join(f"'{r[0]}'" for r in rows)
sql = f"""-- 운영 쓰기: 페르소나 계정 {len(rows)}명 추가(구단당 6명). gen_personas_sql.py 로 생성.
-- 되돌리기: DELETE FROM users WHERE email LIKE 'persona2-%@persona.victoryfairy.internal';  (FK CASCADE)
--   캐스케이드가 의심되면 user_support_team → users_bq → users_account → users 순서로.
SET NAMES utf8mb4;
DROP TEMPORARY TABLE IF EXISTS tmp_persona;
CREATE TEMPORARY TABLE tmp_persona (
  email VARCHAR(100) COLLATE utf8mb4_unicode_ci, nickname VARCHAR(100) COLLATE utf8mb4_unicode_ci,
  team_id BIGINT, bq BIGINT, point BIGINT) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT INTO tmp_persona VALUES
{vals};

-- 이미 있는 이메일·닉네임은 건너뛴다(재실행 안전)
DELETE t FROM tmp_persona t WHERE EXISTS (SELECT 1 FROM users u WHERE u.email = t.email)
   OR EXISTS (SELECT 1 FROM users_account ua WHERE ua.nickname = t.nickname);

START TRANSACTION;
INSERT INTO users (email, email_verified, created_at, updated_at)
SELECT email, 1, NOW(6), NOW(6) FROM tmp_persona;
INSERT INTO users_account (uid, user_id, nickname, password, point, created_at, updated_at)
SELECT UUID(), u.id, t.nickname, 'LOCKED-NO-LOGIN', t.point, NOW(6), NOW(6)
  FROM tmp_persona t JOIN users u ON u.email = t.email;
INSERT INTO users_bq (user_account_id, bq_score, created_at, updated_at)
SELECT ua.id, t.bq, NOW(6), NOW(6) FROM tmp_persona t JOIN users u ON u.email = t.email JOIN users_account ua ON ua.user_id = u.id;
INSERT INTO user_support_team (user_account_id, team_id, created_at, updated_at)
SELECT ua.id, t.team_id, NOW(6), NOW(6) FROM tmp_persona t JOIN users u ON u.email = t.email JOIN users_account ua ON ua.user_id = u.id;
COMMIT;

-- 확인: 새로 들어간 수, 전체 페르소나 수, 구단 분포
SELECT COUNT(*) added FROM tmp_persona;
SELECT COUNT(*) personas FROM users u JOIN users_account ua ON ua.user_id = u.id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' AND ua.exit_at IS NULL;
SELECT t.name, COUNT(*) n FROM users u JOIN users_account ua ON ua.user_id = u.id
  JOIN user_support_team ust ON ust.user_account_id = ua.id JOIN teams t ON t.id = ust.team_id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' GROUP BY t.name ORDER BY n DESC;
"""
open("personas-add-60.sql", "w", encoding="utf-8", newline="\n").write(sql)
print(len(rows), "명")
