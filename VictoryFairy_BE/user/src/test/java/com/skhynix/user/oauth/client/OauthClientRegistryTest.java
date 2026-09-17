package com.skhynix.user.oauth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OauthClientRegistry} — 경로의 provider 이름 → 실제 호출자 해석. 요구사항:
 * {@code docs/requirements/user/oauth-login.md} USER-OAU-4, 5.
 */
@ExtendWith(MockitoExtension.class)
class OauthClientRegistryTest {

    @Mock
    private OauthClient kakaoClient;

    @Mock
    private OauthClient googleClient;

    @Test
    @DisplayName("[USER-OAU-4] 자격증명이 설정된(isConfigured=true) provider만 지원 목록에 포함돼 조회된다")
    void get_configuredProvider_returnsClient() {
        // given
        given(kakaoClient.isConfigured()).willReturn(true);
        given(kakaoClient.provider()).willReturn(OauthProvider.KAKAO);
        OauthClientRegistry registry = new OauthClientRegistry(List.of(kakaoClient));

        // when
        OauthClient found = registry.get("kakao");

        // then
        assertThat(found).isSameAs(kakaoClient);
    }

    @Test
    @DisplayName("[USER-OAU-4] 자격증명이 비어(isConfigured=false) 지원 목록에서 빠진 provider를 요청하면 "
            + "400 UNSUPPORTED_OAUTH_PROVIDER를 던진다 — 설정 누락이 502(통신 오류)로 둔갑하지 않는다")
    void get_unconfiguredProvider_throwsUnsupported() {
        // given
        given(googleClient.isConfigured()).willReturn(false);
        OauthClientRegistry registry = new OauthClientRegistry(List.of(googleClient));

        // when & then
        assertThatThrownBy(() -> registry.get("google"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }

    @Test
    @DisplayName("[USER-OAU-5] 알려진 provider 이름 자체가 아닌 값(apple)을 요청하면 400 UNSUPPORTED_OAUTH_PROVIDER를 던진다")
    void get_unknownProviderName_throwsUnsupported() {
        // given
        OauthClientRegistry registry = new OauthClientRegistry(List.of());

        // when & then
        assertThatThrownBy(() -> registry.get("apple"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }

    @Test
    @DisplayName("provider 경로 변수가 null이거나 공백이면 400 UNSUPPORTED_OAUTH_PROVIDER를 던진다")
    void get_blankProvider_throwsUnsupported() {
        // given
        OauthClientRegistry registry = new OauthClientRegistry(List.of());

        // when & then
        assertThatThrownBy(() -> registry.get(""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }

    @Test
    @DisplayName("provider 이름은 대소문자를 구분하지 않는다(KAKAO/kakao 동일 해석)")
    void get_caseInsensitiveProviderName_resolvesSameClient() {
        // given
        given(kakaoClient.isConfigured()).willReturn(true);
        given(kakaoClient.provider()).willReturn(OauthProvider.KAKAO);
        OauthClientRegistry registry = new OauthClientRegistry(List.of(kakaoClient));

        // when
        OauthClient found = registry.get("KAKAO");

        // then
        assertThat(found).isSameAs(kakaoClient);
    }
}
