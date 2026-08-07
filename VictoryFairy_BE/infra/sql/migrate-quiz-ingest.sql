-- ============================================================================
-- 퀴즈 적재(S3 → RDB) 지원 스키마 확장 (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상: `quizzes` 4개 테이블이 **이미 만들어져 있는** 환경만.
--   - dev(52.78.153.242): 2026-08-07 실측 — 4개 테이블 존재·전부 0행 → 이 파일 적용 대상.
--     (0행이므로 4개 테이블을 DROP 하고 user 앱을 재기동해 재생성하는 지름길도 동등하게
--      유효하다. 그 경우 이 파일은 건너뛴다.)
--   - 신규 환경(prod 포함, 테이블 생성 전): 엔티티 선언대로 Hibernate 가 컬럼·UNIQUE·FK 를
--     전부 갖춰 만들므로 이 파일 불필요.
--
-- 왜 손으로 도는가: **`ddl-auto=update` 는 이미 존재하는 테이블에 컬럼은 추가하지만
-- UNIQUE·FK 제약은 추가하지 않는다**(2026-08-05 game_statuses 실측 — migrate-game-status-
-- unique.sql 머리 주석 참고). external_id UNIQUE 가 없으면 적재 멱등성(재시도·파드 동시
-- 실행의 중복 차단)이 통째로 사라지므로, 컬럼만 자동으로 생기고 제약이 빠진 상태를
-- 방치하면 안 된다. 이 파일은 컬럼까지 함께 추가하므로 **새 앱 기동 전/후 어느 시점에
-- 적용해도 된다** (기동 후라면 Step 2 의 ADD COLUMN 이 Duplicate column 으로 실패하니
-- Step 2 만 건너뛰고 Step 3~5 를 적용).
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것. 재실행하면 Duplicate column/
--   Duplicate key name 으로 죽어 **모든 파드가 기동 실패**한다(migrate-*.sql 공통 규칙).
-- ============================================================================


-- ============================================================================
-- Step 1. 선행 확인 — 모두 0행이어야 Step 4·5 의 UNIQUE 가 성공한다
-- ============================================================================
-- (quizzes 가 비어 있으면 자동으로 0행. 데이터가 있다면 quiz_date backfill 계획부터 세울 것 —
--  Step 2 는 quiz_date 를 NOT NULL 로 추가하므로 기존 행이 있으면 실패한다)
SELECT external_id, COUNT(*) AS cnt FROM quizzes
WHERE external_id IS NOT NULL GROUP BY external_id HAVING COUNT(*) > 1;

SELECT user_account_id, quiz_id, COUNT(*) AS cnt FROM quiz_users_submit
GROUP BY user_account_id, quiz_id HAVING COUNT(*) > 1;


-- ============================================================================
-- Step 2. quizzes 컬럼 추가 (새 앱이 이미 기동해 컬럼이 생겼다면 이 Step 은 건너뛴다)
-- ============================================================================
ALTER TABLE quizzes
    ADD COLUMN external_id      VARCHAR(32) NULL,
    ADD COLUMN quiz_date        DATE        NOT NULL,
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
