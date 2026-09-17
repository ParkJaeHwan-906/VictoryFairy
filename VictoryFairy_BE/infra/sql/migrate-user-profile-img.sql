-- ============================================================================
-- 프로필 이미지 지원 — users_account.profile_img_url 신설 (1회성, MySQL 8.0 — 수동 실행)
--
-- ⚠⚠ 이 DDL 은 **앱 배포보다 먼저**다. 순서를 지키지 않으면 quiz 앱의 모든 인증 요청이 500 이 된다.
--     users_account 는 user·quiz 두 앱이 공유하는 테이블인데, prod ddl-auto 가 user=update 이고
--     quiz=none 이다. 즉 컬럼 없이 배포하면 user 는 기동하면서 스스로 컬럼을 만들어 멀쩡해 보이지만,
--     quiz 는 만들지 않은 채 UserAccount 엔티티를 매핑한다(공유 JwtAuthenticationFilter·
--     UserAccountRepository 경로) → Unknown column 'profile_img_url'.
--     같은 함정을 nickname_changed_at·password_changed_epoch_second 에서 이미 두 번 밟았다.
--     "user 가 알아서 만든다"는 관측은 사실이지만, 그게 안전하다는 뜻이 아니다 — 깨지는 쪽은 quiz 다.
--
-- 무엇을/왜: 프로필 이미지의 **EP(오브젝트 키)** 를 담는다. 값은 `user-profile-img/{uuid}.jpg` 처럼
--   BaseURL(버킷 공개 도메인·CDN)을 뺀 나머지이며 **전체 URL 이 아니다** — 도메인이 바뀌는 날
--   저장된 값이 전부 죽은 링크가 되어 DB 를 통째로 UPDATE 하는 상황을 만들지 않기 위해서다.
--   길이 255 는 그 전제(접두사 없음) 위의 값이라, 전체 URL 을 넣기 시작하면 길이도 함께 무너진다.
--
-- 대상: **users_account 가 이미 존재하는 모든 환경**(prod · devdb · 로컬).
--   테이블이 아직 없는 진짜 신규 환경은 불필요하다(user 앱이 엔티티 선언대로 컬럼까지 생성한다).
--
-- 백필하지 않는다: 기존 계정은 전부 NULL 로 남고 그게 정상 상태다(이미지 없음). DEFAULT 를 두지
--   않는 이유도 같다 — "값이 아직 없음"과 구분해야 할 다른 상태가 없다.
--
-- 인덱스·UNIQUE 없음: 이 컬럼으로 조회하는 경로가 없다(계정을 찾은 뒤 딸려 읽히기만 한다).
--   같은 이미지를 두 계정이 가리키는 것도 스키마가 막을 일이 아니다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙).
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 현재 상태 확인 — 0행이면 아직 컬럼이 없다(Step 1 이 실제로 ALTER 를 돈다).
--   이미 있으면(예: user 앱이 먼저 떠서 ddl-auto=update 가 만든 환경) Step 1 은 스스로 건너뛴다.
-- ============================================================================
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users_account'
  AND COLUMN_NAME = 'profile_img_url';


-- ============================================================================
-- Step 1. 컬럼 추가 — VARCHAR(255) NULL, 기본값 없음.
--   NOT NULL 로는 애초에 추가할 수 없다(기존 행이 있다). 그리고 NULL 이 곧 "이미지 없음"이라
--   나중에도 NOT NULL 로 조일 이유가 없다.
--
--   ⚠ `ADD COLUMN` 을 그냥 적으면 재실행 시 1060(Duplicate column name)으로 죽는다. 이 파일은
--   컬럼이 이미 있는 환경(위 Step 0 참고)에서도 그대로 다시 돌 수 있어야 하므로 존재 여부를 보고
--   동적으로 실행한다 — 다른 migrate-*.sql 의 "Step 0 을 눈으로 보고 건너뛴다"보다 한 단계 강하다.
-- ============================================================================
SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users_account'
      AND COLUMN_NAME = 'profile_img_url'
);

SET @stmt := IF(@column_exists > 0,
    'SELECT ''Step 1 건너뜀 — profile_img_url 이 이미 있다'' AS note',
    'ALTER TABLE users_account ADD COLUMN profile_img_url VARCHAR(255) NULL');

PREPARE stmt_add_profile_img FROM @stmt;
EXECUTE stmt_add_profile_img;
DEALLOCATE PREPARE stmt_add_profile_img;


-- ============================================================================
-- 검증
--   ① 컬럼: Type = varchar(255) · Null = YES · Default = NULL
--   ② 기존 행은 전부 NULL 이어야 한다(백필하지 않았다는 확인 — rows_with_image = 0)
--   ③ 인덱스: profile_img_url 을 포함한 인덱스가 0행이어야 한다(의도적으로 두지 않았다)
-- ============================================================================
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users_account'
  AND COLUMN_NAME = 'profile_img_url';

SELECT COUNT(*) AS total_rows, COUNT(profile_img_url) AS rows_with_image FROM users_account;

SHOW INDEX FROM users_account WHERE Column_name = 'profile_img_url';
