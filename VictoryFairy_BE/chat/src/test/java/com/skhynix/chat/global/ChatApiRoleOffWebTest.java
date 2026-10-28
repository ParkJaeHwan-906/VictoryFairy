package com.skhynix.chat.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.chat.global.config.SecurityConfig;
import com.skhynix.chat.message.controller.ChatMessageController;
import com.skhynix.chat.room.controller.ChatRoomController;
import com.skhynix.chat.subscription.controller.ChatSubscriptionController;
import com.skhynix.domain.user.repository.UserAccountRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CHAT-GC-3: API 역할을 끈 파드는 /rooms/** 핸들러를 아예 등록하지 않는다 — 인증된 요청도 404 이다.
 * (같은 컨트롤러 목록을 ChatApiWebTest 는 역할 기본값으로 등록해 200 대로 동작함을 본다.)
 */
@WebMvcTest
@ContextConfiguration(classes = {ChatRoomController.class, ChatMessageController.class,
        ChatSubscriptionController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "chat.role.api=false",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "jwt.access-token-validity=3600000",
        "jwt.refresh-token-validity=7200000"
})
class ChatApiRoleOffWebTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RequestPostProcessor authenticated() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @Test
    @DisplayName("[CHAT-GC-3] chat.role.api=false 면 컨트롤러 빈이 하나도 등록되지 않는다")
    void apiRoleOff_registersNoControllers() {
        assertThat(context.getBeansOfType(ChatRoomController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatSubscriptionController.class)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-3] chat.role.api=false 면 인증된 GET /rooms 도 404 이다")
    void apiRoleOff_authenticatedRoomsRequest_is404() throws Exception {
        mockMvc.perform(get("/rooms").with(authenticated())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[CHAT-GC-3] chat.role.api=false 면 구독·퇴장·전송·히스토리·신고 경로도 전부 404 이다")
    void apiRoleOff_everyRoomPath_is404() throws Exception {
        mockMvc.perform(get("/rooms/G1/subscribe").with(authenticated()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/rooms/G1/subscribe").with(authenticated())).andExpect(status().isNotFound());
        mockMvc.perform(post("/rooms/G1/messages").with(authenticated())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rooms/G1/messages").with(authenticated())).andExpect(status().isNotFound());
        mockMvc.perform(post("/rooms/G1/messages/1/report").with(authenticated())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[CHAT-GC-3] chat.role.api=false 여도 인증 필터는 그대로라 미인증 요청은 404 가 아니라 401 이다")
    void apiRoleOff_unauthenticated_isStill401() throws Exception {
        mockMvc.perform(get("/rooms")).andExpect(status().isUnauthorized());
    }
}
