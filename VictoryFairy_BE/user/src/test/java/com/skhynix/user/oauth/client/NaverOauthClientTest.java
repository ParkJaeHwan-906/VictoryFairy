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
 * {@link NaverOauthClient#parseUserInfo(JsonNode)} — 네이버는 검증 여부 필드가 없어 이메일이 와도
 * 언제나 미검증으로 접는다(USER-OAU-67). 이 판정이 뒤집히면 선점 방어가 네이버에서만 뚫린다.
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
    @DisplayName("[USER-OAU-67] 검증 필드가 없으므로 email이 있어도 미검증으로 판정한다")
    void parseUserInfo_emailPresent_neverMarkedVerified() {
        // given: 실제 네이버 응답에는 email_verified류 필드가 없다
        JsonNode body = parse("""
                {"response":{"id":"naver-id-1","email":"user@naver.com"}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then: 이메일은 쓰되(입력 갈래로 보내지 않는다) 검증 판정만 접는다
        assertThat(info.providerUserId()).isEqualTo("naver-id-1");
        assertThat(info.email()).isEqualTo("user@naver.com");
        assertThat(info.hasEmail()).isTrue();
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("네이버가 email_verified류 필드를 주기 시작해도 그 값을 믿지 않는다"
            + "(판정은 응답이 아니라 이 클래스의 규칙이다)")
    void parseUserInfo_unknownVerifiedField_stillFalse() {
        // given: 네이버가 언젠가 필드를 추가하더라도 계약을 확인하기 전까지는 근거가 아니다
        JsonNode body = parse("""
                {"response":{"id":"naver-id-3","email":"user@naver.com","email_verified":true}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
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
