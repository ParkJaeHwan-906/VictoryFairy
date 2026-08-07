-- ============================================================================
-- positions.name 을 화면 표시용 정식 명칭으로 통일 (dev/prod 공통, MySQL 8.0)
--
-- 배경: `positions.name` 은 API 응답의 `positionName` 으로 **가공 없이** 나간다
-- (GameLineupResponse). 그런데 지금 값이 두 갈래로 섞여 있어 사용자가 읽을 수 없다.
--   (1) py-collector 가 변환한 자체 영문 약어 12종 — `P` `C` `1B` … `PH` `PR`
--   (2) 변환에 걸리지 않은 네이버 원문 조합 — `중좌` `타二` `三유` …
--       (경기 중 수비 위치를 바꾼 선수. 2026-08-04 dev DB 기준 58종, 로컬 30종 관측)
-- 이 파일은 둘 다 정식 명칭(`투수`/`중견수`/`1루수`/`대타` …) 12종으로 수렴시킨다.
-- 조합은 **첫 글자 = 그 경기 시작 위치**로 접는다(사용자 결정) — 라인업 화면은
-- "이 선수가 어디로 나왔나"를 보여주므로 시작 위치가 맞고, 접지 않으면 조합이
-- 12x12 까지 늘어 코드테이블이 계속 불어난다.
--
-- 스키마 변경은 없다(값만 바뀜) — `ddl-auto: update` 와 무관하게 이 파일만 돌리면 된다.
-- 짝이 되는 수집기 변경은 py-collector `db.py` 의 `POSITION_NAMES`/`position_name`.
--
-- **재실행 안전(멱등)하고 배포 순서에 무관하다.** 수집기 새 이미지가 먼저 떠서
-- 정식 명칭 행을 이미 만들어 놨더라도, 이 파일은 비-정식 행을 정식 행으로 병합하는
-- 방식이라 중복이 생기지 않는다. 반대 순서(이 파일 먼저)도 안전하다.
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 변환표 두 벌. MySQL 임시 테이블은 한 쿼리에서 두 번 참조할 수 없어
--    (ERROR 1137 Can't reopen table) 정확 일치용과 첫 글자용을 따로 만든다.
--    1·2·3루를 네이버가 한자(一/二/三)로 보내는 것에 주의.
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS pos_canon;
DROP TEMPORARY TABLE IF EXISTS pos_first;

-- (a) 정확 일치용 — 기존 영문 약어 12종 + 네이버 원문 단일 표기 12종
CREATE TEMPORARY TABLE pos_canon (
  raw  VARCHAR(10)  NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL
);
INSERT INTO pos_canon (raw, name) VALUES
  ('P','투수'),   ('C','포수'),   ('1B','1루수'), ('2B','2루수'), ('3B','3루수'),
  ('SS','유격수'),('LF','좌익수'),('CF','중견수'),('RF','우익수'),('DH','지명타자'),
  ('PH','대타'),  ('PR','대주자'),
  ('투','투수'),  ('포','포수'),  ('一','1루수'), ('二','2루수'), ('三','3루수'),
  ('유','유격수'),('좌','좌익수'),('중','중견수'),('우','우익수'),('지','지명타자'),
  ('타','대타'),  ('주','대주자');

-- (b) 첫 글자용 — 네이버 원문 단일 표기만. 영문 약어를 넣으면 'CF' 의 첫 글자 'C' 가
--     '포수' 로 잘못 접힌다.
CREATE TEMPORARY TABLE pos_first (
  raw  VARCHAR(10)  NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL
);
INSERT INTO pos_first (raw, name) VALUES
  ('투','투수'),  ('포','포수'),  ('一','1루수'), ('二','2루수'), ('三','3루수'),
  ('유','유격수'),('좌','좌익수'),('중','중견수'),('우','우익수'),('지','지명타자'),
  ('타','대타'),  ('주','대주자');

-- ---------------------------------------------------------------------------
-- 1. 정식 명칭 12행 확보 (없는 것만 생성). name 에 UNIQUE 가 없어 NOT EXISTS 로 막는다.
-- ---------------------------------------------------------------------------
INSERT INTO positions (name, created_at, updated_at)
SELECT t.name, NOW(6), NOW(6)
FROM (SELECT DISTINCT name FROM pos_canon) t
WHERE NOT EXISTS (SELECT 1 FROM positions p WHERE p.name = t.name);

-- ---------------------------------------------------------------------------
-- 2. 비-정식 행 -> 정식 행 매핑. 정확 일치(약어·단일 원문)를 먼저 넣고,
--    거기서 안 잡힌 2글자 이상 표기를 첫 글자로 접는다(INSERT IGNORE 로 중복 방지).
--    정식 명칭 자체는 `p.name <> k.name` 조건에서 걸러진다
--    (예: '중견수' -> 첫 글자 '중' -> '중견수' = 자기 자신이라 제외).
--    canonical 이 중복 존재하는 DB 도 있을 수 있어 MIN(id) 로 하나만 고른다.
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS pos_remap;
CREATE TEMPORARY TABLE pos_remap (
  old_id BIGINT NOT NULL PRIMARY KEY,
  new_id BIGINT NOT NULL
);

-- (a) 정확 일치: 영문 약어 12종 + 아직 변환 안 된 단일 원문
INSERT INTO pos_remap (old_id, new_id)
SELECT p.id, c.id
FROM positions p
JOIN pos_canon k ON k.raw = p.name
JOIN (SELECT name, MIN(id) AS id FROM positions GROUP BY name) c ON c.name = k.name
WHERE p.name <> k.name;

-- (b) 조합 표기: 첫 글자로 접기. 단일 원문(1글자)은 (a)에서 처리됐고,
--     정식 명칭은 `p.name <> k.name` 으로 빠진다.
INSERT IGNORE INTO pos_remap (old_id, new_id)
SELECT p.id, c.id
FROM positions p
JOIN pos_first k ON k.raw = LEFT(p.name, 1)
JOIN (SELECT name, MIN(id) AS id FROM positions GROUP BY name) c ON c.name = k.name
WHERE CHAR_LENGTH(p.name) >= 2 AND p.name <> k.name;

-- 매핑에서 빠진 행이 있는지 먼저 볼 것 — 첫 글자조차 변환표에 없는 미지 표기다.
-- 0 이 아니면 값 목록을 확인하고(아래 두 번째 쿼리) 변환표 보강 여부를 상의한다.
SELECT COUNT(*) AS unmapped_rows
FROM positions p
WHERE p.name NOT IN (SELECT name FROM pos_canon)
  AND p.id NOT IN (SELECT old_id FROM pos_remap);
SELECT p.id, p.name
FROM positions p
WHERE p.name NOT IN (SELECT name FROM pos_canon)
  AND p.id NOT IN (SELECT old_id FROM pos_remap);

-- ---------------------------------------------------------------------------
-- 3. game_lineups FK 재지정 -> 고아가 된 positions 행 삭제 (순서 중요: FK 제약)
-- ---------------------------------------------------------------------------
UPDATE game_lineups gl
JOIN pos_remap r ON gl.position_id = r.old_id
SET gl.position_id = r.new_id, gl.updated_at = NOW(6);

DELETE p FROM positions p JOIN pos_remap r ON p.id = r.old_id;

-- ---------------------------------------------------------------------------
-- 4. 검증 — 아래 3개가 전부 0 이어야 한다.
-- ---------------------------------------------------------------------------
-- (a) 정식 명칭이 아닌 행이 남았나 (2번 unmapped 를 남겨뒀다면 그 수만큼 나온다)
SELECT COUNT(*) AS non_canonical_positions
FROM positions WHERE name NOT IN (SELECT name FROM pos_canon);

-- (b) 같은 이름이 두 행 이상인가 (lookup-or-insert 가 중복을 만들면 여기서 잡힌다)
SELECT COUNT(*) AS duplicated_names FROM (
  SELECT name FROM positions GROUP BY name HAVING COUNT(*) > 1
) d;

-- (c) 끊어진 FK 가 있나
SELECT COUNT(*) AS dangling_lineups
FROM game_lineups gl
WHERE gl.position_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM positions p WHERE p.id = gl.position_id);

-- 최종 분포 확인 (12행이어야 정상)
SELECT p.name, COUNT(gl.id) AS lineup_rows
FROM positions p LEFT JOIN game_lineups gl ON gl.position_id = p.id
GROUP BY p.id, p.name ORDER BY lineup_rows DESC;

DROP TEMPORARY TABLE IF EXISTS pos_remap;
DROP TEMPORARY TABLE IF EXISTS pos_canon;
DROP TEMPORARY TABLE IF EXISTS pos_first;
