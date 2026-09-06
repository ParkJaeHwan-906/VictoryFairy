package com.skhynix.user.oauth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.user.auth.dto.TokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OauthAuthResponse} 팩터리 — 5키 계약(USER-OAU-10)과 provider 토큰 미포함(USER-OAU-9)을 고정한다.
 */
class OauthAuthResponseTest {

    @Test
    @DisplayName("[USER-OAU-10, 11] login()은 status=LOGIN에 토큰 쌍을 싣고 ticket·email은 null이다")
    void login_setsStatusAndTokensLeavesTicketAndEmailNull() {
        // when
        OauthAuthResponse response = OauthAuthResponse.login(new TokenResponse("access", "refresh"));

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.LOGIN);
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.ticket()).isNull();
        assertThat(response.email()).isNull();
    }

    @Test
    @DisplayName("[USER-OAU-10, 12] signupRequired()는 status=SIGNUP_REQUIRED에 ticket·email을 싣고 토큰은 null이다")
    void signupRequired_setsStatusTicketAndEmailLeavesTokensNull() {
        // when
        OauthAuthResponse response = OauthAuthResponse.signupRequired("ticket-1", "new@example.com");

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.SIGNUP_REQUIRED);
        assertThat(response.ticket()).isEqualTo("ticket-1");
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.accessToken()).isNull();
        assertThat(response.refreshToken()).isNull();
    }

    @Test
    @DisplayName("[USER-OAU-10, 68] emailVerificationRequired()는 status=EMAIL_VERIFICATION_REQUIRED에 "
            + "ticket·email을 싣고 토큰은 null이다")
    void emailVerificationRequired_setsStatusTicketAndEmail() {
        // when
        OauthAuthResponse response = OauthAuthResponse.emailVerificationRequired("ticket-2", "existing@example.com");

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.EMAIL_VERIFICATION_REQUIRED);
        assertThat(response.ticket()).isEqualTo("ticket-2");
        assertThat(response.email()).isEqualTo("existing@example.com");
        assertThat(response.accessToken()).isNull();
    }

    @Test
    @DisplayName("[USER-OAU-10, 90] emailInputRequired()는 status=EMAIL_INPUT_REQUIRED에 ticket만 싣고 "
            + "email·토큰은 null이다")
    void emailInputRequired_setsStatusAndTicketLeavesEmailAndTokensNull() {
        // when
        OauthAuthResponse response = OauthAuthResponse.emailInputRequired("ticket-3");

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.EMAIL_INPUT_REQUIRED);
        assertThat(response.ticket()).isEqualTo("ticket-3");
        assertThat(response.email()).isNull();
        assertThat(response.accessToken()).isNull();
        assertThat(response.refreshToken()).isNull();
    }

    @Test
    @DisplayName("[USER-OAU-10] status는 정확히 4값이다")
    void status_hasExactlyFourValues() {
        assertThat(OauthAuthStatus.values()).hasSize(4);
        assertThat(OauthAuthStatus.values()).containsExactlyInAnyOrder(
                OauthAuthStatus.LOGIN, OauthAuthStatus.SIGNUP_REQUIRED,
                OauthAuthStatus.EMAIL_VERIFICATION_REQUIRED, OauthAuthStatus.EMAIL_INPUT_REQUIRED);
    }
}
