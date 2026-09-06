-- ============================================================================
-- 퀴즈 배점 분리 — quizzes.score → quizzes.point 이름 변경 + quizzes.bq 신설·백필
-- (1회성, MySQL 8.0 — 수동 실행)
--
-- ⚠⚠ 이 DDL 은 quiz 앱 배포의 **선행 조건**이다. 올바른 순서는
--       ① 이 스크립트 수동 적용  →  ② quiz·user 앱 배포
--     이며, 두 방향 모두 어기면 조용히 망가지는 것이 아니라 눈에 보이게 깨진다.
--
--     • 미적용 상태로 quiz 앱을 배포하면: quiz prod 는 ddl-auto=none 이라 컬럼이 저절로 생기지
--       않는다. 엔티티가 point 를 매핑하므로 GET /rt/quizzes/today 가 통째로 500 이다
--       (Unknown column 'point'). 적용하면 **재시작 없이** 200 으로 돌아온다(커넥션 풀이 스키마
--       변경을 그때그때 반영 — migrate-quiz-submit-game.sql 때 실측된 성질).
--     • 반대 방향(더 고약함): user 앱은 ddl-auto=update + @EntityScan("com.skhynix") 라 quizzes
--       테이블까지 자기 관할로 본다. 이 DDL 보다 user 앱이 먼저 재기동되면 **Hibernate 가 빈
--       point 컬럼(과 bq 컬럼)을 스스로 만들고**, 값이 든 score 는 아무도 안 읽는 고아 컬럼으로
--       남는다. 앱은 500 도 내지 않고 전 문제의 배점이 NULL(적립 0)이 될 뿐이라 알아채기 어렵다.
--       그 상태의 복구 경로가 아래 **Step R** 이다(요구사항 QUIZ-PBQ-43).
--
-- 무엇을/왜: score 라는 컬럼 하나가 두 가지 뜻을 겸하고 있었다 — 응답 JSON 은 이미 point(재화 축,
--   users_account.point → 캐릭터 상점 소비처)로 내보내는데 컬럼 이름만 score(점수)여서 코드와
--   프론트 계약이 서로 다른 단어를 썼다. 동시에 레이팅 축(users_bq.bq_score)은 아무 경로에서도
--   증가하지 않아 전 계정이 영구히 0이었다. 이번 변경은 컬럼 이름을 뜻에 맞추고(point), 난이도에
--   연동된 별도 배점 축(bq)을 신설해 정답 시 두 원장에 각각 적립한다.
--   계약: docs/requirements/quiz/quiz-point-bq-split.md (QUIZ-PBQ-37~45)
--
-- ⚠ point 는 타입을 바꾸지 않는다(DOUBLE 유지). 값이 늘 정수여도 정수 컬럼으로 좁히면 응답 JSON 의
--   30.0 이 30 이 되어 프론트 계약이 함께 흔들린다 — 이름만 바꾸는 것이 이 Step 의 전부다.
--
-- 대상: **quizzes 가 이미 존재하는 모든 환경**(prod · devdb · 로컬).
--   테이블이 아직 없는 진짜 신규 환경은 불필요하다(엔티티 선언대로 point·bq 가 생성된다).
--
-- 왜 손으로 도는가: quiz 앱의 prod ddl-auto 는 none 이라 quiz 배포만으로는 컬럼이 생기지 않고,
--   user 앱의 update 는 **컬럼 이름 변경을 하지 못한다**(새 컬럼을 추가할 뿐 옛 컬럼과 값을 잇지
--   않는다). 즉 rename 은 어느 앱도 대신 해 주지 않는다. deploy-eks.yml 에도 이 단계는 없다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것(migrate-*.sql 공통 규칙) —
--   재실행하면 Unknown column 'score' / Duplicate column name 으로 죽어 모든 파드가 기동 실패한다.
-- ============================================================================


-- ============================================================================
-- Step 0. 환경 판단 — 아래 셋의 조합으로 어느 경로를 탈지 정한다.
--   ① score 만 있다            → 정상 경로: Step 1 → 2 → 3
--   ② score 와 point 이 함께 있다 → user 앱이 먼저 뜬 환경: Step R → 1 → 2 → 3
--   ③ point 만 있다            → 이미 적용됨: Step 2·3 만 필요한지 확인하고 나머지는 건너뛴다
--   (bq 컬럼은 ②·③ 에서 user 앱이 이미 만들어 뒀을 수 있다 — 그러면 Step 2 를 건너뛰고 Step 3
--    백필만 돈다. 컬럼이 있어도 값은 전부 NULL 이므로 백필은 반드시 돌려야 한다.)
--
-- ⚠ Step 1 에 쓸 **타입 문자열을 여기서 확인할 것.** 아래 SHOW COLUMNS 가 score 에 대해 보고하는
--   Type 을 그대로 쓴다(기본은 double). 이름만 바꾸는 것이 계약이라 타입이 달라지면 안 된다.
-- ============================================================================
SHOW COLUMNS FROM quizzes LIKE 'score';

SHOW COLUMNS FROM quizzes LIKE 'point';

SHOW COLUMNS FROM quizzes LIKE 'bq';

-- 적용 전 기준값(Step 검증에서 대조한다) — rename 으로 값이 새지 않았는지 세는 근거다.
SELECT COUNT(*) AS total_rows, COUNT(score) AS rows_with_score FROM quizzes;


-- ============================================================================
-- Step R. **복구 경로 — Step 0 이 ②(score·point 공존)일 때만 돈다.**
--   user 앱(ddl-auto=update)이 이 DDL 보다 먼저 재기동해 빈 point 컬럼을 만들어 둔 상태다.
--
--   ⚠ 먼저 아래 SELECT 로 point 가 **전부 NULL** 인지 확인할 것. 0 이 아니면 누군가 이미 값을
--     옮겼다는 뜻이므로 **DROP 하지 말고 멈춘다** — 그 상태에서 드롭하면 진짜 데이터가 사라진다.
--
--   빈 컬럼을 지운 뒤 Step 1(CHANGE)로 내려간다. UPDATE 로 값을 복사하는 대신 드롭 후 rename 을
--   택한 이유는 결과 스키마가 정상 경로와 **문자 단위로 같아지기 때문**이다 — Hibernate 가 만든
--   컬럼 정의가 미묘하게 다른 채 남으면 환경마다 스키마가 갈린다.
-- ============================================================================
SELECT COUNT(point) AS rows_with_point_before_drop FROM quizzes;

ALTER TABLE quizzes DROP COLUMN point;


-- ============================================================================
-- Step 1. 이름 변경 — score → point. 값·널 허용·타입 전부 그대로다.
--   RENAME COLUMN 이 아니라 CHANGE 를 쓰는 이유: RENAME COLUMN(8.0+)은 타입을 안 적어 편하지만,
--   CHANGE 는 타입을 명시하게 강제해 "타입은 안 바뀐다"는 계약이 파일에 눈으로 보인다.
--
--   ⚠ 타입은 Step 0 의 SHOW COLUMNS 결과를 그대로 옮겨 적을 것(기본은 double).
--     Hibernate 가 Double 필드를 float(53)로 만들어도 MySQL 은 그것을 double 로 보고한다.
-- ============================================================================
ALTER TABLE quizzes CHANGE COLUMN score point DOUBLE NULL;


-- ============================================================================
-- Step 2. 레이팅 축 배점 컬럼 추가 — nullable 이다.
--   NOT NULL 로 못 만드는 이유는 기존 행 때문만이 아니다: 난이도가 없거나 표에 없는 문제는
--   배점을 결정할 근거가 없고(Step 3), 그런 문제의 정답은 "bq 적립 0"으로 정상 처리된다.
--   0 을 기본값으로 박으면 "배점 미상"과 "배점이 0"이 한 값으로 뭉개진다.
-- ============================================================================
ALTER TABLE quizzes ADD COLUMN bq INT NULL AFTER point;


-- ============================================================================
-- Step 3. 기존 행 백필 — difficulty → bq.
--   EASY=1 · MEDIUM=2 · HARD=3 · EXPERT=4. **5 는 예약값**이라 어떤 난이도에도 부여하지 않는다.
--
--   ⚠ 이 CASE 는 앱의 DifficultyBqMapping(quiz 모듈, 적재 경로)과 **같은 규칙의 두 번째 사본**이다.
--     SQL 이 자바를 부를 수 없어 어쩔 수 없이 둘인 것이고, 갈리면 같은 난이도의 문제가 적재냐
--     백필이냐에 따라 다른 배점을 갖는다 — 한쪽을 고치면 반드시 다른 쪽도 고칠 것.
--
--   WHERE bq IS NULL 이 **재실행 멱등성**이다: 이미 값이 있는 행은 덮어쓰지 않는다(운영자가 손으로
--   조정한 배점을 백필 재실행이 되돌리면 안 된다). 매핑 밖 난이도·NULL 난이도는 CASE 의 ELSE 로
--   NULL 이 되어 그대로 남는다.
-- ============================================================================
UPDATE quizzes
SET bq = CASE difficulty
             WHEN 'EASY' THEN 1
             WHEN 'MEDIUM' THEN 2
             WHEN 'HARD' THEN 3
             WHEN 'EXPERT' THEN 4
             ELSE NULL
         END
WHERE bq IS NULL;


-- ============================================================================
-- 검증
--   ① point: 1행, Null = YES, Type 이 Step 0 의 score Type 과 같은 문자열
--   ② score: 0행(컬럼이 사라졌다)
--   ③ bq: 1행, Type = int, Null = YES
--   ④ 값 보존: rows_with_point 가 Step 0 의 rows_with_score 와 같아야 한다
--   ⑤ 백필: difficulty × bq 조합이 EASY→1 · MEDIUM→2 · HARD→3 · EXPERT→4 로만 나온다
--   ⑥ 매핑 밖 행: 아래 카운트가 0 이어야 한다(난이도 미상인데 bq 가 채워진 행 없음)
-- ============================================================================
SHOW COLUMNS FROM quizzes LIKE 'point';

SHOW COLUMNS FROM quizzes LIKE 'score';

SHOW COLUMNS FROM quizzes LIKE 'bq';

SELECT COUNT(*) AS total_rows, COUNT(point) AS rows_with_point FROM quizzes;

SELECT difficulty, bq, COUNT(*) AS cnt FROM quizzes GROUP BY difficulty, bq ORDER BY difficulty, bq;

SELECT COUNT(*) AS unmapped_rows_with_bq FROM quizzes
WHERE (difficulty IS NULL OR difficulty NOT IN ('EASY', 'MEDIUM', 'HARD', 'EXPERT'))
  AND bq IS NOT NULL;
