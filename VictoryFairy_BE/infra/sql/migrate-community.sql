-- ============================================================================
-- 커뮤니티 8테이블 제약 보정 (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상: **커뮤니티 테이블이 제약 없이 먼저 생겨 버린 환경만.** 정상 경로에서는 이 파일이 통째로 불필요하다
--   — 테이블이 없는 환경은 ddl-auto=update 인 user 앱이 기동하면서 엔티티 선언대로 UNIQUE·FK·인덱스까지
--   갖춰 만든다(quizzes_like 선례). **"없을 것"을 전제하지 말고 Step 0 으로 실제 상태를 조회해서 판단할 것.**
--
-- 왜 이 파일이 필요한가: ddl-auto=update 는 이미 존재하는 테이블에 UNIQUE·FK 를 추가하지도, 기존 FK 의
--   삭제 규칙을 바꾸지도 않는다(game_statuses · quizzes_like 실측 — 다시 조사하지 말 것). 어떤 이유로든
--   테이블이 먼저 생기면 아래 제약은 영원히 자동으로 붙지 않는다:
--     · 반응 UNIQUE 2건 — 없으면 같은 계정의 동시 반응이 행을 둘 만들고 카운터가 행 수와 어긋난다
--     · 신고 UNIQUE 1건 — 없으면 재신고마다 행이 쌓인다
--     · 반응·신고의 계정 FK 가 SET NULL 이 아니면 계정 하드 삭제가 반응을 지워(CASCADE) 카운터와 어긋나거나,
--       NO ACTION 이면 계정 삭제 자체가 실패한다
--     · 게시글·댓글의 작성자 FK 가 CASCADE 면 계정 하드 삭제가 글을 조용히 지운다(의도는 NO ACTION +
--       삭제 전 이관 — ExpiredAccountEraser)
--
-- ⚠ 이 파일을 spring.sql.init.data-locations 에 넣지 말 것(migrate-*.sql 공통 규칙). 재실행하면 Duplicate
--   key name 등으로 죽어 모든 파드가 기동 실패한다. 각 Step 은 Step 0 결과를 보고 사람이 골라 적용한다.
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 환경 판단 — 여기 결과에 따라 아래를 적용할지 말지가 갈린다
-- ============================================================================
-- (0-a) 테이블 8개가 있는가. 0행이면 이 파일은 대상이 아니다 — user 앱을 기동해 Hibernate 가 만들게 둔다.
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (
    'community_categories', 'community_posts', 'community_comments',
    'community_post_images', 'community_comment_images',
    'community_post_reactions', 'community_comment_reactions', 'community_reports');

-- (0-b) UNIQUE 4건이 걸려 있는가. 4행이 나오면 Step 2 는 전부 건너뛴다. 빠진 것만 골라 적용한다.
SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'UNIQUE' AND CONSTRAINT_NAME IN (
    'uk_community_categories_name',
    'uk_community_post_reactions_account_post',
    'uk_community_comment_reactions_account_comment',
    'uk_community_reports_reporter_target');

-- (0-c) FK 의 실제 이름과 삭제 규칙. 기대값:
--   community_posts.user_account_id        → NO ACTION   (이관 전 삭제를 막는 fail-closed)
--   community_posts.category_id            → NO ACTION   (코드 테이블)
--   community_comments.user_account_id     → NO ACTION
--   community_comments.post_id             → CASCADE
--   community_comments.parent_comment_id   → CASCADE     (DB 단 하드 삭제 전용 — 앱 삭제는 전파 안 함)
--   community_post_images.post_id          → CASCADE
--   community_comment_images.comment_id    → CASCADE
--   community_post_reactions.user_account_id    → SET NULL / .post_id → CASCADE
--   community_comment_reactions.user_account_id → SET NULL / .comment_id → CASCADE
--   community_reports.reporter_account_id  → SET NULL
--   community_categories.team_id           → NO ACTION
SELECT rc.TABLE_NAME, kcu.COLUMN_NAME, rc.CONSTRAINT_NAME, rc.DELETE_RULE, kcu.REFERENCED_TABLE_NAME
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME LIKE 'community\_%'
ORDER BY rc.TABLE_NAME, kcu.COLUMN_NAME;

-- (0-d) 반응·신고의 계정 컬럼이 NULL 허용인가(SET NULL 의 전제). IS_NULLABLE = 'NO' 면 Step 3 의 MODIFY 가 필요하다.
SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND (
    (TABLE_NAME = 'community_post_reactions' AND COLUMN_NAME = 'user_account_id') OR
    (TABLE_NAME = 'community_comment_reactions' AND COLUMN_NAME = 'user_account_id') OR
    (TABLE_NAME = 'community_reports' AND COLUMN_NAME = 'reporter_account_id'));

-- (0-e) 인기 목록 범위 스캔용 인덱스
SHOW INDEX FROM community_posts WHERE Key_name = 'idx_community_posts_created_at';


-- ============================================================================
-- Step 1. 선행 확인 — 중복이 있으면 UNIQUE 추가가 실패한다. 어느 행을 남길지는 사람이 판단한다
--   (반응은 (계정, 대상) 한 행이 정본이므로 보통 updated_at 이 가장 늦은 행을 남긴다 — 자동화하지 않는다).
-- ============================================================================
SELECT user_account_id, post_id, COUNT(*) AS cnt FROM community_post_reactions
GROUP BY user_account_id, post_id HAVING COUNT(*) > 1;

SELECT user_account_id, comment_id, COUNT(*) AS cnt FROM community_comment_reactions
GROUP BY user_account_id, comment_id HAVING COUNT(*) > 1;

SELECT reporter_account_id, target_type, target_id, COUNT(*) AS cnt FROM community_reports
GROUP BY reporter_account_id, target_type, target_id HAVING COUNT(*) > 1;

SELECT name, COUNT(*) AS cnt FROM community_categories GROUP BY name HAVING COUNT(*) > 1;


-- ============================================================================
-- Step 2. UNIQUE — Step 0-b 에서 빠진 것만. 이름을 반드시 명시한다(자동 생성명이면 "이미 걸렸는지"를 못 본다).
-- ============================================================================
ALTER TABLE community_categories
    ADD CONSTRAINT uk_community_categories_name UNIQUE (name);

ALTER TABLE community_post_reactions
    ADD CONSTRAINT uk_community_post_reactions_account_post UNIQUE (user_account_id, post_id);

ALTER TABLE community_comment_reactions
    ADD CONSTRAINT uk_community_comment_reactions_account_comment UNIQUE (user_account_id, comment_id);

ALTER TABLE community_reports
    ADD CONSTRAINT uk_community_reports_reporter_target UNIQUE (reporter_account_id, target_type, target_id);


-- ============================================================================
-- Step 3. 계정 FK 를 SET NULL 로 (반응 2 + 신고 1) — Step 0-c 의 DELETE_RULE 이 'SET NULL' 이 아닐 때만.
--   순서: FK 먼저 떼고 → 컬럼 NULL 허용 → FK 재생성. FK 이름은 환경마다 다르므로 손으로 적지 않고
--   information_schema 에서 찾아 동적으로 실행한다(migrate-quiz-like-account-set-null.sql 과 같은 방식 —
--   이름을 가정해 적었다가 1091 로 죽은 전례가 있다).
-- ============================================================================

-- (3-a) community_post_reactions.user_account_id
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_post_reactions'
      AND kcu.COLUMN_NAME = 'user_account_id' AND rc.DELETE_RULE <> 'SET NULL'
    LIMIT 1);
SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''3-a 건너뜀 — 이미 SET NULL 이거나 FK 없음'' AS note',
    CONCAT('ALTER TABLE community_post_reactions DROP FOREIGN KEY `', @fk_name, '`'));
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

ALTER TABLE community_post_reactions MODIFY COLUMN user_account_id BIGINT NULL;

SET @cnt := (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_post_reactions'
      AND kcu.COLUMN_NAME = 'user_account_id');
SET @stmt := IF(@cnt > 0,
    'SELECT ''3-a FK 재생성 건너뜀 — 계정 FK 가 이미 있다'' AS note',
    'ALTER TABLE community_post_reactions
        ADD CONSTRAINT fk_community_post_reactions_user_account FOREIGN KEY (user_account_id)
            REFERENCES users_account (id) ON DELETE SET NULL');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- (3-b) community_comment_reactions.user_account_id
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_comment_reactions'
      AND kcu.COLUMN_NAME = 'user_account_id' AND rc.DELETE_RULE <> 'SET NULL'
    LIMIT 1);
SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''3-b 건너뜀 — 이미 SET NULL 이거나 FK 없음'' AS note',
    CONCAT('ALTER TABLE community_comment_reactions DROP FOREIGN KEY `', @fk_name, '`'));
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

ALTER TABLE community_comment_reactions MODIFY COLUMN user_account_id BIGINT NULL;

SET @cnt := (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_comment_reactions'
      AND kcu.COLUMN_NAME = 'user_account_id');
SET @stmt := IF(@cnt > 0,
    'SELECT ''3-b FK 재생성 건너뜀 — 계정 FK 가 이미 있다'' AS note',
    'ALTER TABLE community_comment_reactions
        ADD CONSTRAINT fk_community_comment_reactions_user_account FOREIGN KEY (user_account_id)
            REFERENCES users_account (id) ON DELETE SET NULL');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- (3-c) community_reports.reporter_account_id
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_reports'
      AND kcu.COLUMN_NAME = 'reporter_account_id' AND rc.DELETE_RULE <> 'SET NULL'
    LIMIT 1);
SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''3-c 건너뜀 — 이미 SET NULL 이거나 FK 없음'' AS note',
    CONCAT('ALTER TABLE community_reports DROP FOREIGN KEY `', @fk_name, '`'));
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

ALTER TABLE community_reports MODIFY COLUMN reporter_account_id BIGINT NULL;

SET @cnt := (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_reports'
      AND kcu.COLUMN_NAME = 'reporter_account_id');
SET @stmt := IF(@cnt > 0,
    'SELECT ''3-c FK 재생성 건너뜀 — 계정 FK 가 이미 있다'' AS note',
    'ALTER TABLE community_reports
        ADD CONSTRAINT fk_community_reports_reporter_account FOREIGN KEY (reporter_account_id)
            REFERENCES users_account (id) ON DELETE SET NULL');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;


-- ============================================================================
-- Step 4. 작성자 FK 가 CASCADE 로 잘못 걸린 경우 — NO ACTION 으로 되돌린다(Step 0-c 에서 CASCADE 로 보일 때만).
--   CASCADE 그대로면 만료 데이터 정리의 이관 단계가 돌기 전에 계정 삭제가 글을 조용히 지운다.
-- ============================================================================
-- (4-a) community_posts.user_account_id
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_posts'
      AND kcu.COLUMN_NAME = 'user_account_id' AND rc.DELETE_RULE <> 'NO ACTION' AND rc.DELETE_RULE <> 'RESTRICT'
    LIMIT 1);
SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''4-a 건너뜀 — 이미 NO ACTION 이거나 FK 없음'' AS note',
    CONCAT('ALTER TABLE community_posts DROP FOREIGN KEY `', @fk_name, '`, ',
           'ADD CONSTRAINT fk_community_posts_user_account FOREIGN KEY (user_account_id) ',
           'REFERENCES users_account (id)'));
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- (4-b) community_comments.user_account_id
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'community_comments'
      AND kcu.COLUMN_NAME = 'user_account_id' AND rc.DELETE_RULE <> 'NO ACTION' AND rc.DELETE_RULE <> 'RESTRICT'
    LIMIT 1);
SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''4-b 건너뜀 — 이미 NO ACTION 이거나 FK 없음'' AS note',
    CONCAT('ALTER TABLE community_comments DROP FOREIGN KEY `', @fk_name, '`, ',
           'ADD CONSTRAINT fk_community_comments_user_account FOREIGN KEY (user_account_id) ',
           'REFERENCES users_account (id)'));
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;


-- ============================================================================
-- Step 5. 인덱스 — Step 0-e 가 비었을 때만
-- ============================================================================
ALTER TABLE community_posts ADD INDEX idx_community_posts_created_at (created_at);


-- ============================================================================
-- 검증 — Step 0-b/0-c/0-d/0-e 를 다시 돌려 기대값과 대조한다
-- ============================================================================
