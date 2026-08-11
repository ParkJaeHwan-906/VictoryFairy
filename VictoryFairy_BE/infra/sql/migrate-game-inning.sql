-- ============================================================================
-- games 현재 이닝 컬럼 + CHECK 제약 추가 (1회성, MySQL 8.0 — prod/dev 수동 실행)
--
-- 이 파일의 진짜 목적은 **CHECK 제약**이다. `Game` 엔티티에 `ck_games_current_inning`/
-- `ck_games_inning_half` 를 선언했지만, **`ddl-auto=update` 는 이미 존재하는 테이블에
-- 제약을 추가하지 않는다**(2026-08-05 실측으로 UNIQUE 에서 확인된 성질이 그대로 적용된다).
--   → 신규 환경: Hibernate 가 테이블을 만들며 엔티티 선언대로 함께 건다. 이 파일 불필요.
--   → 이미 뜬 prod/dev: 이 파일을 **한 번** 적용해야 실제로 걸린다.
--
-- 반면 **컬럼 자체는 손댈 필요가 없을 수 있다** — `user` 앱이 `ddl-auto=update` 라, 배포 후
-- 기동만으로 Hibernate 가 `current_inning`/`inning_half` 를 붙인다(nullable 컬럼 추가는
-- `update` 가 하는 일이다). 이미 기동된 뒤라면 Step 2 는 건너뛸 것 — 중복 실행하면
-- `ERROR 1060 Duplicate column name` 으로 실패한다.
--
-- ⚠ 이 파일을 `spring.sql.init.data-locations` 에 넣지 말 것. 재실행하면 중복 컬럼·중복
--   제약으로 죽어 **모든 파드가 기동 실패**한다(= 전면 장애). 같은 이유로 migrate-*.sql 은
--   전부 data-locations 금지 대상이다(application-prod.yaml 주석 참고).
--
-- NULL 과 CHECK: SQL 의 CHECK 는 결과가 UNKNOWN 이면 통과시키므로, 두 컬럼이 nullable 인
--   것과 아래 제약은 충돌하지 않는다. 진행 중이 아닌 경기(예정·종료·취소·무승부)는 둘 다
--   NULL 이고 그대로 허용된다.
--
-- 이 제약이 막는 것: 두 컬럼을 채우는 주체는 py-collector 뿐이고 앱에는 `games` 쓰기 경로가
--   없다. 즉 방어 대상은 수집기의 파싱 이상(0 이닝, 12 이닝, 초/말 아닌 값)이며, 조용히
--   저장돼 화면에 "0회초" 로 나가는 대신 적재 시점에 시끄럽게 실패시키는 쪽을 택한 것이다.
--   11 은 연장 한도(정규 9 + 연장 2).
-- ============================================================================


-- ============================================================================
-- Step 1. 컬럼이 이미 있는지 확인 — 이 결과가 Step 2 의 실행 여부를 가른다
-- (Step 3 의 범위 확인 쿼리는 컬럼이 없으면 ERROR 1054 Unknown column 으로 죽는다.
--  그래서 "값 확인" 이 아니라 "컬럼 확인" 이 먼저다.)
-- ============================================================================
SHOW COLUMNS FROM games WHERE Field IN ('current_inning', 'inning_half');

-- 2행이 나오면  → `user` 앱 기동이 이미 만들었다. Step 2 를 건너뛰고 Step 3 으로.
-- 0행이 나오면  → Step 2 를 실행한다.
-- 1행이 나오면  → 한쪽만 있는 비정상 상태다. 없는 컬럼만 골라 Step 2 를 쪼개 실행할 것.


-- ============================================================================
-- Step 2. 컬럼 추가 — **Step 1 이 0행일 때만 실행**
-- (`user` 앱이 `ddl-auto=update` 라 배포 후 기동만으로 같은 모양이 붙는다.
--  이미 붙은 뒤 실행하면 ERROR 1060 Duplicate column name 으로 실패한다.)
-- ============================================================================
ALTER TABLE games
    ADD COLUMN current_inning TINYINT NULL,
    ADD COLUMN inning_half TINYINT NULL;


-- ============================================================================
-- Step 3. 선행 확인 — 범위를 벗어난 값이 없어야 Step 4 의 ALTER 가 성공한다 (둘 다 0행이어야 함)
-- (방금 추가한 컬럼이면 전부 NULL 이라 자명하지만, 컬럼이 이미 있고 수집기가 값을 넣은
--  뒤라면 자명하지 않다 — 그 경우를 위해 절차로 남긴다)
-- ============================================================================
SELECT id, naver_game_id, current_inning
FROM games
WHERE current_inning IS NOT NULL
  AND current_inning NOT BETWEEN 1 AND 11;

SELECT id, naver_game_id, inning_half
FROM games
WHERE inning_half IS NOT NULL
  AND inning_half NOT IN (0, 1);

-- 위가 0행이 아니면 여기서 멈출 것. 범위 밖 값은 수집 이상이므로 무엇이 들어왔는지 확인한 뒤
-- NULL 로 되돌리거나(값을 신뢰할 수 없으므로 보통 이쪽) 올바른 값으로 고치고 다시 확인한다.
--   UPDATE games SET current_inning = NULL WHERE current_inning NOT BETWEEN 1 AND 11;
--   UPDATE games SET inning_half = NULL WHERE inning_half NOT IN (0, 1);


-- ============================================================================
-- Step 4. CHECK 제약 추가 — 이 파일의 목적
--
-- 제약 이름은 엔티티 @Table(check = @CheckConstraint(name = ...)) 선언과 같은 값이어야 한다 —
-- 신규 환경에서 Hibernate 가 만드는 이름과 일치시켜 환경 간 스키마를 같게 유지한다.
--
-- ⚠ 먼저 읽을 것 — `inning_half` 에는 **우리 것이 아닌 CHECK 가 이미 붙어 있을 수 있다.**
--   2026-08-11 devdb 실측: `ddl-auto=update` 기동 후 `SHOW CREATE TABLE games` 에
--     CONSTRAINT `games_chk_1` CHECK ((`inning_half` between 0 and 1))
--   이 있었다. 이것은 우리가 선언한 `ck_games_inning_half`(`IN (0, 1)`) 가 아니다 — 문구가
--   `between 0 and 1` 인 데서 드러나듯, **Hibernate 가 @Enumerated(ORDINAL) 컬럼에 자동으로
--   붙이는 서수 범위 검사**가 `add column` 문에 딸려 들어간 것이다. 실제 기동 로그:
--     alter table games add column current_inning TINYINT
--     alter table games add column inning_half TINYINT check ((inning_half between 0 and 1))
--   → 테이블 레벨 CHECK 2건은 **둘 다 걸리지 않았다**(위 헤더의 전제 그대로). 컬럼에 딸려오는
--     자동 검사만 통과한 것이라 "비대칭" 처럼 보이지만 원인은 이 둘의 차이다.
--   → 이름이 `games_chk_N` 자동 생성명이라 **환경마다 다를 수 있다.** 반드시 아래 4-0 으로
--     실제 이름을 확인한 뒤 진행할 것.
-- ============================================================================

-- 4-0. 이미 붙어 있는 CHECK 확인 (이름은 환경마다 다를 수 있다)
SHOW CREATE TABLE games;
-- 또는
SELECT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS cc
JOIN information_schema.TABLE_CONSTRAINTS tc
  ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
 AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
WHERE tc.TABLE_NAME = 'games';


-- 4-1. current_inning — 자동 검사가 붙지 않는다(enum 이 아니라 Integer). 그대로 추가한다.
ALTER TABLE games
    ADD CONSTRAINT ck_games_current_inning CHECK (current_inning BETWEEN 1 AND 11);


-- 4-2. inning_half — 4-0 에서 자동 생성 CHECK 를 발견했다면 **먼저 지우고** 우리 이름으로 다시 건다.
--      MySQL 은 논리적으로 중복인 CHECK 를 막지 않으므로, 지우지 않고 추가하면 같은 뜻의 제약이
--      두 개 남는다(스키마가 깨지진 않지만 환경마다 제약 개수가 달라져 "이미 걸렸는지" 판단이 무너진다).
--      <자동생성명> 자리에 4-0 에서 확인한 실제 이름을 넣을 것(devdb 관측값은 games_chk_1).
-- ALTER TABLE games DROP CHECK <자동생성명>;

ALTER TABLE games
    ADD CONSTRAINT ck_games_inning_half CHECK (inning_half IN (0, 1));


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 컬럼과 제약이 실제로 걸렸는지
--    SHOW CREATE TABLE games;
--    -- 기대: `current_inning` tinyint DEFAULT NULL / `inning_half` tinyint DEFAULT NULL 두 줄과
--    --       CONSTRAINT `ck_games_current_inning` CHECK ((`current_inning` between 1 and 11))
--    --       CONSTRAINT `ck_games_inning_half` CHECK ((`inning_half` in (0,1)))
--    -- 그리고 `games_chk_N` 형태의 자동 생성 CHECK 가 **남아 있지 않아야** 한다(4-2 에서 지웠다면).
--    -- 남아 있으면 같은 뜻의 제약이 둘이라는 뜻이므로 4-2 의 DROP 을 놓친 것이다.
--
-- 2) 행이 그대로인지 (ALTER 는 데이터를 바꾸지 않는다)
--    SELECT COUNT(*) FROM games;
--    SELECT COUNT(*) FROM games WHERE current_inning IS NOT NULL;  -- 기대: 수집기 구현 전이면 0
--
-- 3) NULL 이 여전히 허용되는지 (CHECK 는 UNKNOWN 을 통과시킨다)
--    UPDATE games SET current_inning = NULL, inning_half = NULL WHERE id = (SELECT MIN(id) FROM (SELECT id FROM games) t);
--    -- 기대: 정상 실행 (Rows matched 1)
--
-- 4) 실제로 막는지 (선택 — 되돌아갈 것)
--    UPDATE games SET current_inning = 12 WHERE id = (SELECT MIN(id) FROM (SELECT id FROM games) t);
--    -- 기대: ERROR 3819 Check constraint 'ck_games_current_inning' is violated.
--    UPDATE games SET inning_half = 2 WHERE id = (SELECT MIN(id) FROM (SELECT id FROM games) t);
--    -- 기대: ERROR 3819 Check constraint 'ck_games_inning_half' is violated.
-- ============================================================================
