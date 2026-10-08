package com.skhynix.user.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.jayway.jsonpath.JsonPath;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * 커뮤니티 컨트롤러 슬라이스 공용 뼈대. 기존 user 슬라이스 테스트와 같은 방식이다 - 실제 {@code SecurityConfig}(실제
 * {@code JwtAuthenticationFilter})를 태우고 {@link JwtTokenProvider}·{@link UserAccountRepository} 만 목으로 제어해,
 * 토큰이 해석된 내부 id({@link #ME}, Long principal)로 서비스가 호출되는지와 401 을 필터 레벨에서 함께 검증한다.
 *
 * <p>MockMvc 는 context-path 를 적용하지 않으므로 외부 경로 {@code /api/community/**} 대신 {@code /community/**} 를 호출한다.
 */
abstract class CommunitySliceSupport {

    static final Long ME = 7L;
    static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    protected JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    protected UserAccountRepository userAccountRepository;

    /** 유효한 access 토큰을 스텁하고 그 uid 가 활성 계정 {@link #ME} 로 해석되게 만든 뒤 Authorization 헤더 값을 돌려준다. */
    protected String bearer() {
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(ME, null)));
        return "Bearer " + token;
    }

    /** JSON 경로가 가리키는 객체의 키 집합이 정확히 {@code keys} 인지(값이 null 인 키 포함) 본다. */
    @SuppressWarnings("unchecked")
    static ResultMatcher keySet(String path, String... keys) {
        return result -> {
            Object value = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
            assertThat(value).as(path).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) value).as(path).containsOnlyKeys(keys);
        };
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }
}
