-- 사용자 정의 퀴즈방 7테이블 선행 DDL (1회성, MySQL 8.0 — 수동 실행)
-- 계약: docs/requirements/quiz/custom-quiz-room.md
--
-- ⚠ 이 파일은 spring.sql.init 으로 자동 실행되지 않는다. 손으로 적용하는 마이그레이션 기록이다(migrate-*.sql 공통 규칙).
-- ⚠ quiz 앱은 prod ddl-auto=none 이라 이 테이블을 스스로 만들지 않는다 — 테이블이 없으면 /rt/quiz-rooms/** 전체가 500 이다.
--    길은 둘이다: (a) quiz 배포 전에 이 파일을 적용하거나, (b) ddl-auto=update 인 user 앱을 먼저 띄워 엔티티 선언대로
--    만들게 둔다. 어느 쪽이든 결과가 같도록 제약 이름을 엔티티(@ForeignKey/@UniqueConstraint)와 문자 그대로 맞췄다 —
--    이름이 다르면 다음 user 기동의 ddl-auto=update 가 같은 FK 를 하나 더 붙인다.
-- ⚠ user 앱 만료 데이터 정리(AccountFkDeleteRuleInspector)가 quiz_room_likes·quiz_room_answers·quiz_room_weekly_bests 의 계정 FK 가
--    SET NULL 인지 회차마다 확인한다. 테이블이 없으면 fail-closed 로 계정 삭제 단계가 통째로 멈춘다 —
--    user 앱을 이 테이블 없는 DB 에 먼저 배포하지 말 것(update 로 user 가 직접 만들면 문제없다).
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- 재실행해도 안전하다(IF NOT EXISTS). 다만 이미 있는 테이블의 제약은 고치지 않는다 — Step 0 결과로 판단할 것.


-- ============================================================================
-- Step 0. 환경 판단 — 0행이면 아래 Step 1 을 그대로 적용한다
-- ============================================================================
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (
    'quiz_rooms', 'quiz_room_quizzes', 'quiz_room_quiz_options', 'quiz_room_answers', 'quiz_room_likes',
    'quiz_room_weeks', 'quiz_room_weekly_bests');


-- ============================================================================
-- Step 1. 테이블 생성 — 부모 → 자식 순서
-- ============================================================================

-- 작성자 FK 는 NO ACTION(삭제 규칙 미지정): 계정 하드 삭제 전에 (알수없음) 계정으로 이관하는 것이 규약이고,
-- 이관을 빠뜨리면 조용히 방을 지우는 대신 DELETE FROM users 가 실패한다(community_posts 와 같은 fail-closed).
CREATE TABLE IF NOT EXISTS `quiz_rooms` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `author_account_id` BIGINT       NOT NULL,
    `title`             VARCHAR(50)  NOT NULL,
    `description`       VARCHAR(500) NOT NULL,
    `quiz_count`        INT          NOT NULL,
    `like_count`        BIGINT       NOT NULL DEFAULT 0,
    `created_at`        DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_quiz_rooms_author` FOREIGN KEY (`author_account_id`)
        REFERENCES `users_account` (`id`)
) ENGINE=InnoDB;

-- type: 0=객관식, 1=O/X (QuizRoomQuizType ORDINAL — 선언 순서가 곧 값이다)
CREATE TABLE IF NOT EXISTS `quiz_room_quizzes` (
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `room_id`  BIGINT       NOT NULL,
    `type`     TINYINT      NOT NULL,
    `question` VARCHAR(200) NOT NULL,
    `answer`   TINYINT      NOT NULL,
    PRIMARY KEY (`id`),
    -- Hibernate 가 ORDINAL enum 에 붙이는 CHECK 와 맞춘다(ddl-auto 로 만든 테이블과 차이를 없애려고)
    CHECK (`type` BETWEEN 0 AND 1),
    CONSTRAINT `fk_quiz_room_quizzes_room` FOREIGN KEY (`room_id`)
        REFERENCES `quiz_rooms` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `quiz_room_quiz_options` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `quiz_id`     BIGINT       NOT NULL,
    `option_no`   TINYINT      NOT NULL,
    `option_text` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_quiz_room_quiz_options_quiz_no` UNIQUE (`quiz_id`, `option_no`),
    CONSTRAINT `fk_quiz_room_quiz_options_quiz` FOREIGN KEY (`quiz_id`)
        REFERENCES `quiz_room_quizzes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 계정 FK SET NULL: 계정 하드 삭제 뒤에도 답 행이 남아 투표 분포가 보존된다(QUIZ-CQR-123).
-- UNIQUE(user_account_id, quiz_id) 가 같은 계정의 같은 문항 중복 답을 막는다. NULL 은 서로 달라 탈퇴자끼리 충돌하지 않는다.
CREATE TABLE IF NOT EXISTS `quiz_room_answers` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_account_id` BIGINT      NULL,
    `room_id`         BIGINT      NOT NULL,
    `quiz_id`         BIGINT      NOT NULL,
    `option_no`       TINYINT     NOT NULL,
    `created_at`      DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_quiz_room_answers_account_quiz` UNIQUE (`user_account_id`, `quiz_id`),
    -- 세 인덱스 모두 커버링. 앞의 둘은 room_id·quiz_id FK 자동 인덱스 자리를 대신한다(선행 컬럼이 FK 컬럼이라 따로 안 생긴다)
    INDEX `idx_quiz_room_answers_room_quiz_option` (`room_id`, `quiz_id`, `option_no`),
    INDEX `idx_quiz_room_answers_quiz_option` (`quiz_id`, `option_no`),
    INDEX `idx_quiz_room_answers_account_room` (`user_account_id`, `room_id`),
    CONSTRAINT `fk_quiz_room_answers_account` FOREIGN KEY (`user_account_id`)
        REFERENCES `users_account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_quiz_room_answers_room` FOREIGN KEY (`room_id`)
        REFERENCES `quiz_rooms` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_quiz_room_answers_quiz` FOREIGN KEY (`quiz_id`)
        REFERENCES `quiz_room_quizzes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 계정 FK SET NULL: 하드 삭제 뒤에도 행이 남아 like_count 카운터와 행 수가 어긋나지 않는다(QUIZ-CQR-122).
CREATE TABLE IF NOT EXISTS `quiz_room_likes` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_account_id` BIGINT      NULL,
    `room_id`         BIGINT      NOT NULL,
    `created_at`      DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_quiz_room_likes_account_room` UNIQUE (`user_account_id`, `room_id`),
    CONSTRAINT `fk_quiz_room_likes_account` FOREIGN KEY (`user_account_id`)
        REFERENCES `users_account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_quiz_room_likes_room` FOREIGN KEY (`room_id`)
        REFERENCES `quiz_rooms` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 주간 베스트를 확정한 주(베스트 0건인 주도 행이 생긴다). week_start = 그 주 월요일(KST).
-- uk_quiz_room_weeks_week_start 가 다중 파드 동시 확정의 심판이다 — 늦은 쪽은 이 UNIQUE 위반으로 지급까지 롤백된다.
CREATE TABLE IF NOT EXISTS `quiz_room_weeks` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `week_start`   DATE        NOT NULL,
    `confirmed_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_quiz_room_weeks_week_start` UNIQUE (`week_start`)
) ENGINE=InnoDB;

-- 확정 순위 + 보상 지급 기록. 지급하지 않은 순위(소유자 탈퇴·(알수없음))는 rewarded_account_id NULL, reward_point 0.
-- (주, 방) UNIQUE 가 "한 방에 한 주 한 번" 지급을, (주, 순위) UNIQUE 가 같은 주에 확정 결과가 섞이지 않음을 DB 에서 막는다.
-- 계정 FK SET NULL: 하드 삭제 뒤에도 순위가 남는다(지급은 회수하지 않는다).
CREATE TABLE IF NOT EXISTS `quiz_room_weekly_bests` (
    `id`                  BIGINT  NOT NULL AUTO_INCREMENT,
    `week_id`             BIGINT  NOT NULL,
    `rank_no`             TINYINT NOT NULL,
    `room_id`             BIGINT  NOT NULL,
    `score`               BIGINT  NOT NULL,
    `rewarded_account_id` BIGINT  NULL,
    `reward_point`        INT     NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_quiz_room_weekly_bests_week_room` UNIQUE (`week_id`, `room_id`),
    CONSTRAINT `uk_quiz_room_weekly_bests_week_rank` UNIQUE (`week_id`, `rank_no`),
    CONSTRAINT `fk_quiz_room_weekly_bests_week` FOREIGN KEY (`week_id`)
        REFERENCES `quiz_room_weeks` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_quiz_room_weekly_bests_room` FOREIGN KEY (`room_id`)
        REFERENCES `quiz_rooms` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_quiz_room_weekly_bests_rewarded_account` FOREIGN KEY (`rewarded_account_id`)
        REFERENCES `users_account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB;


-- ============================================================================
-- Step 1-b. Step 0 에서 테이블이 이미 있었던 환경만 — 위 CREATE 가 건너뛰어 인덱스가 없을 수 있다
--   먼저 확인하고, 결과에 없는 이름만 아래에서 골라 실행한다(MySQL 8.0 은 CREATE INDEX IF NOT EXISTS 가 없다).
--   기존 room_id·quiz_id FK 자동 인덱스(fk_quiz_room_answers_room / _quiz)는 새 인덱스가 생기면 MySQL 이 스스로 지운다.
--   (user 앱 ddl-auto=update 도 같은 이름으로 만들므로, user 가 이미 기동했다면 0건이 정상이다)
-- ============================================================================
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('quiz_rooms', 'quiz_room_answers')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

-- ALTER TABLE `quiz_room_answers` ADD INDEX `idx_quiz_room_answers_room_quiz_option` (`room_id`, `quiz_id`, `option_no`);
-- ALTER TABLE `quiz_room_answers` ADD INDEX `idx_quiz_room_answers_quiz_option` (`quiz_id`, `option_no`);
-- ALTER TABLE `quiz_room_answers` ADD INDEX `idx_quiz_room_answers_account_room` (`user_account_id`, `room_id`);


-- ============================================================================
-- Step 2. 적용 확인 — 기대값:
--   quiz_rooms.author_account_id              → NO ACTION
--   quiz_room_quizzes.room_id                 → CASCADE
--   quiz_room_quiz_options.quiz_id            → CASCADE
--   quiz_room_answers.user_account_id         → SET NULL / .room_id → CASCADE / .quiz_id → CASCADE
--   quiz_room_likes.user_account_id           → SET NULL / .room_id → CASCADE
--   quiz_room_weekly_bests.rewarded_account_id → SET NULL / .room_id → CASCADE / .week_id → CASCADE
-- ============================================================================
SELECT rc.TABLE_NAME, kcu.COLUMN_NAME, rc.CONSTRAINT_NAME, rc.DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME LIKE 'quiz\_room%'
ORDER BY rc.TABLE_NAME, kcu.COLUMN_NAME;


-- ============================================================================
-- Step 3. 이 파일의 이전 판(인기 top5 실시간 계산 시절)을 이미 적용한 환경만 — 폐기된 인덱스 제거
--   인기 목록이 주간 확정으로 바뀌어 like_count 정렬 쿼리가 사라졌다. 아래 SELECT 가 1행일 때만 DROP 을 실행한다.
--   (user 앱 ddl-auto=update 는 엔티티에서 빠진 인덱스를 지우지 않는다 — 손으로 지워야 남지 않는다)
-- ============================================================================
SELECT INDEX_NAME FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_rooms' AND INDEX_NAME = 'idx_quiz_rooms_like_count'
GROUP BY INDEX_NAME;

-- ALTER TABLE `quiz_rooms` DROP INDEX `idx_quiz_rooms_like_count`;
