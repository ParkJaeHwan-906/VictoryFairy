-- ============================================================================
-- games.removed 추가 (1회성, MySQL 8.0 — prod/devdb 수동 실행)
--
-- 네이버 일정에서 사라진 경기 표시(Game.removed). py-collector 가 채우고 BE 조회가 거른다.
--
-- 손으로 먼저 거는 이유: user 앱이 ddl-auto=update 라 배포만으로도 붙지만, 수집기가 이 컬럼을 쓰는
-- 시점과 BE 배포 시점이 엇갈린다. 컬럼이 먼저 있어야 둘 중 무엇이 먼저 나가도 안전하다.
-- DEFAULT 0 이 있어야 이 컬럼을 모르는 쓰기(옛 수집기 INSERT)가 strict 모드에서 죽지 않는다.
--
-- ⚠ spring.sql.init.data-locations 에 넣지 말 것 — 재실행하면 ERROR 1060 으로 전 파드 기동 실패.
-- ============================================================================

-- 1) 이미 있는지 확인 — 1행이면 2) 를 건너뛴다(user 앱 기동이 이미 만들었다).
SHOW COLUMNS FROM games WHERE Field = 'removed';

-- 2) 추가 — 1) 이 0행일 때만. 기존 행은 전부 0 으로 채워진다.
ALTER TABLE games ADD COLUMN removed TINYINT(1) NOT NULL DEFAULT 0;

-- 3) 확인 — 기대: Type tinyint(1), Null NO, Default 0 / 그리고 removed=1 행은 0건
SHOW COLUMNS FROM games WHERE Field = 'removed';
SELECT COUNT(*) FROM games WHERE removed = 1;
