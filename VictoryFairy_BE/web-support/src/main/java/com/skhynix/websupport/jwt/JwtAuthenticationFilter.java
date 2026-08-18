package com.skhynix.websupport.jwt;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserAccountRepository userAccountRepository;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
            UserAccountRepository userAccountRepository) {
        this.tokenProvider = tokenProvider;
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && tokenProvider.validateToken(token) && !tokenProvider.isRefreshToken(token)) {
            // access 토큰은 stateless라 서버가 폐기할 수 없다 — 탈퇴(계정 없음)와 비밀번호 변경
            // (기준 시각보다 앞선 초에 발급됨) 둘 다 이 한 번의 조회로 막는다. 기준 시각을 따로
            // 조회하면 요청당 SELECT 가 늘어나므로 같은 행에서 함께 실어 온다.
            Optional<ActiveAccountView> account =
                    userAccountRepository.findActiveAuthByUid(tokenProvider.getUid(token));
            if (account.isPresent()
                    && account.get().acceptsTokenIssuedAt(tokenProvider.getIssuedAtEpochSecond(token))) {
                // 무효화된 토큰은 예외를 던지지 않고 그냥 principal 을 안 채운다 — 인증 필수 경로는
                // 기존 미인증과 문자 그대로 같은 401 이 되고, permitAll 경로(/api/players)는 헤더가
                // 없는 요청과 동일하게 200 으로 지나간다. 여기서 응답을 직접 쓰면 그 두 성질이 함께 깨진다.
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                account.get().id(), null, Collections.emptyList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
