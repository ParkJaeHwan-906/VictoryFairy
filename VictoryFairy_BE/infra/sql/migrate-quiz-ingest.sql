-- ============================================================================
-- 퀴즈 적재(S3 → RDB) 지원 스키마 확장 (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상: `quizzes` 4개 테이블이 **이미 만들어져 있는** 환경만. 실측으로 dev·prod 둘 다다:
--   - dev(52.78.153.242): 2026-08-07 실측 — 4개 테이블 존재·전부 0행.
--   - prod: **2026-08-10 실측 — 이 파일의 Step 2~5 는 이미 전부 반영돼 있다.** 6개 컬럼·FK 2개·
--     idx_quizzes_quiz_date·uk_quizzes_external_id·uk_quiz_users_submit_account_quiz 모두 존재하고
--     구 idx_quiz_users_submit_account_quiz 는 제거돼 있다. 그래서 지금 prod 에서 이 파일을 돌리면
--     Step 2 가 `Duplicate column name 'external_id'` 로 죽는다 — **고장이 아니라 "이미 적용됨"의
--     신호다.** prod 에 남아 있던 갭은 quiz_date 의 NOT NULL 뿐이었고 migrate-quiz-serve.sql 로
--     해소했다. (2026-08-08 에 external_id 부재로 실패했다는 종전 기록은 그 사이 해소되어 낡았다.)
--   - 진짜 신규 환경(테이블 생성 전)만 이 파일이 불필요하다 — 엔티티 선언대로 Hibernate 가
--     컬럼·UNIQUE·FK 를 전부 갖춰 만든다.
--   (0행인 환경은 4개 테이블을 DROP 하고 user 앱을 재기동해 재생성하는 지름길도 동등하게
--    유효하다. 그 경우 이 파일은 건너뛴다. 단 quiz_types 시드는 재기동이 다시 넣는지 확인.)
--
-- 왜 손으로 도는가: **`ddl-auto=update` 는 이미 존재하는 테이블에 컬럼은 추가하지만
-- UNIQUE·FK 제약은 추가하지 않는다**(2026-08-05 game_statuses 실측 — migrate-game-status-
-- unique.sql 머리 주석 참고). external_id UNIQUE 가 없으면 적재 멱등성(재시도·파드 동시
-- 실행의 중복 차단)이 통째로 사라지므로, 컬럼만 자동으로 생기고 제약이 빠진 상태를
-- 방치하면 안 된다. 이 파일은 컬럼까지 함께 추가하므로 **새 앱 기동 전/후 어느 시점에
-- 적용해도 된다** (기동 후라면 Step 2 의 ADD COLUMN 이 Duplicate column 으로 실패하니
-- Step 2 만 건너뛰고 Step 3~5 를 적용).
--
-- ⚠ 문장 순서를 지킬 것 — external_id 를 참조하는 확인 쿼리는 Step 2 '뒤'에 있다.
--   (초판은 그 쿼리가 Step 1 에 있어 첫 문장부터 Unknown column 으로 죽었다 —
--    2026-08-08 prod 실행 실패의 원인. 컬럼을 만들기 전에는 참조할 수 없다.)
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것. 재실행하면 Duplicate column/
--   Duplicate key name 으로 죽어 **모든 파드가 기동 실패**한다(migrate-*.sql 공통 규칙).
-- ============================================================================


-- ============================================================================
-- Step 1. 선행 확인 — 0행이어야 Step 5 의 UNIQUE 승격이 성공한다
-- ============================================================================
SELECT user_account_id, quiz_id, COUNT(*) AS cnt FROM quiz_users_submit
GROUP BY user_account_id, quiz_id HAVING COUNT(*) > 1;


-- ============================================================================
-- Step 2. quizzes 컬럼 추가 (새 앱이 이미 기동해 컬럼이 생겼다면 이 Step 은 건너뛴다)
-- ============================================================================
-- quiz_date 는 NULL 허용 — NULL = 미편성 풀 대기(Quiz 엔티티 규약: 역대기록형 문제는 풀에
-- 쌓이고 매일 편성 잡이 날짜를 스탬프한다. 경기 문항만 적재 시점에 날짜가 찍힌다).
-- 초판의 NOT NULL 은 편성 잡 도입 전 설계의 잔재다 — 그대로 적용하면 풀 대기 INSERT 가 전부
-- 실패하므로 절대 NOT NULL 로 걸지 말 것.
ALTER TABLE quizzes
    ADD COLUMN external_id      VARCHAR(32) NULL,
    ADD COLUMN quiz_date        DATE        NULL,
    ADD COLUMN game_id          BIGINT      NULL,
    ADD COLUMN opponent_team_id BIGINT      NULL,
    ADD COLUMN difficulty       VARCHAR(10) NULL,
    ADD COLUMN template_id      VARCHAR(64) NULL;


-- ============================================================================
-- Step 3. quizzes FK·인덱스 (ddl-auto 가 기존 테이블에 추가하지 않는 것들)
-- ============================================================================
-- ON DELETE 미지정(RESTRICT) — Quiz.team 의 non-cascade 정책과 동일(엔티티 주석 참고)
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_game FOREIGN KEY (game_id) REFERENCES games (id),
    ADD CONSTRAINT fk_quizzes_opponent_team FOREIGN KEY (opponent_team_id) REFERENCES teams (id);

CREATE INDEX idx_quizzes_quiz_date ON quizzes (quiz_date);


-- ============================================================================
-- Step 4. 적재 멱등키 — 이게 빠지면 로더 재실행마다 문제가 복제된다
-- ============================================================================
-- 선행 확인(0행이어야 함): 컬럼이 Step 2 에서 생겼으므로 여기서야 참조할 수 있다.
-- Step 2 를 건너뛴 경우(앱이 먼저 기동해 컬럼 자동 생성 + 적재까지 돌았던 환경)의
-- 중복 검출용이다. 방금 Step 2 로 만든 컬럼이면 전부 NULL 이라 자동으로 0행.
SELECT external_id, COUNT(*) AS cnt FROM quizzes
WHERE external_id IS NOT NULL GROUP BY external_id HAVING COUNT(*) > 1;

ALTER TABLE quizzes ADD CONSTRAINT uk_quizzes_external_id UNIQUE (external_id);


-- ============================================================================
-- Step 5. 중복 제출 차단 — 같은 컬럼 쌍의 일반 인덱스를 UNIQUE 로 승격
--   (따로 추가하면 같은 컬럼 쌍에 인덱스가 둘이 된다 — QuizUserSubmit 엔티티 주석의 예고)
-- ============================================================================
ALTER TABLE quiz_users_submit
    ADD CONSTRAINT uk_quiz_users_submit_account_quiz UNIQUE (user_account_id, quiz_id),
    DROP INDEX idx_quiz_users_submit_account_quiz;


-- ============================================================================
-- 검증 — UNIQUE 2개·FK 2개·인덱스 1개가 보여야 한다
-- ============================================================================
SHOW INDEX FROM quizzes WHERE Key_name IN ('uk_quizzes_external_id', 'idx_quizzes_quiz_date');
SHOW INDEX FROM quiz_users_submit WHERE Key_name = 'uk_quiz_users_submit_account_quiz';
SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes'
  AND CONSTRAINT_NAME IN ('fk_quizzes_game', 'fk_quizzes_opponent_team');
