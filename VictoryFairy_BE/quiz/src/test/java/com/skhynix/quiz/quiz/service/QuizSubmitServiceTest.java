package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
 * 의존하지 않는다({@code QuizSubmissionTicketStore}는 이 브랜치에서 폐기됐다).
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

    // ---------- 풀이 이력 ----------

    @Test
    @DisplayName("이력은 페이지 항목(정답 텍스트 포함)과 전체 제출 기준 요약을 함께 만든다")
    void getHistory_mapsPageAndComputesSummary() {
        UserAccount account = account(0L);
        Quiz quiz1 = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 7));
        Quiz quiz2 = quiz(20L, 1, 5.0, LocalDate.of(2026, 8, 8));
        QuizUserSubmit wrong = submitOf(account, quiz1, option(quiz1, 1, "오답 보기"), false,
                LocalDateTime.of(2026, 8, 7, 20, 55), LocalDateTime.of(2026, 8, 7, 21, 0));
        QuizUserSubmit correct = submitOf(account, quiz2, option(quiz2, 1, "X"), true,
                LocalDateTime.of(2026, 8, 8, 9, 22), LocalDateTime.of(2026, 8, 8, 9, 30));
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(correct, wrong), PageRequest.of(0, 20), 2));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(20L, 10L)))
                .willReturn(List.of(
                        option(quiz1, 0, "정답 보기"), option(quiz1, 1, "오답 보기"),
                        option(quiz2, 0, "O"), option(quiz2, 1, "X")));
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(10L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(4L);
        given(quizLikeService.likesOf(USER_ID, List.of(20L, 10L))).willReturn(Map.of(
                20L, new QuizLikeResponse(true, 7L),
                10L, new QuizLikeResponse(false, 0L)));

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, 0);

        assertThat(response.summary().total()).isEqualTo(10L);
        assertThat(response.summary().correctCount()).isEqualTo(4L);
        assertThat(response.summary().accuracy()).isEqualTo(0.4);
        assertThat(response.submissions().content()).hasSize(2);
        assertThat(response.submissions().size()).isEqualTo(20);
        assertThat(response.submissions().totalElements()).isEqualTo(2L);
        assertThat(response.submissions().hasNext()).isFalse();

        QuizSubmissionItemResponse first = response.submissions().content().get(0);
        assertThat(first.quizId()).isEqualTo(20L);
        assertThat(first.question()).isEqualTo("문제 20");
        assertThat(first.type()).isEqualTo("객관식");
        assertThat(first.difficulty()).isEqualTo("EASY");
        assertThat(first.quizDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(first.myOption()).isEqualTo(1);
        assertThat(first.myOptionText()).isEqualTo("X");
        assertThat(first.correct()).isTrue();
        assertThat(first.expired()).isFalse();
        assertThat(first.answer()).isEqualTo(1);
        assertThat(first.answerText()).isEqualTo("X");
        assertThat(first.earnedPoint()).isEqualTo(5L);
        // [회귀] submittedAt은 받은 시각(createdAt=9:22)이 아니라 답을 낸 시각(updatedAt=9:30)이다
        assertThat(first.submittedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 9, 30));
        assertThat(first.liked()).isTrue();
        assertThat(first.likeCount()).isEqualTo(7L);

        QuizSubmissionItemResponse second = response.submissions().content().get(1);
        assertThat(second.quizId()).isEqualTo(10L);
        assertThat(second.correct()).isFalse();
        assertThat(second.myOptionText()).isEqualTo("오답 보기");
        assertThat(second.answerText()).isEqualTo("정답 보기");
        assertThat(second.earnedPoint()).isZero();
        assertThat(second.submittedAt()).isEqualTo(LocalDateTime.of(2026, 8, 7, 21, 0));
        // AC-LIKE-36: liked=true 행만 세므로, 켜진 적 없는(또는 group by 에서 빠진) 문제는 0으로 흡수된다
        assertThat(second.liked()).isFalse();
        assertThat(second.likeCount()).isZero();
    }

    @Test
    @DisplayName("[AC-INN-78-3,78-4,78-5] 답 없는 행도 이력 항목으로 실린다 — myOption·myOptionText는 "
            + "null, correct는 false, earnedPoint는 0이고, 시한이 남았으면 expired=false·지났으면 "
            + "expired=true다(감출 수 없다: total이 이 행까지 센다)")
    void getHistory_includesUnansweredSubmission_withNullMyOptionAndComputedExpired() {
        UserAccount account = account(0L);
        Quiz inProgressQuiz = quiz(30L, 0, 10.0, LocalDate.of(2026, 8, 12));
        Quiz expiredQuiz = quiz(40L, 0, 10.0, LocalDate.of(2026, 8, 12));
        LocalDateTime now = QuizSubmitWindow.now();
        QuizUserSubmit inProgress = submitOf(account, inProgressQuiz, null, false,
                now.minusMinutes(1), now.minusMinutes(1));
        QuizUserSubmit expired = submitOf(account, expiredQuiz, null, false,
                now.minusMinutes(9), now.minusMinutes(9));
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(inProgress, expired), PageRequest.of(0, 20), 2));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(30L, 40L)))
                .willReturn(List.of());
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(2L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(0L);
        given(quizLikeService.likesOf(USER_ID, List.of(30L, 40L))).willReturn(Map.of());

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, 0);

        assertThat(response.submissions().content()).hasSize(2);
        QuizSubmissionItemResponse inProgressItem = response.submissions().content().get(0);
        assertThat(inProgressItem.quizId()).isEqualTo(30L);
        assertThat(inProgressItem.myOption()).isNull();
        assertThat(inProgressItem.myOptionText()).isNull();
        assertThat(inProgressItem.correct()).isFalse();
        assertThat(inProgressItem.earnedPoint()).isZero();
        assertThat(inProgressItem.expired()).isFalse();
        // 답 없는 항목은 낸 시각이 없다 — 받은 시각(createdAt)이 그대로 submittedAt에 실린다
        assertThat(inProgressItem.submittedAt()).isEqualTo(now.minusMinutes(1));

        QuizSubmissionItemResponse expiredItem = response.submissions().content().get(1);
        assertThat(expiredItem.quizId()).isEqualTo(40L);
        assertThat(expiredItem.myOption()).isNull();
        assertThat(expiredItem.correct()).isFalse();
        assertThat(expiredItem.expired()).isTrue();
    }

    @Test
    @DisplayName("[AC-LIKE-35-1,2] 이력 항목이 20건이어도 좋아요 조립 호출은 1건이다"
            + "(N+1 금지 — 항목 수와 무관하게 QuizLikeService.likesOf 는 정확히 1회)")
    void getHistory_twentyItems_callsLikesOfExactlyOnce() {
        UserAccount account = account(0L);
        List<QuizUserSubmit> submits = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> {
                    Quiz quiz = quiz((long) i, 0, 10.0, LocalDate.of(2026, 8, 8));
                    return submitOf(account, quiz, option(quiz, 0, "정답 보기"), true,
                            LocalDateTime.of(2026, 8, 8, 9, 0), LocalDateTime.of(2026, 8, 8, 9, 0));
                })
                .toList();
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(submits, PageRequest.of(0, 20), 20));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(20L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(20L);
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        quizSubmitService.getHistory(USER_ID, 0);

        verify(quizLikeService, times(1)).likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any());
    }

    @Test
    @DisplayName("[AC-LIKE-35-2] 이력 항목이 1건이어도 좋아요 조립 호출은 1건이다"
            + "(20건일 때와 호출 횟수가 같다 — N+1 이면 1건일 때만 우연히 통과할 수 있어 별도로 고정한다)")
    void getHistory_oneItem_callsLikesOfExactlyOnceSameAsTwentyItems() {
        UserAccount account = account(0L);
        Quiz quiz = quiz(1L, 0, 10.0, LocalDate.of(2026, 8, 8));
        QuizUserSubmit submit = submitOf(account, quiz, option(quiz, 0, "정답 보기"), true,
                LocalDateTime.of(2026, 8, 8, 9, 0), LocalDateTime.of(2026, 8, 8, 9, 0));
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(submit), PageRequest.of(0, 20), 1));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(1L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(1L);
        given(quizLikeService.likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .willReturn(Map.of());

        quizSubmitService.getHistory(USER_ID, 0);

        verify(quizLikeService, times(1)).likesOf(org.mockito.ArgumentMatchers.eq(USER_ID), any());
    }

    @Test
    @DisplayName("[AC-INN-77-1,77-2,77-3] 제출 이력이 0건이면 정답률은 NaN이 아니라 0.0이고 보기 조회를 "
            + "하지 않는다 — 요약 두 카운트(total/correctCount)는 서비스가 재계산하지 않고 리포지토리 "
            + "값을 그대로 옮긴다(미답 행을 분모에서 빼는 보정이 없다는 것을 통과값 그대로 확인)")
    void getHistory_empty_accuracyZeroWithoutOptionLookup() {
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(Page.empty(PageRequest.of(0, 20)));
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(0L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(0L);

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, 0);

        assertThat(response.summary().total()).isZero();
        assertThat(response.summary().correctCount()).isZero();
        assertThat(response.summary().accuracy()).isEqualTo(0.0);
        assertThat(response.submissions().content()).isEmpty();

        verify(quizOptionRepository, never()).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyCollection());
    }

    @Test
    @DisplayName("[AC-INN-77-1] 미답 행이 섞여 있어도 total·correctCount는 리포지토리가 돌려준 값 "
            + "그대로다(분모에 미답 행이 포함된 값을 그대로 신뢰 — 서비스가 답한 행만 골라 다시 세지 "
            + "않는다)")
    void getHistory_totalIncludesUnansweredRows_passthroughFromRepository() {
        // total(10) > correctCount(4)로, 그 차이(6)에는 오답과 미답이 섞여 있어도 서비스는
        // 이 둘을 구분해 재계산하지 않는다 — 리포지토리 카운트를 그대로 옮긴다
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(Page.empty(PageRequest.of(0, 20)));
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(10L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(4L);

        QuizSubmissionHistoryResponse response = quizSubmitService.getHistory(USER_ID, 0);

        assertThat(response.summary().total()).isEqualTo(10L);
        assertThat(response.summary().correctCount()).isEqualTo(4L);
        assertThat(response.summary().accuracy()).isEqualTo(0.4);
    }
}
