-- ============================================================================
-- quizzes_like 계정 FK 를 ON DELETE SET NULL 로 바꾼다 (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상 환경: devdb + 운영 DB **양쪽 모두.** 두 곳 다 적용해야 한다.
--
-- 왜 필요한가: 만료 데이터 정리 스케줄러
--   (docs/requirements/user/expired-data-cleanup.md, USER-EDC-46~49)가 탈퇴 30일이 지난 계정을
--   **실제로 지우기 시작한다.** 지금 이 FK 는 ON DELETE CASCADE 라, 계정을 지우는 순간 그 사람이
--   누른 좋아요 행이 함께 사라진다 — 문제별 추천 수(liked = true 행 수)가 **조용히 줄어들고,
--   되돌릴 수 없다.** 추천 수에는 "누가 눌렀는지"가 들어가지 않으므로, 계정 정체성만 비우고
--   (user_account_id = NULL) 행은 남기는 것이 데이터 의미와도 맞는다.
--
-- 왜 UNIQUE 가 문제되지 않는가: MySQL 의 UNIQUE 는 NULL 을 서로 다른 값으로 본다.
--   uk_quizzes_like_account_quiz(user_account_id, quiz_id) 가 그대로 있어도
--   (NULL, 같은 quiz_id) 행은 몇 개든 공존한다(devdb 실측). 즉 탈퇴자가 몇 명이든 추천 수 손실이 0 이다.
--   ↳ 더미 계정으로 '이관'하는 방식은 이 UNIQUE 때문에 한 명분만 남아 카운트가 깎인다. 그래서
--     채팅방·채팅(이관)과 달리 좋아요만 SET NULL 로 가른다 — 가르는 기준은 "그 데이터를 읽는 코드가
--     계정을 역참조해 사람 이름을 표시하는가"이며, 좋아요는 아니다(QuizLikeRepository 4개 전수 확인).
--
-- ⚠ **앱 배포보다 이 파일이 먼저다.** ddl-auto=update 는 (a) 기존 컬럼의 NOT NULL 을 완화하지 않고
--   (b) 기존 FK 의 삭제 규칙도 바꾸지 않는다. 즉 엔티티 매핑만 SET NULL 로 바꿔 배포하면 코드와 DB 가
--   어긋난 채 CASCADE 로 계속 돈다. 스케줄러에 선행 검사(information_schema 조회)가 있어 미적용
--   상태에서는 계정 삭제 단계를 스스로 멈추지만, 그건 안전장치일 뿐 적용을 대신하지 않는다.
--
-- ⚠ 이 파일을 spring.sql.init.data-locations 에 넣지 말 것(migrate-*.sql 공통 규칙).
--   재실행하면 "Duplicate foreign key constraint name" 등으로 죽어 모든 파드가 기동 실패한다.
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 현재 상태 확인 — 여기 결과로 아래 각 Step 을 적용할지가 갈린다
-- ============================================================================
-- (0-a) 테이블이 있는가. 0행이면 이 파일은 대상이 아니다 — 아직 테이블이 없는 환경은 user 앱
--       (ddl-auto=update)이 기동하면서 엔티티 선언대로 nullable + SET NULL 로 만들어 준다.
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes_like';

-- (0-b) 컬럼 nullability. IS_NULLABLE = 'NO' 이면 Step 2 가 필요하다.
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes_like'
  AND COLUMN_NAME = 'user_account_id';

-- (0-c) **FK 의 실제 이름과 현재 삭제 규칙.** 확인용이며, 손으로 치환할 곳은 없다 —
--       Step 1·3 이 이 조회를 스스로 해서 이름을 찾는다(이름을 가정해 적었다가 1091 로 죽은 적이 있다).
--       DELETE_RULE 이 이미 'SET NULL' 이면 이 파일은 이미 적용된 것이고, 그대로 다시 돌려도
--       Step 1·3 이 모두 건너뛴다(안전).
SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE, kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quizzes_like';

-- (0-d) 참고용 전체 정의(컬럼 타입·인덱스·FK 를 한눈에).
SHOW CREATE TABLE quizzes_like;


-- ============================================================================
-- Step 1. 기존 계정 FK 제거
--   순서 주의: **FK 를 먼저 떼고 컬럼을 고친다.** FK 가 걸린 컬럼을 그대로 MODIFY 하면 MySQL 이
--   1832(Cannot change column ... used in a foreign key constraint)로 거부할 수 있다.
--   FK 를 떼도 그 FK 가 쓰던 인덱스는 남으므로(Step 3 이 다시 쓴다) 인덱스를 새로 만들 필요는 없다.
--
--   FK 이름을 **손으로 적지 않는다.** 환경마다 이름이 다르기 때문이다 — Hibernate 가 만든 devdb 는
--   FKfyl3b9pbew3iy58tyfabr605n 이고, 다른 환경은 또 다른 자동 생성명일 수 있다. 이름을 가정해
--   적으면 1091(Can't DROP)로 죽는다(실제로 그렇게 한 번 죽었다). 그래서 information_schema 에서
--   이름을 찾아 동적으로 실행한다.
--
--   조건에 DELETE_RULE <> 'SET NULL' 이 들어간 것이 재실행 안전장치다 — 이미 적용된 DB 에서
--   다시 돌리면 @fk_name 이 NULL 이 되어 DROP 을 건너뛴다. quiz_id 축 FK 를 잡지 않도록
--   COLUMN_NAME 조건도 반드시 함께 걸어야 한다(그쪽 CASCADE 는 유지 대상이다).
-- ============================================================================
SET @fk_name := (
    SELECT rc.CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
     AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
      AND rc.TABLE_NAME = 'quizzes_like'
      AND kcu.COLUMN_NAME = 'user_account_id'
      AND rc.DELETE_RULE <> 'SET NULL'
    LIMIT 1
);

SET @stmt := IF(@fk_name IS NULL,
    'SELECT ''Step 1 건너뜀 — 계정 FK 가 없거나 이미 SET NULL 이다'' AS note',
    CONCAT('ALTER TABLE quizzes_like DROP FOREIGN KEY `', @fk_name, '`'));

PREPARE stmt_drop_fk FROM @stmt;
EXECUTE stmt_drop_fk;
DEALLOCATE PREPARE stmt_drop_fk;


-- ============================================================================
-- Step 2. 컬럼을 NULL 허용으로
--   ⚠ 타입(BIGINT)을 Step 0-b/0-d 에서 확인한 값 그대로 다시 적어야 한다. MODIFY 는 전체 정의를
--     덮어쓰므로, 빠뜨린 속성은 사라진다.
--   기존 행에는 아무 영향이 없다(NOT NULL → NULL 완화는 데이터를 건드리지 않는다).
-- ============================================================================
ALTER TABLE quizzes_like MODIFY COLUMN user_account_id BIGINT NULL;


-- ============================================================================
-- Step 3. FK 재생성 — ON DELETE SET NULL
--   이름은 fk_<테이블>_<대상> 형태로 되돌린다(자동 생성명이었다면 이 기회에 정규화된다).
--   quiz_id 쪽 FK 는 **건드리지 않는다** — 문제가 사라지면 그 문제의 좋아요도 사라져야 하므로
--   그쪽은 CASCADE 가 맞다. 바뀌는 것은 계정 축뿐이다.
--
--   Step 1 과 같은 이유로 존재 여부를 먼저 보고 실행한다 — 이미 계정 FK 가 있는 DB 에서 그냥
--   ADD CONSTRAINT 를 하면 1826(Duplicate foreign key constraint name)으로 죽는다.
-- ============================================================================
SET @account_fk_count := (
    SELECT COUNT(*)
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    JOIN information_schema.KEY_COLUMN_USAGE kcu
      ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
     AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
      AND rc.TABLE_NAME = 'quizzes_like'
      AND kcu.COLUMN_NAME = 'user_account_id'
);

SET @stmt := IF(@account_fk_count > 0,
    'SELECT ''Step 3 건너뜀 — 계정 FK 가 이미 있다(Step 1 의 DROP 이 건너뛰어졌다는 뜻)'' AS note',
    'ALTER TABLE quizzes_like
        ADD CONSTRAINT fk_quizzes_like_user_account FOREIGN KEY (user_account_id)
            REFERENCES users_account (id) ON DELETE SET NULL');

PREPARE stmt_add_fk FROM @stmt;
EXECUTE stmt_add_fk;
DEALLOCATE PREPARE stmt_add_fk;


-- ============================================================================
-- 검증 — 아래 셋이 모두 기대값이어야 스케줄러의 선행 검사가 통과한다
-- ============================================================================
-- 1) 계정 FK 의 DELETE_RULE = 'SET NULL' (스케줄러가 실제로 조회하는 바로 그 값)
SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quizzes_like'
  AND kcu.COLUMN_NAME = 'user_account_id';

-- 2) 컬럼 IS_NULLABLE = 'YES'
SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes_like'
  AND COLUMN_NAME = 'user_account_id';

-- 3) UNIQUE 가 그대로 살아 있는지(이 마이그레이션은 UNIQUE 를 건드리지 않는다).
--    남아 있어야 정상이다 — NULL 은 서로 다른 값으로 취급되므로 추천 수 보존과 충돌하지 않는다.
SHOW INDEX FROM quizzes_like WHERE Key_name = 'uk_quizzes_like_account_quiz';
