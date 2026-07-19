-- KBO 1군 로스터 적재 스키마 (collector 소유). MySQL 8.0.
CREATE TABLE IF NOT EXISTS teams (
  team_code  VARCHAR(4)  PRIMARY KEY,
  name       VARCHAR(20) NOT NULL,
  full_name  VARCHAR(40),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS players (
  player_id          VARCHAR(16) PRIMARY KEY,
  name               VARCHAR(30) NOT NULL,
  team_code          VARCHAR(4),
  back_number        VARCHAR(4),
  position           VARCHAR(10),
  throw_bat          VARCHAR(10),
  birth_date         DATE,
  height_cm          SMALLINT,
  weight_kg          SMALLINT,
  is_first_team      BOOLEAN NOT NULL DEFAULT TRUE,
  last_registered_on DATE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_player_team FOREIGN KEY (team_code) REFERENCES teams(team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_registrations (
  snapshot_date DATE        NOT NULL,
  player_id     VARCHAR(16) NOT NULL,
  team_code     VARCHAR(4)  NOT NULL,
  PRIMARY KEY (snapshot_date, player_id),
  CONSTRAINT fk_reg_player FOREIGN KEY (player_id) REFERENCES players(player_id),
  CONSTRAINT fk_reg_team   FOREIGN KEY (team_code) REFERENCES teams(team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 경기 기록(박스스코어) 적재. 소스: 네이버 스포츠 record API.
-- ============================================================

-- 경기 데이터에 등장하는 선수의 '우리 독자 식별자' 레지스트리.
-- player_uid = 내부 canonical id (네이버 pcode와 분리).
-- naver_pcode = 소스 매핑키, kbo_player_id = 기존 1군 로스터(players)와의 best-effort 링크.
CREATE TABLE IF NOT EXISTS game_players (
  player_uid    BIGINT      AUTO_INCREMENT PRIMARY KEY,
  naver_pcode   VARCHAR(16) NOT NULL UNIQUE,
  name          VARCHAR(30) NOT NULL,
  team_code     VARCHAR(4),
  kbo_player_id VARCHAR(16),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_gp_kbo (kbo_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS games (
  game_id          VARCHAR(20) PRIMARY KEY,   -- 네이버 gameId
  game_date        DATE        NOT NULL,
  game_type        VARCHAR(12) NOT NULL,      -- regular / preseason
  round_no         INT,
  stadium          VARCHAR(20),
  start_time       VARCHAR(8),
  away_team_code   VARCHAR(4)  NOT NULL,
  home_team_code   VARCHAR(4)  NOT NULL,
  away_score       INT,
  home_score       INT,
  away_hits        INT,
  home_hits        INT,
  away_errors      INT,
  home_errors      INT,
  away_bb          INT,
  home_bb          INT,
  winner           VARCHAR(4),                -- away / home / draw
  away_starter_uid BIGINT,
  home_starter_uid BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_game_away FOREIGN KEY (away_team_code) REFERENCES teams(team_code),
  CONSTRAINT fk_game_home FOREIGN KEY (home_team_code) REFERENCES teams(team_code),
  KEY idx_game_date (game_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 이닝별 득점(라인스코어). 한 경기당 (원정/홈) × 이닝 수 만큼 행.
CREATE TABLE IF NOT EXISTS game_innings (
  game_id VARCHAR(20) NOT NULL,
  inning  INT         NOT NULL,
  is_home BOOLEAN     NOT NULL,
  runs    INT         NOT NULL,
  PRIMARY KEY (game_id, is_home, inning),
  CONSTRAINT fk_gi_game FOREIGN KEY (game_id) REFERENCES games(game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_pitching (
  game_id       VARCHAR(20) NOT NULL,
  player_uid    BIGINT      NOT NULL,
  team_code     VARCHAR(4)  NOT NULL,
  is_home       BOOLEAN     NOT NULL,
  seq           INT         NOT NULL,          -- 등판 순서(0=선발)
  decision      VARCHAR(2),                    -- W/L/S/H, 없으면 NULL
  ip_display    VARCHAR(8),                    -- "6", "6 ⅓"
  ip_outs       INT,                           -- 아웃 카운트(6 ⅓ = 19)
  batters_faced INT,
  at_bats       INT,
  hits          INT,
  runs          INT,
  earned_runs   INT,
  home_runs     INT,
  walks_hbp     INT,
  strikeouts    INT,
  PRIMARY KEY (game_id, player_uid),
  CONSTRAINT fk_gp_game   FOREIGN KEY (game_id)    REFERENCES games(game_id),
  CONSTRAINT fk_gp_player FOREIGN KEY (player_uid) REFERENCES game_players(player_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_batting (
  game_id       VARCHAR(20) NOT NULL,
  player_uid    BIGINT      NOT NULL,
  team_code     VARCHAR(4)  NOT NULL,
  is_home       BOOLEAN     NOT NULL,
  bat_order     INT,
  position      VARCHAR(4),
  at_bats       INT,
  runs          INT,
  hits          INT,
  home_runs     INT,
  rbi           INT,
  walks         INT,
  strikeouts    INT,
  stolen_bases  INT,
  PRIMARY KEY (game_id, player_uid),
  CONSTRAINT fk_gb_game   FOREIGN KEY (game_id)    REFERENCES games(game_id),
  CONSTRAINT fk_gb_player FOREIGN KEY (player_uid) REFERENCES game_players(player_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
