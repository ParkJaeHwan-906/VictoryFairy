-- ============================================================================
-- game_statuses 시드 (MySQL 8.0)
--
-- 배경: `Game.gameStatus` 가 non-null FK 라 이 테이블이 비어 있으면 경기 저장 자체가
-- 불가능하다. 그동안 이 5행을 채우는 수단이 없어(.claude/modules/domain.md 의
-- "game_statuses 시드 없음"), py-collector 가 경기를 적재하다 처음 만나는 상태명을
-- lookup-or-insert 로 그때그때 만들어 왔다(`kbo_collector/db.py` 의 STATUS_SELECT/
-- STATUS_INSERT). 그래서 **실제로 일어난 상태만** 행으로 생겼고, 운영 DB 는 2026-08-05
-- 기준 FINISHED/DRAW/CANCELED 3행뿐이다 — 코드 테이블인데 정의역이 데이터 유입 순서에
-- 좌우되는 상태다. 이 파일이 그 정의역을 기동 시점에 확정한다.
--
-- 적용은 사람이 아니라 앱이 한다 — `user` 앱의 dev/prod 프로파일이
-- `spring.sql.init.data-locations` 로 teams-init.sql · chat-init.sql 과 함께 매 기동
-- 실행한다. 순서 의존은 없다(다른 시드와 참조 관계가 없는 독립 코드 테이블).
--
-- ⚠ Hibernate 가 `game_statuses` 를 만든 **뒤에** 돌아야 한다. 두 프로파일 모두
--   `spring.jpa.defer-datasource-initialization: true` 가 켜져 있어 그 순서가 보장된다.
--
-- 재실행 안전: name 기준 anti-join 이라 이미 있는 상태는 건너뛴다. 기존 행의 id 를
--   재부여하지 않는다(= FK 를 건드리지 않는다) — 운영에 이미 1=FINISHED, 2=DRAW,
--   3=CANCELED 가 있으므로 이 파일이 실제로 넣는 건 SCHEDULED/IN_PROGRESS 두 행이다.
--   id 를 1..5 로 예쁘게 맞추려 들지 말 것: `games.game_status_id` 가 이미 참조 중이다.
--
-- 동시 기동: anti-join 은 "내가 조회한 그 순간" 기준이라, 파드 여러 개가 **완전히 비어 있는**
--   DB 에 동시에 뜨면 서로의 미커밋 INSERT 를 못 봐 같은 이름을 두 번 넣으려 든다.
--   `name` 에 UNIQUE(`uk_game_statuses_name`)가 걸려 있으면 두 번째가 `Duplicate entry` 로 막혀
--   **그 파드의 기동이 실패**하고, 재시작하면 앞선 파드가 넣은 행이 보여 no-op 으로 통과한다
--   (teams.code 와 같은 거동 — 조용한 중복 대신 시끄러운 실패). 이미 시드된 DB 에서는 전부
--   no-op 이라 롤링 업데이트에서는 애초에 일어나지 않는다. 초기 구축 때만 파드 1개로 띄울 것.
--   ⚠ 그 UNIQUE 는 **기존 DB 에는 자동으로 생기지 않는다.** `ddl-auto=update` 는 이미 존재하는
--     테이블에 UNIQUE 를 추가하지 않는다(2026-08-05 실측). 신규 환경은 Hibernate 가 테이블을
--     만들며 함께 걸지만, 이미 뜬 prod/dev 는 `migrate-game-status-unique.sql` 을 한 번 손으로
--     적용해야 한다. 아래 검증 쿼리 2번으로 걸렸는지 확인할 것.
--
-- ⚠ name 값은 임의로 정한 게 아니다. py-collector 의 상태 판정
--   (`kbo_collector/game_records.py` 의 map_status, 원천은 네이버 스포츠 schedule API)이
--   내보내는 문자열과 문자 그대로 일치해야 한다. 다르면 수집기가 자기 표기로 행을 하나
--   더 만들어 **같은 상태가 두 행으로 갈라진다**. 매핑 근거는 GameStatus.java 주석 참고:
--     SCHEDULED   = statusCode "BEFORE"         (예정)
--     IN_PROGRESS = statusCode "LIVE"/"STARTED" (진행 중)
--     FINISHED    = statusCode "RESULT"         (정상 종료, 승패 확정)
--     DRAW        = statusCode "RESULT" + 동점  (무승부)
--     CANCELED    = cancel == true              (우천 취소/노게임)
--   네이버가 내려주는 suspended(중단 후 재개)는 아직 목록에 없다. 필요해지면 아래
--   UNION ALL 에 한 줄 추가하면 되고 코드 변경은 필요 없다(재배포만).
-- ============================================================================

INSERT INTO game_statuses (name, created_at, updated_at)
SELECT seed.name, NOW(6), NOW(6)
FROM (
    SELECT 'SCHEDULED' AS name
    UNION ALL SELECT 'IN_PROGRESS'
    UNION ALL SELECT 'FINISHED'
    UNION ALL SELECT 'DRAW'
    UNION ALL SELECT 'CANCELED'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM game_statuses gs WHERE gs.name = seed.name);


-- ============================================================================
-- 검증 쿼리 (적용 후 수동 실행)
-- ============================================================================
-- 1) 5행이 다 있는지
--    SELECT id, name FROM game_statuses ORDER BY id;                              -- 기대: 5행
--
-- 2) 같은 상태가 갈라지지 않았는지 + UNIQUE 인덱스가 실제로 걸렸는지 (위 ⚠ 참고)
--    SELECT name, COUNT(*) FROM game_statuses GROUP BY name HAVING COUNT(*) > 1;  -- 기대: 0행
--    SHOW INDEX FROM game_statuses WHERE Column_name='name';
--    -- 기대: Key_name='uk_game_statuses_name', Non_unique=0. 비어 있으면 아직 안 걸린 것 —
--    --       migrate-game-status-unique.sql 을 적용할 것(신규 환경이면 Hibernate 가 이미 걸어둠).
--
-- 3) 수집기 표기와 어긋난 행이 섞이지 않았는지
--    SELECT * FROM game_statuses
--     WHERE name NOT IN ('SCHEDULED','IN_PROGRESS','FINISHED','DRAW','CANCELED'); -- 기대: 0행
-- ============================================================================
