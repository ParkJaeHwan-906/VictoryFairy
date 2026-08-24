-- 소셜 로그인(OAuth) 도입 선행 DDL — 2026-08-21 운영·devdb 적용 완료
--
-- ⚠ 이 파일은 spring.sql.init 으로 자동 실행되지 않는다. 손으로 적용하는 마이그레이션 기록이다.
-- ⚠ 앱 배포보다 반드시 먼저 적용해야 한다. user 앱은 ddl-auto=update 인데, update 는
--    (1) 기존 컬럼의 NOT NULL 을 풀어주지 않고 (2) 기존 FK 의 삭제 규칙을 바꾸지 않는다.
--    따라서 이 DDL 없이 배포하면 로컬·신규 DB 에서만 멀쩡하고 운영에서만 소셜 가입이 실패한다.

-- 1) 소셜 가입은 name·tel·gender 를 채울 수 없다(어느 provider 도 주지 않는다).
--    ⚠ 자체 회원가입의 필수 검증은 그대로 살아 있다 — 이제 DB 가 아니라 애플리케이션이 막는다.
--    tel 의 UNIQUE 는 유지한다. MySQL 은 UNIQUE 컬럼의 NULL 중복을 허용하므로 문제되지 않는다.
ALTER TABLE users MODIFY COLUMN `name`   VARCHAR(30) NULL;
ALTER TABLE users MODIFY COLUMN `tel`    VARCHAR(11) NULL;
ALTER TABLE users MODIFY COLUMN `gender` TINYINT     NULL;

-- 2) 이메일 소유가 증명됐는가 — 자동 계정 통합의 키를 신뢰할 수 있는지 판정하는 근거.
--    ⚠ DEFAULT 1 이 필수다. 자체 가입은 이메일 인증이 선행조건이라 기존 계정은 전부 검증된 것이
--       맞고, 이 기본값을 빠뜨리면 기존 사용자 전원이 미검증이 되어 모든 소셜 통합이 인증번호를
--       요구하게 된다(기능은 동작하므로 테스트로는 걸리지 않는다).
ALTER TABLE users ADD COLUMN `email_verified` TINYINT NOT NULL DEFAULT 1;

-- 3) 소셜 신원 ↔ 계정 연동.
--    UNIQUE 2종이 "1인 1계정"의 실제 보장 주체다(애플리케이션 로직이 아니라 DB 가 막는다).
--      - (provider, provider_user_id): 한 소셜 계정은 우리 계정 하나에만 붙는다
--      - (user_account_id, provider)  : 우리 계정 하나에 provider 당 최대 1개
--    ⚠ FK 는 ON DELETE CASCADE 여야 한다. 아니면 만료 계정 하드 삭제 배치의 DELETE FROM users 가
--       FK 위반으로 실패해 그 계정이 통째로 스킵된다(탈퇴 30일 뒤에야 증상이 드러난다).
--    ⚠ FK 제약 이름을 명시하는 것이 계약이다. 비워 두면 Hibernate 가 기대하는 난수 이름과 실제
--       이름이 달라, 다음 기동의 ddl-auto=update 가 같은 FK 를 하나 더 붙인다.
--    provider_user_id 는 ascii_bin — 기본 collation 은 대소문자를 구분하지 않아, 대소문자만 다른
--    두 provider 식별자가 같은 값으로 취급되면 조회가 남의 연동 행을 물어 온다.
CREATE TABLE `users_oauth_link` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `user_account_id`  BIGINT       NOT NULL,
    `provider`         VARCHAR(20)  NOT NULL,
    `provider_user_id` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `created_at`       DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_users_oauth_link_provider_user`    UNIQUE (`provider`, `provider_user_id`),
    CONSTRAINT `uk_users_oauth_link_account_provider` UNIQUE (`user_account_id`, `provider`),
    CONSTRAINT `fk_users_oauth_link_account` FOREIGN KEY (`user_account_id`)
        REFERENCES `users_account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 적용 확인 (DELETE_RULE 이 CASCADE, email_verified 의 COLUMN_DEFAULT 가 1 이어야 한다)
-- SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
--  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'users_oauth_link';
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
--    AND COLUMN_NAME IN ('name','tel','gender','email_verified');
