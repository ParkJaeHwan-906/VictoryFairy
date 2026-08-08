-- ============================================================================
-- 퀴즈 편성(quiz_date = 출제일) 지원 — quiz_date NULL 허용 (1회성, MySQL 8.0 — 수동 실행)
--
-- 선행: migrate-quiz-ingest.sql 적용 이후 실행한다(quiz_date 컬럼이 그 파일 Step 2 산물이다).
-- 대상: **초판 migrate-quiz-ingest.sql(quiz_date NOT NULL)을 이미 적용한 환경만.**
--   - 2026-08-08 수정판 ingest SQL 은 quiz_date 를 처음부터 NULL 로 추가한다 — 그 버전으로
--     적용하는 환경(prod 포함 — 구모양 테이블이 있어 ingest SQL 대상이다, 그 파일 머리 주석)은
--     이 파일이 no-op 이라 건너뛴다(실행해도 무해).
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
