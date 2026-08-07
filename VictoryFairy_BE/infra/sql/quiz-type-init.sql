-- ============================================================================
-- quiz_type 시드 (MySQL 8.0)
--
-- 배경: `Quiz.quizType` 이 non-null FK 라 이 테이블이 비어 있으면 **문제 저장 자체가
-- 불가능**하다. `game_statuses` 가 겪은 것과 정확히 같은 종류의 선행 제약이며, 다른 점은
-- 그쪽은 py-collector 의 lookup-or-insert 가 실제로 일어난 상태만 뒤늦게 만들어 줬지만
-- **퀴즈에는 그런 폴백이 없다**는 것이다 — 유형을 만드는 코드 경로가 아직 어디에도 없어서,
-- 이 시드가 없으면 첫 출제 요청이 FK 위반으로 실패한다.
--
-- 적용은 사람이 아니라 앱이 한다 — `user` 앱의 dev/prod 프로파일이
-- `spring.sql.init.data-locations` 로 teams-init.sql · chat-init.sql · game-statuses-init.sql
-- 과 함께 매 기동 실행한다. 순서 의존은 없다(다른 시드와 참조 관계가 없는 독립 코드 테이블).
--
-- ⚠ Hibernate 가 `quiz_type` 을 만든 **뒤에** 돌아야 한다. 두 프로파일 모두
--   `spring.jpa.defer-datasource-initialization: true` 가 켜져 있어 그 순서가 보장된다.
--
-- 재실행 안전: name 기준 anti-join 이라 이미 있는 유형은 건너뛴다. 기존 행의 id 를
--   재부여하지 않는다(= FK 를 건드리지 않는다).
--
-- 동시 기동: `quiz_type.name` 은 UNIQUE 다(`QuizType` 의 `uk_quiz_type_name`). anti-join 은
--   "내가 조회한 그 순간" 기준이라 파드 여러 개가 빈 DB 에 동시에 뜨면 서로의 미커밋 INSERT 를
--   못 봐 같은 이름을 두 번 넣으려 들지만, UNIQUE 가 두 번째를 `Duplicate entry` 로 막아
--   그 파드의 기동이 실패한다 — 재시작하면 앞선 파드가 넣은 행이 보여 no-op 으로 통과하므로
--   자가 치유된다(teams.code · game_statuses.name 과 같은 거동).
--   `game_statuses` 와 달리 **UNIQUE 를 손으로 걸 필요가 없다** — 이 테이블은 아직 어느 환경에도
--   없어서 엔티티 선언대로 Hibernate 가 만들며 함께 건다(`ddl-auto=update` 는 이미 존재하는
--   테이블에는 UNIQUE 를 추가하지 않으므로, 그 점이 `migrate-game-status-unique.sql` 이 따로
--   필요했던 이유다).
--
-- ⚠ name 값은 화면·API 로 그대로 나가는 표기이자 `QuizTypeRepository.findByName` 의 조회 키다.
--   표기를 바꾸면 그 이름으로 찾던 코드가 조용히 빈 `Optional` 을 받는다. 유형이 늘어나면
--   아래 UNION ALL 에 한 줄 추가하면 되고 코드 변경은 필요 없다(재배포만).
--     객관식 — 보기 여러 개 중 하나. quiz_options.option 이 보기 번호(1,2,3…).
--     O/X    — 보기가 둘뿐인 특수 형태. 보기 번호를 0(X)/1(O) 로 표기한다.
-- ============================================================================

INSERT INTO quiz_type (name, created_at, updated_at)
SELECT seed.name, NOW(6), NOW(6)
FROM (
    SELECT '객관식' AS name
    UNION ALL SELECT 'O/X'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM quiz_type qt WHERE qt.name = seed.name);


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 2행이 다 있는지
--    SELECT id, name FROM quiz_type ORDER BY id;                            -- 기대: 2행
--
-- 2) UNIQUE 가 실제로 걸렸는지
--    SHOW INDEX FROM quiz_type WHERE Column_name = 'name';
--    -- 기대: Key_name='uk_quiz_type_name', Non_unique=0
--
-- 3) 표기가 어긋난 행이 섞이지 않았는지
--    SELECT * FROM quiz_type WHERE name NOT IN ('객관식','O/X');             -- 기대: 0행
-- ============================================================================
