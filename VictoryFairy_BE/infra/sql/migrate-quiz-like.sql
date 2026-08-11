-- ============================================================================
-- 퀴즈 좋아요 — quizzes_like 테이블 보정 (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상: **quizzes_like 테이블이 이미 존재하는 환경만.** 신규 엔티티라 지금 시점에는 어느 환경에도
--   없을 가능성이 크고, 그렇다면 이 파일은 통째로 불필요하다 — 테이블이 없는 환경에서는
--   `ddl-auto=update` 인 user 앱이 기동하면서 엔티티 선언대로 UNIQUE 까지 갖춰 만들어 준다.
--   **"없을 것"을 전제하지 말고 Step 0 으로 실제 상태를 조회해서 판단할 것.**
--
-- 왜 이 파일이 필요한가: **`ddl-auto=update` 는 이미 존재하는 테이블에 UNIQUE 를 추가하지 않는다**
--   (2026-08-05 game_statuses 실측 — migrate-game-status-unique.sql 머리 주석 참고. 다시 조사하지
--   말 것). 즉 어떤 이유로든 테이블이 제약 없이 먼저 생겨 버리면 UNIQUE 는 **영원히 자동으로
--   붙지 않는다.** 그 제약이 없으면 좋아요 토글의 동시 요청 2건이 같은 (계정, 문제) 행을 둘 만들 수
--   있고, 그때부터 그 사용자의 토글 단건 조회가 IncorrectResultSizeDataAccessException 으로 죽는다.
--   테이블이 아직 없는 지금이 1회성 DDL 없이 제약을 거는 유일한 기회라는 점은 quiz_type 때와 같다.
--
-- ⚠ 스키마를 만드는 앱과 쓰는 앱이 다르다. quiz 앱의 prod ddl-auto 는 `none` 이라 quiz 만 배포해서는
--   테이블이 생기지 않는다 — 생성 주체는 ddl-auto=update 인 **user 앱의 기동**이다
--   (user_support_team·users_bq 와 같은 처지). 배포 묶음에 user 앱 재기동이 포함되어야 한다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙).
--   재실행하면 Duplicate key name 으로 죽어 **모든 파드가 기동 실패**한다.
-- ============================================================================


-- ============================================================================
-- Step 0. 환경 판단 — 여기 결과에 따라 아래를 적용할지 말지가 갈린다
-- ============================================================================
-- (0-a) 테이블이 있는가. 0행이면 **이 파일은 대상이 아니다** — 여기서 멈추고 user 앱을 기동해
--       Hibernate 가 엔티티 선언대로 만들게 둔다(그때 UNIQUE 도 함께 붙는다).
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes_like';

-- (0-b) UNIQUE 가 이미 걸려 있는가. 1행이 나오면 **이미 적용됨** — 아래 Step 2 는 건너뛴다
--       (그냥 돌리면 Duplicate key name 으로 실패하는데, 그건 고장이 아니라 "이미 적용됨"의 신호다).
SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes_like'
  AND CONSTRAINT_NAME = 'uk_quizzes_like_account_quiz';

-- (0-c) 컬럼 모양 확인 — liked 가 TINYINT NOT NULL 이고 created_at/updated_at 이 둘 다 있어야 한다.
--       컬럼이 비면 그건 이 파일이 아니라 user 앱 기동(ddl-auto=update 의 ADD COLUMN)이 채운다.
SHOW COLUMNS FROM quizzes_like;

-- (0-d) FK 2개가 ON DELETE CASCADE 인가. ddl-auto=update 는 기존 테이블에 FK 도 추가하지 않으므로
--       비어 있으면 Step 3 을 함께 적용한다.
SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE, kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quizzes_like';


-- ============================================================================
-- Step 1. 선행 확인 — 0행이어야 Step 2 의 UNIQUE 추가가 성공한다
--   (중복이 있으면 어느 행을 남길지 사람이 판단해야 한다. 좋아요는 (계정, 문제) 한 행이 정본이므로
--    보통 liked=1 인 행이나 created_at 이 가장 이른 행을 남기고 나머지를 지운다 — 자동화하지 않는다.)
-- ============================================================================
SELECT user_account_id, quiz_id, COUNT(*) AS cnt FROM quizzes_like
GROUP BY user_account_id, quiz_id HAVING COUNT(*) > 1;


-- ============================================================================
-- Step 2. 중복 좋아요 차단 — 이게 빠지면 동시 요청이 같은 조합의 행을 둘 만든다
-- ============================================================================
-- 선행 컬럼이 user_account_id 인 것은 기존 UNIQUE 3건(uk_quiz_users_submit_account_quiz ·
-- uk_user_support_team_account_team · uk_user_support_player_account_player)과 맞춘 것이다.
-- 이름을 반드시 명시한다 — Hibernate 자동 생성명(UK6x04…)이면 위 Step 0-b 로 "이미 걸렸는지"를
-- 확인할 수 없다.
ALTER TABLE quizzes_like
    ADD CONSTRAINT uk_quizzes_like_account_quiz UNIQUE (user_account_id, quiz_id);


-- ============================================================================
-- Step 3. FK — Step 0-d 가 비었을 때만 적용한다
-- ============================================================================
-- 둘 다 ON DELETE CASCADE: 좋아요는 계정·문제에 완전히 종속돼 대상이 사라지면 함께 사라져도 된다
-- (제출 기록과 같은 판단). 이름은 fk_<테이블>_<대상> 형태로 quizzes 쪽 선례를 따른다.
-- ⚠ quiz_id 쪽 FK 가 만드는 자동 인덱스가 "문제별 좋아요 수" 조회의 진입 인덱스다 — FK 를 빠뜨리면
--   제약뿐 아니라 그 인덱스까지 없는 상태가 된다(별도 인덱스를 두지 않은 근거가 이 자동 인덱스다).
ALTER TABLE quizzes_like
    ADD CONSTRAINT fk_quizzes_like_user_account FOREIGN KEY (user_account_id)
        REFERENCES users_account (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_quizzes_like_quiz FOREIGN KEY (quiz_id)
        REFERENCES quizzes (id) ON DELETE CASCADE;


-- ============================================================================
-- 검증 — UNIQUE 1개와 FK 2개(DELETE_RULE = CASCADE)가 보여야 한다
-- ============================================================================
SHOW INDEX FROM quizzes_like WHERE Key_name = 'uk_quizzes_like_account_quiz';

SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quizzes_like';
