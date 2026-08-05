-- ============================================================================
-- users_bq 기존 계정 백필 (MySQL 8.0 / AWS RDS 대상)
-- 계약: docs/requirements/user/me-profile.md USER-ME-26 ~ USER-ME-28
--
-- 무엇을 하는가
--   users_bq 행이 없는 모든 users_account 행에 대해 bq_score = 0 행을 1개씩 만든다.
--   앞으로 가입하는 계정은 회원가입 트랜잭션이 행을 함께 만들지만(USER-ME-23), 이미
--   적재된 계정에는 행이 없다. "계정 1행 = users_bq 1행" 전제를 과거 데이터에도
--   성립시키는 것이 이 스크립트의 유일한 목적이다.
--
-- 언제 실행하는가 (순서가 계약의 일부다 — 뒤집을 수 없다)
--   1. user 앱을 재기동한다. 저장소에 Flyway가 없고 prod 스키마는 user 앱의
--      `ddl-auto: update`가 만든다(.claude/modules/domain.md). 기동 시 Hibernate가
--      `users_account.point` 컬럼과 `users_bq` 테이블을 생성한다.
--      → 이 시점 이후의 신규 가입은 이미 users_bq 행을 갖는다.
--   2. **곧바로** 이 스크립트를 실행한다. 1과 2 사이에 가입한 계정은 아래 NOT EXISTS가
--      걸러내므로 중복이 생기지 않는다.
--   3. 파일 맨 끝의 검증 쿼리를 돌려 0이 나오는지 확인한다.
--
--   ⚠ 1 이전에 실행하면 테이블이 없어 스크립트 자체가 실패한다.
--   ⚠ 1~2 사이 구간에는 기존 계정의 GET /api/member/users/me 가 500이 아니라
--     `bqScore: 0`으로 200을 유지한다(USER-ME-19 안전망). 즉 이 백필을 빠뜨려도
--     장애로 드러나지 않으므로, 반드시 3번 검증까지 수행할 것.
--
-- 멱등하다 (USER-ME-27)
--   NOT EXISTS 조건이 이미 행이 있는 계정을 건너뛴다. 몇 번을 실행해도 2회차부터는
--   affected rows = 0 이고 UNIQUE(user_account_id) 위반이 나지 않는다. 기존 행의
--   bq_score 도 건드리지 않는다(INSERT 전용, UPDATE 없음 — USER-ME-28).
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- 백필
--
-- 컬럼명은 UserBq.java 매핑과 문자 그대로 일치한다
--   (user_account_id / bq_score / created_at / updated_at).
--   id 는 AUTO_INCREMENT 라 지정하지 않는다.
--
-- ▸ NOT IN 이 아니라 NOT EXISTS 다. NOT IN 은 서브쿼리 결과에 NULL이 하나라도 섞이면
--   전체가 0건이 되는 함정이 있다. 지금은 user_account_id 가 NOT NULL 이라 결과가
--   같지만, 조건이 바뀌면 에러 없이 조용히 아무것도 넣지 않는 쿼리로 변한다.
--
-- ▸ NOW() 가 아니라 NOW(6) 이다. Hibernate 가 LocalDateTime 을 datetime(6) 으로
--   매핑하므로 NOW()(초 단위)를 쓰면 백필로 만든 행만 마이크로초가 0으로 잘린다.
--
-- ▸ 탈퇴 계정(exit_at IS NOT NULL)도 포함한다. 탈퇴는 소프트 삭제라 행이 그대로 남아
--   있고, 제외하면 "계정 1행 = bq 1행" 불변식이 깨져 아래 검증 쿼리가 복잡해진다.
--   제외해서 얻는 실익이 없다.
-- ============================================================================

INSERT INTO users_bq (user_account_id, bq_score, created_at, updated_at)
SELECT ua.id, 0, NOW(6), NOW(6)
FROM users_account ua
WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);


-- ============================================================================
-- 검증 쿼리 (적용 후 수동으로 돌려볼 것 — 이 파일이 자동 실행하지는 않음)
-- ============================================================================
-- 1) USER-ME-26: 행이 없는 계정이 0건이어야 한다 (기대값 0)
--    SELECT COUNT(*) AS missing
--    FROM users_account ua
--    WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);
--
-- 2) USER-ME-26: 두 테이블의 행 수가 같아야 한다 (기대값 동일)
--    SELECT (SELECT COUNT(*) FROM users_account) AS accounts,
--           (SELECT COUNT(*) FROM users_bq)      AS bq_rows;
--
-- 3) USER-ME-5 / USER-ME-27: 계정당 2행 이상이 없어야 한다 (기대값 0건)
--    SELECT user_account_id, COUNT(*) AS cnt
--    FROM users_bq GROUP BY user_account_id HAVING COUNT(*) > 1;
--
-- 4) USER-ME-1: point 컬럼이 의도대로 생성됐는지 (기대: Type=bigint, Null=NO, Default=0)
--    SHOW COLUMNS FROM users_account LIKE 'point';
--
-- 5) USER-ME-2: 기존 계정의 point 가 NULL 이거나 0이 아닌 행이 없어야 한다 (기대값 0)
--    SELECT COUNT(*) FROM users_account WHERE point IS NULL OR point <> 0;
--
-- 6) USER-ME-3 / USER-ME-4: 테이블 구조·FK·UNIQUE 확인
--    SHOW COLUMNS FROM users_bq;
--    SHOW CREATE TABLE users_bq;
--    -- FOREIGN KEY (user_account_id) REFERENCES users_account (id) ON DELETE CASCADE 와
--    -- user_account_id 단독 UNIQUE 키가 보여야 한다.
--    -- ⚠ ON DELETE CASCADE 는 @OnDelete 로 매핑돼 있으나, Hibernate 의 ddl-auto=update 는
--    --   **이미 존재하는 FK 제약을 수정하지 않는다.** 어떤 이유로든 users_bq 가 이 스크립트
--    --   이전에 다른 정의로 만들어져 있었다면 CASCADE 절이 빠져 있을 수 있으니 여기서 반드시
--    --   눈으로 확인할 것(빠져 있으면 제약을 DROP 후 재생성해야 한다).
-- ============================================================================
