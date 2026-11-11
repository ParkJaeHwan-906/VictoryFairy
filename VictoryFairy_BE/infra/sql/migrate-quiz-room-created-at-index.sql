-- ============================================================================
-- 퀴즈방 주간 집계용 created_at 인덱스 2개 (1회성, MySQL 8.0 — prod/devdb 수동 실행)
--   quiz_room_likes   : idx_quiz_room_likes_created_at   (created_at)
--   quiz_room_answers : idx_quiz_room_answers_created_at (created_at)
--
-- 왜: 인기 목록(GET /rt/quiz-rooms/popular)이 캐시 없이 요청마다 이번 주 실시간 순위를 내며
--   QuizRoomLikeRepository.countWeeklyLikesByRoom · QuizRoomAnswerRepository.countWeeklyNewSolversByRoom 을
--   돌린다. 둘 다 "created_at in [이번 주 월요일 0시 KST, 다음 주)" 범위라, 인덱스가 없으면 요청마다 풀스캔이다.
--   합성 데이터 EXPLAIN ANALYZE(MySQL 8.0): likes 6만 행 13ms → 2.5ms, answers 35만 행 118ms → 54ms.
--   answers 쪽은 비용 대부분이 not exists 반조인이라 절반만 준다(근본 해결은 (계정·방) 첫 풀이 시각 테이블 — 후속 과제).
--   대가: 추천·해제·답 제출마다 인덱스 유지비(answers 일괄 INSERT 4.2s → 6.4s 측정).
--
-- 누가 만드나: user 앱이 prod ddl-auto=update 라 이 엔티티 선언(@Index)을 담은 이미지가 기동하면 같은 이름으로
--   스스로 만든다(Hibernate update 는 인덱스를 이름으로만 대조한다). quiz 앱은 ddl-auto=none 이라 만들지 않는다.
--   손으로 먼저 거는 이유: (1) quiz 만 먼저 배포돼도 인기 목록이 풀스캔으로 돌지 않게, (2) user 기동 중
--   Hibernate 의 평범한 CREATE INDEX 대신 아래처럼 ALGORITHM/LOCK 을 명시해 온라인 DDL 을 보장하려고.
--   이 파일을 먼저 돌리면 user 기동 때는 같은 이름이 이미 있어 아무것도 하지 않는다.
--   ⚠ 이름을 바꾸지 말 것 — 엔티티(QuizRoomLike·QuizRoomAnswer @Table indexes)와 다르면 update 가 같은 컬럼에 하나 더 만든다.
--
-- 온라인 DDL: ADD INDEX(secondary) 는 InnoDB 에서 INPLACE · LOCK=NONE 이 가능하다 — 빌드 중에도 읽기·쓰기가 막히지 않는다.
--   지원되지 않는 상황이면 MySQL 이 조용히 COPY/락으로 떨어지는 대신 에러를 낸다(명시한 이유). 시작·끝에 짧은 메타데이터 락이
--   필요하므로, 긴 트랜잭션이 이 테이블을 잡고 있으면 그 뒤에서 대기한다 — 트래픽 적은 시간에 실행할 것.
--
-- 재실행 안전: MySQL 8.0 은 ADD INDEX IF NOT EXISTS 가 없다. information_schema.STATISTICS 로 이름을 보고
--   이미 있으면 건너뛰는 동적 SQL 로 실행한다(migrate-user-profile-img.sql 과 같은 방식). 몇 번을 돌려도 결과가 같다.
--
-- ⚠ 이 파일을 spring.sql.init.data-locations 에 넣지 말 것(migrate-*.sql 공통 규칙).
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 현재 상태 — created_at 을 포함한 인덱스. 0행이면 아래 Step 1·2 가 실제로 ALTER 를 돈다.
--   다른 이름으로 created_at 단독 인덱스가 이미 있다면 멈추고 확인할 것(그대로 두면 같은 인덱스가 둘이 된다).
-- ============================================================================
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('quiz_room_likes', 'quiz_room_answers')
GROUP BY TABLE_NAME, INDEX_NAME
HAVING FIND_IN_SET('created_at', cols) > 0;

SELECT TABLE_NAME, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('quiz_room_likes', 'quiz_room_answers');


-- ============================================================================
-- Step 1. quiz_room_likes
-- ============================================================================
SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_room_likes'
      AND INDEX_NAME = 'idx_quiz_room_likes_created_at'
);

SET @stmt := IF(@idx_exists > 0,
    'SELECT ''Step 1 건너뜀 — idx_quiz_room_likes_created_at 이 이미 있다'' AS note',
    'ALTER TABLE `quiz_room_likes` ADD INDEX `idx_quiz_room_likes_created_at` (`created_at`), ALGORITHM=INPLACE, LOCK=NONE');

PREPARE stmt_add_likes_created_at FROM @stmt;
EXECUTE stmt_add_likes_created_at;
DEALLOCATE PREPARE stmt_add_likes_created_at;


-- ============================================================================
-- Step 2. quiz_room_answers
-- ============================================================================
SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_room_answers'
      AND INDEX_NAME = 'idx_quiz_room_answers_created_at'
);

SET @stmt := IF(@idx_exists > 0,
    'SELECT ''Step 2 건너뜀 — idx_quiz_room_answers_created_at 이 이미 있다'' AS note',
    'ALTER TABLE `quiz_room_answers` ADD INDEX `idx_quiz_room_answers_created_at` (`created_at`), ALGORITHM=INPLACE, LOCK=NONE');

PREPARE stmt_add_answers_created_at FROM @stmt;
EXECUTE stmt_add_answers_created_at;
DEALLOCATE PREPARE stmt_add_answers_created_at;


-- ============================================================================
-- 검증 — 기대: 두 행
--   quiz_room_answers | idx_quiz_room_answers_created_at | created_at
--   quiz_room_likes   | idx_quiz_room_likes_created_at   | created_at
-- ============================================================================
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND INDEX_NAME IN ('idx_quiz_room_likes_created_at', 'idx_quiz_room_answers_created_at')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME;


-- ============================================================================
-- 되돌리기(필요할 때만, 수동) — 엔티티 @Index 도 함께 빼지 않으면 다음 user 기동이 다시 만든다.
-- ============================================================================
-- ALTER TABLE `quiz_room_likes`   DROP INDEX `idx_quiz_room_likes_created_at`,   ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE `quiz_room_answers` DROP INDEX `idx_quiz_room_answers_created_at`, ALGORITHM=INPLACE, LOCK=NONE;
