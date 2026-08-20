package com.skhynix.user.oauth.client;

import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.user.oauth.config.OauthProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 네이버.
 *
 * <p>사용자 정보가 {@code response} 아래 한 겹 들어가 있고, <b>이메일 검증 여부를 알려 주는 필드가
 * 없다.</b> 그래서 이메일이 오면 검증됨으로 취급한다 — 이것이 자동 통합의 가장 약한 고리이며, 네이버
 * 쪽에 미검증 이메일 계정이 존재할 수 있다면 그 계정은 코드 인증 없이 기존 계정에 붙는다. 네이버가
 * 정책을 바꾸거나 사고가 보고되면 가장 먼저 재검토할 자리다.
 *
 * <p>이메일은 선택 동의라 사용자가 거부할 수 있다 — 그때는 카카오와 같은 이메일 입력 갈래로 간다
 * (분기 기준은 provider 종류가 아니라 "이번 응답에 이메일이 있는가"다).
 */
@Component
public class NaverOauthClient extends AbstractOauthClient {

    private static final String TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    public NaverOauthClient(RestClient oauthRestClient, ObjectMapper objectMapper,
            OauthProperties properties) {
        super(oauthRestClient, objectMapper, properties.getNaver());
    }

    @Override
    public OauthProvider provider() {
        return OauthProvider.NAVER;
    }

    @Override
    protected String tokenUri() {
        return TOKEN_URI;
    }

    @Override
    protected String userInfoUri() {
        return USER_INFO_URI;
    }

    @Override
    protected OauthUserInfo parseUserInfo(JsonNode body) {
        JsonNode response = body.get("response");
        String email = text(response, "email");
        return new OauthUserInfo(text(response, "id"), email, email != null);
    }
}
