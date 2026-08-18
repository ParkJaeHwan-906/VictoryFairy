package com.skhynix.user.cleanup.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@link UnknownAccountPolicy}의 예약값 계약 — 실제 {@link BCryptPasswordEncoder}를 그대로 써서
 * (목이 아니다) "이 계정으로는 로그인이 성립하지 않는다"(USER-EDC-33)를 코드 수준에서 증명한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 */
class UnknownAccountPolicyTest {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("[USER-EDC-33] LOCKED_PASSWORD는 BCrypt 패턴이 아니라서 어떤 원문으로도 matches()가 "
            + "예외 없이 항상 false다 — 임의 비밀번호로 시도해도 로그인이 성립하지 않는 근거")
    void lockedPassword_matchesAnyRawPassword_returnsFalseWithoutException() {
        // given
        String[] attempts = {"anything", "password123!", "", UnknownAccountPolicy.LOCKED_PASSWORD};

        for (String raw : attempts) {
            // when / then
            assertThatCode(() -> passwordEncoder.matches(raw, UnknownAccountPolicy.LOCKED_PASSWORD))
                    .doesNotThrowAnyException();
            assertThat(passwordEncoder.matches(raw, UnknownAccountPolicy.LOCKED_PASSWORD)).isFalse();
        }
    }

    @Test
    @DisplayName("[USER-EDC-32] 더미 계정 uid는 SYSTEM 시드 계정의 uid 와 겹치지 않는 확정값이다")
    void uid_isFixedAndDistinctFromSystemAccount() {
        assertThat(UnknownAccountPolicy.UID).isEqualTo("568ee3c3-029f-4514-b87f-9d90e729f755");
    }

    @Test
    @DisplayName("[USER-EDC-30] 더미 계정 email·tel은 SYSTEM 계정과 같은 .internal 예약 방식의 확정값이다")
    void reservedEmailAndTel_areFixedValues() {
        assertThat(UnknownAccountPolicy.EMAIL).isEqualTo("unknown@victoryfairy.internal");
        assertThat(UnknownAccountPolicy.TEL).isEqualTo("00000000001");
    }
}
