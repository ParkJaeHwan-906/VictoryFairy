package com.skhynix.user.oauth.client;

import com.skhynix.domain.user.entity.OauthProvider;

/**
 * provider 한 곳과 이야기하는 포트. 인가코드를 신원으로 바꾸는 것이 전부다.
 *
 * <p>provider access·refresh 토큰은 이 인터페이스 밖으로 나가지 않는다 — 신원 확인 1회용이며
 * 저장하지도, 로그에 남기지도 않는다(인가코드도 같다).
 */
public interface OauthClient {

    OauthProvider provider();

    /** 클라이언트 자격증명이 주입됐는가. 아니면 지원 목록에서 빠진다. */
    boolean isConfigured();

    /** 허용 목록과 <b>문자 그대로 완전 일치</b>하는가. 호출자는 provider 호출 '전에' 물어야 한다. */
    boolean allowsRedirectUri(String redirectUri);

    /**
     * 인가코드 → 토큰 교환 → 사용자 정보 조회.
     *
     * @param redirectUri 요청 본문으로 받은 값을 <b>그대로</b> 실어 보낸다. 서버 고정값으로 치환하면
     *                    인가코드 발급 때와 값이 달라져 provider 가 교환을 거절한다
     * @throws com.skhynix.common.error.BusinessException {@code INVALID_OAUTH_CODE}(코드 거절) 또는
     *                                                    {@code OAUTH_PROVIDER_UNAVAILABLE}(통신 실패)
     */
    OauthUserInfo authenticate(String code, String redirectUri);
}
