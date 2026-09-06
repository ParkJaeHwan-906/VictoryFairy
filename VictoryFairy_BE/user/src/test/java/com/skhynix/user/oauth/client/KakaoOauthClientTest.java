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
 * {@link KakaoOauthClient#parseUserInfo(JsonNode)} — 카카오 검증 판정 규칙(USER-OAU-67:
 * {@code is_email_verified} AND {@code is_email_valid} 동시 만족)을 다룬다. 이 갈래는 비즈 앱 전환
 * 전까지 실제로는 도달하지 않지만(카카오가 이메일 자체를 안 준다), 전환 승인 시를 위해 파싱 로직만은
 * 지금 고정해 둔다(요구사항 문서 제약 12).
 */
class KakaoOauthClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KakaoOauthClient newClient() {
        OauthProperties properties = new OauthProperties();
        properties.getKakao().setClientId("kakao-client-id");
        return new KakaoOauthClient(Mockito.mock(RestClient.class), objectMapper, properties);
    }

    private JsonNode parse(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("[USER-OAU-67] is_email_verified=true, is_email_valid=true면 검증됨으로 판정한다")
    void parseUserInfo_bothVerifiedAndValid_marksVerified() {
        // given
        JsonNode body = parse("""
                {"id":123456789,"kakao_account":{"email":"user@kakao.com",
                "is_email_verified":true,"is_email_valid":true}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.providerUserId()).isEqualTo("123456789");
        assertThat(info.email()).isEqualTo("user@kakao.com");
        assertThat(info.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("[USER-OAU-67 인수기준] is_email_verified=true, is_email_valid=false면 미검증으로 판정한다"
            + "(폐기된 주소가 검증됨으로 통과하지 않도록 두 필드를 모두 본다)")
    void parseUserInfo_verifiedButInvalid_marksUnverified() {
        // given
        JsonNode body = parse("""
                {"id":123456790,"kakao_account":{"email":"stale@kakao.com",
                "is_email_verified":true,"is_email_valid":false}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("is_email_verified=false, is_email_valid=true면 미검증으로 판정한다")
    void parseUserInfo_validButNotVerified_marksUnverified() {
        // given
        JsonNode body = parse("""
                {"id":123456791,"kakao_account":{"email":"user@kakao.com",
                "is_email_verified":false,"is_email_valid":true}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("두 필드가 모두 없으면 fail-closed로 미검증 처리한다")
    void parseUserInfo_missingBothFields_defaultsToUnverified() {
        // given
        JsonNode body = parse("""
                {"id":123456792,"kakao_account":{"email":"user@kakao.com"}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("[선행 조건 3] account_email 동의항목을 못 써 이메일이 오지 않는 지금의 정상 응답 모양에서는 "
            + "email이 null이고 hasEmail()이 false다(USER-OAU-90 입력 갈래로 간다)")
    void parseUserInfo_noEmailConsent_hasEmailFalse() {
        // given: 비즈 앱이 아닌 지금, kakao_account에 email 필드 자체가 없는 실제 응답 모양
        JsonNode body = parse("""
                {"id":123456793,"kakao_account":{}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.email()).isNull();
        assertThat(info.hasEmail()).isFalse();
    }

    @Test
    @DisplayName("id는 숫자로 오지만 가공 없이 문자열 그대로 저장된다(해싱·접두 부착 금지)")
    void parseUserInfo_numericId_convertedToStringVerbatim() {
        // given
        JsonNode body = parse("""
                {"id":987654321,"kakao_account":{}}
                """);

        // when
        OauthUserInfo info = newClient().parseUserInfo(body);

        // then
        assertThat(info.providerUserId()).isEqualTo("987654321");
    }

    @Test
    @DisplayName("provider()는 KAKAO를 반환한다")
    void provider_returnsKakao() {
        assertThat(newClient().provider())
                .isEqualTo(com.skhynix.domain.user.entity.OauthProvider.KAKAO);
    }
}
