-- ============================================================================
-- teams 시드 (prod 전용, MySQL 8.0)
--
-- 배경: 저장소에 teams 시드가 없어 운영 DB의 teams 가 0행이었다. 그 상태에서는
-- chat-init.sql Step 3(팀 이름으로 조인해 구단별 채팅방 생성)이 **에러 없이 0행**만
-- 넣고 끝난다. 그래서 chat-init.sql 보다 먼저 이 파일을 적용해야 한다.
--
-- 적용 순서:  teams-init.sql  →  chat-init.sql
--
-- ⚠ name/code 는 임의로 정한 값이 아니다. py-collector 의 팀 차원 정의
--   (VictoryFairy_AI/py-collector/kbo_collector/dimensions.py 의 TEAMS)를 그대로 옮겼다.
--   code 는 Team.java 주석대로 "py-collector 가 재실행해도 중복 없이 upsert 하기 위한
--   소스 자연키(UNIQUE)"이므로, 값을 비워두거나 다르게 넣으면 수집기가 같은 구단을
--   자기 코드로 한 번 더 INSERT 해 **팀이 중복 행으로 갈라진다**(그리고 채팅방은 먼저
--   만들어진 쪽에 묶인 채 남는다). 수집기 쪽 TEAMS 가 바뀌면 이 파일도 함께 맞출 것.
--
-- 재실행 안전: code(UNIQUE) 기준 anti-join 이라 이미 있는 구단은 건너뛴다.
--   이름만 다르고 code 가 같은 행이 이미 있으면 이 파일은 이름을 덮어쓰지 않는다
--   (수집기가 소유한 값이므로 의도적으로 건드리지 않는다).
-- ============================================================================

INSERT INTO teams (name, code, created_at, updated_at)
SELECT seed.name, seed.code, NOW(6), NOW(6)
FROM (
    SELECT '두산' AS name, 'OB' AS code
    UNION ALL SELECT 'LG', 'LG'
    UNION ALL SELECT '삼성', 'SS'
    UNION ALL SELECT 'KT', 'KT'
    UNION ALL SELECT '키움', 'WO'
    UNION ALL SELECT 'KIA', 'HT'
    UNION ALL SELECT '한화', 'HH'
    UNION ALL SELECT 'NC', 'NC'
    UNION ALL SELECT '롯데', 'LT'
    UNION ALL SELECT 'SSG', 'SK'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM teams t WHERE t.code = seed.code);


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 10행이 다 들어갔는지
--    SELECT COUNT(*) FROM teams;                       -- 기대: 10
--    SELECT id, name, code FROM teams ORDER BY id;
--
-- 2) 동명 팀이 갈라지지 않았는지 (chat-init.sql 이 이름으로 조인하므로 중요)
--    SELECT name, COUNT(*) FROM teams GROUP BY name HAVING COUNT(*) > 1;   -- 기대: 0행
-- ============================================================================
