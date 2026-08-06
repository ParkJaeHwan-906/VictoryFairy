-- ============================================================================
-- game_statuses.name UNIQUE 추가 (1회성, MySQL 8.0 — prod/dev 수동 실행)
--
-- 왜 손으로 도는가: `GameStatus` 엔티티에 `uk_game_statuses_name` 을 선언했지만,
-- **`ddl-auto=update` 는 이미 존재하는 테이블에 UNIQUE 를 추가하지 않는다.** 중복 행
-- 유무와 무관하게 아예 시도하지 않는다(2026-08-05 실측: 운영과 같은 모양의 스키마에
-- `user` 앱을 dev 프로파일로 붙여 기동한 결과, 같은 기동에서 새로 만들어진 `teams` 에는
-- `code` UNIQUE 가 붙었으나 미리 존재하던 `game_statuses` 에는 아무것도 붙지 않았다).
--   → 신규 환경: 엔티티 선언대로 Hibernate 가 테이블을 만들며 함께 건다. 이 파일 불필요.
--   → 이미 뜬 prod/dev: 이 파일을 **한 번** 적용해야 실제로 걸린다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것. 재실행하면
--   `Duplicate key name` 으로 죽어 **모든 파드가 기동 실패**한다(= 전면 장애). 같은 이유로
--   migrate-*.sql 은 전부 data-locations 금지 대상이다(application-prod.yaml 주석 참고).
--
-- 적용 순서: game-statuses-init.sql 시드가 먼저 돌아 5행이 채워진 뒤여도 상관없고 그 전이어도
--   상관없다(서로 의존하지 않음). 다만 **Step 1 이 0행인지 반드시 먼저 확인**할 것 — 중복이
--   있으면 Step 2 의 ALTER 가 `Duplicate entry` 로 실패한다.
--
-- 이 제약이 막는 것: `game_statuses` 는 앱 기동 시드와 py-collector 의 lookup-or-insert
--   두 주체가 name 으로 행을 찾아 쓰는 코드 테이블이다. 제약이 없으면 같은 상태가 두 행으로
--   갈라진 채 조용히 남고(수집기가 실행마다 다른 id 를 집어감), `findByName` 은 `Optional`
--   이라 2행이면 예외가 된다. 자세한 배경은 `GameStatus` 엔티티 주석 참고.
-- ============================================================================


-- ============================================================================
-- Step 1. 선행 확인 — 중복이 없어야 ALTER 가 성공한다 (0행이어야 함)
-- ============================================================================
SELECT name, COUNT(*) AS cnt
FROM game_statuses
GROUP BY name
HAVING COUNT(*) > 1;

-- 위가 0행이 아니면 여기서 멈출 것. 중복 정리는 남길 행(가장 작은 id)을 고르고 나머지를
-- 참조하는 games 를 먼저 옮겨야 하므로 기계적으로 지울 수 없다. 정리 절차:
--   1) 어떤 경기가 버릴 id 를 참조하는지 확인
--      SELECT g.game_status_id, COUNT(*) FROM games g
--       JOIN game_statuses gs ON gs.id = g.game_status_id GROUP BY g.game_status_id;
--   2) 참조를 남길 id 로 이동
--      UPDATE games SET game_status_id = <남길id> WHERE game_status_id = <버릴id>;
--   3) 빈 행 삭제
--      DELETE FROM game_statuses WHERE id = <버릴id>;


-- ============================================================================
-- Step 2. UNIQUE 추가
-- (제약 이름은 엔티티 @Table(uniqueConstraints=...) 선언과 같은 값이어야 한다 —
--  신규 환경에서 Hibernate 가 만드는 이름과 일치시켜 환경 간 스키마를 같게 유지한다)
-- ============================================================================
ALTER TABLE game_statuses
    ADD CONSTRAINT uk_game_statuses_name UNIQUE (name);


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 제약이 실제로 걸렸는지
--    SHOW INDEX FROM game_statuses WHERE Column_name = 'name';
--    -- 기대: Key_name='uk_game_statuses_name', Non_unique=0 인 행 1개
--
-- 2) 행이 그대로인지 (ALTER 는 데이터를 바꾸지 않는다)
--    SELECT id, name FROM game_statuses ORDER BY id;   -- 기대: 시드 적용 후라면 5행
--
-- 3) 실제로 막는지 (선택 — 되돌아갈 것)
--    INSERT INTO game_statuses (name, created_at, updated_at)
--      VALUES ('FINISHED', NOW(6), NOW(6));            -- 기대: ERROR 1062 Duplicate entry
-- ============================================================================
