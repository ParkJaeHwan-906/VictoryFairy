package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserOauthLink}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음). 요구사항:
 * {@code docs/requirements/user/oauth-login.md}(USER-OAU-18, 19, 57, 58 — 아래 "DB 전략 관련 결정" 참고).
 *
 * <p><b>DB 전략 관련 결정</b>: 이 클래스가 신원 그 자체를 보장하는 근거는 DB 제약 두 개다 —
 * {@code uk_users_oauth_link_provider_user}(USER-OAU-19), {@code uk_users_oauth_link_account_provider}
 * (USER-OAU-18). 여기에 더해 {@code provider} 컬럼이 네이티브 MySQL {@code enum}이 아니라 VARCHAR로
 * 저장되는지, {@code provider_user_id}가 {@code ascii_bin} collation이라 대소문자를 구분하는지
 * (모듈 컨텍스트 "필수 커버" 4항), FK({@code fk_users_oauth_link_account})가 실제로
 * {@code ON DELETE CASCADE}로 걸리는지(USER-OAU-54)는 <b>전부 실제 MySQL 스키마 확인이 필요하다.</b>
 * 이 모듈({@code domain})에는 H2/Testcontainers도, 구동 중인 로컬 MySQL 컨테이너도 없어
 * ({@code docker compose ps} 결과 없음, {@code build.gradle}에 {@code spring-boot-starter-data-jpa-test}
 * 자체가 없음 — {@code UserBqTest}·{@code GameTest}와 같은 이유) 위 항목들은 이 테스트로 증명되지 않는다.
 * 여기서는 <b>애플리케이션이 만드는 신규 인스턴스</b>의 필드 배선만 다룬다.
 */
class UserOauthLinkTest {

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("encoded-password").build();
    }

    @Test
    @DisplayName("builder로 만들면 userAccount·provider·providerUserId가 그대로 배선되고, "
            + "영속화 전이므로 id·created_at은 null이다")
    void builder_wiresFields_andLeavesIdAndCreatedAtNullBeforePersist() {
        // given
        UserAccount userAccount = newUserAccount("nickname");

        // when
        UserOauthLink link = UserOauthLink.builder()
                .userAccount(userAccount)
                .provider(OauthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        // then
        assertThat(link.getUserAccount()).isSameAs(userAccount);
        assertThat(link.getProvider()).isEqualTo(OauthProvider.GOOGLE);
        assertThat(link.getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(link.getId()).isNull();
        assertThat(link.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("[USER-OAU-57 관련] providerUserId는 가공(대소문자 변환·트림 등) 없이 넘긴 값 그대로 보존된다"
            + "(실제 저장 시 대소문자 구분은 DB collation이 심판하며 이 테스트로는 증명되지 않는다 — 클래스 javadoc 참고)")
    void builder_providerUserId_preservedVerbatimWithoutTransformation() {
        // given: 대소문자가 섞인 네이버 스타일 식별자
        String mixedCaseId = "NaVeR_UsEr_AbC123";

        // when
        UserOauthLink link = UserOauthLink.builder()
                .userAccount(newUserAccount("nickname"))
                .provider(OauthProvider.NAVER)
                .providerUserId(mixedCaseId)
                .build();

        // then
        assertThat(link.getProviderUserId()).isEqualTo(mixedCaseId);
    }
}
