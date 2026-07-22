-- ============================================================================
-- Chat 기능 배포 선행 SQL (prod 전용, MySQL 8.0 / AWS RDS 대상)
--
-- 배경: user/quiz 두 앱 모두 prod 프로파일이 `ddl-auto: none`이고 저장소 전체에
-- Flyway가 없다(.claude/modules/domain.md 참고). 즉 Hibernate가 스키마를 자동
-- 생성/검증만 할 뿐 만들어주지 않으므로, chat 기능을 배포하기 전에 이 SQL을
-- **수동으로 먼저** RDS에 적용해야 한다. 적용하지 않고 배포하면 quiz 앱이
-- `ddl-auto: validate` 단계에서 기동 실패한다(chatrooms/chats 테이블 부재).
--
-- 적용 순서 (반드시 이 순서, 뒤 단계는 앞 단계 존재를 전제로 함):
--   0. (조건부, 아래 "선행 조건" 참고) teams 시드, users_account.uid 컬럼
--   1. DDL — CREATE TABLE chatrooms, chats
--   2. 시스템 계정 시드 — users 1행 + users_account 1행 (채팅방 owner FK용)
--   3. 구단별 채팅방 시드 — teams 각 행당 chatroom 1행
--
-- 실행 전 반드시 올바른 스키마를 선택할 것: `USE <해당 DB_NAME>;`
--
-- ============================================================================
-- 선행 조건 (이 파일이 만들지 않는 것 — 반드시 사전 확인)
-- ============================================================================
-- (A) teams: 10개 구단 행이 이미 존재해야 한다. 이 파일은 팀 목록 자체를 시드하지
--     않는다(관례상 별도 관심사 — py-collector 크롤러 또는 별도 팀 시드가 전제).
--     이 저장소 안에는 teams 시드 스크립트가 없고(domain.md에도 teams 시드
--     인프라가 없다고 명시), py-collector 레포는 이 체크아웃에 포함돼 있지
--     않아 teams.name 실제 값을 이 세션에서 직접 확인하지 못했다. 아래
--     Step 3은 KBO 10개 구단의 관례적 약칭
--     (본 저장소 테스트 코드 `ChatServiceTest.team()`이 쓰는 "두산" 표기와
--     동일한 스타일)을 기준으로 작성했다.
--     **배포 전 반드시 `SELECT name FROM teams;`로 teams.name의 실제 표기와
--     Step 3의 문자열이 정확히 일치하는지 확인할 것.** 일치하지 않으면
--     INSERT ... SELECT ... WHERE t.name = '…' 가 0행을 매칭해 조용히 아무것도
--     삽입하지 않는다(에러 없이 no-op이라 눈치채기 어려움 — 그래서 파일 맨 끝에
--     검증 쿼리를 둔다).
-- (B) users_account.uid 컬럼: domain.md에 이미 별도 배포 선행 조건으로 기록된
--     기존 갭이다("prod DDL 반영도 미착수... 이 상태로 배포하면 인증 전체가
--     실패한다"). 이 파일의 Step 2가 uid 컬럼에 값을 쓰므로, 그 uid 마이그레이션이
--     이미 적용돼 있지 않으면 Step 2가 "Unknown column 'uid'"로 실패한다.
--     그 마이그레이션은 기존 사용자 데이터 백필이 필요한 별개 작업이라 이 파일이
--     대신 만들지 않는다 — **배포 전 `DESCRIBE users_account;`로 uid 컬럼 존재를
--     반드시 먼저 확인할 것.**
-- ============================================================================


-- ============================================================================
-- Step 1. DDL — chatrooms, chats
-- (Chatroom.java / Chat.java 엔티티 매핑과 1:1로 맞춤. 재실행 안전: IF NOT EXISTS)
-- ============================================================================

CREATE TABLE IF NOT EXISTS chatrooms (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    -- UserAccount.uid와 동일한 컬럼 정의(엔티티 columnDefinition 그대로): ascii 고정,
    -- PK 순차 열거 방지용 외부 노출 식별자(SSE 구독 URL 등).
    uid               VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    team_id           BIGINT NOT NULL,
    owner_account_id  BIGINT NOT NULL,
    name              VARCHAR(255) NOT NULL,
    participants      INT NOT NULL DEFAULT 0,
    deleted_at        DATETIME NULL,
    created_at        DATETIME NOT NULL,
    updated_at        DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chatrooms_uid (uid),
    -- 채팅방은 팀 종속 데이터 → 팀 삭제 시 연쇄 삭제 (Chatroom.java 주석·domain.md CASCADE 기준)
    CONSTRAINT fk_chatrooms_team
        FOREIGN KEY (team_id) REFERENCES teams (id)
        ON DELETE CASCADE,
    -- 소유자 계정은 방과 독립된 공용 자원 보존 대상 → non-cascade (Game→Team과 동일 정책).
    -- @OnDelete 미지정 = ON DELETE 절 생략과 동일 효과(InnoDB 기본 RESTRICT)이며,
    -- 수기 마이그레이션에서는 의도를 명시적으로 남기기 위해 RESTRICT를 그대로 적는다.
    CONSTRAINT fk_chatrooms_owner_account
        FOREIGN KEY (owner_account_id) REFERENCES users_account (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chats (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    chatroom_id       BIGINT NOT NULL,
    user_account_id   BIGINT NOT NULL,
    content           TEXT NOT NULL,
    -- Chat.java @Column(columnDefinition = "TINYINT") 그대로. boolean blind 매핑.
    blind             TINYINT NOT NULL DEFAULT 0,
    deleted_at        DATETIME NULL,
    created_at        DATETIME NOT NULL,
    updated_at        DATETIME NOT NULL,
    PRIMARY KEY (id),
    -- Chat.java @Table(indexes = @Index(name="idx_chats_chatroom_created", ...))와 동일.
    -- chatroom_id가 선행 컬럼이라 FK에 필요한 인덱스도 이걸로 충족(별도 인덱스 불필요).
    KEY idx_chats_chatroom_created (chatroom_id, created_at),
    -- Chat → Chatroom / UserAccount 둘 다 CASCADE (domain.md CASCADE 기준: 완전 종속 데이터)
    CONSTRAINT fk_chats_chatroom
        FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chats_user_account
        FOREIGN KEY (user_account_id) REFERENCES users_account (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================================
-- Step 2. 시스템 계정 시드 — chatrooms.owner_account_id FK가 non-null이라
-- 방 시드보다 먼저 존재해야 하는 계정 (domain.md "배포 순서 제약" 참고).
-- 이 계정은 로그인 용도가 아니라 "팀 공용 채팅방의 소유자"로만 쓰는 시스템 계정이다.
-- 재실행 안전: INSERT ... SELECT ... WHERE NOT EXISTS (email/uid 유니크 기준 idempotent)
-- ============================================================================

-- users: gender는 필수 컬럼이라 값이 있어야 하지만 이 계정에 실제 의미는 없다.
-- Gender enum은 ORDINAL 저장이라 0 = MALE(선언 순서 그대로, 임의 관례값일 뿐).
INSERT INTO users (name, tel, email, gender, created_at, updated_at)
SELECT 'SYSTEM', '00000000000', 'system@victoryfairy.internal', 0, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE email = 'system@victoryfairy.internal'
);

-- users_account: password는 평문도 유효 BCrypt 해시도 아닌 placeholder다.
-- Spring Security BCryptPasswordEncoder.matches()는 encodedPassword가 BCrypt 패턴
-- (예: "$2a$10$...")에 맞지 않으면 예외 없이 항상 false를 반환하므로, 이 값으로는
-- 어떤 원문 비밀번호를 넣어도 로그인이 성립하지 않는다.
-- ↳ 이 계정은 시드 전용이며 로그인 불가능하다 — 애플리케이션 로그인 경로로 이 계정에
--   접근하는 시나리오는 없다(단순히 chatrooms.owner_account_id FK를 채우기 위한 존재).
INSERT INTO users_account (uid, user_id, nickname, password, created_at, updated_at)
SELECT
    '00000000-0000-0000-0000-000000000001',
    u.id,
    'SYSTEM',
    'LOCKED-NO-LOGIN',
    NOW(),
    NOW()
FROM users u
WHERE u.email = 'system@victoryfairy.internal'
  AND NOT EXISTS (
      SELECT 1 FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001'
  );


-- ============================================================================
-- Step 3. 구단별 채팅방 시드 — teams 각 행당 chatroom 1행, owner는 위 시스템 계정.
-- team_id는 팀 이름으로 조회해 하드코딩 PK를 피한다(환경마다 teams.id 값이 다를 수 있음).
-- 재실행 안전: INSERT ... SELECT ... WHERE NOT EXISTS(uid 유니크 기준 idempotent).
-- teams.name에는 unique 제약이 없다(domain.md "Team.name에 unique 제약은 의도적으로
-- 걸지 않음") — 동명 팀이 여러 행 존재할 가능성을 배제할 수 없어 각 SELECT에 LIMIT 1을
-- 붙여 방어적으로 1행만 선택한다. 실제로 동명 행이 여러 개라면 어느 행이 선택될지는
-- 보장되지 않으니(정렬 없는 LIMIT) 배포 전 `SELECT name, COUNT(*) FROM teams GROUP BY
-- name HAVING COUNT(*) > 1;`로 중복 여부를 확인하는 것을 권장한다.
--
-- ⚠ 아래 10개 팀명 문자열('두산' 등)은 이 저장소 테스트 코드의 표기 관례를 따른
-- 추정값이다. 실제 teams.name 값과 다르면 조용히 매칭 0건 → INSERT 0행(에러 없음).
-- 배포 후 반드시 파일 맨 끝의 검증 쿼리로 10행이 다 들어갔는지 확인할 것.
-- ============================================================================

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000002', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = '두산' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000002');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000003', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = '한화' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000003');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000004', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = 'LG' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000004');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000005', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = 'KT' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000005');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000006', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = 'SSG' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000006');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000007', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = '롯데' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000007');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000008', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = '삼성' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000008');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000009', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = 'NC' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-000000000009');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-00000000000a', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = 'KIA' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-00000000000a');

INSERT INTO chatrooms (uid, team_id, owner_account_id, name, participants, deleted_at, created_at, updated_at)
SELECT '00000000-0000-0000-0000-00000000000b', t.id, ua.id, CONCAT(t.name, ' 채팅방'), 0, NULL, NOW(), NOW()
FROM (SELECT id, name FROM teams WHERE name = '키움' LIMIT 1) t
CROSS JOIN (SELECT id FROM users_account WHERE uid = '00000000-0000-0000-0000-000000000001' LIMIT 1) ua
WHERE NOT EXISTS (SELECT 1 FROM chatrooms WHERE uid = '00000000-0000-0000-0000-00000000000b');


-- ============================================================================
-- 검증 쿼리 (적용 후 수동으로 돌려볼 것 — 이 파일이 자동 실행하지는 않음)
-- ============================================================================
-- 1) 시스템 계정 1행 존재 확인
--    SELECT id, uid, nickname, exit_at FROM users_account
--    WHERE uid = '00000000-0000-0000-0000-000000000001';
--
-- 2) 채팅방 10행이 다 들어갔는지 확인 (10보다 적으면 Step 3의 팀명 문자열이
--    실제 teams.name과 어긋난 것 — teams.name을 직접 조회해 문자열을 맞추고
--    누락된 uid만 골라 재실행할 것, 이미 들어간 행은 WHERE NOT EXISTS로 스킵됨)
--    SELECT uid, name, team_id FROM chatrooms
--    WHERE uid IN (
--        '00000000-0000-0000-0000-000000000002',
--        '00000000-0000-0000-0000-000000000003',
--        '00000000-0000-0000-0000-000000000004',
--        '00000000-0000-0000-0000-000000000005',
--        '00000000-0000-0000-0000-000000000006',
--        '00000000-0000-0000-0000-000000000007',
--        '00000000-0000-0000-0000-000000000008',
--        '00000000-0000-0000-0000-000000000009',
--        '00000000-0000-0000-0000-00000000000a',
--        '00000000-0000-0000-0000-00000000000b'
--    );
--
-- 3) FK 정책 확인(선택): 팀 삭제 시 방이 같이 사라지는지, owner 계정은
--    RESTRICT라 owner_account_id를 참조하는 방이 있으면 users_account 삭제가
--    막히는지는 스테이징에서 실제 DELETE로 검증할 것(이 파일은 검증만 안내,
--    운영 DB에 DELETE를 실행하지 않는다).
-- ============================================================================
