package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link User}의 {@code emailVerified} 기본값·{@link User#socialSignup(String, boolean)}·
 * {@link User#verifyEmail()}만 다루는 순수 단위 테스트(Spring 컨텍스트/DB 없음). 요구사항:
 * {@code docs/requirements/user/oauth-login.md}(USER-OAU-82, 84~87).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code users.name}/{@code tel}/{@code gender}의 실제 nullable 여부
 * (USER-OAU-87, {@code information_schema.COLUMNS.IS_NULLABLE})와 {@code email_verified} 컬럼의
 * {@code DEFAULT TRUE} 백필(USER-OAU-83)은 실제 MySQL 라운드트립이 필요해 이 테스트로 증명되지
 * 않는다({@code UserBqTest}·{@code UserSupportTeamTest}와 같은 이유로 이 모듈에
 * H2/Testcontainers/구동 중인 MySQL이 없음). 여기서는 <b>애플리케이션이 만드는 신규 인스턴스</b>의
 * 필드 배선과 전이 규칙만 다룬다.
 */
class UserTest {

    // ---------- 자체 가입 경로 — emailVerified 기본값 (USER-OAU-82) ----------

    @Test
    @DisplayName("[USER-OAU-82] builder()로 만든 계정(자체 가입)은 emailVerified가 기본적으로 true다")
    void builder_selfSignup_emailVerifiedDefaultsToTrue() {
        // when
        User user = User.builder()
                .name("홍길동")
                .tel("01012345678")
                .email("gildong@example.com")
                .gender(Gender.MALE)
                .build();

        // then
        assertThat(user.isEmailVerified()).isTrue();
    }

    // ---------- 소셜 가입 경로 (USER-OAU-84, 86) ----------

    @Test
    @DisplayName("[USER-OAU-86] socialSignup()으로 만든 계정은 name·tel·gender가 모두 NULL이고 email만 채워진다")
    void socialSignup_leavesNameTelGenderNull() {
        // when
        User user = User.socialSignup("social@example.com", true);

        // then
        assertThat(user.getName()).isNull();
        assertThat(user.getTel()).isNull();
        assertThat(user.getGender()).isNull();
        assertThat(user.getEmail()).isEqualTo("social@example.com");
    }

    @Test
    @DisplayName("[USER-OAU-84] socialSignup(email, true)로 만든 계정은 emailVerified가 true다"
            + "(provider 검증 이메일 또는 입력 티켓 인증을 거친 이메일)")
    void socialSignup_verifiedEmail_setsEmailVerifiedTrue() {
        // when
        User user = User.socialSignup("verified@example.com", true);

        // then
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-84] socialSignup(email, false)로 만든 계정은 emailVerified가 false다"
            + "(provider 미검증 이메일로 가입한 경우)")
    void socialSignup_unverifiedEmail_setsEmailVerifiedFalse() {
        // when
        User user = User.socialSignup("unverified@example.com", false);

        // then
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("socialSignup()으로 만든 계정도 영속화 전이므로 id·생성/수정시각은 null이다")
    void socialSignup_beforePersist_hasNullIdAndTimestamps() {
        // when
        User user = User.socialSignup("social@example.com", true);

        // then
        assertThat(user.getId()).isNull();
        assertThat(user.getCreatedAt()).isNull();
        assertThat(user.getUpdatedAt()).isNull();
    }

    // ---------- 검증 승격 verifyEmail() — 단방향 (USER-OAU-72, 85) ----------

    @Test
    @DisplayName("[USER-OAU-72, 85] 미검증 계정에 verifyEmail()을 호출하면 emailVerified가 true로 승격된다")
    void verifyEmail_unverifiedAccount_promotesToVerified() {
        // given
        User user = User.socialSignup("unverified@example.com", false);
        assertThat(user.isEmailVerified()).isFalse();

        // when
        user.verifyEmail();

        // then
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-85] 이미 검증됨인 계정에 verifyEmail()을 다시 호출해도 계속 true다(no-op, 되돌릴 수단이 없다)")
    void verifyEmail_alreadyVerifiedAccount_staysVerified() {
        // given
        User user = User.socialSignup("verified@example.com", true);
        assertThat(user.isEmailVerified()).isTrue();

        // when
        user.verifyEmail();

        // then
        assertThat(user.isEmailVerified()).isTrue();
    }
}
