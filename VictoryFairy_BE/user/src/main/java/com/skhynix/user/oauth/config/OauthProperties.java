package com.skhynix.user.oauth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 3사의 클라이언트 자격증명과 리다이렉트 허용 목록.
 *
 * <p>세 provider 를 {@code Map} 이 아니라 이름 붙은 필드로 두는 이유는 오타 때문이다. Map 키는
 * 무엇을 적어도 바인딩이 성공하므로 {@code oauth.kakoo.client-id} 같은 오타가 기동에 아무 흔적을
 * 남기지 않고 <b>그 provider 만 조용히 미지원</b>이 된다(증상은 배포 후 첫 카카오 로그인의 400 이다).
 *
 * <p>값의 출처는 전부 환경변수다 — 시크릿을 yaml·코드에 적지 않는다.
 */
@ConfigurationProperties(prefix = "oauth")
public class OauthProperties {

    private Registration kakao = new Registration();
    private Registration naver = new Registration();
    private Registration google = new Registration();

    public Registration getKakao() {
        return kakao;
    }

    public void setKakao(Registration kakao) {
        this.kakao = kakao;
    }

    public Registration getNaver() {
        return naver;
    }

    public void setNaver(Registration naver) {
        this.naver = naver;
    }

    public Registration getGoogle() {
        return google;
    }

    public void setGoogle(Registration google) {
        this.google = google;
    }

    public static class Registration {

        private String clientId;
        private String clientSecret;

        /**
         * 인가코드 발급에 쓰인 리다이렉트 주소의 허용 목록.
         *
         * <p>서버 고정값으로 대체할 수 없다 — 웹({@code https://…})과 앱(커스텀 스킴)이 서로 다른
         * 값을 쓰고, 토큰 교환 시 인가코드를 받을 때와 <b>문자 그대로 같은 값</b>이어야 한다. 그래서
         * 요청 본문으로 받되 이 목록과 완전 일치로 대조한다.
         *
         * <p>⚠ 이 목록은 provider 콘솔의 화이트리스트와 같은 값이어야 한다. 한쪽만 늘리면 provider 가
         * 인가코드를 안 주거나(콘솔 누락) 우리가 400 을 준다(여기 누락).
         */
        private List<String> redirectUris = List.of();

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<String> getRedirectUris() {
            return redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris == null ? List.of() : redirectUris;
        }

        /** 자격증명이 주입된 provider 만 지원 목록에 든다. 시크릿은 구글·네이버만 쓰므로 보지 않는다. */
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank();
        }

        /**
         * ⚠ 접두 일치({@code startsWith})로 바꾸지 말 것 — {@code https://victoryfairy.com} 을 접두로
         * 검사하면 {@code https://victoryfairy.com.evil.com} 이 통과해 인가코드가 공격자에게 간다.
         */
        public boolean allowsRedirectUri(String redirectUri) {
            return redirectUri != null && !redirectUri.isBlank() && redirectUris.contains(redirectUri);
        }
    }
}
