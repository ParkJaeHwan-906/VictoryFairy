package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#point}의 초기값만 다루는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * 요구사항: {@code docs/requirements/user/me-profile.md}(USER-ME-1 ~ 2 — 선행 스키마의 애플리케이션
 * 계약 측면).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code point} 컬럼의 {@code NOT NULL DEFAULT 0} DDL 자체(USER-ME-1),
 * 기존 계정의 값이 0으로 유지되는지(USER-ME-2)는 실제 MySQL 스키마 확인이 필요해 이 테스트로 증명되지
 * 않는다({@code UserSupportTeamTest}·{@code StadiumTest}와 같은 이유로 이 모듈에 H2/Testcontainers/구동
 * 중인 MySQL이 없음). 여기서는 <b>애플리케이션이 만드는 신규 인스턴스</b>가 항상 0에서 시작하는지만 다룬다.
 */
class UserAccountPointTest {

    @Test
    @DisplayName("[USER-ME-1, 2 관련] builder()로 새 계정을 만들면 point는 항상 0에서 시작한다")
    void builder_newAccount_startsWithZeroPoint() {
        // when
        UserAccount account = UserAccount.builder()
                .nickname("nickname")
                .password("encoded-password")
                .build();

        // then
        assertThat(account.getPoint()).isZero();
    }
}
