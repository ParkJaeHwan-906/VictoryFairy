package com.skhynix.user.global.jwt;

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

/**
 * 토큰의 subject(외부 노출용 {@code uid})를 내부 PK({@code id})로 바꿔주는 경계.
 *
 * <p>외부에는 {@code uid}만 오가고, 인증 이후 서비스·컨트롤러가 보는 principal은
 * 종전과 동일하게 {@code Long userAccountId}다. 이 변환 때문에 요청당 조회 1회가 발생하지만,
 * {@code id}를 토큰 claim에 함께 실어 조회를 없애는 것은 PK 노출과 같으므로 의도적으로 감수한다.
 * 대신 필터가 쓰는 값이 {@code id} 하나뿐이므로 엔티티가 아니라 {@code id}만 조회한다.
 */
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
            // uid에 해당하는 계정이 없으면(탈퇴/삭제 등) 인증하지 않고 통과시킨다.
            // SecurityContext가 빈 채로 남으면 authorizeHttpRequests의 authenticated() 규칙이
            // AuthenticationException을 던지고, 이를 받은 ExceptionTranslationFilter가
            // SecurityConfig에 등록된 AuthenticationEntryPoint를 호출해 401을 내린다.
            Optional<Long> accountId = userAccountRepository.findIdByUid(tokenProvider.getUid(token));
            if (accountId.isPresent()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                accountId.get(), null, Collections.emptyList());
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
