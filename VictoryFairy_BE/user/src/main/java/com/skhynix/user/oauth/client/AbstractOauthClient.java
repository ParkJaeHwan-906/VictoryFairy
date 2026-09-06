package com.skhynix.user.oauth.client;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.oauth.config.OauthProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 3사가 공유하는 절차(토큰 교환 → 사용자 정보 조회 → 응답 해석)와 <b>실패의 번역</b>을 모아 둔 골격.
 *
 * <p>세 provider 가 다른 것은 엔드포인트 주소와 응답 필드 모양뿐이라 그 둘만 하위 클래스로 내렸다.
 *
 * <p>응답을 타입 바인딩이 아니라 {@link JsonNode} 로 읽는 이유: 3사의 사용자 정보 응답이 중첩 구조도
 * 필드 이름도 제각각이고(카카오는 {@code kakao_account} 아래, 네이버는 {@code response} 아래),
 * provider 가 필드를 <b>늘리는 것</b>이 정상이라 엄격한 DTO 바인딩은 로그인을 깨는 방향으로만 작동한다.
 */
public abstract class AbstractOauthClient implements OauthClient {

    private static final String GRANT_TYPE = "authorization_code";

    protected final RestClient restClient;
    protected final ObjectMapper objectMapper;
    private final OauthProperties.Registration registration;

    protected AbstractOauthClient(RestClient restClient, ObjectMapper objectMapper,
            OauthProperties.Registration registration) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.registration = registration;
    }

    @Override
    public boolean isConfigured() {
        return registration.isConfigured();
    }

    @Override
    public boolean allowsRedirectUri(String redirectUri) {
        return registration.allowsRedirectUri(redirectUri);
    }

    @Override
    public final OauthUserInfo authenticate(String code, String redirectUri) {
        String accessToken = exchangeCode(code, redirectUri);
        return parseUserInfo(fetchUserInfo(accessToken));
    }

    /** 토큰 엔드포인트 주소. */
    protected abstract String tokenUri();

    /** 사용자 정보 엔드포인트 주소. */
    protected abstract String userInfoUri();

    /** provider 응답(JSON) → 신원. 검증 판정 규칙이 3사가 다르므로 여기서 갈린다. */
    protected abstract OauthUserInfo parseUserInfo(JsonNode body);

    private String exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("client_id", registration.getClientId());
        form.add("code", code);
        // 인가코드를 받을 때 쓰인 값과 문자 그대로 같아야 한다 — 서버 고정값으로 치환하지 말 것.
        form.add("redirect_uri", redirectUri);
        if (registration.getClientSecret() != null && !registration.getClientSecret().isBlank()) {
            form.add("client_secret", registration.getClientSecret());
        }

        String accessToken = text(post(tokenUri(), form), "access_token");
        // 네이버는 잘못된 인가코드에도 200 + {"error":"..."} 를 준다 — 상태코드만 보면 통과한다.
        if (accessToken == null) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_CODE);
        }
        return accessToken;
    }

    private JsonNode fetchUserInfo(String accessToken) {
        try {
            return readTree(restClient.get()
                    .uri(userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new BusinessException(ErrorCode.INVALID_OAUTH_CODE);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
                    })
                    .body(String.class));
        } catch (RestClientException e) {
            // 타임아웃·연결 실패·해석 불가 응답. ⚠ 예외 메시지에 요청 본문이 실릴 수 있어 로그로도 남기지 않는다.
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private JsonNode post(String uri, MultiValueMap<String, String> form) {
        try {
            return readTree(restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    // 4xx 는 코드가 만료됐거나 이미 쓰였거나 provider 가 redirect URI 불일치로 본 경우다.
                    // 셋 다 사용자가 할 일이 "다시 로그인"으로 같아 한 응답으로 합친다.
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new BusinessException(ErrorCode.INVALID_OAUTH_CODE);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
                    })
                    .body(String.class));
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            // JSON 이 아닌 응답(점검 페이지 HTML 등)은 provider 쪽 사정이다.
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    /** 없거나 null 이면 {@code null}. 빈 문자열도 "값 없음"으로 접는다. */
    protected static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        // stringValue() 가 아니라 asString() 이다 — 카카오 id 처럼 문자열이 아닌 노드에 엄격 접근자를
        // 쓰면 Jackson 3 는 기본값을 주는 대신 예외를 던진다(그 예외는 곧 502 로 번역돼 로그인이 죽는다).
        String text = value.asString("");
        return text.isBlank() ? null : text;
    }

    /** 없으면 {@code defaultValue}. provider 가 필드를 안 주는 것과 {@code false} 를 구분하기 위함이다. */
    protected static boolean bool(JsonNode node, String field, boolean defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.booleanValue(defaultValue);
    }
}
