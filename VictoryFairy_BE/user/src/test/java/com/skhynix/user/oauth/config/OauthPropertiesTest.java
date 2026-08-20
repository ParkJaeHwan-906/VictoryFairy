package com.skhynix.user.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OauthProperties.Registration} — {@code redirectUri} 허용 목록 대조(USER-OAU-63~66)와
 * provider 지원 판정(USER-OAU-4)의 단일 출처.
 */
class OauthPropertiesTest {

    private OauthProperties.Registration registrationWithUris(String... uris) {
        OauthProperties.Registration registration = new OauthProperties.Registration();
        registration.setRedirectUris(List.of(uris));
        return registration;
    }

    @Test
    @DisplayName("[USER-OAU-63] 허용 목록과 문자 그대로 완전 일치하면 true다")
    void allowsRedirectUri_exactMatch_returnsTrue() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("https://victoryfairy.com/oauth/kakao");

        // when & then
        assertThat(registration.allowsRedirectUri("https://victoryfairy.com/oauth/kakao")).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-63] 허용 목록 값 뒤에 쿼리스트링이 붙으면(접두 일치) 통과하지 않는다")
    void allowsRedirectUri_suffixedWithQueryString_returnsFalse() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("https://victoryfairy.com/oauth/kakao");

        // when & then
        assertThat(registration.allowsRedirectUri("https://victoryfairy.com/oauth/kakao?x=1")).isFalse();
    }

    @Test
    @DisplayName("[USER-OAU-63] 허용 목록 값을 접두로 갖는 다른 도메인(evil.com)은 통과하지 않는다"
            + "(startsWith로 구현하면 이 테스트가 깨진다)")
    void allowsRedirectUri_prefixMatchDifferentDomain_returnsFalse() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("https://victoryfairy.com");

        // when & then
        assertThat(registration.allowsRedirectUri("https://victoryfairy.com.evil.com")).isFalse();
    }

    @Test
    @DisplayName("[USER-OAU-64] 허용 목록에 없는 임의 주소는 통과하지 않는다")
    void allowsRedirectUri_notInList_returnsFalse() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("https://victoryfairy.com/oauth/kakao");

        // when & then
        assertThat(registration.allowsRedirectUri("https://attacker.example.com")).isFalse();
    }

    @Test
    @DisplayName("[USER-OAU-65] redirectUri가 null이거나 공백이면 통과하지 않는다")
    void allowsRedirectUri_nullOrBlank_returnsFalse() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("https://victoryfairy.com/oauth/kakao");

        // when & then
        assertThat(registration.allowsRedirectUri(null)).isFalse();
        assertThat(registration.allowsRedirectUri("")).isFalse();
        assertThat(registration.allowsRedirectUri("   ")).isFalse();
    }

    @Test
    @DisplayName("앱 커스텀 스킴 값도 완전 일치하면 허용된다(웹 주소와 다른 형태라고 특별 취급하지 않는다)")
    void allowsRedirectUri_customScheme_exactMatch_returnsTrue() {
        // given
        OauthProperties.Registration registration =
                registrationWithUris("victoryfairy://oauth/kakao");

        // when & then
        assertThat(registration.allowsRedirectUri("victoryfairy://oauth/kakao")).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-4] clientId가 채워지면 isConfigured()가 true다")
    void isConfigured_clientIdPresent_returnsTrue() {
        // given
        OauthProperties.Registration registration = new OauthProperties.Registration();
        registration.setClientId("client-id");

        // when & then
        assertThat(registration.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-4] clientId가 null이거나 공백이면 isConfigured()가 false다")
    void isConfigured_clientIdMissing_returnsFalse() {
        // given
        OauthProperties.Registration registration = new OauthProperties.Registration();

        // when & then
        assertThat(registration.isConfigured()).isFalse();

        registration.setClientId("   ");
        assertThat(registration.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("setRedirectUris(null)은 빈 리스트로 흡수한다(NPE 대신 안전한 기본값)")
    void setRedirectUris_null_absorbsToEmptyList() {
        // given
        OauthProperties.Registration registration = new OauthProperties.Registration();

        // when
        registration.setRedirectUris(null);

        // then
        assertThat(registration.getRedirectUris()).isEmpty();
        assertThat(registration.allowsRedirectUri("anything")).isFalse();
    }
}
