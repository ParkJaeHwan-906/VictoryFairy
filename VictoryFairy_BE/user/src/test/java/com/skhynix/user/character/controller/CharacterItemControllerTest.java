package com.skhynix.user.character.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.character.dto.CharacterItemActiveResponse;
import com.skhynix.user.character.dto.CharacterItemPurchaseResponse;
import com.skhynix.user.character.dto.CharacterItemResponse;
import com.skhynix.user.character.service.CharacterItemService;
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
 * {@link CharacterItemController} 슬라이스 테스트. 실제 {@link SecurityConfig}(따라서 실제
 * {@code JwtAuthenticationFilter})를 태우므로 <b>401 판정을 서비스 목이 아니라 필터 레벨에서</b>
 * 검증한다 — 이 세 경로가 permitAll 목록에 없어야 한다는 사실이 그 테스트로 고정된다.
 */
@WebMvcTest(CharacterItemController.class)
@ContextConfiguration(classes = CharacterItemController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CharacterItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CharacterItemService characterItemService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private String stubValidAccessToken(String uid, Long accountId) {
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        return token;
    }

    // ---------- 인증 ----------

    @Test
    @DisplayName("토큰 없이 목록을 요청하면 401 이고 서비스는 호출되지 않는다")
    void findAll_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/characters/items"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(characterItemService);
    }

    @Test
    @DisplayName("토큰 없이 구매를 요청하면 401 이고 서비스는 호출되지 않는다")
    void purchase_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/characters/items/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(characterItemService);
    }

    @Test
    @DisplayName("토큰 없이 착용 토글을 요청하면 401 이고 서비스는 호출되지 않는다")
    void toggleActive_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/characters/items/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(characterItemService);
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록은 ApiResponse.data 배열로 나가고 각 항목의 키는 정확히 7개다")
    void findAll_authenticated_returnsItemArray() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        given(characterItemService.findAll(1L)).willReturn(List.of(
                new CharacterItemResponse(10L, "의상", "기본 의상", "stores/cloth/basic.svg",
                        100L, true, true),
                new CharacterItemResponse(11L, "모자", "블루 캡", "stores/head/cap-blue.svg",
                        100L, false, false)));

        mockMvc.perform(get("/characters/items").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].length()").value(7))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].itemType").value("의상"))
                .andExpect(jsonPath("$.data[0].name").value("기본 의상"))
                // 상점 진열용 EP 다 — 착용용(using_img)은 이 응답에 싣지 않는다.
                .andExpect(jsonPath("$.data[0].displayImg").value("stores/cloth/basic.svg"))
                .andExpect(jsonPath("$.data[0].price").value(100))
                .andExpect(jsonPath("$.data[0].having").value(true))
                .andExpect(jsonPath("$.data[0].active").value(true))
                .andExpect(jsonPath("$.data[1].having").value(false));
    }

    // ---------- 구매 ----------

    @Test
    @DisplayName("구매에 성공하면 200 과 함께 아이템 id·차감 후 잔액을 돌려준다")
    void purchase_success_returnsRemainingPoint() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        given(characterItemService.purchase(1L, 10L))
                .willReturn(new CharacterItemPurchaseResponse(10L, 200L));

        mockMvc.perform(post("/characters/items/purchase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characterItemId").value(10))
                .andExpect(jsonPath("$.data.remainingPoint").value(200));
    }

    @Test
    @DisplayName("characterItemId 가 없으면 400 이고 서비스는 호출되지 않는다")
    void purchase_missingItemId_returns400() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);

        mockMvc.perform(post("/characters/items/purchase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(characterItemService);
    }

    @Test
    @DisplayName("포인트가 모자라면 400 과 해당 문구를 그대로 돌려준다")
    void purchase_insufficientPoint_returns400() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        willThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINT))
                .given(characterItemService).purchase(eq(1L), eq(10L));

        mockMvc.perform(post("/characters/items/purchase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INSUFFICIENT_POINT.getMessage()));
    }

    @Test
    @DisplayName("이미 보유한 아이템이면 409 를 돌려준다")
    void purchase_alreadyOwned_returns409() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        willThrow(new BusinessException(ErrorCode.CHARACTER_ITEM_ALREADY_OWNED))
                .given(characterItemService).purchase(eq(1L), eq(10L));

        mockMvc.perform(post("/characters/items/purchase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.CHARACTER_ITEM_ALREADY_OWNED.getMessage()));
    }

    // ---------- 착용 토글 ----------

    @Test
    @DisplayName("토글 응답은 요청 후의 상태를 그대로 돌려준다")
    void toggleActive_success_returnsResultingState() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        given(characterItemService.toggleActive(1L, 10L))
                .willReturn(new CharacterItemActiveResponse(10L, true));

        mockMvc.perform(put("/characters/items/active")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characterItemId").value(10))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("보유하지 않은 아이템을 토글하면 404 를 돌려준다")
    void toggleActive_notOwned_returns404() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid, 1L);
        willThrow(new BusinessException(ErrorCode.CHARACTER_ITEM_NOT_OWNED))
                .given(characterItemService).toggleActive(eq(1L), eq(10L));

        mockMvc.perform(put("/characters/items/active")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterItemId\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.CHARACTER_ITEM_NOT_OWNED.getMessage()));
    }
}
