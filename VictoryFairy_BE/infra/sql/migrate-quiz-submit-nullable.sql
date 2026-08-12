-- ============================================================================
-- 미답 행(출제 시점 선생성) 지원 — quiz_users_submit.submit_option_id NULL 허용
-- (1회성, MySQL 8.0 — 수동 실행)
--
-- ⚠⚠ 이 DDL 은 quiz 앱 배포의 **선행 조건**이다. 순서를 지키지 않으면 GET /rt/quizzes/today 가
--     통째로 실패한다 — 새 서빙 경로가 응답에 실은 문제마다 submit_option_id 가 NULL 인 행을
--     INSERT 하는데, 컬럼이 NOT NULL 이면 그 INSERT 가 제약 위반으로 막히고 목록도 못 준다
--     (행 생성과 목록 조회가 같은 트랜잭션이라 부분 성공이 없다).
--
-- 대상: **submit_option_id 가 NOT NULL 인 모든 환경**(prod · devdb · 로컬). 누가 컬럼을 만들었는지가
--   아니라 현재 Null 속성이 기준이다 — 아래 Step 0 을 돌려 Null=NO 면 이 파일이 필요하다.
--   테이블이 아직 없는 진짜 신규 환경은 불필요하다(엔티티 선언대로 NULL 허용으로 생성된다).
--
-- 왜 손으로 도는가: **`ddl-auto=update` 는 기존 컬럼의 NOT NULL 을 완화하지 않는다**
--   (quizzes.quiz_date 선례 — migrate-quiz-serve.sql 머리 주석. 다시 조사하지 말 것).
--   엔티티(QuizUserSubmit.submitOption)를 nullable 로 바꿔도 이미 만들어진 컬럼은 그대로다.
--
-- ⚠ 스키마를 만드는 앱과 쓰는 앱이 다르다. quiz 앱의 prod ddl-auto 는 `none` 이라 quiz 만 배포해서는
--   컬럼이 바뀌지 않는다 — 그리고 이 완화는 user 앱(ddl-auto=update)이 재기동해도 일어나지 않는다.
--   **오직 이 파일만이 적용 수단이다.**
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙).
-- ============================================================================


-- ============================================================================
-- Step 0. 환경 판단 — Null=NO 이면 아래 Step 1 을 적용한다(YES 면 이미 적용됨, 재실행해도 무해).
--   Type 도 함께 확인한다: 아래 MODIFY 는 컬럼 정의를 통째로 다시 쓰므로 실제 타입과 달라선 안 된다
--   (id 계열은 BIGINT 다 — 다르게 나오면 그 값으로 아래 문장을 고쳐 적용할 것).
-- ============================================================================
SHOW COLUMNS FROM quiz_users_submit LIKE 'submit_option_id';


-- ============================================================================
-- Step 1. NOT NULL 완화 — "고른 보기가 없다"를 임의의 보기(0번 등)로 대체하지 않고 NULL 로 남긴다
-- ============================================================================
-- ⚠ FK(제출 보기 → quiz_options)와 ON DELETE CASCADE 는 건드리지 않는다. MODIFY COLUMN 은 컬럼
--   정의만 바꾸고 FK 제약은 그대로 둔다(SET NULL 로 바꾸지 말 것 — 그러면 "보기가 지워진 제출"과
--   "아직 답하지 않은 행"이 스키마상 구분 불가능해진다).
ALTER TABLE quiz_users_submit MODIFY COLUMN submit_option_id BIGINT NULL;


-- ============================================================================
-- 검증 — Null 이 YES 로 보여야 한다. FK 는 그대로 1건(DELETE_RULE = CASCADE)이어야 한다.
-- ============================================================================
SHOW COLUMNS FROM quiz_users_submit LIKE 'submit_option_id';

SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE, kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'quiz_users_submit'
  AND kcu.COLUMN_NAME = 'submit_option_id';
