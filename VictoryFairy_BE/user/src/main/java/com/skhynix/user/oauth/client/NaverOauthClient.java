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
 * 없다.</b> 그래서 검증 판정을 언제나 {@code false} 로 접는다 — 필드가 없다는 것은 검증됐다는 근거가
 * 없다는 뜻이지 검증됐다는 뜻이 아니다(구글이 필드 부재를 미검증으로 접는 것과 같은 규칙).
 *
 * <p>⚠ 이메일이 있으면 검증됨으로 되돌리지 말 것. 그렇게 두면 공격자가 피해자 이메일로 네이버 계정을
 * 만들어 두는 것만으로 그 이메일이 <b>검증된</b> 계정을 점유하고, 진짜 주인이 다른 provider 로 들어올
 * 때 인증번호 한 번 없이 공격자 계정에 자동 통합된다 — 선점을 막는 장치가 통째로 무력화된다.
 *
 * <p>대가는 네이버로 만든 계정이 미검증으로 남는 것이다. 나중에 다른 provider 가 그 계정에 붙을 때
 * 인증번호를 한 번 요구받고, 그 승격 시점의 선점 해제가 네이버 연동까지 함께 끊어 재연동이 필요하다.
 * 정직한 사용자가 내는 일회성 비용이며 선점 방어와 맞바꾼 값이다.
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
        return new OauthUserInfo(text(response, "id"), text(response, "email"), false);
    }
}
