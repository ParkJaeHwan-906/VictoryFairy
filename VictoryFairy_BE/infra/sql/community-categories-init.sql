-- ============================================================================
-- community_categories 시드 (MySQL 8.0) — 구단 10 + 자유게시판 = 11행
--
-- 적용은 사람이 아니라 앱이 한다 — `user` 앱의 dev/prod 프로파일이 `spring.sql.init.data-locations`
-- 로 매 기동 실행한다(teams-init.sql · chat-init.sql 등과 같은 방식).
--
-- ⚠ 순서: teams-init.sql 뒤여야 한다. 구단 카테고리의 team_id 를 teams.code(LG, OB …)로 해석해
--   채우므로 teams 가 0행이면 구단 카테고리 10행이 에러 없이 0행만 들어가고 끝난다.
--   환경마다 다른 AUTO_INCREMENT id 를 박아 넣지 않는 이유가 이것이다(DefaultCharacterPolicy 가 id
--   대신 이름을 쓰는 것과 같은 이유).
-- ⚠ Hibernate 가 community_categories 를 만든 뒤에 돌아야 한다 — 두 프로파일 모두
--   spring.jpa.defer-datasource-initialization: true 가 켜져 있어 그 순서가 보장된다.
--
-- 재실행 안전: name 기준 anti-join 이라 이미 있는 카테고리는 건너뛴다. 기존 행의 id·sort_order 를
--   재부여하지 않는다(= 게시글 FK 를 건드리지 않는다). 동시 기동의 중복 INSERT 는
--   uk_community_categories_name 이 막는다(quiz_type 과 같은 거동 — 재시작하면 자가 치유).
--
-- 표기: 구단 카테고리의 name 은 teams.name 그대로다(GET /api/teams 의 name 과 같은 값이어야 한다는
--   계약). 자유게시판은 team_id NULL, sort_order 11 로 구단 뒤에 둔다.
-- ============================================================================

-- Step 1. 구단 카테고리 10행 — sort_order 는 teams-init.sql 의 나열 순서와 같다
INSERT INTO community_categories (name, team_id, sort_order, created_at, updated_at)
SELECT t.name, t.id, seed.sort_order, NOW(6), NOW(6)
FROM (
    SELECT 'OB' AS code, 1 AS sort_order
    UNION ALL SELECT 'LG', 2
    UNION ALL SELECT 'SS', 3
    UNION ALL SELECT 'KT', 4
    UNION ALL SELECT 'WO', 5
    UNION ALL SELECT 'HT', 6
    UNION ALL SELECT 'HH', 7
    UNION ALL SELECT 'NC', 8
    UNION ALL SELECT 'LT', 9
    UNION ALL SELECT 'SK', 10
) AS seed
JOIN teams t ON t.code = seed.code
WHERE NOT EXISTS (SELECT 1 FROM community_categories c WHERE c.name = t.name);

-- Step 2. 자유게시판 1행 — 구단과 무관(team_id NULL)
INSERT INTO community_categories (name, team_id, sort_order, created_at, updated_at)
SELECT '자유게시판', NULL, 11, NOW(6), NOW(6)
WHERE NOT EXISTS (SELECT 1 FROM community_categories c WHERE c.name = '자유게시판');


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 11행이 다 있는지 (10행이면 teams 가 비어 있던 것 — teams-init 뒤 재기동하면 채워진다)
--    SELECT COUNT(*) FROM community_categories;                                 -- 기대: 11
--    SELECT id, name, team_id, sort_order FROM community_categories ORDER BY sort_order;
--
-- 2) 구단 카테고리 name 이 teams.name 과 일치하는지
--    SELECT c.name, t.name FROM community_categories c JOIN teams t ON t.id = c.team_id
--    WHERE c.name <> t.name;                                                    -- 기대: 0행
--
-- 3) UNIQUE 가 실제로 걸렸는지
--    SHOW INDEX FROM community_categories WHERE Key_name = 'uk_community_categories_name';
-- ============================================================================
