-- ============================================================================
-- users_bq.bq_score 소급 산정 — 과거 정답 기록에서 레이팅을 역산한다
-- (MySQL 8.0 — 수동 실행)
-- 계약: docs/requirements/quiz/quiz-point-bq-split.md (QUIZ-PBQ-15~25 의 소급분)
--
-- 무엇을/왜
--   quizzes.bq 신설·백필(migrate-quiz-point-bq.sql)과 정답 시 적립 경로는 함께 배포됐지만,
--   적립은 **배포 이후의 제출부터** 일어난다. 그래서 그 전에 이미 문제를 맞힌 사용자는
--   bq_score 가 0 인 채로 남는다 — 같은 정답인데 푼 시점이 배포 전이냐 후냐로 레이팅이
--   갈리는 셈이다. 이 스크립트가 그 격차를 메운다.
--
--   point(재화) 는 소급하지 않는다. 그쪽은 배포 전에도 정상 적립되고 있었고 이미 상점에서
--   소비된 잔액이라, 다시 계산하면 소비 이력과 어긋난다. bq 만 "한 번도 적립된 적 없는 축"
--   이라 재계산이 성립한다.
--
-- 선행 조건
--   ⚠ migrate-quiz-point-bq.sql 이 **먼저** 적용돼 있어야 한다. quizzes.bq 가 없으면
--     Step 2 가 Unknown column 으로 실패하고, 있어도 백필 전이면 전부 NULL 이라 결과가
--     전원 0 이 된다(조용히 틀린다 — 아래 검증 ③이 이걸 잡는다).
--
-- 멱등하다 — 그리고 그 이상이다
--   Step 2 는 증분(+=)이 아니라 **절대값 SET** 이다. bq_score 의 정의를 "그 계정의 모든
--   정답에 걸린 quizzes.bq 의 합" 으로 두고 매번 그 값을 다시 계산해 덮는다. 그래서
--   ① 몇 번을 돌려도 같은 결과이고 ② 배포 후 쌓인 신규 적립분까지 함께 맞춘다
--   (앱의 증분 적립과 결과가 같은 정의를 공유하므로 서로 어긋나지 않는다).
--
--   ⚠ 뒤집어 말하면 **제출 이력에서 파생되지 않는 bq 는 이 스크립트가 지운다.** 이벤트
--     보너스·운영자 수동 보정 같은 것이 생기면 그 순간부터 이 스크립트를 그대로 돌리면
--     안 된다(그때는 파생분과 보정분을 나눠 저장하는 설계가 먼저다).
--
--   ⚠ 실행 중 들어온 제출과 경합할 수 있다. 앱은 users_bq 행을 잠그고 읽기-수정-쓰기를
--     하는데 이 UPDATE 는 그 락 순서 밖이라, 하필 겹치면 그 한 건의 증분이 덮여 사라진다.
--     제출이 없는 시간대에 돌릴 것. (겹쳐서 한 건을 잃어도 다음 실행이 정정한다 — 절대값
--     이라 영구 손실은 아니다.)
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 적용 전 기준값 — 검증에서 대조한다.
--   ⚠ correct_with_bq_null 이 0 이 아니면 **멈출 것.** 정답인데 그 문제의 bq 가 NULL 이라는
--     뜻이고, 원인은 둘 중 하나다: migrate-quiz-point-bq.sql 백필 누락, 또는 난이도가
--     매핑 밖(EASY/MEDIUM/HARD/EXPERT 아님)인 문제. 전자면 그 백필을 먼저 돌리고, 후자면
--     그 문제들의 bq 를 어떻게 할지 정한 뒤에 이 스크립트를 돌린다. 그냥 돌리면 그 정답만
--     0 으로 세어져 조용히 낮은 레이팅이 나온다.
-- ============================================================================
SELECT COUNT(*) AS all_submits,
       SUM(is_answer = 1) AS correct_submits
FROM quiz_users_submit;

SELECT COUNT(*) AS correct_with_bq_null
FROM quiz_users_submit s
JOIN quizzes q ON q.id = s.quiz_id
WHERE s.is_answer = 1 AND q.bq IS NULL;

SELECT COUNT(*) AS accounts_missing_bq_row
FROM users_account ua
WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);


-- ============================================================================
-- Step 1. 행이 없는 계정에 bq_score = 0 행을 만든다.
--   users-bq-backfill.sql 의 INSERT 와 **같은 문장**이다. 여기 다시 두는 이유는 Step 2 가
--   UPDATE 라서 행이 없는 계정을 **말없이 건너뛰기 때문**이다 — 정답 기록이 있는데 행이
--   없는 계정이 하나라도 있으면 그 사람의 레이팅만 통째로 누락된다.
--   (가입 트랜잭션이 행을 만들지만, 그 경로가 생기기 전에 가입한 계정이 남아 있다.)
--
--   NOT EXISTS·NOW(6)·탈퇴 계정 포함의 근거는 users-bq-backfill.sql 주석 참고.
-- ============================================================================
INSERT INTO users_bq (user_account_id, bq_score, created_at, updated_at)
SELECT ua.id, 0, NOW(6), NOW(6)
FROM users_account ua
WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);


-- ============================================================================
-- Step 2. 소급 산정 — 정답에 걸린 quizzes.bq 의 합으로 덮는다.
--
--   ▸ is_answer = 1 만 센다. 미답 행(submit_option_id IS NULL)의 is_answer 는 "틀렸다"가
--     아니라 "아직 채점 전"이지만(QuizUserSubmit javadoc), 어느 쪽이든 적립 대상이 아니다.
--   ▸ q.bq 가 NULL 인 문제는 SUM 이 알아서 무시한다 = 그 축 적립 0. 앱의 적립 규칙
--     (배점 NULL 이면 그 축만 0)과 같은 결과다.
--   ▸ 정답이 하나도 없는 계정은 SUM 이 NULL 이라 COALESCE 로 0 을 넣는다. 여기서 COALESCE
--     를 빼면 그 계정들의 bq_score 가 NOT NULL 컬럼에 NULL 을 쓰려다 실패한다.
--   ▸ updated_at 을 함께 찍는다. 이 UPDATE 는 앱을 거치지 않아 @UpdateTimestamp 가 안 돈다.
-- ============================================================================
UPDATE users_bq b
SET b.bq_score = COALESCE((
        SELECT SUM(q.bq)
        FROM quiz_users_submit s
        JOIN quizzes q ON q.id = s.quiz_id
        WHERE s.user_account_id = b.user_account_id
          AND s.is_answer = 1
    ), 0),
    b.updated_at = NOW(6);


-- ============================================================================
-- 검증
--   ① 행 누락: 0 이어야 한다(계정 1행 = users_bq 1행)
--   ② 계정별 대조: bq_score 가 계산값과 정확히 일치해야 한다(diff 컬럼 전부 0)
--   ③ 총합 대조: 두 값이 같아야 한다. 다르면 Step 1 을 건너뛴 계정이 있다는 뜻이다
--   ④ 음수·NULL 이 없어야 한다
-- ============================================================================
SELECT COUNT(*) AS missing_rows
FROM users_account ua
WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);

SELECT b.user_account_id,
       b.bq_score,
       COALESCE(SUM(q.bq), 0) AS computed,
       b.bq_score - COALESCE(SUM(q.bq), 0) AS diff
FROM users_bq b
LEFT JOIN quiz_users_submit s
       ON s.user_account_id = b.user_account_id AND s.is_answer = 1
LEFT JOIN quizzes q ON q.id = s.quiz_id
GROUP BY b.user_account_id, b.bq_score
ORDER BY b.user_account_id;

SELECT (SELECT SUM(bq_score) FROM users_bq) AS stored_total,
       (SELECT SUM(q.bq)
        FROM quiz_users_submit s JOIN quizzes q ON q.id = s.quiz_id
        WHERE s.is_answer = 1) AS computed_total;

SELECT COUNT(*) AS bad_rows FROM users_bq WHERE bq_score IS NULL OR bq_score < 0;
