-- ============================================================================
-- 캐릭터 꾸미기 시드 (dev·prod 공통, MySQL 8.0)
--
-- 테이블은 만들지 않는다 — user 앱이 ddl-auto=update 로 만들고, defer-datasource-initialization: true
-- 라 그쪽이 먼저 돈다. chat-init.sql 과 달리 CREATE TABLE 절이 없는 이유다.
--
-- 재실행 안전: 전부 INSERT ... SELECT ... WHERE NOT EXISTS 다.
--
-- ⚠ Step 4·5 는 백필이자 자가 치유 장치다. 가입 시 지급(DefaultCharacterGrantService)이 시드 부재로
--   건너뛰어진 계정도 다음 기동에 여기서 채워진다 — 그래서 그쪽이 가입을 막지 않고 넘어갈 수 있다.
--
-- ⚠ 이름 문자열('승리요정'·'기본 의상'·부위 3종)은 임의 값이 아니다. DefaultCharacterPolicy 가 id 가
--   아닌 이 이름으로 지급 대상을 찾으므로, 한쪽만 바꾸면 지급이 조용히 건너뛰어진다.
--
-- ⚠ EP(img·display_img·using_img)는 BaseURL 을 뺀 S3 오브젝트 키다. 절대 URL 을 넣지 말 것.
--   키 규칙의 단일 출처는 VictoryFairy_Infra/scripts/character-assets.tsv 이고, 어긋나면 상점에
--   깨진 이미지가 걸린다.
-- ============================================================================


-- ============================================================================
-- Step 1. characters — 유일한 캐릭터
-- ============================================================================

INSERT INTO characters (name, img, created_at, updated_at)
SELECT seed.name, seed.img, NOW(6), NOW(6)
FROM (
    SELECT '승리요정' AS name, 'characters/victory-fairy.svg' AS img
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM characters c WHERE c.name = seed.name);


-- ============================================================================
-- Step 2. item_types — 부위 코드(원본 에셋의 cloth/head/item 과 1:1)
--
-- ORDER BY 가 붙은 이유: 상점 목록이 부위 id 를 1차 정렬 키로 쓰므로 여기서 매겨지는 AUTO_INCREMENT
-- 가 곧 부위 진열 순서다. 빼면 순서가 실행 계획에 달려 환경마다 탭 순서가 달라진다.
-- ============================================================================

INSERT INTO item_types (name, created_at, updated_at)
SELECT seed.name, NOW(6), NOW(6)
FROM (
    SELECT 1 AS sort_no, '의상' AS name
    UNION ALL SELECT 2, '모자'
    UNION ALL SELECT 3, '소품'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM item_types t WHERE t.name = seed.name)
ORDER BY seed.sort_no;


-- ============================================================================
-- Step 3. character_items — 카탈로그 23행 (전 항목 100 포인트)
--
-- 이름으로 조인해 하드코딩 PK 를 피한다. ⚠ Step 1·2 가 먼저 돌지 않으면 조인이 0행을 매칭해
-- 에러 없이 아무것도 넣지 않는다 — 파일 안의 순서를 바꾸지 말 것.
--
-- display_img(상점 진열용 80x80)와 using_img(캐릭터에 겹치는 착용용 160x200)는 같은 그림이지만
-- 좌표계가 달라 한 컬럼으로 합칠 수 없다.
--
-- ⚠ 유니폼 11종의 이름이 구단명이 아니라 색상명인 것은 의도다(사용자 확정). 착용본 파일명(구단명)과의
--   대조는 파일명이 아니라 SVG fill 색상으로 맞춘 것이다 — character-assets.tsv 참고.
--
-- ORDER BY seed.sort_no 가 붙은 이유는 Step 2 와 같다(빼면 실제로 모자가 1번이 됐었다).
-- ============================================================================

INSERT INTO character_items (character_id, item_type_id, name, display_img, using_img, price, created_at, updated_at)
SELECT c.id, t.id, seed.name, seed.display_img, seed.using_img, 100, NOW(6), NOW(6)
FROM (
    SELECT 0 AS sort_no, '의상' AS type_name, '기본 의상' AS name, 'stores/cloth/basic.svg' AS display_img, 'items/cloth/basic.svg' AS using_img
    UNION ALL SELECT 1, '의상', '블랙 라인 유니폼', 'stores/cloth/uniform-blackline.svg', 'items/cloth/uniform-blackline.svg'
    UNION ALL SELECT 2, '의상', '블랙 스트라이프 유니폼', 'stores/cloth/uniform-blackstripe.svg', 'items/cloth/uniform-blackstripe.svg'
    UNION ALL SELECT 3, '의상', '블루 라인 유니폼', 'stores/cloth/uniform-blueline.svg', 'items/cloth/uniform-blueline.svg'
    UNION ALL SELECT 4, '의상', '블루 스트라이프 유니폼', 'stores/cloth/uniform-bluestripe.svg', 'items/cloth/uniform-bluestripe.svg'
    UNION ALL SELECT 5, '의상', '네이비 라인 유니폼', 'stores/cloth/uniform-navyline.svg', 'items/cloth/uniform-navyline.svg'
    UNION ALL SELECT 6, '의상', '오렌지 라인 유니폼', 'stores/cloth/uniform-orangeline.svg', 'items/cloth/uniform-orangeline.svg'
    UNION ALL SELECT 7, '의상', '레드 라인 유니폼', 'stores/cloth/uniform-redline.svg', 'items/cloth/uniform-redline.svg'
    UNION ALL SELECT 8, '의상', '레드 스트라이프 유니폼', 'stores/cloth/uniform-redstripe.svg', 'items/cloth/uniform-redstripe.svg'
    UNION ALL SELECT 9, '의상', '화이트 블루 라인 유니폼', 'stores/cloth/uniform-whiteblueline.svg', 'items/cloth/uniform-whiteblueline.svg'
    UNION ALL SELECT 10, '의상', '화이트 와인 라인 유니폼', 'stores/cloth/uniform-whitewineline.svg', 'items/cloth/uniform-whitewineline.svg'
    UNION ALL SELECT 11, '모자', '블루 캡', 'stores/head/cap-blue.svg', 'items/head/cap-blue.svg'
    UNION ALL SELECT 12, '모자', '레드 캡', 'stores/head/cap-red.svg', 'items/head/cap-red.svg'
    UNION ALL SELECT 13, '모자', '옐로우 캡', 'stores/head/cap-yellow.svg', 'items/head/cap-yellow.svg'
    UNION ALL SELECT 14, '모자', '블랙 헬멧', 'stores/head/helmet-black.svg', 'items/head/helmet-black.svg'
    UNION ALL SELECT 15, '모자', '블루 헬멧', 'stores/head/helmet-blue.svg', 'items/head/helmet-blue.svg'
    UNION ALL SELECT 16, '모자', '레드 헬멧', 'stores/head/helmet-red.svg', 'items/head/helmet-red.svg'
    UNION ALL SELECT 17, '소품', '야구공', 'stores/item/ball.svg', 'items/item/ball.svg'
    UNION ALL SELECT 18, '소품', '응원 풍선', 'stores/item/balloon.svg', 'items/item/balloon.svg'
    UNION ALL SELECT 19, '소품', '야구 배트', 'stores/item/bat.svg', 'items/item/bat.svg'
    UNION ALL SELECT 20, '소품', '글러브', 'stores/item/glove.svg', 'items/item/glove.svg'
    UNION ALL SELECT 21, '소품', '메가폰', 'stores/item/megaphone.svg', 'items/item/megaphone.svg'
    UNION ALL SELECT 22, '소품', '응원봉', 'stores/item/peak.svg', 'items/item/peak.svg'
) AS seed
JOIN characters c ON c.name = '승리요정'
JOIN item_types t ON t.name = seed.type_name
WHERE NOT EXISTS (
    SELECT 1 FROM character_items ci WHERE ci.character_id = c.id AND ci.name = seed.name
)
ORDER BY seed.sort_no;


-- ============================================================================
-- Step 4. user_characters_inventory 백필 — 기존 전 계정에 '승리요정'을 켜진 채로 지급
--
-- 탈퇴 계정도 거르지 않는다 — 거르면 "전 행에 하나씩"에 예외가 생기고, 어차피 탈퇴 계정은 이 데이터를
-- 읽는 경로가 없다.
--
-- ⚠ active 를 무조건 1 로 두는 근거는 캐릭터가 하나뿐이라는 것이다. 캐릭터가 둘 이상이 되는 순간
--   이 단계는 "이미 켜진 캐릭터가 있으면 0" 으로 바뀌어야 한다(Step 5 가 그 모양이다).
-- ============================================================================

INSERT INTO user_characters_inventory (user_account_id, character_id, active, created_at, updated_at)
SELECT ua.id, c.id, 1, NOW(6), NOW(6)
FROM users_account ua
CROSS JOIN characters c
WHERE c.name = '승리요정'
  AND NOT EXISTS (
      SELECT 1 FROM user_characters_inventory i
      WHERE i.user_account_id = ua.id AND i.character_id = c.id
  );


-- ============================================================================
-- Step 5. user_character_items_inventory 백필 — 기존 전 계정에 '기본 의상' 지급
--
-- 이미 같은 부위(의상)를 착용 중인 계정에는 꺼진 채로 넣는다. 무조건 1 로 넣으면 그 계정은 의상
-- 두 벌이 동시에 켜진 상태가 되어, 토글 API 가 전제하는 "부위당 하나"가 백필 때문에 깨진다.
-- (정상 경로에서는 거의 생기지 않지만, 지급이 건너뛰어진 계정이 다른 의상을 먼저 산 경우가 있다.)
-- ============================================================================

INSERT INTO user_character_items_inventory (user_account_id, character_item_id, active, created_at, updated_at)
SELECT ua.id, ci.id,
       CASE WHEN EXISTS (
           SELECT 1 FROM user_character_items_inventory x
           JOIN character_items xc ON xc.id = x.character_item_id
           WHERE x.user_account_id = ua.id AND x.active = 1 AND xc.item_type_id = ci.item_type_id
       ) THEN 0 ELSE 1 END,
       NOW(6), NOW(6)
FROM users_account ua
CROSS JOIN character_items ci
WHERE ci.name = '기본 의상'
  AND NOT EXISTS (
      SELECT 1 FROM user_character_items_inventory i
      WHERE i.user_account_id = ua.id AND i.character_item_id = ci.id
  );


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 카탈로그가 다 들어갔는지
--    SELECT COUNT(*) FROM characters;        -- 기대: 1
--    SELECT COUNT(*) FROM item_types;        -- 기대: 3
--    SELECT COUNT(*) FROM character_items;   -- 기대: 23
--    SELECT t.name, COUNT(*) FROM character_items ci JOIN item_types t ON t.id = ci.item_type_id
--     GROUP BY t.name;                       -- 기대: 의상 11 / 모자 6 / 소품 6
--
-- 2) 백필이 전 계정을 덮었는지 (두 쿼리 모두 0행이어야 한다)
--    SELECT ua.id FROM users_account ua
--     WHERE NOT EXISTS (SELECT 1 FROM user_characters_inventory i WHERE i.user_account_id = ua.id);
--    SELECT ua.id FROM users_account ua
--     WHERE NOT EXISTS (SELECT 1 FROM user_character_items_inventory i WHERE i.user_account_id = ua.id);
--
-- 3) 부위당 하나가 지켜지는지 (0행이어야 한다)
--    SELECT x.user_account_id, xc.item_type_id, COUNT(*)
--      FROM user_character_items_inventory x JOIN character_items xc ON xc.id = x.character_item_id
--     WHERE x.active = 1 GROUP BY 1, 2 HAVING COUNT(*) > 1;
-- ============================================================================
