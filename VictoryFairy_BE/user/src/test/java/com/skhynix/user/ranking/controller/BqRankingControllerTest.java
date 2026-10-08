package com.skhynix.user.ranking.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.ranking.dto.BqRankingResponse;
import com.skhynix.user.ranking.service.BqRankingService;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /rankings/bq/top}·{@code GET /rankings/bq}·{@code GET /rankings/bq/me}(외부 노출 경로
 * {@code /api/rankings/bq/**})를 검증한다. 요구사항: {@code docs/requirements/user/team-bq-ranking.md}
 * (USER-RK-1~84).
 *
 * <p>슬라이스 구성은 {@code GameControllerSupportTest}·{@code UserAccountControllerMeTest}와 같은 패턴이다 —
 * 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우고 {@link JwtTokenProvider}·
 * {@link UserAccountRepository}를 목으로 제어해 인증 401을 필터 레벨에서 검증한다. 순위 산정 자체
 * ({@link BqRankingService})는 목으로 대체해 이 슬라이스의 검증 대상이 아니다({@code BqRankingServiceTest} 몫).
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/rankings/bq/**}지만 이
 * 슬라이스에서 호출하는 경로는 {@code /rankings/bq/**}다.
 */
@WebMvcTest(BqRankingController.class)
@ContextConfiguration(classes = BqRankingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class BqRankingControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final String METHOD_NOT_ALLOWED_MESSAGE = "지원하지 않는 요청 메서드입니다.";
    private static final Long ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BqRankingService bqRankingService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final BqRankingResponse SAMPLE_ENTRY =
            new BqRankingResponse(1, "user-profile-img/a.jpg", "gildong", 340L);

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

    // ---------- 인증 (USER-RK-1, 2, 3) ----------

    @Test
    @DisplayName("[USER-RK-1] Authorization 헤더 없이 /rankings/bq/top을 호출하면 401과 "
            + "\"인증이 필요합니다.\" 바디를 반환하고 서비스는 호출되지 않는다")
    void getTopRanking_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/rankings/bq/top"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-1] Authorization 헤더 없이 /rankings/bq를 호출하면 401을 반환한다")
    void getRanking_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/rankings/bq"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-1] Authorization 헤더 없이 /rankings/bq/me를 호출하면 401을 반환한다")
    void getMyRanking_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/rankings/bq/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-2] 위조된(검증 실패) access 토큰으로 요청하면 401을 반환한다")
    void getTopRanking_forgedToken_returns401() throws Exception {
        // given: validateToken 미스텁이면 기본 false를 반환하는 목이므로 위조 토큰과 동일한 상황이다.
        // when & then
        mockMvc.perform(get("/rankings/bq/top").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-2] 유효한 refresh 토큰을 Bearer로 실어 호출하면 401을 반환한다")
    void getRanking_refreshToken_returns401() throws Exception {
        // given
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        // when & then
        mockMvc.perform(get("/rankings/bq").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-3] 탈퇴한 계정의 access 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 "
            + "/rankings/bq/me를 요청하면 401을 반환한다(요청자 차단 — 모집단 포함과는 별개)")
    void getMyRanking_withdrawnAccountToken_returns401() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/rankings/bq/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    // ---------- 정상 응답 형태 (USER-RK-20~25, 30, 40, 50) ----------

    @Test
    @DisplayName("[USER-RK-30] 활성 응원 구단이 있으면 /rankings/bq/top은 200과 ApiResponse에 담긴 "
            + "순위 항목 배열을 반환한다")
    void getTopRanking_authenticated_returns200WithArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getTopRanking(ACCOUNT_ID)).willReturn(List.of(SAMPLE_ENTRY));

        // when & then
        mockMvc.perform(get("/rankings/bq/top").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].length()").value(4))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].nickname").value("gildong"))
                .andExpect(jsonPath("$.data[0].bqScore").value(340))
                .andExpect(jsonPath("$.data[0].profileImgUrl").value("user-profile-img/a.jpg"));
    }

    @Test
    @DisplayName("[USER-RK-40] 활성 응원 구단이 있으면 /rankings/bq는 200과 ApiResponse에 담긴 순위 항목 "
            + "배열을 반환하고, 항목 키는 정확히 4개다")
    void getRanking_authenticated_returns200WithFourKeyItems() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getRanking(ACCOUNT_ID)).willReturn(List.of(SAMPLE_ENTRY));

        // when & then
        mockMvc.perform(get("/rankings/bq").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].length()").value(4))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].profileImgUrl").value("user-profile-img/a.jpg"))
                .andExpect(jsonPath("$.data[0].nickname").value("gildong"))
                .andExpect(jsonPath("$.data[0].bqScore").value(340));
    }

    @Test
    @DisplayName("[USER-RK-50] 활성 응원 구단이 있으면 /rankings/bq/me는 200과 배열이 아니라 순위 항목 "
            + "객체 1개를 반환하고, 키는 정확히 4개다")
    void getMyRanking_authenticated_returns200WithSingleObject() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getMyRanking(ACCOUNT_ID)).willReturn(SAMPLE_ENTRY);

        // when & then
        mockMvc.perform(get("/rankings/bq/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.nickname").value("gildong"))
                .andExpect(jsonPath("$.data.bqScore").value(340))
                .andExpect(jsonPath("$.data.profileImgUrl").value("user-profile-img/a.jpg"));
    }

    // ---------- 구단 없음 안전망 (USER-RK-60, 61) ----------

    @Test
    @DisplayName("[USER-RK-61] /rankings/bq/me에서 요청자에게 활성 응원 구단이 없으면(서비스가 null 반환) "
            + "200과 data:null을 반환한다(빈 객체·rank:0이 아니다)")
    void getMyRanking_noActiveTeam_returns200WithNullData() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getMyRanking(ACCOUNT_ID)).willReturn(null);

        // when & then
        mockMvc.perform(get("/rankings/bq/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("[USER-RK-60] /rankings/bq/top에서 요청자에게 활성 응원 구단이 없으면(서비스가 빈 목록 "
            + "반환) 200과 빈 배열을 반환한다(400·404·500이 아니다)")
    void getTopRanking_noActiveTeam_returns200WithEmptyArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getTopRanking(ACCOUNT_ID)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/rankings/bq/top").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 비-GET 메서드 (USER-RK-84) ----------

    @Test
    @DisplayName("[USER-RK-84] 유효한 access 토큰으로 /rankings/bq에 POST를 요청하면 405와 ApiResponse "
            + "래퍼(success:false)를 반환한다")
    void postToRanking_withValidToken_returns405() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(post("/rankings/bq").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(METHOD_NOT_ALLOWED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    @Test
    @DisplayName("[USER-RK-1] 토큰 없이 /rankings/bq에 POST를 요청하면 405가 아니라 401을 반환한다"
            + "(인증이 메서드 판정보다 앞선다)")
    void postToRanking_withoutToken_returns401NotMethodNotAllowed() throws Exception {
        // when & then
        mockMvc.perform(post("/rankings/bq"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(bqRankingService);
    }

    // ---------- 파라미터 무시 (USER-RK-4) ----------

    @Test
    @DisplayName("[USER-RK-4] ?teamId=·?userId=를 붙여도 무시되고 토큰 주체 기준으로 서비스가 호출된다")
    void getRanking_ignoresExtraQueryParams_stillCallsServiceWithTokenSubject() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(bqRankingService.getRanking(ACCOUNT_ID)).willReturn(List.of(SAMPLE_ENTRY));

        // when & then
        mockMvc.perform(get("/rankings/bq?teamId=999&userId=someone-elses-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        verify(bqRankingService).getRanking(ACCOUNT_ID);
    }
}
