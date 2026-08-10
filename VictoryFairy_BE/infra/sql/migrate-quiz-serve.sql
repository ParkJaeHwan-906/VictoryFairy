-- ============================================================================
-- 퀴즈 편성(quiz_date = 출제일) 지원 — quiz_date NULL 허용 (1회성, MySQL 8.0 — 수동 실행)
--
-- 선행: migrate-quiz-ingest.sql 적용 이후 실행한다(quiz_date 컬럼이 그 파일 Step 2 산물이다).
-- 대상: **quiz_date 가 NOT NULL 인 모든 환경.** 컬럼을 누가 만들었는지가 아니라 현재 Null 속성이
--   기준이다 — `SHOW COLUMNS FROM quizzes LIKE 'quiz_date'` 가 Null=NO 면 이 파일이 필요하다.
--   - prod: 2026-08-10 적용 완료(0행 상태에서 즉시). **종전 주석의 "prod 는 no-op 이라 건너뛴다"는
--     틀렸다** — 구 엔티티(nullable=false) 시절 user 앱의 ddl-auto=update 가 컬럼을 NOT NULL 로
--     만들어둔 환경이었고, 그래서 적재가 전량 실패하고 있었다(파드 로그로 확인).
--     ⚠ 컬럼을 NULL 로 추가하는 수정판 ingest SQL 을 적용했다고 해서 이 파일이 불필요해지는 게
--     아니다. 컬럼이 이미 있으면 그 파일 Step 2 는 Duplicate column 으로 건너뛰게 되고, 기존 컬럼의
--     NOT NULL 은 그대로 남는다.
--   - dev(52.78.153.242): 초판 ingest SQL(quiz_date NOT NULL)을 적용한 환경이라 동일하게 필요하다.
--   - 진짜 신규 환경(테이블 생성 전): 엔티티 선언(nullable=true)대로 Hibernate 가 NULL 허용
--     컬럼으로 생성하므로 이 파일 불필요.
--
-- 왜 손으로 도는가: quiz_date 의 의미가 "생성일"→"출제일"로 바뀌며 NULL(미편성 풀 대기)이
-- 정상 상태가 됐는데, **`ddl-auto=update` 는 기존 컬럼의 NOT NULL 을 완화하지 않는다**
-- (제약 추가를 안 하는 것과 같은 계열 — migrate-quiz-ingest.sql 머리 주석 참고). 스키마가
-- NOT NULL 인 채면 시효성 없는 후보의 풀 적재(quiz_date=NULL INSERT)가 전부 실패한다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙).
-- ============================================================================

ALTER TABLE quizzes MODIFY COLUMN quiz_date DATE NULL;


-- ============================================================================
-- 검증 — Null 이 YES 로 보여야 한다 (idx_quizzes_quiz_date 는 NULL 행도 담으므로
-- 편성 UPDATE 의 `WHERE quiz_date IS NULL` 이 그대로 인덱스를 탄다 — 추가 조치 없음)
-- ============================================================================
SHOW COLUMNS FROM quizzes LIKE 'quiz_date';
