package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizSubmitService} 단위 테스트 — 협력 객체 전부 Mockito 목, DB·Spring 컨텍스트 없음.
 *
 * <p>따라서 UNIQUE 제약의 실제 중재(동시 INSERT 중 한쪽 실패), {@code findWithLockById}의 실제
 * 행 잠금, <b>그리고 조건부 UPDATE({@code fillAnswer})의 WHERE 절이 실제 DB에서 시한 초과 행을
 * 걸러내는지는 여기서 검증되지 않는다</b> — 서비스가 그 조건에 어떤 파라미터를 넘기는지, 그리고
 * 서비스 자체의 사전 검사가 만료 행을 403으로 먼저 막는지까지만 이 계층의 책임이다. 실제 DB
 * 라운드트립은 저장소 전체에 H2/Testcontainers/구동 중인 MySQL이 없어 보류 상태다(domain 모듈
 * 컨텍스트 참고).
 *
 * <p>제출 자격의 근거는 이제 Redis 티켓이 아니라 <b>{@code quiz_users_submit} 행 자체</b>다 —
 * {@code /today}가 미리 만들어 둔 행을 조건부 UPDATE로 채우는 구조라, 이 서비스 자체는 Redis에
 * 의존하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class QuizSubmitServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long QUIZ_ID = 10L;

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizOptionRepository quizOptionRepository;

    @Mock
    private QuizUserSubmitRepository quizUserSubmitRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private QuizLikeService quizLikeService;

    @InjectMocks
    private QuizSubmitService quizSubmitService;

    private Quiz quiz(Long id, Integer answer, Double score, LocalDate quizDate) {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .content("문제 " + id)
                .answer(answer)
                .score(score)
                .quizDate(quizDate)
                .difficulty("EASY")
                .build();
        ReflectionTestUtils.setField(quiz, "id", id);
        return quiz;
    }

    private Quiz publishedQuiz(Integer answer, Double score) {
        return quiz(QUIZ_ID, answer, score, LocalDate.of(2026, 8, 8));
    }

    private QuizOption option(Quiz quiz, int no, String text) {
        QuizOption option = QuizOption.builder().quiz(quiz).contents(text).option(no).build();
        ReflectionTestUtils.setField(option, "id", quiz.getId() * 100 + no);
        return option;
    }

    private UserAccount account(long point) {
        UserAccount account = UserAccount.builder().nickname("무관").password("password1!").build();
        ReflectionTestUtils.setField(account, "id", USER_ID);
        ReflectionTestUtils.setField(account, "point", point);
        return account;
    }

    /** {@code /today}가 서빙 시점에 만들어 둔 행 — 미답(진행 중 또는 시한 초과) 상태로 준비한다. */
    private QuizUserSubmit unansweredRow(Quiz quiz, LocalDateTime createdAt) {
        QuizUserSubmit submit = QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build();
        ReflectionTestUtils.setField(submit, "createdAt", createdAt);
        ReflectionTestUtils.setField(submit, "updatedAt", createdAt);
        return submit;
    }

    /** 이미 답이 채워진 행(재제출 시나리오·이미 답한 문제에 잘못된 보기 번호를 보내는 시나리오용). */
    private QuizUserSubmit answeredRow(Quiz quiz, QuizOption submitOption, boolean isAnswer,
            LocalDateTime createdAt) {
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .quiz(quiz).submitOption(submitOption).isAnswer(isAnswer).build();
        ReflectionTestUtils.setField(submit, "createdAt", createdAt);
        return submit;
    }

    private QuizUserSubmit submitOf(UserAccount account, Quiz quiz, QuizOption option,
            boolean isAnswer, LocalDateTime createdAt, LocalDateTime updatedAt) {
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(account)
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(isAnswer)
                .build();
        ReflectionTestUtils.setField(submit, "createdAt", createdAt);
        ReflectionTestUtils.setField(submit, "updatedAt", updatedAt);
        return submit;
    }

    // ---------- 제출 성공 ----------

    @Test
    @DisplayName("정답을 제출하면 배점을 계정에 적립하고, 조건부 UPDATE(fillAnswer)를 정답으로 호출한다")
    void submit_correctAnswer_addsPointAndCallsFillAnswerWithCorrectTrue() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        UserAccount account = account(5L);
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(response.correct()).isTrue();
        assertThat(response.answer()).isZero();
        assertThat(response.myOption()).isZero();
        assertThat(response.earnedPoint()).isEqualTo(10L);
        assertThat(response.totalPoint()).isEqualTo(15L);
        assertThat(account.getPoint()).isEqualTo(15L);

        verify(quizUserSubmitRepository).fillAnswer(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(QUIZ_ID), org.mockito.ArgumentMatchers.eq(option),
                org.mockito.ArgumentMatchers.eq(true), any(), any());
    }

    @Test
    @DisplayName("오답을 제출하면 적립 없이 fillAnswer를 correct=false로 호출한다")
    void submit_wrongAnswer_earnsNothingAndCallsFillAnswerWithCorrectFalse() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 1, "오답 보기");
        UserAccount account = account(5L);
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 1))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 1);

        assertThat(response.correct()).isFalse();
        assertThat(response.earnedPoint()).isZero();
        assertThat(response.totalPoint()).isEqualTo(5L);
        assertThat(account.getPoint()).isEqualTo(5L);

        ArgumentCaptor<Boolean> correctCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(quizUserSubmitRepository).fillAnswer(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(QUIZ_ID), org.mockito.ArgumentMatchers.eq(option),
                correctCaptor.capture(), any(), any());
        assertThat(correctCaptor.getValue()).isFalse();
    }

    @Test
    @DisplayName("배점(score)이 null인 문제는 정답이어도 적립이 0이다")
    void submit_nullScore_correctButEarnsNothing() {
        Quiz quiz = publishedQuiz(0, null);
        QuizOption option = option(quiz, 0, "정답 보기");
        UserAccount account = account(5L);
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(response.correct()).isTrue();
        assertThat(response.earnedPoint()).isZero();
        assertThat(response.totalPoint()).isEqualTo(5L);
        assertThat(account.getPoint()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[AC-INN-67-1] 조건부 UPDATE에는 지금 시각과 '지금-8분'(시한 하한)이 정확히 함께 "
            + "전달된다 — 시한 조건이 실제로 SQL 파라미터에 실린다는 배선을 고정한다")
    void submit_success_passesEightMinuteWindowBoundToFillAnswer() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        LocalDateTime beforeCall = QuizSubmitWindow.now();
        quizSubmitService.submit(USER_ID, QUIZ_ID, 0);
        LocalDateTime afterCall = QuizSubmitWindow.now();

        ArgumentCaptor<LocalDateTime> earliestCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(quizUserSubmitRepository).fillAnswer(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(QUIZ_ID), org.mockito.ArgumentMatchers.eq(option),
                org.mockito.ArgumentMatchers.eq(true), earliestCaptor.capture(), nowCaptor.capture());

        LocalDateTime now = nowCaptor.getValue();
        assertThat(now).isBetween(beforeCall, afterCall);
        // earliestValidCreatedAt == now - 8분 이어야 한다(허용 오차 없이 정확히 그 계산이어야 함)
        assertThat(earliestCaptor.getValue()).isEqualTo(now.minusMinutes(8));
    }

    @Test
    @DisplayName("미편성 문제(quizDate=null)는 행이 있어도 404 QUIZ_NOT_FOUND다")
    void submit_unscheduledQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(QUIZ_ID))
                .willReturn(Optional.of(quiz(QUIZ_ID, 0, 10.0, null)));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
        // [AC-INN-72-1] 404 판정은 행 유무와 무관하다 — 존재하지 않는 문제는 행 조회 자체가 없다
        verify(quizUserSubmitRepository, never()).findByUserAccount_IdAndQuiz_Id(anyLong(), anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 문제는 404 QUIZ_NOT_FOUND다")
    void submit_missingQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    // ---------- 자격 검사(QUIZ-INN-68·71) ----------

    @Test
    @DisplayName("[AC-INN-68-1,68-2,68-3,68-4,68-5,31-1] 행 자체가 없으면(/today 미경유 또는 상한 절삭) "
            + "403 QUIZ_SUBMIT_NOT_ALLOWED다 — 보기 조회·계정 락·조건부 UPDATE 어느 것도 일어나지 않는다"
            + "(아무 흔적도 남기지 않는다)")
    void submit_noRow_throwsSubmitNotAllowedWithNoTrace() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));

        verify(quizOptionRepository, never()).findFirstByQuiz_IdAndOptionOrderByIdAsc(anyLong(), anyInt());
        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("[AC-INN-71-1,71-2,71-3,71-4] 행은 있으나 미답이고 시한(8분)이 지났으면 403 "
            + "QUIZ_SUBMIT_NOT_ALLOWED이고, 조건부 UPDATE(fillAnswer) 자체가 호출되지 않는다 — 그 행이 "
            + "실제로 바뀌지 않는다는 것을 '갱신 시도조차 하지 않음'으로 고정한다(새 ErrorCode 없이 "
            + "기존 403과 상태코드·본문이 동일하다)")
    void submit_expiredUnansweredRow_throwsSubmitNotAllowedWithoutAttemptingUpdate() {
        Quiz quiz = publishedQuiz(0, 10.0);
        // 정확히 8분보다 더 지난(9분 전) 미답 행 — 경계를 넉넉히 벗어나 흔들리지 않게 한다
        QuizUserSubmit expired = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(9));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(expired));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));

        // 시한 초과는 보기 조회보다도 먼저 걸러진다 — 어떤 갱신 시도도 이 요청에서 나가지 않는다
        verify(quizOptionRepository, never()).findFirstByQuiz_IdAndOptionOrderByIdAsc(anyLong(), anyInt());
        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("[AC-INN-71-4] 답한 행은 아무리 오래돼도(시한을 훌쩍 넘겨도) 403이 아니다 — 재제출은 "
            + "409로만 판정된다(답한 행에는 시한 초과 검사 자체가 적용되지 않는다)")
    void submit_answeredRowLongAfterWindow_isNotTreatedAs403() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption alreadyChosen = option(quiz, 0, "정답 보기");
        QuizUserSubmit answered = answeredRow(quiz, alreadyChosen, true,
                QuizSubmitWindow.now().minusDays(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(answered));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(alreadyChosen));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(0); // 이미 답이 채워진 행이라 영향 행 0

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));
    }

    // ---------- 검증 순서·중복 제출(QUIZ-INN-70·72) ----------

    @Test
    @DisplayName("[AC-INN-72-5] 이미 답한 문제에 없는 보기 번호를 보내면 409가 아니라 400 "
            + "QUIZ_OPTION_NOT_FOUND다(종전 409에서 바뀐 관측 가능한 계약 변화) — 계정 락·조건부 "
            + "UPDATE는 일어나지 않는다")
    void submit_answeredRowWithInvalidOptionNumber_throws400NotConflict() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption alreadyChosen = option(quiz, 0, "정답 보기");
        QuizUserSubmit answered = answeredRow(quiz, alreadyChosen, true,
                QuizSubmitWindow.now().minusMinutes(5));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(answered));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 9))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 9))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("[AC-INN-33-old,72-3] 미답 행에 없는 보기 번호를 보내면(첫 제출 실패) 400 "
            + "QUIZ_OPTION_NOT_FOUND다 — 계정 락은 일어나지 않는다")
    void submit_unansweredRowWithInvalidOptionNumber_throws400() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 9))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 9))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("[AC-INN-73-1] 400으로 실패한 뒤 곧바로 올바른 보기로 재시도하면(같은 행, 시한 내) "
            + "성공한다 — 오타 한 번이 기회를 태우지 않는다")
    void submit_afterInvalidOptionFailure_retryWithCorrectOptionSucceeds() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption correctOption = option(quiz, 0, "정답 보기");
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 9))
                .willReturn(Optional.empty());
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(correctOption));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 9))
                .isInstanceOf(BusinessException.class);
        QuizSubmitResponse retried = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(retried.correct()).isTrue();
        verify(quizUserSubmitRepository, times(1)).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("[AC-INN-70-1,70-3,70-4] 조건부 UPDATE의 영향 행 수가 0이면(동시 제출 race 포함) 409 "
            + "QUIZ_ALREADY_SUBMITTED다 — 적립은 이미 이루어졌더라도(실제로는 트랜잭션 롤백으로 함께 "
            + "되돌아간다) 응답은 실패다")
    void submit_fillAnswerReturnsZero_throwsConflict() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(0);

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));
    }

    @Test
    @DisplayName("[AC-INN-72-2,72-4] 성공 경로의 협력 객체 호출 순서는 행 조회(403 검사) → 보기 조회"
            + "(400 검사) → 계정 락·적립 → 조건부 UPDATE(409 판정) 순이다 — 404→403→400→(락·적립)→409 "
            + "검증 순서가 실제 호출 순서로도 지켜진다")
    void submit_success_callsCollaboratorsInGateOrder() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        QuizUserSubmit served = unansweredRow(quiz, QuizSubmitWindow.now().minusMinutes(1));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(served));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        given(quizUserSubmitRepository.fillAnswer(anyLong(), anyLong(), any(), anyBoolean(),
                any(), any())).willReturn(1);

        quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        InOrder inOrder = org.mockito.Mockito.inOrder(quizUserSubmitRepository,
                quizOptionRepository, userAccountRepository);
        inOrder.verify(quizUserSubmitRepository).findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID);
        inOrder.verify(quizOptionRepository)
                .findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0);
        inOrder.verify(userAccountRepository).findWithLockById(USER_ID);
        inOrder.verify(quizUserSubmitRepository).fillAnswer(anyLong(), anyLong(), any(),
                anyBoolean(), any(), any());
    }

    // ---------- 풀이 이력(경기 축, docs/requirements/quiz/quiz-submission-by-inning.md) ----------

    private static final String GAME_ID = "20260812LGWO02026";
    private static final Long GAME_PK = 100L;

    /** 상태·이닝 두 컬럼을 원하는 대로 채운 {@link Game} — 내부 PK는 {@link #GAME_PK}로 고정한다. */
    private Game game(String statusName, Integer currentInning, Integer lastInning) {
        GameStatus status = GameStatus.builder().name(statusName).build();
        Game game = Game.builder().gameStatus(status).currentInning(currentInning)
                .naverGameId(GAME_ID).build();
        ReflectionTestUtils.setField(game, "id", GAME_PK);
        ReflectionTestUtils.setField(game, "lastInning", lastInning);
        return game;
    }

    /** {@code findGameSubmissions}가 돌려주는 행 하나 — 이닝 값을 명시적으로 갖는다. */
    private QuizUserSubmit submitInInning(UserAccount account, Quiz quiz, QuizOption submitOption,
            boolean isAnswer, Integer inning, LocalDateTime createdAt, LocalDateTime updatedAt) {
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(account).quiz(quiz).submitOption(submitOption)
                .isAnswer(isAnswer).inning(inning).build();
        ReflectionTestUtils.setField(submit, "createdAt", createdAt);
        ReflectionTestUtils.setField(submit, "updatedAt", updatedAt);
        return submit;
    }

    // ---------- 검사 순서(QUIZ-SUB-14-4): 404 → 403 → 200 ----------

    @Test
    @DisplayName("[AC-SUB-5-1,5-2] gameId가 어떤 경기와도 매칭되지 않으면 404 GAME_NOT_FOUND이고 "
            + "제출 행 조회는 일어나지 않는다")
    void getHistory_gameNotFound_throwsGameNotFoundWithoutQueryingSubmissions() {
        given(gameRepository.findWithStatusByNaverGameId("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.getHistory(USER_ID, "NOPE"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GAME_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).findGameSubmissions(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-SUB-36-1,36-2,36-3,37-1,37-2] 예정 경기는 이닝 컬럼을 읽지 않고 403 "
            + "GAME_NOT_STARTED다 — QUIZ_NOT_SERVABLE(출제 거절 문구)이 아니고, 제출 행 조회도 "
            + "일어나지 않는다")
    void getHistory_scheduledGame_throwsGameNotStartedWithoutReadingInningOrQuerying() {
        Game scheduled = game("SCHEDULED", null, null);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(scheduled));

        assertThatThrownBy(() -> quizSubmitService.getHistory(USER_ID, GAME_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GAME_NOT_STARTED);
                    assertThat(e.getErrorCode()).isNotEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
                });

        verify(quizUserSubmitRepository, never()).findGameSubmissions(anyLong(), anyLong());
    }

    // ---------- QUIZ-SUB-6: /today의 제공 가능 검증을 복사하지 않는다 ----------

    @Test
    @DisplayName("[AC-SUB-6-1] 어제 끝난(FINISHED) 경기도 200이다 — 오늘·응원 구단 검증이 없다")
    void getHistory_finishedGame_returns200WithoutTodayOrSupportTeamChecks() {
        UserAccount account = account(0L);
        Game finished = game("FINISHED", null, 9);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "정답 보기"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.summary().total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[AC-SUB-6-3,23-4] 취소(CANCELED) 경기도 200이고 진행된 이닝까지 열거한다 — 노게임은 "
            + "접히지 않는다")
    void getHistory_canceledGameWithLastInningValue_enumeratesUpToThatInning() {
        Game canceled = game("CANCELED", null, 5);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 3,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(canceled));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(5);
        assertThat(response.innings().stream().map(i -> i.inning()).toList())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("[AC-SUB-26-1] 취소 경기인데 last_inning이 NULL이면(경기 시작 전 취소) 에러가 아니라 "
            + "200과 빈 배열이다")
    void getHistory_canceledGameWithNullLastInning_returnsEmptyInnings() {
        Game canceled = game("CANCELED", null, null);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(canceled));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
    }

    // ---------- QUIZ-SUB-39 vs QUIZ-SUB-33: 경기 단위 접기 vs 이닝 단위 비접기 ----------

    @Test
    @DisplayName("[AC-SUB-39-1,39-2,39-3,39-4] 대상 행이 0건이면 열거 범위가 계산됐어도(1~8) innings를 "
            + "통째로 접는다 — 0/0 원소 8개가 아니다")
    void getHistory_noSubmissions_foldsInningsDespiteCalculatedRange() {
        Game inProgress = game("IN_PROGRESS", 9, null);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK)).willReturn(List.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
        assertThat(response.summary().correctCount()).isZero();
        assertThat(response.summary().accuracy()).isEqualTo(0.0);
        assertThat(response.summary().earnedPoint()).isZero();
        verify(quizOptionRepository, never())
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any());
    }

    @Test
    @DisplayName("[AC-SUB-33-1,33-2,33-3] 그 경기에 행이 하나라도 있으면 열거 범위(1~8)의 이닝을 "
            + "하나도 빼지 않는다 — 기록 없는 이닝도 quizzes:[] + 0/0 원소로 남는다")
    void getHistory_atLeastOneSubmission_keepsAllEnumeratedInningsIncludingEmptyOnes() {
        Game inProgress = game("IN_PROGRESS", 9, null);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "정답 보기"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(8);
        assertThat(response.innings().get(0).quizzes()).hasSize(1);
        // 2~8회는 빈 원소로 남는다 — 골라 빠지지 않는다
        for (int i = 1; i < 8; i++) {
            assertThat(response.innings().get(i).quizzes()).isEmpty();
            assertThat(response.innings().get(i).summary().correctCount()).isZero();
            assertThat(response.innings().get(i).summary().total()).isZero();
            assertThat(response.innings().get(i).summary().accuracy()).isEqualTo(0.0);
        }
    }

    // ---------- QUIZ-SUB-20/21/22: IN_PROGRESS ----------

    @Test
    @DisplayName("[AC-SUB-20-2] current_inning=9면 1~8이 열거된다(진행 중인 9회 자체는 빠진다)")
    void getHistory_inProgressCurrentInningNine_enumeratesOneToEight() {
        Game inProgress = game("IN_PROGRESS", 9, null);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings().stream().map(i -> i.inning()).toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    @DisplayName("[AC-SUB-21-1,21-2,21-3] current_inning=1이면 1회에 이미 세트를 받아 풀었어도 "
            + "innings:[]다(완료된 이닝만 결산한다)")
    void getHistory_inProgressCurrentInningOne_returnsEmptyInningsEvenWithSubmission() {
        Game inProgress = game("IN_PROGRESS", 1, null);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "정답 보기"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
        assertThat(response.summary().correctCount()).isZero();
        assertThat(response.summary().accuracy()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("[AC-SUB-22-1,22-2] current_inning이 NULL이면(진행 중인데 값 미상) 에러가 아니라 200과 "
            + "빈 배열이다")
    void getHistory_inProgressNullCurrentInning_returnsEmptyInningsNotError() {
        Game inProgress = game("IN_PROGRESS", null, null);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
    }

    // ---------- QUIZ-SUB-23/26/38: FINISHED·DRAW·CANCELED, 표에 없는 상태 ----------

    @Test
    @DisplayName("[AC-SUB-23-1] FINISHED + last_inning=9면 1~9가 열거된다")
    void getHistory_finishedLastInningNine_enumeratesOneToNine() {
        Game finished = game("FINISHED", null, 9);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 9,
                LocalDateTime.of(2026, 8, 12, 22, 0), LocalDateTime.of(2026, 8, 12, 22, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings().stream().map(i -> i.inning()).toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    @DisplayName("[AC-SUB-23-2] 연장(last_inning=11)이면 1~11이 열거된다")
    void getHistory_finishedLastInningEleven_enumeratesOneToEleven() {
        Game finished = game("FINISHED", null, 11);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 11,
                LocalDateTime.of(2026, 8, 12, 23, 0), LocalDateTime.of(2026, 8, 12, 23, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(11);
    }

    @Test
    @DisplayName("[AC-SUB-23-3] DRAW는 FINISHED와 완전히 동일하게 처리된다")
    void getHistory_drawGame_treatedSameAsFinished() {
        Game draw = game("DRAW", null, 9);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 9,
                LocalDateTime.of(2026, 8, 12, 22, 0), LocalDateTime.of(2026, 8, 12, 22, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(draw));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(9);
    }

    @Test
    @DisplayName("[AC-SUB-26-2] FINISHED인데 last_inning을 아직 못 채웠으면(NULL) 에러가 아니라 200과 "
            + "빈 배열이다")
    void getHistory_finishedNullLastInning_returnsEmptyInningsNotError() {
        Game finished = game("FINISHED", null, null);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
    }

    @Test
    @DisplayName("[AC-SUB-38-1,38-2] 표에 없는 상태 이름(예: suspended)은 SCHEDULED(403)로 접히지 않고 "
            + "FINISHED와 동일하게 last_inning 기준으로 처리된다")
    void getHistory_unknownStatusName_treatedSameAsFinished() {
        Game suspended = game("suspended", null, 5);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 5,
                LocalDateTime.of(2026, 8, 12, 21, 0), LocalDateTime.of(2026, 8, 12, 21, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(suspended));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(5);
    }

    // ---------- QUIZ-SUB-27-3: 두 이닝 컬럼을 섞는 폴백 금지(회귀 고정) ----------

    @Test
    @DisplayName("[회귀][AC-SUB-27-3] IN_PROGRESS에서 current_inning이 NULL이면 last_inning에 값이 "
            + "있어도 그쪽으로 대체하지 않는다 — 빈 배열 그대로다")
    void getHistory_inProgressNullCurrentInning_doesNotFallBackToLastInning() {
        // last_inning=9 라는 '엉뚱한' 값이 있어도 IN_PROGRESS 는 current_inning 만 본다.
        // 대상 행을 실제로 채워, 폴백이 있었다면(lastInning=9 등 0보다 큰 값이 됐다면) innings가
        // 비지 않았을 상황을 만들어 "정말 안 읽는지"를 구분해낸다(대상 행 0건이라 접힌 것과 구별).
        Game inProgress = game("IN_PROGRESS", null, 9);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
    }

    @Test
    @DisplayName("[회귀][AC-SUB-27-3] FINISHED에서 last_inning이 NULL이면 current_inning에 값이 남아 "
            + "있어도(스테일 값) 그쪽으로 대체하지 않는다 — 빈 배열 그대로다")
    void getHistory_finishedNullLastInning_doesNotFallBackToCurrentInning() {
        // current_inning=7 이라는 '엉뚱한'(경기 종료 후 지워지지 않은) 값이 있어도 FINISHED 는
        // last_inning 만 본다. 위와 같은 이유로 대상 행을 채워 폴백 유무를 구분한다.
        Game finished = game("FINISHED", 7, null);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).isEmpty();
        assertThat(response.summary().total()).isZero();
    }

    // ---------- QUIZ-SUB-25/55: 범위 밖 행 제외 + 요약=이닝 합계 ----------

    @Test
    @DisplayName("[AC-SUB-25-1,25-2,55-1,55-2,55-3] 열거 범위 밖(진행 중인 현재 이닝) 행은 목록에도 "
            + "전체 요약에도 잡히지 않는다 — 전체 요약은 열거된 이닝들의 합계와 정확히 같다")
    void getHistory_rowsOutsideRange_excludedFromBothListAndSummary() {
        // current_inning=3 → 범위 1~2. 3회 행은 범위 밖이라 완전히 빠져야 한다.
        Game inProgress = game("IN_PROGRESS", 3, null);
        UserAccount account = account(0L);
        Quiz quiz1 = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        Quiz quiz2 = quiz(20L, 0, 20.0, LocalDate.of(2026, 8, 12));
        Quiz outOfRangeQuiz = quiz(30L, 0, 999.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit inning1 = submitInInning(account, quiz1, option(quiz1, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        QuizUserSubmit inning2 = submitInInning(account, quiz2, option(quiz2, 1, "오답"), false, 2,
                LocalDateTime.of(2026, 8, 12, 19, 20), LocalDateTime.of(2026, 8, 12, 19, 21));
        QuizUserSubmit outOfRange = submitInInning(account, outOfRangeQuiz,
                option(outOfRangeQuiz, 0, "정답"), true, 3,
                LocalDateTime.of(2026, 8, 12, 19, 40), LocalDateTime.of(2026, 8, 12, 19, 41));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(inProgress));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(inning1, inning2, outOfRange));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings()).hasSize(2);
        assertThat(response.innings().stream().flatMap(i -> i.quizzes().stream())
                .map(QuizSubmissionItemResponse::quizId))
                .containsExactlyInAnyOrder(10L, 20L);
        // 요약은 정확히 이닝 원소들의 합이다 — 범위 밖(3회, quizId=30)은 어디에도 없다
        long summedTotal = response.innings().stream().mapToLong(i -> i.summary().total()).sum();
        long summedCorrect = response.innings().stream().mapToLong(i -> i.summary().correctCount()).sum();
        assertThat(response.summary().total()).isEqualTo(summedTotal).isEqualTo(2L);
        assertThat(response.summary().correctCount()).isEqualTo(summedCorrect).isEqualTo(1L);
        assertThat(response.summary().earnedPoint()).isEqualTo(10L); // outOfRange(999점)는 제외
    }

    // ---------- QUIZ-SUB-52/53: earnedPoint 산식 ----------

    @Test
    @DisplayName("[AC-SUB-52-1,52-2,53-1] earnedPoint는 정답 행의 score 합이고, 오답·미답 행은 더하지 "
            + "않으며 score가 NULL인 문제는 0으로 센다(예외 없음)")
    void getHistory_earnedPoint_sumsOnlyCorrectRowsAndTreatsNullScoreAsZero() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz correctScored = quiz(10L, 0, 50.0, LocalDate.of(2026, 8, 12));
        Quiz correctNullScore = quiz(20L, 0, null, LocalDate.of(2026, 8, 12));
        Quiz wrongScored = quiz(30L, 0, 100.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit correct1 = submitInInning(account, correctScored,
                option(correctScored, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        QuizUserSubmit correctNoScore = submitInInning(account, correctNullScore,
                option(correctNullScore, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 5), LocalDateTime.of(2026, 8, 12, 19, 6));
        QuizUserSubmit wrong = submitInInning(account, wrongScored,
                option(wrongScored, 1, "오답"), false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 10), LocalDateTime.of(2026, 8, 12, 19, 11));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(correct1, correctNoScore, wrong));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.summary().earnedPoint()).isEqualTo(50L);
    }

    // ---------- QUIZ-SUB-43: correct는 isAnswer 그대로, 재계산하지 않는다 ----------

    @Test
    @DisplayName("[AC-SUB-43-1] 정답이 사후 정정돼 myOption==answer가 됐어도 correct는 저장된 "
            + "isAnswer(false) 그대로다 — myOption==answer로 재계산하지 않는다")
    void getHistory_correct_isTakenFromStoredIsAnswerNotRecomputed() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12)); // 현재 정답은 0
        QuizOption chosenZero = option(quiz, 0, "당시엔 오답이던 보기");
        // 당시 채점은 오답(isAnswer=false)이었는데, 지금 quiz.answer 도 0이라 myOption==answer 다.
        // 그래도 응답은 저장된 판정(false)을 그대로 싣는다.
        QuizUserSubmit submit = submitInInning(account, quiz, chosenZero, false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        QuizSubmissionItemResponse item = response.innings().get(0).quizzes().get(0);
        assertThat(item.myOption()).isEqualTo(item.answer());
        assertThat(item.correct()).isFalse();
        assertThat(item.earnedPoint()).isZero();
    }

    // ---------- QUIZ-SUB-40/41/42/44/45/46: 항목 필드 ----------

    @Test
    @DisplayName("[AC-SUB-41-1,41-2] options는 보기 전체를 번호 오름차순으로 담는다")
    void getHistory_options_includeAllChoicesSortedByNumber() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "O"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(10L)))
                .willReturn(List.of(option(quiz, 0, "O"), option(quiz, 1, "X")));
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        QuizSubmissionItemResponse item = response.innings().get(0).quizzes().get(0);
        assertThat(item.options()).hasSize(2);
        assertThat(item.options().get(0).no()).isZero();
        assertThat(item.options().get(0).text()).isEqualTo("O");
        assertThat(item.options().get(1).no()).isEqualTo(1);
        assertThat(item.options().get(1).text()).isEqualTo("X");
    }

    @Test
    @DisplayName("[AC-SUB-42-1,44-1,45-1,46-1] 미답 행은 myOption=null·correct=false·earnedPoint=0이고, "
            + "정답 번호(answer)와 liked/likeCount는 그대로 실리며, submittedAt은 받은 시각(createdAt)이 "
            + "그대로 남는다")
    void getHistory_unansweredItem_hasNullMyOptionButStillExposesAnswerAndLikeFields() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        LocalDateTime receivedAt = LocalDateTime.of(2026, 8, 12, 19, 0);
        QuizUserSubmit unanswered = submitInInning(account, quiz, null, false, 1,
                receivedAt, receivedAt);
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(unanswered));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of(10L, new QuizLikeResponse(true, 3L)));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        QuizSubmissionItemResponse item = response.innings().get(0).quizzes().get(0);
        assertThat(item.myOption()).isNull();
        assertThat(item.correct()).isFalse();
        assertThat(item.earnedPoint()).isZero();
        assertThat(item.answer()).isEqualTo(0);
        assertThat(item.liked()).isTrue();
        assertThat(item.likeCount()).isEqualTo(3L);
        assertThat(item.submittedAt()).isEqualTo(receivedAt);
    }

    @Test
    @DisplayName("[AC-SUB-42-2] 미답 행 중 받은 시각+8분이 지난 것은 expired=true, 아직 남은 것은 "
            + "false다")
    void getHistory_unansweredItems_expiredFlagReflectsEightMinuteWindow() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz stillOpen = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        Quiz pastWindow = quiz(20L, 0, 10.0, LocalDate.of(2026, 8, 12));
        LocalDateTime now = QuizSubmitWindow.now();
        QuizUserSubmit notExpired = submitInInning(account, stillOpen, null, false, 1,
                now.minusMinutes(1), now.minusMinutes(1));
        QuizUserSubmit expired = submitInInning(account, pastWindow, null, false, 1,
                now.minusMinutes(9), now.minusMinutes(9));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(notExpired, expired));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        List<QuizSubmissionItemResponse> items = response.innings().get(0).quizzes();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).quizId()).isEqualTo(10L);
        assertThat(items.get(0).expired()).isFalse();
        assertThat(items.get(1).quizId()).isEqualTo(20L);
        assertThat(items.get(1).expired()).isTrue();
    }

    @Test
    @DisplayName("[AC-SUB-46-1] 답한 항목의 submittedAt은 받은 시각(createdAt)이 아니라 답을 낸 시각"
            + "(updatedAt)이다")
    void getHistory_answeredItem_submittedAtIsUpdatedAtNotCreatedAt() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 30));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings().get(0).quizzes().get(0).submittedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 12, 19, 30));
    }

    @Test
    @DisplayName("[AC-SUB-45-2] 좋아요 집계에서 빠진 문제는 liked=false·likeCount=0으로 흡수된다")
    void getHistory_likeMissingFromMap_defaultsToNoneNotError() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, option(quiz, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of()); // 이 문제는 집계에서 빠져 있다

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        QuizSubmissionItemResponse item = response.innings().get(0).quizzes().get(0);
        assertThat(item.liked()).isFalse();
        assertThat(item.likeCount()).isZero();
    }

    // ---------- QUIZ-SUB-24/34: 정렬 ----------

    @Test
    @DisplayName("[AC-SUB-24-1,24-2] 이닝 배열은 오름차순이고 번호가 건너뛰지 않는다")
    void getHistory_innings_areSortedAscendingWithoutGaps() {
        Game finished = game("FINISHED", null, 5);
        UserAccount account = account(0L);
        Quiz quiz = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit submit = submitInInning(account, quiz, null, false, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 0));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(submit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings().stream().map(i -> i.inning()).toList())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("[AC-SUB-34-1] 같은 이닝 안의 문제는 받은 순서(행 id 오름차순 = 리포지토리 반환 순서) "
            + "그대로다")
    void getHistory_itemsWithinInning_keepRepositoryReturnOrder() {
        Game finished = game("FINISHED", null, 1);
        UserAccount account = account(0L);
        Quiz first = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 12));
        Quiz second = quiz(20L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit firstSubmit = submitInInning(account, first, option(first, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        QuizUserSubmit secondSubmit = submitInInning(account, second, option(second, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 5), LocalDateTime.of(2026, 8, 12, 19, 6));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(finished));
        // 리포지토리는 id asc(=받은 순서)로 이미 정렬해 돌려준다 — first, second 순
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(firstSubmit, secondSubmit));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, GAME_ID);

        assertThat(response.innings().get(0).quizzes())
                .extracting(QuizSubmissionItemResponse::quizId)
                .containsExactly(10L, 20L);
    }

    // ---------- QUIZ-SUB-60/61/62: SQL 상수성(항목 수와 무관하게 고정 호출 횟수) ----------

    @Test
    @DisplayName("[AC-SUB-60-1,60-2,61-1,61-2,62-1] 이닝 1개·문항 1건일 때와 이닝 5개·문항 100건일 때 "
            + "보기 조회(findAllByQuiz_IdIn)·좋아요 조회(likesOf) 호출 횟수가 각각 1회로 동일하다"
            + "(이닝 N+1·좋아요 N+1 둘 다 금지)")
    void getHistory_optionAndLikeQueries_areCalledExactlyOnceRegardlessOfItemCount() {
        UserAccount account = account(0L);

        // 1) 이닝 1개·문항 1건
        Game oneInning = game("FINISHED", null, 1);
        Quiz singleQuiz = quiz(1L, 0, 10.0, LocalDate.of(2026, 8, 12));
        QuizUserSubmit single = submitInInning(account, singleQuiz,
                option(singleQuiz, 0, "정답"), true, 1,
                LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(oneInning));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK))
                .willReturn(List.of(single));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        quizSubmitService.getHistory(USER_ID, GAME_ID);

        verify(quizOptionRepository, times(1))
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any());
        verify(quizLikeService, times(1))
                .likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any());

        // 2) 이닝 5개(1~5, 20문항씩)·문항 100건 — 같은 두 협력 객체가 여전히 각각 1회여야 한다
        Game fiveInnings = game("FINISHED", null, 5);
        List<QuizUserSubmit> hundred = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> {
                    Quiz quiz = quiz((long) (i + 1), 0, 10.0, LocalDate.of(2026, 8, 12));
                    int inning = ((i - 1) / 20) + 1; // 1~5회에 20문항씩 고르게 분배
                    return submitInInning(account, quiz, option(quiz, 0, "정답"), true, inning,
                            LocalDateTime.of(2026, 8, 12, 19, 0), LocalDateTime.of(2026, 8, 12, 19, 1));
                })
                .toList();
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(fiveInnings));
        given(quizUserSubmitRepository.findGameSubmissions(USER_ID, GAME_PK)).willReturn(hundred);

        quizSubmitService.getHistory(USER_ID, GAME_ID);

        verify(quizOptionRepository, times(2))
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any());
        verify(quizLikeService, times(2))
                .likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any());
    }
}
