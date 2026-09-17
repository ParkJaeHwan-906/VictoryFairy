package com.skhynix.user.oauth.client;

import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.user.oauth.config.OauthProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구글. 3사 중 유일하게 검증 여부를 한 필드({@code email_verified})로 직접 알려 준다.
 *
 * <p>식별자는 {@code sub} 다 — {@code email} 이 아니다. 구글 계정은 이메일이 바뀔 수 있고, 이메일을
 * 신원 키로 쓰면 그날 같은 사람이 남남이 된다.
 */
@Component
public class GoogleOauthClient extends AbstractOauthClient {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    public GoogleOauthClient(RestClient oauthRestClient, ObjectMapper objectMapper,
            OauthProperties properties) {
        super(oauthRestClient, objectMapper, properties.getGoogle());
    }

    @Override
    public OauthProvider provider() {
        return OauthProvider.GOOGLE;
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
        // 필드가 없으면 미검증으로 접는다(fail-closed) — 없는 것을 검증됨으로 보면 자동 통합이 뚫린다.
        return new OauthUserInfo(text(body, "sub"), text(body, "email"),
                bool(body, "email_verified", false));
    }
}
