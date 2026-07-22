-- 운영 서비스 스키마(도메인 JPA 엔티티와 동일 구조) + 수집기 매핑 컬럼. MySQL 8.0.
--
-- 소유권: 테이블 구조의 원천은 domain 모듈의 JPA 엔티티다
--   (Team/Player/Stadium/GameStatus/Game/GameLineup — 운영은 ddl-auto:none 이라
--   실제 반영은 이 파일을 수동 실행). 수집기는 여기에 "쓰기"만 한다.
-- 매핑 컬럼: teams.code / players.naver_pcode / players.kbo_player_id /
--   games.naver_game_id 는 수집기가 재실행해도 중복 없이 upsert 하기 위한
--   소스 자연키(UNIQUE)다. 서비스 로직은 몰라도 된다.
-- 기존(구 수집기) 스키마에서 넘어올 때는 migrate-legacy-collector.sql 을 먼저 실행.

CREATE TABLE IF NOT EXISTS teams (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  code       VARCHAR(4)   NULL,               -- KBO 구단 코드(LG, OB …)
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_teams_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stadiums (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_statuses (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,           -- SCHEDULED/IN_PROGRESS/FINISHED/DRAW/CANCELED
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS players (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id       BIGINT       NOT NULL,
  name          VARCHAR(100) NOT NULL,
  average       DOUBLE       NOT NULL DEFAULT 0,
  naver_pcode   VARCHAR(16)  NULL,            -- 네이버 record API pcode
  kbo_player_id VARCHAR(16)  NULL,            -- KBO 공식 playerId (pcode와 다른 체계)
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_players_naver_pcode (naver_pcode),
  UNIQUE KEY uq_players_kbo_player_id (kbo_player_id),
  KEY idx_players_team_name (team_id, name),
  CONSTRAINT fk_players_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS games (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  game_date      DATETIME(6) NOT NULL,        -- 경기 시작 일시(시간 포함)
  home_team_id   BIGINT      NOT NULL,
  away_team_id   BIGINT      NOT NULL,
  stadium_id     BIGINT      NULL,
  home_score     INT         NULL,
  away_score     INT         NULL,
  game_status_id BIGINT      NOT NULL,
  naver_game_id  VARCHAR(20) NULL,            -- 네이버 gameId (더블헤더 구분 자연키)
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_games_naver_game_id (naver_game_id),
  KEY idx_games_game_date (game_date),
  CONSTRAINT fk_games_home_team FOREIGN KEY (home_team_id) REFERENCES teams(id),
  CONSTRAINT fk_games_away_team FOREIGN KEY (away_team_id) REFERENCES teams(id),
  CONSTRAINT fk_games_stadium   FOREIGN KEY (stadium_id) REFERENCES stadiums(id),
  CONSTRAINT fk_games_status    FOREIGN KEY (game_status_id) REFERENCES game_statuses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 경기별 출전 명단(교체 포함). is_starter=TRUE 만 조회하면 선발 라인업(타순 1~9 + 선발투수).
CREATE TABLE IF NOT EXISTS game_lineups (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  game_id    BIGINT NOT NULL,
  team_id    BIGINT NOT NULL,
  player_id  BIGINT NOT NULL,
  bat_order  INT         NULL,                -- 타순 1~9 (투수 등 타석 없으면 NULL)
  position   VARCHAR(10) NULL,                -- 포지션 표기(중/포/지/투 …, 대타=타/대주자=주)
  is_starter BOOLEAN NOT NULL DEFAULT FALSE,  -- 선발 출전 여부
  decision   VARCHAR(2)  NULL,                -- 투수 한정 W/L/S/H
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_game_lineups_game_player (game_id, player_id),
  KEY idx_game_lineups_player (player_id),
  CONSTRAINT fk_game_lineups_game   FOREIGN KEY (game_id)   REFERENCES games(id)   ON DELETE CASCADE,
  CONSTRAINT fk_game_lineups_team   FOREIGN KEY (team_id)   REFERENCES teams(id)   ON DELETE CASCADE,
  CONSTRAINT fk_game_lineups_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 시드 (멱등: 있으면 건너뜀)
-- ============================================================

-- 경기 상태 코드 (GameStatus javadoc 의 5종)
INSERT INTO game_statuses (name)
SELECT s.name FROM (SELECT 'SCHEDULED' name UNION SELECT 'IN_PROGRESS'
  UNION SELECT 'FINISHED' UNION SELECT 'DRAW' UNION SELECT 'CANCELED') s
WHERE NOT EXISTS (SELECT 1 FROM game_statuses g WHERE g.name = s.name);

-- 팀 코드 백필: 이미 이름으로만 시드된 행이 있으면 코드를 붙인다(중복 INSERT 방지).
UPDATE teams SET code='OB' WHERE code IS NULL AND name IN ('두산','두산 베어스');
UPDATE teams SET code='LG' WHERE code IS NULL AND name IN ('LG','LG 트윈스');
UPDATE teams SET code='SS' WHERE code IS NULL AND name IN ('삼성','삼성 라이온즈');
UPDATE teams SET code='KT' WHERE code IS NULL AND name IN ('KT','KT 위즈','kt wiz');
UPDATE teams SET code='WO' WHERE code IS NULL AND name IN ('키움','키움 히어로즈');
UPDATE teams SET code='HT' WHERE code IS NULL AND name IN ('KIA','KIA 타이거즈');
UPDATE teams SET code='HH' WHERE code IS NULL AND name IN ('한화','한화 이글스');
UPDATE teams SET code='NC' WHERE code IS NULL AND name IN ('NC','NC 다이노스');
UPDATE teams SET code='LT' WHERE code IS NULL AND name IN ('롯데','롯데 자이언츠');
UPDATE teams SET code='SK' WHERE code IS NULL AND name IN ('SSG','SSG 랜더스');
-- 코드가 없는 팀은 새로 넣는다(이름은 dimensions.TEAMS 의 짧은 이름과 동일).
INSERT INTO teams (name, code)
SELECT t.name, t.code FROM (
  SELECT '두산' name,'OB' code UNION SELECT 'LG','LG' UNION SELECT '삼성','SS'
  UNION SELECT 'KT','KT' UNION SELECT '키움','WO' UNION SELECT 'KIA','HT'
  UNION SELECT '한화','HH' UNION SELECT 'NC','NC' UNION SELECT '롯데','LT'
  UNION SELECT 'SSG','SK') t
WHERE NOT EXISTS (SELECT 1 FROM teams x WHERE x.code = t.code);
