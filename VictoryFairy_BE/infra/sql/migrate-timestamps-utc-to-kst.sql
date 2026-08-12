-- ============================================================================
-- 저장된 타임스탬프를 UTC 벽시계 → KST 벽시계로 이동 (+9시간)
-- (1회성, MySQL 8.0 — 수동 실행. ⚠ 두 번 돌리면 +18시간이 된다)
--
-- 왜: 파드에 TZ 가 없어 JVM 이 UTC 로 돌았고, LocalDateTime 컬럼이 존 정보 없이 UTC 벽시계로
--   저장돼 왔다. 응답에도 오프셋이 없어 FE 가 로컬로 읽으면 9시간 과거로 보인다(채팅 시간 오표시).
--   파드를 TZ=Asia/Seoul 로 바꾸면 앞으로 쓰이는 값은 KST 가 되므로, 기존 행을 같은 존으로 옮긴다.
--
-- 값이 재해석되는 게 아니라 실제로 이동한다: 이 스키마에 TIMESTAMP 타입 컬럼은 0개이고
--   (datetime 52 + date 2, 실측) 드라이버도 preserveInstants=false 라 변환하지 않는다.
--   즉 저장값의 존은 전적으로 "쓴 쪽의 존"이며, 이 UPDATE 만이 과거 행을 옮길 수 있다.
--
-- ⚠⚠ 선행 조건 셋 — 하나라도 어기면 데이터가 섞이거나 사용자가 끊긴다
--   1) **쓰기가 멈춘 상태에서 돌려야 한다.** 실행 중에 새 행이 들어오면 그 행이 이미 KST 인지
--      아직 UTC 인지 구분할 방법이 영영 없어진다. 파드를 0으로 내리고 돌린다.
--   2) **파드 TZ 전환과 같은 점검 창에서 해야 한다.** 백필만 하고 파드가 UTC 로 남으면 정반대로
--      어긋나고, 파드만 KST 로 바꾸면 과거 행이 9시간 뒤처진 채 남는다.
--   3) **MySQL 서버 TZ 도 같은 창에서 KST 로 바꿔야 한다.** 아래 §B 컬럼들은 앱이 아니라
--      수집기·시드 SQL 의 `NOW(6)` 가 채우고, 그건 파드가 아니라 **MySQL 서버 존**을 따른다.
--      서버를 UTC 로 두면 이 백필 이후 들어오는 수집기 행만 다시 UTC 가 되어 한 컬럼에 두 존이 섞인다.
--      (mysqld 재시작까지 해야 `NOW()` 에 반영된다 — time_zone=SYSTEM 은 기동 시 읽는다.)
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙).
-- ============================================================================


-- ============================================================================
-- Step 0. 재실행 방지 장치 — 반드시 먼저 만든다
--   이 백필은 "한 번 더 돌면 +18시간"이 되는데 스키마에 흔적이 남지 않아 눈으로 구분할 수 없다.
--   그래서 적용 여부를 기록하는 표식 테이블을 두고, 아래 트랜잭션의 첫 문장으로 INSERT 한다 —
--   이미 적용된 DB 라면 PK 중복으로 트랜잭션 전체가 실패하고 **한 행도 이동하지 않는다.**
--   (CREATE TABLE 은 암시적 커밋이라 트랜잭션 밖에 둔다.)
-- ============================================================================
CREATE TABLE IF NOT EXISTS schema_migration_applied (
    name        VARCHAR(191) NOT NULL,
    applied_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ============================================================================
-- Step 1. 적용 전 실측 — 이 값들을 반드시 기록해 두고 Step 4 와 대조한다
--   샘플 한 건의 시각이 "지금보다 9시간 과거"로 보이면 아직 UTC 다.
-- ============================================================================
SELECT NOW() AS mysql_now, UTC_TIMESTAMP() AS mysql_utc;

SELECT 'users_account' AS t, MIN(created_at) AS oldest, MAX(created_at) AS newest, COUNT(*) AS rows_cnt FROM users_account
UNION ALL SELECT 'chats',            MIN(created_at), MAX(created_at), COUNT(*) FROM chats
UNION ALL SELECT 'quiz_users_submit',MIN(created_at), MAX(created_at), COUNT(*) FROM quiz_users_submit
UNION ALL SELECT 'games',            MIN(created_at), MAX(created_at), COUNT(*) FROM games;

-- 살아 있는 리프레시 토큰 수. Step 4 에서 같은 수가 남아 있어야 한다(로그아웃 사고 감지).
SELECT COUNT(*) AS live_tokens_before FROM users_refreshtoken WHERE expired_at > UTC_TIMESTAMP();

-- games.game_date 샘플 — 이 값은 **이동하지 않는다**. Step 4 에서 그대로여야 한다(§C 참고).
SELECT id, game_date FROM games ORDER BY game_date DESC LIMIT 3;


-- ============================================================================
-- Step 2. 이동 — 전부 한 트랜잭션이다. 중간에 끊겨 절반만 옮겨지는 상태를 만들지 않는다.
-- ============================================================================
START TRANSACTION;

-- 표식 먼저. 이미 있으면 여기서 실패하고 아래 UPDATE 는 한 건도 실행되지 않는다.
INSERT INTO schema_migration_applied (name, applied_at)
VALUES ('migrate-timestamps-utc-to-kst', NOW(6));

-- ── §A. 앱(파드 JVM)이 UTC 로 쓴 컬럼 ──────────────────────────────────────
-- 파드가 KST 로 바뀌면 앞으로는 KST 로 쓰인다. 과거 행을 같은 존으로 맞춘다.

UPDATE users               SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

-- exit_at 은 탈퇴 완료 시각(NULL 이면 활성 — NULL 은 그대로 NULL 이다)
UPDATE users_account       SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR,
                               exit_at    = exit_at    + INTERVAL 9 HOUR;

UPDATE users_bq            SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

-- ⚠ expired_at 이 빠지면 TZ 전환 직후 비교 기준(now)만 9시간 앞으로 뛰어 **유효 토큰이 전부
--   만료 판정된다 → 전원 로그아웃.** 이 테이블에는 updated_at 컬럼이 없다.
UPDATE users_refreshtoken  SET created_at = created_at + INTERVAL 9 HOUR,
                               expired_at = expired_at + INTERVAL 9 HOUR;

-- oppose = 응원 취소 시각(NULL 이면 유효한 응원)
UPDATE user_support_team   SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR,
                               oppose     = oppose     + INTERVAL 9 HOUR;

UPDATE user_support_player SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR,
                               oppose     = oppose     + INTERVAL 9 HOUR;

UPDATE quizzes             SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE quiz_options        SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE quizzes_like        SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

-- ⚠ quiz_users_submit.created_at 은 감사 컬럼이 아니라 **제출 시한(8분)의 기준점**이다
--   (QuizSubmitWindow). 빠뜨리면 미답 행 전체가 즉시 시한 초과가 되어 그 세트 제출이 전부 403 이 된다.
UPDATE quiz_users_submit   SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR;

-- deleted_at 은 앱에 쓰기 경로가 없어 존을 특정하지 못했다. 다만 non-null 행이 0건이라
-- (2026-08-12 운영 실측) 포함해도 무해하다 — NULL + INTERVAL 은 NULL 이다. 나중에 누가 값을
-- 넣기 시작하면 그 경로의 존을 다시 판정해야 한다.
UPDATE chats               SET created_at = created_at + INTERVAL 9 HOUR,
                               updated_at = updated_at + INTERVAL 9 HOUR,
                               deleted_at = deleted_at + INTERVAL 9 HOUR;

-- ── §B. 수집기·시드 SQL 의 NOW(6) 가 UTC 로 쓴 컬럼 ────────────────────────
-- 값이 UTC 인 것은 §A 와 같고 원인만 다르다(앱 JVM 이 아니라 MySQL 서버 존).
-- ⚠ 그래서 이 구간은 **MySQL 서버 TZ 를 KST 로 바꾸는 것이 전제**다(머리 주석 선행 조건 3).
--   서버가 UTC 로 남으면 앞으로 들어올 수집기 행만 UTC 가 되어 한 컬럼에 두 존이 섞인다.

UPDATE chatrooms       SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR,
                           deleted_at = deleted_at + INTERVAL 9 HOUR;

UPDATE quiz_type       SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE teams           SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE stadiums        SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE positions       SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE players         SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

-- ⚠ games 는 created_at·updated_at 만 옮긴다. game_date 는 절대 건드리지 않는다(§C).
UPDATE games           SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE game_statuses   SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE game_lineups    SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

-- ⚠ registrations 도 registration_date 는 건드리지 않는다(§C).
UPDATE registrations   SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE batter_records  SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE pitcher_records SET created_at = created_at + INTERVAL 9 HOUR,
                           updated_at = updated_at + INTERVAL 9 HOUR;

COMMIT;


-- ============================================================================
-- §C. 이동하지 않는 컬럼 — 실수로 추가하지 말 것 (전부 이미 KST 이거나 시각이 아니다)
--
--   games.game_date              네이버 원천의 **KST 경기 시각**이 그대로 들어간 값이다.
--                                앱에는 games 쓰기 경로가 아예 없다. +9 하면 18:30 경기가
--                                03:30 으로 밀리고, 날짜로 조회하는 모든 화면이 하루씩 어긋난다.
--   quizzes.quiz_date            date 타입이고 값의 출처가 kstClock 이라 이미 KST 다.
--                                date 에 INTERVAL 9 HOUR 를 걸면 사실상 +1 DAY 가 되어
--                                "오늘의 퀴즈"가 하루 밀리고 전 사용자에게 빈 목록이 나간다.
--   registrations.registration_date  date 타입, KBO 사이트가 준 등록일. 시각 개념이 없다.
-- ============================================================================


-- ============================================================================
-- Step 3. MySQL 서버 TZ 는 이 파일이 바꾸지 않는다
--   OS 시간대이므로 SQL 이 아니라 인스턴스에서 바꾼다(SSM 등). 두 대 모두 대상이다:
--     운영  victoryfairy-mysql-dev (10.0.0.14  / 43.200.82.148)
--     개발  victoryfairy-devdb-dev (10.0.0.163 / 52.78.153.242)
--   `sudo timedatectl set-timezone Asia/Seoul` 후 **mysqld 재시작**까지 해야 NOW() 에 반영된다.
--   ⚠ terraform user_data 를 고쳐 apply 하는 방식은 쓰지 말 것 — DB 인스턴스 stop/start 만
--     유발하고 cloud-init 은 재실행되지 않아 시간대가 바뀌지 않는다(저장소 기존 규약).
-- ============================================================================


-- ============================================================================
-- Step 4. 검증 — Step 1 과 대조한다
--   ① 위 샘플들의 시각이 정확히 9시간 앞으로 갔는가
--   ② live_tokens 가 Step 1 과 같은가 (줄었으면 expired_at 이동이 누락됐거나 이중 적용이다)
--   ③ games.game_date 샘플이 **그대로**인가 (바뀌었으면 즉시 롤백 검토 — §C 위반)
--   ④ 표식이 1행 남았는가
-- ============================================================================
SELECT NOW() AS mysql_now, UTC_TIMESTAMP() AS mysql_utc;

SELECT 'users_account' AS t, MIN(created_at) AS oldest, MAX(created_at) AS newest, COUNT(*) AS rows_cnt FROM users_account
UNION ALL SELECT 'chats',            MIN(created_at), MAX(created_at), COUNT(*) FROM chats
UNION ALL SELECT 'quiz_users_submit',MIN(created_at), MAX(created_at), COUNT(*) FROM quiz_users_submit
UNION ALL SELECT 'games',            MIN(created_at), MAX(created_at), COUNT(*) FROM games;

-- ⚠ 이 비교 기준은 서버 TZ 변경 전이면 UTC_TIMESTAMP(), 변경 후면 NOW() 다. 창 안에서 어느
--   시점에 확인하는지에 따라 쓸 함수가 다르니 둘 다 본다.
SELECT COUNT(*) AS live_tokens_after_utc FROM users_refreshtoken WHERE expired_at > UTC_TIMESTAMP();
SELECT COUNT(*) AS live_tokens_after_now FROM users_refreshtoken WHERE expired_at > NOW();

SELECT id, game_date FROM games ORDER BY game_date DESC LIMIT 3;

SELECT * FROM schema_migration_applied WHERE name = 'migrate-timestamps-utc-to-kst';
