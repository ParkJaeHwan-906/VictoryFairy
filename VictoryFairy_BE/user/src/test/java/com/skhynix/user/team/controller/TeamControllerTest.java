package com.skhynix.user.team.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.team.dto.TeamResponse;
import com.skhynix.user.team.service.TeamService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /teams}(외부 노출 경로 {@code /api/member/teams})를 검증한다.
 * 요구사항: {@code docs/requirements/user/team-list.md}(USER-TM-1 ~ 9).
 *
 * <p>슬라이스 구성은 기존 {@code AuthController*Test}/{@code UserAccountControllerTest}와 동일한 패턴을
 * 따른다: {@code @WebMvcTest} + {@code @ContextConfiguration(classes = TeamController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다.
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 문서의 인수 기준은 외부 경로
 * {@code /api/member/teams} 기준이지만, 이 슬라이스에서 실제로 호출하는 경로는 {@code /teams}다.
 *
 * <p><b>USER-TM-3의 한계</b>: 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를
 * 태우므로 USER-TM-6/7/9(permitAll 배선)은 이 컨트롤러 슬라이스가 실질적으로 검증하는 핵심이다. 다만
 * 정렬 자체(콜레이션)는 이 테스트가 검증할 수 없다 — {@code TeamService}를 목으로 대체해 스텁이 준 순서를
 * 컨트롤러가 재배열하지 않는지만 확인한다.
 */
@WebMvcTest(TeamController.class)
@ContextConfiguration(classes = TeamController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TeamControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamService teamService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final List<TeamResponse> TEN_TEAMS_IN_EXPECTED_ORDER = List.of(
            new TeamResponse(6L, "KIA"),
            new TeamResponse(7L, "KT"),
            new TeamResponse(8L, "LG"),
            new TeamResponse(9L, "NC"),
            new TeamResponse(10L, "SSG"),
            new TeamResponse(1L, "두산"),
            new TeamResponse(2L, "롯데"),
            new TeamResponse(3L, "삼성"),
            new TeamResponse(4L, "키움"),
            new TeamResponse(5L, "한화"));

    @Test
    @DisplayName("[USER-TM-1] 구단 목록을 요청하면 200과 ApiResponse 래퍼(success:true, message:null)에 "
            + "담긴 구단 배열을 반환한다")
    void getTeams_returns200WithApiResponseWrappedArray() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then
        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(10));
    }

    @Test
    @DisplayName("[USER-TM-2] 구단 항목은 id·name 두 필드만 담고 code·createdAt·updatedAt은 응답 "
            + "어디에도 없다")
    void getTeams_itemContainsOnlyIdAndName() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(List.of(new TeamResponse(6L, "KIA")));

        // when & then
        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(6))
                .andExpect(jsonPath("$.data[0].name").value("KIA"))
                .andExpect(jsonPath("$.data[0].code").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("[USER-TM-3, 한계 있음] 서비스가 준 순서를 컨트롤러가 재배열하지 않고 그대로 응답한다"
            + "(실제 콜레이션 정렬 자체는 DB 통합 테스트가 필요해 여기서 검증 불가)")
    void getTeams_returnsServiceOrderUnchanged() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then
        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("KIA"))
                .andExpect(jsonPath("$.data[1].name").value("KT"))
                .andExpect(jsonPath("$.data[2].name").value("LG"))
                .andExpect(jsonPath("$.data[3].name").value("NC"))
                .andExpect(jsonPath("$.data[4].name").value("SSG"))
                .andExpect(jsonPath("$.data[5].name").value("두산"))
                .andExpect(jsonPath("$.data[6].name").value("롯데"))
                .andExpect(jsonPath("$.data[7].name").value("삼성"))
                .andExpect(jsonPath("$.data[8].name").value("키움"))
                .andExpect(jsonPath("$.data[9].name").value("한화"));
    }

    @Test
    @DisplayName("[USER-TM-5] page/size 쿼리 파라미터를 붙여도 무시하고 전체 목록을 배열로 반환하며 "
            + "content/totalElements 같은 페이지 필드가 없다")
    void getTeams_ignoresPagingParameters_returnsFullArray() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then
        mockMvc.perform(get("/teams").queryParam("page", "1").queryParam("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    @Test
    @DisplayName("[USER-TM-6] Authorization 헤더 없이 요청해도 401이 아니라 200과 구단 목록을 반환한다")
    void getTeams_withoutAuthorizationHeader_returns200() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then: Authorization 헤더를 일부러 붙이지 않는다.
        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(10));
    }

    @Test
    @DisplayName("[USER-TM-7] 만료된 access 토큰이 Authorization 헤더에 담겨 와도 200과 구단 목록을 "
            + "반환한다(헤더 없을 때와 본문 동일)")
    void getTeams_withExpiredAccessToken_returns200() throws Exception {
        // given: JwtTokenProvider.validateToken이 false를 반환하면
        // JwtAuthenticationFilter는 SecurityContext를 건드리지 않고 그대로 통과시킨다(permitAll이라
        // anyRequest().authenticated()에 걸리지 않음).
        given(jwtTokenProvider.validateToken("expired-token")).willReturn(false);
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then
        mockMvc.perform(get("/teams").header("Authorization", "Bearer expired-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-TM-7] 위조된(JWT 형식이 아닌) access 토큰이 Authorization 헤더에 담겨 와도 "
            + "200과 구단 목록을 반환한다(헤더 없을 때와 본문 동일)")
    void getTeams_withForgedAccessToken_returns200() throws Exception {
        // given: 실제 JwtTokenProvider라면 형식이 깨진 토큰에 validateToken이 false를 반환할 것이다.
        // 여기서는 슬라이스이므로 그 동작을 명시적으로 스텁한다.
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(teamService.getTeams()).willReturn(TEN_TEAMS_IN_EXPECTED_ORDER);

        // when & then
        mockMvc.perform(get("/teams").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-TM-8] teams 테이블에 행이 없으면 200과 빈 배열({success:true, data:[], "
            + "message:null})을 반환한다")
    void getTeams_noRows_returns200WithEmptyArray() throws Exception {
        // given
        given(teamService.getTeams()).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-TM-9] 구단 목록 경로에 GET 이외의 메서드(POST)로 헤더 없이 요청하면 401과 "
            + "\"인증이 필요합니다.\"를 반환하고 서비스는 호출되지 않는다")
    void postToTeamsPath_withoutAuth_returns401() throws Exception {
        // when & then: permitAll이 GET으로만 좁혀져 있어 POST는 anyRequest().authenticated()로 떨어진다.
        mockMvc.perform(post("/teams"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(teamService);
    }
}
