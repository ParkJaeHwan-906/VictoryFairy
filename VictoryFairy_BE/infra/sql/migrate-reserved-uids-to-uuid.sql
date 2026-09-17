-- ============================================================================
-- 예약 행의 uid 를 손으로 지어낸 순차값에서 실제 생성된 UUID 로 바꾼다
-- (1회성, MySQL 8.0 — 수동 실행)
--
-- 대상 환경: devdb + 운영 DB **양쪽 모두.** 두 곳 다 적용해야 한다.
--
-- 대상 12건 (uid 값만 바뀐다 — id 는 그대로다):
--   users_account  SYSTEM 계정            1건  (chat-init.sql Step 2 가 넣은 행)
--   chatrooms      구단 공용 채팅방      10건  (chat-init.sql Step 3 이 넣은 행)
--   users_account  (알수없음) 더미 계정   1건  (UnknownAccountBootstrapper 가 기동 시 만든 행)
--
-- 왜 필요한가: 이 값들은 `00000000-0000-0000-0000-00000000000X` 처럼 사람이 순번으로 지어낸
--   문자열이었다. uid 는 PK 순차 열거를 막으려고 두는 외부 노출 식별자인데 이 값들은 그 목적을
--   정면으로 배반한다(하나를 보면 나머지를 그대로 유추할 수 있다). 코드·시드의 상수는 실제
--   생성된 UUID v4 로 이미 교체됐고, 이 파일은 **이미 DB 에 들어가 있는 행**을 거기에 맞춘다.
--
-- ⚠ **앱 배포보다 이 파일이 먼저다.** 순서를 뒤집으면 두 가지가 동시에 깨진다:
--   (1) chat-init.sql 의 멱등성 가드가 `WHERE NOT EXISTS (... uid = '<새 uid>')` 로 바뀌었으므로,
--       DB 에 옛 uid 행만 있는 상태에서 그 파일을 다시 돌리면 매칭이 0 이라 **SYSTEM 계정과
--       채팅방 10건이 통째로 중복 생성**된다(구단마다 방이 둘이 된다).
--   (2) `(알수없음)` 부트스트랩(UnknownAccountBootstrapper, ApplicationRunner 라 **기동 경로**에
--       있다)은 새 uid 로 계정을 못 찾아 새로 만들려 하고, users.email·users.tel 이 UNIQUE 라
--       INSERT 가 실패해 **앱이 기동하지 못한다.**
--   즉 "SQL 먼저, 배포 나중"이다.
--
-- ⚠ **채팅방 uid 는 외부 노출 식별자다** — SSE 구독 URL(`/chat/rooms/{roomUid}/subscribe`)과
--   방 조회·메시지 전송 경로가 전부 이 값을 경로 변수로 쓴다. 바꾸는 순간 **옛 uid 를 들고 있던
--   클라이언트의 요청·SSE 연결이 404(CHATROOM_NOT_FOUND)로 끊긴다.** 프런트가 방 목록을 다시
--   받으면 새 uid 로 복구되지만 **무중단은 아니다** — 트래픽이 적은 시간대에 적용할 것.
--   반대로 계정 쪽 uid 2건은 로그인이 성립하지 않는 예약 계정이라(BCrypt 패턴이 아닌 placeholder
--   비밀번호) 발급된 토큰이 없다 — **실사용자 토큰은 하나도 무효화되지 않는다.**
--
-- FK 확인 결과: **uid 를 참조하는 FK 는 없다.** 저장소 전체에서 FK 참조 대상은 전부 `id` 이며
--   (chat-init.sql 의 fk_chatrooms_team / fk_chatrooms_owner_account / fk_chats_chatroom /
--   fk_chats_user_account 모두 `REFERENCES ... (id)`), 엔티티에도 `referencedColumnName` 사용처가
--   0 건이다. 즉 uid 를 바꿔도 자식 행이 따라 깨지지 않는다. 아래 Step 0-c 로 각 환경에서 직접
--   다시 확인할 수 있다(결과가 0행이어야 정상).
--
-- 재실행 안전: 12개 UPDATE 가 모두 **옛 uid 를 WHERE 조건으로** 잡는다. 한 번 적용된 뒤에는 그
--   조건에 걸리는 행이 없으므로 다시 돌려도 0행 매칭 no-op 이다(에러 없음). 별도 가드 로직이
--   필요 없는 이유가 이것이다. uid 에는 UNIQUE 가 걸려 있어 "이미 새 uid 인 행"과 충돌하지도 않는다
--   — 애초에 UPDATE 가 실행되지 않기 때문이다.
--
-- ⚠ 이 파일을 spring.sql.init.data-locations 에 넣지 말 것(migrate-*.sql 공통 규칙).
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
-- ============================================================================


-- ============================================================================
-- Step 0. 적용 전 현재 상태 확인 — 여기서 몇 건이 잡히는지 먼저 눈으로 볼 것
-- ============================================================================
-- (0-a) 예약 계정 2건. 옛 uid 로 잡히면 이 파일의 대상이고, 새 uid 로 잡히면 이미 적용된 것이다.
--       운영 DB 에는 `(알수없음)` 계정이 아직 없을 수 있다 — 그 경우 SYSTEM 1행만 나오는 것이 정상이며
--       Step 1 의 해당 UPDATE 는 0행 매칭으로 조용히 넘어간다(실패가 아니다).
SELECT id, uid, nickname, exit_at,
       CASE uid
           WHEN '00000000-0000-0000-0000-000000000001' THEN 'OLD SYSTEM'
           WHEN '00000000-0000-0000-0000-000000000100' THEN 'OLD UNKNOWN'
           WHEN 'be9cbf98-9ba5-48de-b56a-57a1547bb760' THEN 'NEW SYSTEM (이미 적용됨)'
           WHEN '568ee3c3-029f-4514-b87f-9d90e729f755' THEN 'NEW UNKNOWN (이미 적용됨)'
       END AS state
FROM users_account
WHERE uid IN (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000100',
    'be9cbf98-9ba5-48de-b56a-57a1547bb760',
    '568ee3c3-029f-4514-b87f-9d90e729f755'
)
ORDER BY id;

-- (0-b) 구단 공용 채팅방 10건. 적용 전이면 old_count = 10, new_count = 0 이어야 한다.
SELECT
    SUM(uid IN (
        '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
        '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000005',
        '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000007',
        '00000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000009',
        '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000b'
    )) AS old_count,
    SUM(uid IN (
        '7b9a38d8-de1e-4916-8b8c-4cd8ceafbbbd', '5273cc52-f3df-4bd8-837c-32abc06fae5b',
        '38c1394b-c2a0-4bae-a5a5-ef305b389915', '53ca40bb-afbb-4303-853d-6debc9f26f31',
        'c6b001b7-9d57-4903-ac34-b49988015b6e', '9a1e69bd-a209-4c98-9a3d-c8b87285efca',
        'a4d07dbd-4916-462d-b231-78536a37cae6', 'd8334fcb-2331-4227-b9f9-09ff3b786982',
        '9a2c7a01-f8dc-431e-943b-bbc1c4ee3edd', '86bebcda-a1cb-42f1-9a53-9882612c30fd'
    )) AS new_count
FROM chatrooms;

-- (0-c) uid 를 참조하는 FK 가 정말 없는지 각 환경에서 직접 확인 — **0행이어야 정상.**
--       1행이라도 나오면 여기서 멈추고 보고할 것(그 FK 는 uid 값을 따라가야 한다).
SELECT rc.CONSTRAINT_NAME, rc.TABLE_NAME, kcu.COLUMN_NAME,
       kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME, rc.UPDATE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
  AND kcu.REFERENCED_COLUMN_NAME = 'uid';


-- ============================================================================
-- Step 1. uid 제자리 갱신 (12건)
--   **UPDATE 다.** DELETE 후 재삽입이나 INSERT 계열로 대체하지 말 것 — id 가 바뀌면
--   chats.chatroom_id / chats.user_account_id / chatrooms.owner_account_id 가 가리키던 대상이
--   깨진다. 이 파일이 바꾸는 것은 uid 컬럼 값 하나뿐이고 id 는 손대지 않는다.
--
--   각 문장은 UNIQUE 인 옛 uid 를 조건으로 하므로 최대 1행에만 영향을 준다.
--   클라이언트가 autocommit 을 끌 수 있다면 12건을 한 트랜잭션으로 묶어도 좋다(선택 사항 —
--   중간에 멈춰도 남은 문장을 그대로 다시 돌리면 되므로 필수는 아니다).
-- ============================================================================

-- SYSTEM 계정 (채팅방 10건의 owner). 계정 uid 를 먼저 바꿔도 방과의 연결은 owner_account_id(=id)
-- 기준이라 아무 영향이 없다 — 그래서 순서 제약이 없다.
UPDATE users_account SET uid = 'be9cbf98-9ba5-48de-b56a-57a1547bb760'
WHERE uid = '00000000-0000-0000-0000-000000000001';

-- 구단 공용 채팅방 10건 (chat-init.sql 의 시드 순서 그대로: 두산·한화·LG·KT·SSG·롯데·삼성·NC·KIA·키움)
UPDATE chatrooms SET uid = '7b9a38d8-de1e-4916-8b8c-4cd8ceafbbbd'
WHERE uid = '00000000-0000-0000-0000-000000000002';

UPDATE chatrooms SET uid = '5273cc52-f3df-4bd8-837c-32abc06fae5b'
WHERE uid = '00000000-0000-0000-0000-000000000003';

UPDATE chatrooms SET uid = '38c1394b-c2a0-4bae-a5a5-ef305b389915'
WHERE uid = '00000000-0000-0000-0000-000000000004';

UPDATE chatrooms SET uid = '53ca40bb-afbb-4303-853d-6debc9f26f31'
WHERE uid = '00000000-0000-0000-0000-000000000005';

UPDATE chatrooms SET uid = 'c6b001b7-9d57-4903-ac34-b49988015b6e'
WHERE uid = '00000000-0000-0000-0000-000000000006';

UPDATE chatrooms SET uid = '9a1e69bd-a209-4c98-9a3d-c8b87285efca'
WHERE uid = '00000000-0000-0000-0000-000000000007';

UPDATE chatrooms SET uid = 'a4d07dbd-4916-462d-b231-78536a37cae6'
WHERE uid = '00000000-0000-0000-0000-000000000008';

UPDATE chatrooms SET uid = 'd8334fcb-2331-4227-b9f9-09ff3b786982'
WHERE uid = '00000000-0000-0000-0000-000000000009';

UPDATE chatrooms SET uid = '9a2c7a01-f8dc-431e-943b-bbc1c4ee3edd'
WHERE uid = '00000000-0000-0000-0000-00000000000a';

UPDATE chatrooms SET uid = '86bebcda-a1cb-42f1-9a53-9882612c30fd'
WHERE uid = '00000000-0000-0000-0000-00000000000b';

-- (알수없음) 더미 계정. **행이 없어도 실패하지 않는다** — 0행 매칭이면 그대로 다음으로 넘어가고,
-- 그 환경은 앱이 새 uid 로 기동하면서 부트스트랩이 처음 만들어 준다(운영이 이 경우일 수 있다).
UPDATE users_account SET uid = '568ee3c3-029f-4514-b87f-9d90e729f755'
WHERE uid = '00000000-0000-0000-0000-000000000100';


-- ============================================================================
-- 적용 후 검증 — 아래 넷이 모두 기대값이어야 배포로 넘어간다
-- ============================================================================
-- 1) 옛 uid 가 한 건도 남아 있지 않은가 — **0 이어야 한다.**
SELECT
    (SELECT COUNT(*) FROM users_account WHERE uid LIKE '00000000-0000-0000-0000-%') AS old_accounts,
    (SELECT COUNT(*) FROM chatrooms     WHERE uid LIKE '00000000-0000-0000-0000-%') AS old_chatrooms;

-- 2) 새 uid 로 계정이 잡히는가 — SYSTEM 1행은 필수, (알수없음)은 devdb 에만 있을 수 있다
--    (운영에서 SYSTEM 1행만 나오는 것은 정상이며, 배포 후 부트스트랩이 (알수없음)을 만든다).
SELECT id, uid, nickname FROM users_account
WHERE uid IN ('be9cbf98-9ba5-48de-b56a-57a1547bb760', '568ee3c3-029f-4514-b87f-9d90e729f755')
ORDER BY id;

-- 3) 새 uid 로 채팅방이 **정확히 10행** 잡히는가 (10 미만이면 옛 uid 행이 남았거나 방이 누락된 것)
SELECT COUNT(*) AS new_chatroom_count FROM chatrooms
WHERE uid IN (
    '7b9a38d8-de1e-4916-8b8c-4cd8ceafbbbd', '5273cc52-f3df-4bd8-837c-32abc06fae5b',
    '38c1394b-c2a0-4bae-a5a5-ef305b389915', '53ca40bb-afbb-4303-853d-6debc9f26f31',
    'c6b001b7-9d57-4903-ac34-b49988015b6e', '9a1e69bd-a209-4c98-9a3d-c8b87285efca',
    'a4d07dbd-4916-462d-b231-78536a37cae6', 'd8334fcb-2331-4227-b9f9-09ff3b786982',
    '9a2c7a01-f8dc-431e-943b-bbc1c4ee3edd', '86bebcda-a1cb-42f1-9a53-9882612c30fd'
);

-- 4) 관계가 그대로인가 — 방 10건의 owner 가 여전히 SYSTEM 계정이고(=uid 만 바뀌었지 id 는 그대로),
--    각 방에 달려 있던 채팅 수가 유지되는지 함께 본다(uid 는 chats 가 참조하지 않으므로 변화가
--    없어야 정상이다 — 적용 전 값과 비교할 것).
SELECT c.uid, c.name, ua.uid AS owner_uid, ua.nickname AS owner_nickname,
       (SELECT COUNT(*) FROM chats ch WHERE ch.chatroom_id = c.id) AS chat_count
FROM chatrooms c
JOIN users_account ua ON ua.id = c.owner_account_id
ORDER BY c.id;
-- ============================================================================
