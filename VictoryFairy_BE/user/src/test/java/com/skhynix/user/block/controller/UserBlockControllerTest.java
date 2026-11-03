package com.skhynix.user.block.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.block.dto.BlockResponse;
import com.skhynix.user.block.service.UserBlockService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /users/me/blocks}·{@code GET /users/me/blocks}(외부 노출 경로
 * {@code /api/users/me/blocks})를 검증한다. 요구사항: {@code docs/requirements/user/user-block.md}
 * (USER-BLK-1~12).
 *
 * <p>슬라이스 구성은 {@code BqRankingControllerTest}·{@code UserAccountControllerMeTest}와 같은
 * 패턴이다 — 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우고
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 목으로 제어해 인증 401을 필터 레벨에서
 * 검증한다. 차단 생성·조회 로직 자체({@link UserBlockService})는 목으로 대체해 이 슬라이스의 검증
 * 대상이 아니다({@code UserBlockServiceTest} 몫).
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/users/me/blocks}지만
 * 이 슬라이스에서 호출하는 경로는 {@code /users/me/blocks}다.
 */
@WebMvcTest(UserBlockController.class)
@ContextConfiguration(classes = UserBlockController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserBlockControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final Long ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserBlockService userBlockService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    /** 유효한 access 토큰을 스텁하고, 그 uid가 활성 계정 {@link #ACCOUNT_ID}로 해석되게 만든다. */
    private String stubAuthenticatedToken() {
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(ACCOUNT_ID, null)));
        return token;
    }

    // ---------- 인증 필수 (USER-BLK-2) ----------

    @Test
    @DisplayName("[USER-BLK-2] Authorization 헤더 없이 POST /users/me/blocks를 호출하면 401과 "
            + "\"인증이 필요합니다.\" 바디를 반환하고 서비스는 호출되지 않는다")
    void block_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"홍길동\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userBlockService);
    }

    @Test
    @DisplayName("[USER-BLK-2] 위조된(검증 실패) access 토큰으로 요청하면 401을 반환한다")
    void block_forgedToken_returns401() throws Exception {
        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"홍길동\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userBlockService);
    }

    // ---------- 차단 주체는 토큰 principal로만 식별 (USER-BLK-3) ----------

    @Test
    @DisplayName("[USER-BLK-3] 요청 본문에 blockerId를 함께 보내도 무시되고, 서비스는 항상 토큰 principal"
            + "(ACCOUNT_ID)로만 호출된다 — 본문·경로로 다른 계정을 대신 차단시킬 수 없다")
    void block_ignoresBlockerIdInBody_usesTokenPrincipalOnly() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.block(eq(ACCOUNT_ID), eq("홍길동")))
                .willReturn(new BlockResponse("홍길동"));

        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"홍길동\",\"blockerId\":999}"))
                .andExpect(status().isOk());

        verify(userBlockService).block(ACCOUNT_ID, "홍길동");
    }

    // ---------- 대상 미존재 (USER-BLK-4) ----------

    @Test
    @DisplayName("[USER-BLK-4] targetNickname에 해당하는 활성 계정이 없으면 404와 "
            + "BLOCK_TARGET_NOT_FOUND 메시지를 반환한다")
    void block_targetNotFound_returns404() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.block(eq(ACCOUNT_ID), eq("ghost")))
                .willThrow(new BusinessException(ErrorCode.BLOCK_TARGET_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.BLOCK_TARGET_NOT_FOUND.getMessage()));
    }

    // ---------- 자기 자신 차단 (USER-BLK-5) ----------

    @Test
    @DisplayName("[USER-BLK-5] 본인 닉네임으로 요청하면 400과 SELF_BLOCK_NOT_ALLOWED 메시지를 반환한다")
    void block_selfNickname_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.block(eq(ACCOUNT_ID), eq("나")))
                .willThrow(new BusinessException(ErrorCode.SELF_BLOCK_NOT_ALLOWED));

        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"나\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.SELF_BLOCK_NOT_ALLOWED.getMessage()));
    }

    // ---------- 형식 검증 ----------

    @Test
    @DisplayName("[요구사항 미기재, 경계] targetNickname이 공백/빈 문자열이면 @NotBlank에 걸려 400을 "
            + "반환하고 서비스는 호출되지 않는다")
    void block_blankTargetNickname_returns400WithoutCallingService() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userBlockService);
    }

    // ---------- 차단 성공(최초·중복 둘 다 200) + 응답 형태 (USER-BLK-6, 7, 9) ----------

    @Test
    @DisplayName("[USER-BLK-6, 9] 최초 차단은 200과 대상 닉네임을 담은 응답을 반환한다")
    void block_firstTime_returns200WithTargetNickname() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.block(eq(ACCOUNT_ID), eq("홍길동")))
                .willReturn(new BlockResponse("홍길동"));

        // when & then
        mockMvc.perform(post("/users/me/blocks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetNickname\":\"홍길동\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nickname").value("홍길동"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-BLK-7] 이미 차단한 대상을 다시 요청해도(서비스가 멱등하게 처리) 여전히 200과 같은 "
            + "응답을 반환한다 — 에러로 바뀌지 않는다")
    void block_alreadyBlockedTarget_stillReturns200() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.block(eq(ACCOUNT_ID), eq("홍길동")))
                .willReturn(new BlockResponse("홍길동"));

        // when & then: 같은 요청 2회 모두 200
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/users/me/blocks")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetNickname\":\"홍길동\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nickname").value("홍길동"));
        }
    }

    // ---------- 차단 목록 조회 (USER-BLK-10, 11, 12) ----------

    @Test
    @DisplayName("[USER-BLK-12] Authorization 헤더 없이 GET /users/me/blocks를 호출하면 401을 반환한다")
    void getMyBlocks_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/users/me/blocks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userBlockService);
    }

    @Test
    @DisplayName("[USER-BLK-10] 인증된 사용자가 조회하면 200과 자신이 차단한 대상 전원(닉네임 배열)을 "
            + "반환한다")
    void getMyBlocks_authenticated_returns200WithBlockedNicknames() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.getMyBlocks(ACCOUNT_ID))
                .willReturn(List.of(new BlockResponse("A"), new BlockResponse("B"), new BlockResponse("C")));

        // when & then
        mockMvc.perform(get("/users/me/blocks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].nickname").value("A"))
                .andExpect(jsonPath("$.data[1].nickname").value("B"))
                .andExpect(jsonPath("$.data[2].nickname").value("C"));
    }

    @Test
    @DisplayName("[USER-BLK-11] 차단 이력이 없으면 200과 빈 배열을 반환한다(에러가 아니다)")
    void getMyBlocks_emptyHistory_returns200WithEmptyArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(userBlockService.getMyBlocks(ACCOUNT_ID)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/users/me/blocks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("[USER-BLK-12] 위조된 access 토큰으로 GET /users/me/blocks를 호출하면 401을 반환한다")
    void getMyBlocks_forgedToken_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/users/me/blocks").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userBlockService);
    }
}
