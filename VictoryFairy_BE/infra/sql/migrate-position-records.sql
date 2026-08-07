-- ============================================================================
-- position·record 스키마 재구축 + players.naver_pcode 단일화 (prod 전용, MySQL 8.0)
--
-- 배경: domain 모듈 3개 커밋(0bf8864/459a726/615354c)이 JPA 엔티티를 아래처럼
-- 바꿨다. user 앱 prod 프로파일은 `ddl-auto: update`라 Hibernate가 "새로 생기는"
-- 컬럼/테이블은 기동 시 알아서 만들지만, "없어져야 하는" 컬럼/테이블은 절대
-- 지우지 않는다(update는 DROP을 내지 않음 — .claude/modules/domain.md "prod
-- 스키마는 user 앱이 만든다" 참고). 그래서 이 파일이 수동으로 필요한 부분은
-- 오직 DROP 계열(컬럼 제거·테이블 제거)이고, CREATE 계열은 실제로는 이미 존재할
-- 가능성이 높다(그래도 재실행 대비 `IF NOT EXISTS`/재현 가능하게 남겨둔다).
--   1. `game_lineups.position`(VARCHAR 텍스트) → `positions` 코드테이블 FK
--      (`position_id`)로 전환 (Position.java / GameLineup.java, 커밋 0bf8864)
--   2. `batter_records`/`pitcher_records`를 이벤트형(FK: `bat_results`/
--      `pitch_results`)에서 경기×선수 1행 집계형으로 재설계, 두 결과코드
--      테이블은 폐기 (BatterRecord.java / PitcherRecord.java, 커밋 459a726)
--   3. `players.naver_pcode` 폐기 — `kbo_player_id`로 단일화 (Player.java, 커밋 615354c)
--
-- 적용 순서: 이 파일 안에서 위 1 → 2 → 3 순서로 실행한다(서로 독립적이라
-- 순서를 바꿔도 안전하지만, 리뷰 편의상 커밋 순서와 맞췄다). 3개 스텝 모두
-- **선행 조건(아래) 확인 전에는 실행하지 말 것** — 특히 스텝 2는 기존 행을
-- 통째로 버리는 DROP TABLE 이라 데이터 유실 방지 확인이 필수다.
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
--
-- ============================================================================
-- 선행 조건 (실행 전 반드시 사람이 직접 확인 — 하나라도 위반이면 중단하고 상의)
-- ============================================================================
-- (A) 스텝 2(record 재구축)는 기존 batter_records/pitcher_records/bat_results/
--     pitch_results 네 테이블을 DROP 한다 — **소비처가 없다는 전제**(domain.md
--     "stadium/record 엔티티를 소비하는 서비스·컨트롤러는 아직 없음")로만 안전하다.
--     아래가 전부 0이어야 한다(하나라도 0이 아니면 데이터가 있다는 뜻이니
--     DROP 하지 말고 먼저 상의할 것):
--       SELECT COUNT(*) FROM batter_records;
--       SELECT COUNT(*) FROM pitcher_records;
--       SELECT COUNT(*) FROM bat_results;
--       SELECT COUNT(*) FROM pitch_results;
-- (B) 스텝 3(pcode 단일화)은 `players.kbo_player_id`로 `naver_pcode` 값을 흡수하고
--     `naver_pcode` 컬럼을 지운다 — 실측(2026-07 교집합 228명 전수 일치)으로
--     "두 컬럼이 항상 같은 사람을 가리킨다"를 확인했지만, 그 실측 이후 데이터가
--     바뀌었을 수 있으니 재확인 없이 이 파일을 그대로 믿지 말 것. 스텝 3 블록
--     안의 검증 쿼리 2개(값 충돌 / 같은 인물 중복 행)가 둘 다 0이어야 한다.
-- (C) 스텝 1(position FK 전환)은 데이터 유실 위험이 상대적으로 낮다(값을
--     버리지 않고 코드테이블로 정규화 이관) — 그래도 `ALTER TABLE game_lineups
--     DROP COLUMN position` 은 되돌릴 수 없으니, 이관 후 `position_id` 가 원래
--     `position` 값과 다 맞물렸는지 파일 끝 검증 쿼리로 먼저 확인할 것.
-- ============================================================================


-- ============================================================================
-- 스텝 1. game_lineups.position(텍스트) → positions 코드테이블 FK
-- (Position.java / GameLineup.java 매핑과 1:1. positions 테이블 자체는
-- 재실행 안전: IF NOT EXISTS. 나머지 ALTER/UPDATE/DROP은 1회성이라 안전장치 없음
-- — 재실행하면 position_id 컬럼이 이미 있어 ALTER가 에러난다, 그게 의도다.)
-- ============================================================================

CREATE TABLE IF NOT EXISTS positions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL
);

-- 포지션 표기 매핑 (네이버 원문 → 자체 영문 약어). 매핑에 없는 표기는 원문 그대로.
-- (수집기 kbo_collector/db.py POSITION_CODES 와 동일해야 한다.)
-- 아래 INSERT/UPDATE 두 문장이 반드시 같은 매핑을 쓰도록 임시 테이블에 한 번만
-- 정의한다 — 두 곳에 따로 하드코딩하면 한쪽만 고쳐졌을 때 position_id 가
-- NULL 로 남는 이관 누락이 생긴다.
CREATE TEMPORARY TABLE pos_map (
  raw  VARCHAR(8) PRIMARY KEY,
  code VARCHAR(8) NOT NULL
);
INSERT INTO pos_map (raw, code) VALUES
  ('투','P'),  ('포','C'),  ('一','1B'), ('二','2B'), ('三','3B'), ('유','SS'),
  ('좌','LF'), ('중','CF'), ('우','RF'), ('지','DH'), ('타','PH'), ('주','PR');

-- 기존 game_lineups.position 텍스트값을 위 매핑으로 정규화(COALESCE(약어, 원문))해
-- 중복 없이 코드테이블로 이관.
INSERT INTO positions (name, created_at, updated_at)
SELECT DISTINCT COALESCE(m.code, gl.position), NOW(6), NOW(6)
FROM game_lineups gl
LEFT JOIN pos_map m ON m.raw = gl.position
WHERE gl.position IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM positions p WHERE p.name = COALESCE(m.code, gl.position));

ALTER TABLE game_lineups
  ADD COLUMN position_id BIGINT NULL,
  ADD CONSTRAINT fk_game_lineups_position FOREIGN KEY (position_id) REFERENCES positions(id);

-- GameLineup.position 은 @OnDelete 미지정(마스터 데이터 정책) — 위 FK도
-- ON DELETE 절을 생략해 InnoDB 기본 RESTRICT 그대로 둔다(positions 삭제 시
-- game_lineups 연쇄 삭제 금지). JOIN 조건도 같은 pos_map 매핑(COALESCE)을 써야
-- INSERT 로 만든 행(약어 name)과 정확히 맞물린다.
UPDATE game_lineups gl
LEFT JOIN pos_map m ON m.raw = gl.position
JOIN positions p ON p.name = COALESCE(m.code, gl.position)
SET gl.position_id = p.id;

DROP TEMPORARY TABLE pos_map;

ALTER TABLE game_lineups DROP COLUMN position;


-- ============================================================================
-- 스텝 2. record 계열: 이벤트형(BatResult/PitchResult FK) → 집계형 재구축
-- (BatterRecord.java / PitcherRecord.java 매핑과 1:1. 선행 조건 (A) 확인 후에만
-- 실행 — 네 테이블 다 빈 테이블이라는 전제로 FK 역순 DROP 후 재생성한다.)
-- ============================================================================

DROP TABLE IF EXISTS batter_records;
DROP TABLE IF EXISTS pitcher_records;
DROP TABLE IF EXISTS bat_results;
DROP TABLE IF EXISTS pitch_results;

CREATE TABLE batter_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  game_id BIGINT NOT NULL,
  at_bats INT NULL, runs INT NULL, hits INT NULL, home_runs INT NULL,
  rbi INT NULL, walks INT NULL, strikeouts INT NULL, stolen_bases INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_batter_records_game_player (game_id, player_id),
  CONSTRAINT fk_batter_records_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
  CONSTRAINT fk_batter_records_game   FOREIGN KEY (game_id)   REFERENCES games(id)   ON DELETE CASCADE
);

CREATE TABLE pitcher_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  game_id BIGINT NOT NULL,
  seq INT NOT NULL,
  ip_display VARCHAR(8) NULL, ip_outs INT NULL,
  batters_faced INT NULL, at_bats INT NULL, hits INT NULL, runs INT NULL,
  earned_runs INT NULL, home_runs INT NULL, walks_hbp INT NULL, strikeouts INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pitcher_records_game_player (game_id, player_id),
  CONSTRAINT fk_pitcher_records_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
  CONSTRAINT fk_pitcher_records_game   FOREIGN KEY (game_id)   REFERENCES games(id)   ON DELETE CASCADE
);


-- ============================================================================
-- 스텝 3. players.naver_pcode 단일화: 실측 동치(2026-07 교집합 228명 전수 일치)
-- 근거로 kbo_player_id 통합. (Player.java 매핑과 1:1 — kbo_player_id 는 이미
-- 존재하는 컬럼이라 CREATE/ALTER ADD 없이 값 백필 + naver_pcode DROP만 한다.)
-- 실행 전 확인: 아래 두 검증 쿼리가 모두 0 이어야 한다 (0이 아니면 중단하고 상의).
--   SELECT COUNT(*) FROM players
--     WHERE naver_pcode IS NOT NULL AND kbo_player_id IS NOT NULL
--       AND naver_pcode != kbo_player_id;                        -- 값 충돌
--   SELECT COUNT(*) FROM players p1 JOIN players p2
--     ON p1.naver_pcode = p2.kbo_player_id AND p1.id != p2.id;   -- 같은 인물 중복 행
-- ============================================================================

UPDATE players SET kbo_player_id = naver_pcode
WHERE kbo_player_id IS NULL AND naver_pcode IS NOT NULL;

ALTER TABLE players DROP COLUMN naver_pcode;


-- ============================================================================
-- 실행 후: user 앱 ddl-auto=update 는 위 결과와 이미 맞는 스키마를 발견하므로
-- 추가 DDL이 없다(재기동해도 아무 것도 바뀌지 않아야 정상).
-- ============================================================================


-- ============================================================================
-- 검증 쿼리 (적용 후 수동으로 돌려볼 것 — 이 파일이 자동 실행하지는 않음)
-- ============================================================================
-- 1) 스텝 1 이관 정합성: position_id가 채워지지 않은 채 남은 행 수.
--    SELECT COUNT(*) FROM game_lineups WHERE position_id IS NULL;
--    -- 원래부터 position 이 NULL 이었던 행(포지션 미상)도 이 카운트에 포함된다 —
--    -- 이관 전에 `SELECT COUNT(*) FROM game_lineups WHERE position IS NULL;`을
--    -- 미리 세어 두고 위 결과와 같아야(그 이상이면 이관 누락) 정상이다.
--
-- 2) 스텝 1 positions 테이블 행수: game_lineups.position 원본 DISTINCT 값 개수와
--    일치해야 한다(매핑이 1:1 치환이라 개수는 그대로, 값만 약어로 바뀐다 —
--    이관 전에 미리 세어둘 것).
--    SELECT COUNT(*) FROM positions;
--    -- 기대 값 예시: '투'→'P', '중'→'CF', '포'→'C' 등(매핑에 없는 원문은 그대로 유지).
--
-- 3) 스텝 2 재구축 후 두 테이블이 빈 테이블로 새로 만들어졌는지(FK 포함):
--    SELECT COUNT(*) FROM batter_records;   -- 기대: 0
--    SELECT COUNT(*) FROM pitcher_records;  -- 기대: 0
--    SHOW CREATE TABLE batter_records;      -- FK CASCADE, UNIQUE(game_id, player_id) 확인
--    SHOW CREATE TABLE pitcher_records;
--
-- 4) 스텝 3 백필 후 naver_pcode 컬럼이 사라졌는지 + kbo_player_id 손실이 없는지:
--    DESCRIBE players;                                          -- naver_pcode 없어야 함
--    SELECT COUNT(*) FROM players WHERE kbo_player_id IS NULL;  -- 백필 전 NULL 이었던 행수와 비교
-- ============================================================================
