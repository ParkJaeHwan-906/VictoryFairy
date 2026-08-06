package com.skhynix.quiz.chat.controller;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * quiz {@code SecurityConfig}의 {@code JwtAuthenticationFilter}가 인증 성공 시 만드는 principal 형태
 * (production: {@code Long userAccountId}, credentials=null, authorities=empty)를 슬라이스 테스트에서
 * 그대로 재현하기 위한 헬퍼.
 *
 * <p><b>{@code @WithMockUser}를 쓰지 않은 이유</b>: {@code @WithMockUser}는 principal을
 * {@code org.springframework.security.core.userdetails.User}(또는 문자열)로 만드는데,
 * {@code ChatController}의 {@code @AuthenticationPrincipal Long userAccountId}는 principal이 정확히
 * {@code Long}이어야 바인딩된다(타입 불일치 시 바인딩 실패). 대신
 * {@code SecurityMockMvcRequestPostProcessors.authentication(...)}로 실제 필터가 만드는 것과 동일한 형태의
 * {@link UsernamePasswordAuthenticationToken}을 직접 {@code SecurityContext}에 주입한다.
 * {@code JwtAuthenticationFilter}는 {@code Authorization} 헤더가 없으면 {@code SecurityContext}를 건드리지
 * 않고 그대로 통과시키므로, 헤더 없이 이 방식으로 주입한 인증은 필터를 거치며 덮어써지지 않는다.
 */
final class ChatControllerTestSupport {

    private ChatControllerTestSupport() {
    }

    static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }
}
