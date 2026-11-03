package com.skhynix.quiz.quizroom.controller;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.authenticatedAs;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.json;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.mc;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.ox;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.repeat;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.repeatChar;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.room;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.valid;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.validMc;
import static com.skhynix.quiz.quizroom.controller.QuizRoomRequestBodies.withQuizzes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomRequest;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomResponse;
import com.skhynix.quiz.quizroom.service.QuizRoomAnswerService;
import com.skhynix.quiz.quizroom.service.QuizRoomLikeService;
import com.skhynix.quiz.quizroom.service.QuizRoomService;
import com.skhynix.quiz.quizroom.service.QuizRoomSubscriptionService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /quiz-rooms}(퀴즈방 생성) 슬라이스 - 입력 검증(B절). 서비스는 목이므로 저장 순서와 원자성은
 * {@code QuizRoomServiceTest} 가 맡고, 여기서는 상태 코드와 검증 위반 키를 고정한다.
 *
 * <p>인증은 {@code @WithMockUser} 가 아니라 principal 이 {@code Long} 인 토큰을 직접 주입한다
 * (컨트롤러의 {@code @AuthenticationPrincipal Long} 바인딩 때문 - 기존 quiz 슬라이스 컨벤션).
 */
@WebMvcTest(QuizRoomController.class)
@ContextConfiguration(classes = QuizRoomController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizRoomControllerCreateTest {

    private static final Long USER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizRoomService roomService;

    @MockitoBean
    private QuizRoomAnswerService answerService;

    @MockitoBean
    private QuizRoomLikeService likeService;

    @MockitoBean
    private QuizRoomSubscriptionService subscriptionService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private ResultActions create(Object body) throws Exception {
        return mockMvc.perform(post("/quiz-rooms").with(authenticatedAs(USER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private void stubCreated() {
        given(roomService.create(eq(USER_ID), any())).willReturn(new CreateQuizRoomResponse(1L));
    }

    private void assertCreated(Object body) throws Exception {
        stubCreated();
        create(body).andExpect(status().isCreated());
    }

    private void assertRejectedWithKey(Object body, String key) throws Exception {
        create(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data['" + key + "']").exists());
        verify(roomService, never()).create(any(), any());
    }

    // ---- QUIZ-CQR-10 ----

    @Test
    @DisplayName("[QUIZ-CQR-10] 유효한 본문이면 201과 {roomId} 를 ApiResponse 로 반환하고 토큰의 계정으로 서비스를 부른다")
    void create_validBody_returns201WithRoomId() throws Exception {
        stubCreated();

        create(valid()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(1))
                .andExpect(jsonPath("$.message").doesNotExist());

        ArgumentCaptor<CreateQuizRoomRequest> captor = ArgumentCaptor.forClass(CreateQuizRoomRequest.class);
        verify(roomService).create(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("LG 레전드 퀴즈");
        assertThat(captor.getValue().quizzes()).hasSize(2);
        assertThat(captor.getValue().quizzes().get(0).options()).containsExactly("이광환", "김성근", "백인천");
    }

    // ---- QUIZ-CQR-12 ----

    @Test
    @DisplayName("[QUIZ-CQR-12] O/X 문항에 보기를 3개 보내도 201 이고, 저장할 보기는 요청값과 상관없이 [O, X] 이다")
    void create_oxWithOptions_acceptedAndResolvedToOX() throws Exception {
        Map<String, Object> oxWithOptions = ox("LG의 연고지는 서울이다", 0);
        oxWithOptions.put("options", List.of("맞다", "아니다", "몰라"));
        stubCreated();

        create(withQuizzes(List.of(oxWithOptions))).andExpect(status().isCreated());

        ArgumentCaptor<CreateQuizRoomRequest> captor = ArgumentCaptor.forClass(CreateQuizRoomRequest.class);
        verify(roomService).create(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().quizzes().get(0).resolvedOptions()).containsExactly("O", "X");
    }

    @Test
    @DisplayName("[QUIZ-CQR-12] O/X 문항은 보기 필드를 아예 생략해도 201 이다")
    void create_oxWithoutOptions_returns201() throws Exception {
        assertCreated(withQuizzes(List.of(ox("LG의 연고지는 서울이다", 1))));
    }

    // ---- QUIZ-CQR-13 ----

    static Stream<Arguments> blankTitles() {
        Map<String, Object> missing = valid();
        missing.remove("title");
        return Stream.of(Arguments.of("null", room(null, "소개", List.of(validMc()))),
                Arguments.of("빈 문자열", room("", "소개", List.of(validMc()))),
                Arguments.of("공백만", room("   ", "소개", List.of(validMc()))),
                Arguments.of("키 누락", missing));
    }

    @ParameterizedTest(name = "[QUIZ-CQR-13] title 이 {0} 이면 400 과 data.title 을 반환한다")
    @MethodSource("blankTitles")
    void create_blankTitle_returns400(String label, Map<String, Object> body) throws Exception {
        assertRejectedWithKey(body, "title");
    }

    // ---- QUIZ-CQR-14 ----

    @Test
    @DisplayName("[QUIZ-CQR-14] 제목 51자는 400 data.title, 50자는 201")
    void create_titleLengthBoundary() throws Exception {
        assertRejectedWithKey(room(repeatChar(51), "소개", List.of(validMc())), "title");
        assertCreated(room(repeatChar(50), "소개", List.of(validMc())));
    }

    // ---- QUIZ-CQR-15 ----

    static Stream<Arguments> blankDescriptions() {
        return Stream.of(Arguments.of("null", room("제목", null, List.of(validMc()))),
                Arguments.of("빈 문자열", room("제목", "", List.of(validMc()))),
                Arguments.of("공백만", room("제목", " \t ", List.of(validMc()))));
    }

    @ParameterizedTest(name = "[QUIZ-CQR-15] description 이 {0} 이면 400 과 data.description 을 반환한다")
    @MethodSource("blankDescriptions")
    void create_blankDescription_returns400(String label, Map<String, Object> body) throws Exception {
        assertRejectedWithKey(body, "description");
    }

    // ---- QUIZ-CQR-16 ----

    @Test
    @DisplayName("[QUIZ-CQR-16] 소개 501자는 400, 500자는 201")
    void create_descriptionLengthBoundary() throws Exception {
        assertRejectedWithKey(room("제목", repeatChar(501), List.of(validMc())), "description");
        assertCreated(room("제목", repeatChar(500), List.of(validMc())));
    }

    // ---- QUIZ-CQR-17 / 18 ----

    @Test
    @DisplayName("[QUIZ-CQR-17] quizzes 가 빈 배열이면 400 data.quizzes, 문항 1개면 201")
    void create_emptyQuizzes_returns400_oneQuizReturns201() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of()), "quizzes");
        assertCreated(withQuizzes(List.of(validMc())));
    }

    @Test
    @DisplayName("[QUIZ-CQR-17] quizzes 키가 누락되면 400 data.quizzes")
    void create_missingQuizzes_returns400() throws Exception {
        assertRejectedWithKey(room("제목", "소개", null), "quizzes");
    }

    @Test
    @DisplayName("[QUIZ-CQR-17] quizzes 에 null 원소가 있으면 NPE 로 새지 않고 400 이다 (요구사항 미기재)")
    void create_nullQuizElement_returns400() throws Exception {
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(validMc());
        withNull.add(null);

        create(withQuizzes(withNull)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verify(roomService, never()).create(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-18] 문항 21개는 400 data.quizzes, 20개는 201")
    void create_quizCountUpperBoundary() throws Exception {
        assertRejectedWithKey(withQuizzes(repeat(21)), "quizzes");
        assertCreated(withQuizzes(repeat(20)));
    }

    // ---- QUIZ-CQR-19 ----

    static Stream<Arguments> invalidTypes() {
        Map<String, Object> noType = validMc();
        noType.remove("type");
        Map<String, Object> wrongType = validMc();
        wrongType.put("type", "주관식");
        Map<String, Object> lowerOx = ox("q", 0);
        lowerOx.put("type", "o/x");
        return Stream.of(Arguments.of("누락", noType), Arguments.of("주관식", wrongType),
                Arguments.of("소문자 o/x", lowerOx));
    }

    @ParameterizedTest(name = "[QUIZ-CQR-19] type 이 {0} 이면 400 과 quizzes[0].type 을 반환한다")
    @MethodSource("invalidTypes")
    void create_invalidType_returns400(String label, Map<String, Object> quiz) throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(quiz)), "quizzes[0].type");
    }

    // ---- QUIZ-CQR-20 / 21 ----

    @Test
    @DisplayName("[QUIZ-CQR-20] 두 번째 문항의 question 이 공백이면 400 이고 위반 키는 quizzes[1].question 이다")
    void create_blankQuestion_pointsAtViolatingQuiz() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(validMc(), ox("   ", 0))), "quizzes[1].question");
    }

    @Test
    @DisplayName("[QUIZ-CQR-20] question 이 null 이어도 400")
    void create_nullQuestion_returns400() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(ox(null, 0))), "quizzes[0].question");
    }

    @Test
    @DisplayName("[QUIZ-CQR-21] question 201자는 400, 200자는 201")
    void create_questionLengthBoundary() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(ox(repeatChar(201), 0))), "quizzes[0].question");
        assertCreated(withQuizzes(List.of(ox(repeatChar(200), 0))));
    }

    // ---- QUIZ-CQR-22 ----

    @Test
    @DisplayName("[QUIZ-CQR-22] 객관식 보기 1개·5개는 400, 2개·4개는 201")
    void create_optionCountBoundary() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("a"), 0))), "quizzes[0].optionsValid");
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("a", "b", "c", "d", "e"), 0))),
                "quizzes[0].optionsValid");
        assertCreated(withQuizzes(List.of(mc("q", List.of("a", "b"), 1))));
        assertCreated(withQuizzes(List.of(mc("q", List.of("a", "b", "c", "d"), 3))));
    }

    @Test
    @DisplayName("[QUIZ-CQR-22] 객관식인데 options 가 누락되면 400")
    void create_missingOptionsForMultipleChoice_returns400() throws Exception {
        Map<String, Object> noOptions = new LinkedHashMap<>(validMc());
        noOptions.remove("options");

        assertRejectedWithKey(withQuizzes(List.of(noOptions)), "quizzes[0].optionsValid");
    }

    // ---- QUIZ-CQR-23 / 24 ----

    @Test
    @DisplayName("[QUIZ-CQR-23] 보기 중 하나가 빈 문자열·공백·null 이면 400")
    void create_blankOption_returns400() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("이광환", ""), 0))), "quizzes[0].optionsValid");
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("이광환", "  "), 0))), "quizzes[0].optionsValid");
        List<String> withNull = new ArrayList<>();
        withNull.add("이광환");
        withNull.add(null);
        assertRejectedWithKey(withQuizzes(List.of(mc("q", withNull, 0))), "quizzes[0].optionsValid");
    }

    @Test
    @DisplayName("[QUIZ-CQR-24] 보기 101자는 400, 100자는 201")
    void create_optionLengthBoundary() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of(repeatChar(101), "b"), 0))),
                "quizzes[0].optionsValid");
        assertCreated(withQuizzes(List.of(mc("q", List.of(repeatChar(100), "b"), 0))));
    }

    // ---- QUIZ-CQR-25 ----

    @Test
    @DisplayName("[QUIZ-CQR-25] 보기 3개인 객관식에서 answer 3 은 400, 2 는 201")
    void create_multipleChoiceAnswerRange() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("a", "b", "c"), 3))),
                "quizzes[0].answerInRange");
        assertCreated(withQuizzes(List.of(mc("q", List.of("a", "b", "c"), 2))));
    }

    @Test
    @DisplayName("[QUIZ-CQR-25] O/X 에서 answer 2 는 400, 1 은 201")
    void create_oxAnswerRange() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(ox("q", 2))), "quizzes[0].answerInRange");
        assertCreated(withQuizzes(List.of(ox("q", 1))));
    }

    @Test
    @DisplayName("[QUIZ-CQR-25] answer -1 은 400")
    void create_negativeAnswer_returns400() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(mc("q", List.of("a", "b", "c"), -1))),
                "quizzes[0].answerInRange");
    }

    @Test
    @DisplayName("[QUIZ-CQR-25] answer 누락(null)은 400 이고 위반 키는 quizzes[0].answer 이다")
    void create_missingAnswer_returns400() throws Exception {
        assertRejectedWithKey(withQuizzes(List.of(ox("q", null))), "quizzes[0].answer");
    }

    @Test
    @DisplayName("[QUIZ-CQR-25] 보기가 잘못된 객관식은 answer 범위를 따로 판정하지 않는다 - optionsValid 만 위반으로 나온다 (요구사항 미기재)")
    void create_invalidOptions_doesNotAlsoReportAnswerRange() throws Exception {
        create(withQuizzes(List.of(mc("q", List.of("a"), 5)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data['quizzes[0].optionsValid']").exists())
                .andExpect(jsonPath("$.data['quizzes[0].answerInRange']").doesNotExist());
    }

    // ---- 인증 / 신원 ----

    @Test
    @DisplayName("[QUIZ-CQR-1] 인증 없이 생성하면 401 이고 서비스는 호출되지 않는다")
    void create_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/quiz-rooms").contentType(MediaType.APPLICATION_JSON).content(json(valid())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
        verifyNoInteractions(roomService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-2] 본문에 userAccountId·uid 를 보내도 서비스는 토큰의 계정으로만 호출된다")
    void create_bodyUserAccountId_isIgnored() throws Exception {
        Map<String, Object> body = valid();
        body.put("userAccountId", 999);
        body.put("uid", "someone-else");
        stubCreated();

        create(body).andExpect(status().isCreated());

        verify(roomService).create(eq(USER_ID), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-2] 생성 요청 DTO 에는 계정 식별자를 받는 필드가 없다")
    void createRequest_hasNoAccountIdentifierField() {
        assertThat(CreateQuizRoomRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("title", "description", "quizzes");
        assertThat(CreateQuizRoomRequest.QuizItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("type", "question", "options", "answer");
    }

    @Test
    @DisplayName("깨진 JSON 본문은 400 이고 서비스는 호출되지 않는다 (요구사항 미기재)")
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/quiz-rooms").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(roomService);
    }
}
