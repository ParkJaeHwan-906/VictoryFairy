package com.skhynix.user.oauth.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.user.oauth.config.OauthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link GoogleOauthClient#parseUserInfo(JsonNode)} — 구글 검증 판정 규칙(USER-OAU-67)만 다룬다.
 * HTTP 왕복(토큰 교환·사용자 정보 조회)은 {@code AbstractOauthClient}의 몫이라 이 테스트로 검증하지
 * 않는다(실제 HTTP를 띄우지 않는다는 제약과, provider 호출은 {@code OauthClient} 인터페이스를 Mockito로
 * 스텁하라는 지시에 따라 상위 서비스 테스트에서는 인터페이스 자체를 목으로 대체한다). {@code parseUserInfo}는
 * 패키지 접근이라 같은 패키지의 이 테스트에서 직접 호출할 수 있다.
 */
class GoogleOauthClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GoogleOauthClient newClient() {
        OauthProperties properties = new OauthProperties();
        properties.getGoogle().setClientId("google-client-id");
        return new GoogleOauthClient(Mockito.mock(RestClient.class), objectMapper, properties);
    }

    private JsonNode parse(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("[USER-OAU-67] email_verified가 true면 검증됨으로 판정한다")
    void parseUserInfo_emailVerifiedTrue_marksVerified() {
        // given
        JsonNode body = parse("""
                {"sub":"google-sub-1","email":"user@gmail.com","email_verified":true}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.providerUserId()).isEqualTo("google-sub-1");
        assertThat(info.email()).isEqualTo("user@gmail.com");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.hasEmail()).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-67] email_verified가 false면 미검증으로 판정한다")
    void parseUserInfo_emailVerifiedFalse_marksUnverified() {
        // given
        JsonNode body = parse("""
                {"sub":"google-sub-2","email":"user2@gmail.com","email_verified":false}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("email_verified 필드 자체가 없으면 fail-closed로 미검증 처리한다(없는 것을 검증됨으로 "
            + "보면 자동 통합이 뚫린다)")
    void parseUserInfo_missingEmailVerifiedField_defaultsToUnverified() {
        // given
        JsonNode body = parse("""
                {"sub":"google-sub-3","email":"user3@gmail.com"}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("email 필드가 없으면 hasEmail()이 false다(USER-OAU-90 입력 갈래로 가는 조건)")
    void parseUserInfo_missingEmail_hasEmailFalse() {
        // given
        JsonNode body = parse("""
                {"sub":"google-sub-4"}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.email()).isNull();
        assertThat(info.hasEmail()).isFalse();
    }

    @Test
    @DisplayName("provider()는 GOOGLE을 반환한다")
    void provider_returnsGoogle() {
        assertThat(newClient().provider())
                .isEqualTo(com.skhynix.domain.user.entity.OauthProvider.GOOGLE);
    }
}
