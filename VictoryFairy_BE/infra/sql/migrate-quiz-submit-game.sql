-- ============================================================================
-- 회차 제한("한 이닝에 한 세트") 지원 — quiz_users_submit.game_id 신설
-- (1회성, MySQL 8.0 — 수동 실행)
--
-- ⚠⚠ 이 DDL 은 quiz 앱 배포의 **선행 조건**이다. 순서를 지키지 않으면 GET /rt/quizzes/today 가
--     통째로 실패한다 — 새 서빙 경로가 미답 행을 INSERT 할 때 game_id 컬럼에 값을 넣기 때문이다
--     (컬럼이 없으면 Unknown column 으로 INSERT 가 죽고, 행 생성과 목록 조회가 같은 트랜잭션이라
--      목록도 못 준다).
--
-- 무엇을/왜: 이닝의 뜻이 "그 문제가 귀속된 경기의 이닝"에서 **"요청자가 지목한 자기 팀 경기의
--   이닝"**으로 바뀌면서, 행이 자기 경기를 직접 들고 있어야 한다. 회차 판정 키가
--   (user_account_id, game_id, inning) 이고, 경기가 키에 들어가면 "어제 9회가 오늘 9회를 막는다"는
--   함정이 **날짜 조건 없이** 사라진다(경기가 다르므로).
--   계약: docs/requirements/quiz/quiz-inning-tracking.md (QUIZ-INN-103·104·112)
--
-- ⚠ quizzes.game_id 와 이름만 같고 뜻이 다르다 — 그쪽은 "문제가 다루는 경기", 이쪽은 "사용자가
--   그 문제를 받은 경기"다. 두 값이 달라도 정상이고 앱이 일치를 검사하지 않는다.
--
-- 대상: **quiz_users_submit 이 이미 존재하는 모든 환경**(prod · devdb · 로컬).
--   테이블이 아직 없는 진짜 신규 환경은 불필요하다(엔티티 선언대로 컬럼·FK·인덱스가 생성된다).
--
-- 왜 손으로 도는가: quiz 앱의 prod ddl-auto 는 `none` 이라 quiz 배포만으로는 컬럼이 생기지 않는다.
--   user 앱(ddl-auto=update)의 재기동이 **nullable 컬럼 추가까지는** 해 주지만
--   (games.cancel_reason 선례), **이미 존재하는 테이블에 UNIQUE·FK 를 추가하지는 않고**
--   (2026-08-05 game_statuses 실측) 일반 인덱스도 붙는다고 전제할 수 없다. 그래서 컬럼·FK·인덱스를
--   한 파일로 묶어 손으로 적용하고, 이미 생긴 것은 Step 0 으로 가려 건너뛴다.
--
-- 백필하지 않는다: 개정 이전 행의 game_id 는 NULL 로 남는다. `NULL = ?` 가 참이 아니라 회차 판정에
--   자연히 걸리지 않으며, 그 행들이 어느 경기를 보며 풀렸는지는 어디에도 남아 있지 않아 복원할
--   수 있는 값이 아니다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙) —
--   재실행하면 Duplicate column/Duplicate key name 으로 죽어 모든 파드가 기동 실패한다.
-- ============================================================================


-- ============================================================================
-- Step 0. 환경 판단 — 아래 셋이 각각 이미 있으면 해당 Step 을 건너뛴다.
--   ① 컬럼 game_id  ② FK fk_quiz_users_submit_game  ③ 인덱스
--      idx_quiz_users_submit_account_game_inning
--   (user 앱이 먼저 기동한 환경은 ①만 있고 ②③은 없을 수 있다 — 그 조합이 정상 경로다.)
-- ============================================================================
SHOW COLUMNS FROM quiz_users_submit LIKE 'game_id';

SHOW INDEX FROM quiz_users_submit
WHERE Key_name IN ('idx_quiz_users_submit_account_game_inning', 'fk_quiz_users_submit_game');

SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_users_submit'
  AND CONSTRAINT_TYPE = 'FOREIGN KEY';


-- ============================================================================
-- Step 1. 컬럼 추가 — nullable 이다(기존 행이 있어 NOT NULL 로는 추가할 수 없다).
--   새로 만들어지는 행은 앱이 반드시 값을 채운다(이닝·경기를 특정하지 못한 요청은 세트 제공
--   자체가 403 으로 거절된다) — 즉 NULL 은 "개정 이전 행"의 표식이다.
-- ============================================================================
ALTER TABLE quiz_users_submit ADD COLUMN game_id BIGINT NULL AFTER quiz_id;


-- ============================================================================
-- Step 2. FK — ON DELETE 미지정(RESTRICT). 경기는 마스터 데이터라 경기 행이 사라졌다고 제출
--   기록이 연쇄 삭제되면 안 된다(quizzes.game_id = fk_quizzes_game 과 같은 정책).
-- ============================================================================
ALTER TABLE quiz_users_submit
    ADD CONSTRAINT fk_quiz_users_submit_game FOREIGN KEY (game_id) REFERENCES games (id);


-- ============================================================================
-- Step 3. 회차 판정용 인덱스 — "이 사용자가 그 경기의 그 이닝에 이미 받았는가"의 커버링 존재 검사.
--
-- ⚠ UNIQUE 로 승격하지 말 것. 한 이닝에 세트(최대 20문제)가 통째로 들어오므로 같은
--   (user_account_id, game_id, inning) 행이 여러 건인 것이 정상이고, UNIQUE 면 두 번째 문제의
--   INSERT 부터 실패한다. 중복 제출 차단의 근거는 여전히 uk_quiz_users_submit_account_quiz 다.
--
-- FK 자동 인덱스(game_id 단독)로 대신할 수 없다 — 그쪽으로 진입하면 "그 경기를 받은 모든 사용자"를
--   훑고, uk_quiz_users_submit_account_quiz 로 진입하면 "그 사용자의 모든 제출"을 훑는다.
-- ============================================================================
CREATE INDEX idx_quiz_users_submit_account_game_inning
    ON quiz_users_submit (user_account_id, game_id, inning);


-- ============================================================================
-- 검증
--   ① 컬럼: Null = YES · Type = bigint
--   ② 인덱스: idx_quiz_users_submit_account_game_inning 이 Seq_in_index 1,2,3 =
--      user_account_id, game_id, inning 순서로 보이고 Non_unique = 1 이어야 한다
--   ③ FK: fk_quiz_users_submit_game 이 games 를 참조하고 DELETE_RULE = RESTRICT(또는 NO ACTION)
--   ④ 기존 행은 전부 game_id IS NULL 이어야 한다(백필하지 않았다는 확인)
-- ============================================================================
SHOW COLUMNS FROM quiz_users_submit LIKE 'game_id';

SHOW INDEX FROM quiz_users_submit WHERE Key_name = 'idx_quiz_users_submit_account_game_inning';

SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE, kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quiz_users_submit'
  AND kcu.COLUMN_NAME = 'game_id';

SELECT COUNT(*) AS total_rows, COUNT(game_id) AS rows_with_game FROM quiz_users_submit;
