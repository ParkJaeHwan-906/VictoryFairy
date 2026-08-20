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
 * {@link NaverOauthClient#parseUserInfo(JsonNode)} — 네이버는 검증 여부 필드가 없어 이메일이 오면
 * 무조건 검증됨으로 취급한다(USER-OAU-67, 제약 11 — 자동 통합의 가장 약한 고리).
 */
class NaverOauthClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NaverOauthClient newClient() {
        OauthProperties properties = new OauthProperties();
        properties.getNaver().setClientId("naver-client-id");
        return new NaverOauthClient(Mockito.mock(RestClient.class), objectMapper, properties);
    }

    private JsonNode parse(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("[USER-OAU-67] 검증 필드가 없어도 email이 있으면 무조건 검증됨으로 판정한다")
    void parseUserInfo_emailPresent_alwaysMarkedVerified() {
        // given: 실제 네이버 응답에는 email_verified류 필드가 없다
        JsonNode body = parse("""
                {"response":{"id":"naver-id-1","email":"user@naver.com"}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.providerUserId()).isEqualTo("naver-id-1");
        assertThat(info.email()).isEqualTo("user@naver.com");
        assertThat(info.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("이메일 동의를 거부해 response.email이 없으면 email이 null이고 검증 여부도 false다"
            + "(USER-OAU-90 입력 갈래로 간다 — 네이버도 카카오와 같은 갈래를 탈 수 있다)")
    void parseUserInfo_emailConsentDenied_hasEmailFalse() {
        // given
        JsonNode body = parse("""
                {"response":{"id":"naver-id-2"}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.email()).isNull();
        assertThat(info.hasEmail()).isFalse();
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("provider()는 NAVER를 반환한다")
    void provider_returnsNaver() {
        assertThat(newClient().provider())
                .isEqualTo(com.skhynix.domain.user.entity.OauthProvider.NAVER);
    }
}
