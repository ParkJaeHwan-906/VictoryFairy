package com.skhynix.user.oauth.client;

import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.user.oauth.config.OauthProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 카카오.
 *
 * <p><b>지금 이 provider 는 이메일을 주지 않는다.</b> {@code account_email} 동의항목이 비즈 앱 권한이라
 * 일반 앱에서는 필수/선택 이전에 항목 자체를 쓸 수 없다 — 즉 이메일이 없는 것이 오류가 아니라 정상
 * 경로이고, 카카오 로그인은 사용자 이메일 입력 갈래로 간다.
 *
 * <p>그래서 아래 검증 판정({@code is_email_verified} <b>와</b> {@code is_email_valid} 동시 만족)은
 * 현재 도달하지 않는 코드다. ⚠ <b>지우지 말 것</b> — 비즈 앱 전환이 승인되면 이메일이 오기 시작하고,
 * 그때 {@code is_email_valid} 를 빠뜨리면 폐기된(회수·재사용될 수 있는) 주소가 검증됨으로 통과해
 * 남의 계정에 자동 통합된다.
 */
@Component
public class KakaoOauthClient extends AbstractOauthClient {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    public KakaoOauthClient(RestClient oauthRestClient, ObjectMapper objectMapper,
            OauthProperties properties) {
        super(oauthRestClient, objectMapper, properties.getKakao());
    }

    @Override
    public OauthProvider provider() {
        return OauthProvider.KAKAO;
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
        // id 는 문자열이 아니라 숫자로 온다 — 그대로 문자열화해 저장한다(가공 금지).
        String providerUserId = text(body, "id");
        JsonNode account = body.get("kakao_account");
        String email = text(account, "email");
        boolean verified = bool(account, "is_email_verified", false)
                && bool(account, "is_email_valid", false);
        return new OauthUserInfo(providerUserId, email, verified);
    }
}
