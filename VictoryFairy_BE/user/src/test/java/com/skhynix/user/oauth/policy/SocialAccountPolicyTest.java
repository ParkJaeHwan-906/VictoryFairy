package com.skhynix.user.oauth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@link SocialAccountPolicy#LOCKED_PASSWORD} — 소셜 전용 계정의 잠긴 비밀번호. 요구사항:
 * {@code docs/requirements/user/oauth-login.md} USER-OAU-43~45.
 */
class SocialAccountPolicyTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("[USER-OAU-43] LOCKED_PASSWORD는 BCrypt 해시 패턴이 아니다")
    void lockedPassword_isNotBcryptPattern() {
        assertThat(SocialAccountPolicy.LOCKED_PASSWORD).doesNotMatch("^\\$2[aby]\\$\\d{2}\\$.{53}$");
    }

    @Test
    @DisplayName("[USER-OAU-43, 44] BCryptPasswordEncoder.matches()는 LOCKED_PASSWORD에 대해 어떤 원문으로도 "
            + "예외 없이 false를 낸다(자체 로그인이 성립하지 않는다)")
    void matches_anyRawPasswordAgainstLockedPassword_returnsFalseWithoutException() {
        assertThat(encoder.matches("", SocialAccountPolicy.LOCKED_PASSWORD)).isFalse();
        assertThat(encoder.matches("password123!", SocialAccountPolicy.LOCKED_PASSWORD)).isFalse();
        assertThat(encoder.matches(SocialAccountPolicy.LOCKED_PASSWORD, SocialAccountPolicy.LOCKED_PASSWORD))
                .isFalse();
    }
}
