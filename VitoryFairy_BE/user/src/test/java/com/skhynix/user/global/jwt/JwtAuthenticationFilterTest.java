package com.skhynix.user.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.user.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link JwtAuthenticationFilter}가 토큰 subject(uid)를 {@code UserAccountRepository}로 내부 PK
 * {@code id}로 바꿔 principal에 싣는 경계를 검증한다.
 *
 * <p>스프링 컨텍스트 없이 {@link JwtTokenProvider}는 실제 인스턴스로(진짜 서명·파싱 로직을 태우도록)
 * 직접 생성하고, {@link UserAccountRepository}만 Mockito로 대체한다. {@code doFilterInternal}은
 * {@code protected}이라 같은 패키지의 테스트 클래스에서 직접 호출할 수 있다.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtTokenProvider tokenProvider = newTokenProvider();
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(tokenProvider, userAccountRepository);

    private static JwtTokenProvider newTokenProvider() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenValidity(3 * 60 * 60 * 1000L);
        properties.setRefreshTokenValidity(14 * 24 * 60 * 60 * 1000L);
        return new JwtTokenProvider(properties);
    }

    @BeforeEach
    void clearContextBefore() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContextAfter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 access 토큰이고 uid에 해당하는 계정이 있으면 principal에 Long id가 담긴다(uid 문자열이 아니다)")
    void validAccessToken_knownUid_setsAuthenticationWithLongId() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 42L;
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        String token = tokenProvider.createAccessToken(uid);
        MockHttpServletRequest request = requestWithBearerToken(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(Long.class);
        assertThat(authentication.getPrincipal()).isEqualTo(accountId);
        assertThat(authentication.isAuthenticated()).isTrue();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("[USER-WD-6, USER-WD-9] uid에 해당하는 활성 계정을 찾지 못하면(탈퇴 등) 예외 없이 "
            + "SecurityContext를 비운 채 체인을 통과시킨다 — findActiveIdByUid가 탈퇴 계정을 "
            + "\"찾지 못함\"으로 흡수하는 지점이라, 탈퇴 전에 발급된 access 토큰이라도 이후 요청은 "
            + "인증되지 않는다(anyRequest().authenticated()에 걸려 최종적으로 401)")
    void validAccessToken_unknownUid_leavesContextEmptyWithoutException() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.empty());
        String token = tokenProvider.createAccessToken(uid);
        MockHttpServletRequest request = requestWithBearerToken(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when & then: 예외가 발생하지 않아야 한다
        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("refresh 토큰으로는 인증되지 않고, uid 조회조차 수행하지 않는다")
    void refreshToken_isRejected_withoutRepositoryLookup() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        String refreshToken = tokenProvider.createRefreshToken(uid);
        MockHttpServletRequest request = requestWithBearerToken(refreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userAccountRepository);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 인증을 시도하지 않고 체인만 통과시킨다")
    void noAuthorizationHeader_skipsAuthentication() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userAccountRepository);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("형식이 깨진 토큰이면 인증을 시도하지 않고 예외 없이 체인만 통과시킨다")
    void malformedToken_skipsAuthenticationWithoutException() throws Exception {
        // given
        MockHttpServletRequest request = requestWithBearerToken("not-a-valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when & then: 예외가 발생하지 않아야 한다
        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userAccountRepository, never()).findActiveIdByUid(anyString());
        verify(filterChain).doFilter(request, response);
    }

    private MockHttpServletRequest requestWithBearerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
