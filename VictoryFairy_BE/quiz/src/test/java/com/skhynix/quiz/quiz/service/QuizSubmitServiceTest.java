package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizSubmitService} 단위 테스트 — 협력 객체 전부 Mockito 목, DB·Spring 컨텍스트 없음.
 *
 * <p>따라서 UNIQUE 제약의 실제 중재(동시 INSERT 중 한쪽 실패)와 {@code findWithLockById}의 실제
 * 행 잠금은 여기서 검증되지 않는다 — "서비스가 락 조회를 골랐고, 제약 위반을 409로 접는다"는 계약만
 * 고정한다. JPQL fetch join 의 실동작도 마찬가지로 리포지토리 목 뒤에 있다.
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

    private QuizUserSubmit submitOf(UserAccount account, Quiz quiz, QuizOption option,
            boolean isAnswer, LocalDateTime submittedAt) {
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(account)
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(isAnswer)
                .build();
        ReflectionTestUtils.setField(submit, "createdAt", submittedAt);
        return submit;
    }

    @Test
    @DisplayName("정답을 제출하면 배점을 계정에 적립하고 isAnswer=true 기록을 저장한다")
    void submit_correctAnswer_addsPointAndSavesCorrectRecord() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        UserAccount account = account(5L);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(response.correct()).isTrue();
        assertThat(response.answer()).isZero();
        assertThat(response.myOption()).isZero();
        assertThat(response.earnedPoint()).isEqualTo(10L);
        assertThat(response.totalPoint()).isEqualTo(15L);
        assertThat(account.getPoint()).isEqualTo(15L);

        ArgumentCaptor<QuizUserSubmit> captor = ArgumentCaptor.forClass(QuizUserSubmit.class);
        verify(quizUserSubmitRepository).save(captor.capture());
        QuizUserSubmit saved = captor.getValue();
        assertThat(saved.getUserAccount()).isSameAs(account);
        assertThat(saved.getQuiz()).isSameAs(quiz);
        assertThat(saved.getSubmitOption()).isSameAs(option);
        assertThat(saved.isAnswer()).isTrue();
    }

    @Test
    @DisplayName("오답을 제출하면 적립 없이 isAnswer=false 기록만 저장한다")
    void submit_wrongAnswer_earnsNothing() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 1, "오답 보기");
        UserAccount account = account(5L);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 1))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 1);

        assertThat(response.correct()).isFalse();
        assertThat(response.earnedPoint()).isZero();
        assertThat(response.totalPoint()).isEqualTo(5L);
        assertThat(account.getPoint()).isEqualTo(5L);

        ArgumentCaptor<QuizUserSubmit> captor = ArgumentCaptor.forClass(QuizUserSubmit.class);
        verify(quizUserSubmitRepository).save(captor.capture());
        assertThat(captor.getValue().isAnswer()).isFalse();
    }

    @Test
    @DisplayName("배점(score)이 null인 문제는 정답이어도 적립이 0이다")
    void submit_nullScore_correctButEarnsNothing() {
        Quiz quiz = publishedQuiz(0, null);
        QuizOption option = option(quiz, 0, "정답 보기");
        UserAccount account = account(5L);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account));

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(response.correct()).isTrue();
        assertThat(response.earnedPoint()).isZero();
        assertThat(response.totalPoint()).isEqualTo(5L);
        assertThat(account.getPoint()).isEqualTo(5L);
    }

    @Test
    @DisplayName("미편성 문제(quizDate=null)는 행이 있어도 404 QUIZ_NOT_FOUND다")
    void submit_unscheduledQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(QUIZ_ID))
                .willReturn(Optional.of(quiz(QUIZ_ID, 0, 10.0, null)));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 문제는 404 QUIZ_NOT_FOUND다")
    void submit_missingQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).save(any());
    }

    @Test
    @DisplayName("그 문제에 실재하지 않는 보기 번호는 400 QUIZ_OPTION_NOT_FOUND다")
    void submit_missingOption_throwsQuizOptionNotFound() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 9))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 9))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 제출한 문제는 409 QUIZ_ALREADY_SUBMITTED이며 저장을 시도하지 않는다")
    void submit_alreadySubmitted_throwsConflict() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        verify(quizOptionRepository, never()).findFirstByQuiz_IdAndOptionOrderByIdAsc(anyLong(), anyInt());
        verify(quizUserSubmitRepository, never()).save(any());
    }

    @Test
    @DisplayName("동시 제출 race로 UNIQUE 위반이 나면 500이 아니라 409 QUIZ_ALREADY_SUBMITTED로 접는다")
    void submit_uniqueViolationOnSave_mapsToConflict() {
        Quiz quiz = publishedQuiz(0, 10.0);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option(quiz, 0, "정답 보기")));
        given(userAccountRepository.findWithLockById(USER_ID))
                .willReturn(Optional.of(account(0L)));
        willThrow(new DataIntegrityViolationException("uk_quiz_users_submit_account_quiz"))
                .given(quizUserSubmitRepository).save(any(QuizUserSubmit.class));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));
    }

    @Test
    @DisplayName("이력은 페이지 항목(정답 텍스트 포함)과 전체 제출 기준 요약을 함께 만든다")
    void getHistory_mapsPageAndComputesSummary() {
        UserAccount account = account(0L);
        Quiz quiz1 = quiz(10L, 0, 10.0, LocalDate.of(2026, 8, 7));
        Quiz quiz2 = quiz(20L, 1, 5.0, LocalDate.of(2026, 8, 8));
        QuizUserSubmit wrong = submitOf(account, quiz1, option(quiz1, 1, "오답 보기"), false,
                LocalDateTime.of(2026, 8, 7, 21, 0));
        QuizUserSubmit correct = submitOf(account, quiz2, option(quiz2, 1, "X"), true,
                LocalDateTime.of(2026, 8, 8, 9, 30));
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(correct, wrong), PageRequest.of(0, 20), 2));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(20L, 10L)))
                .willReturn(List.of(
                        option(quiz1, 0, "정답 보기"), option(quiz1, 1, "오답 보기"),
                        option(quiz2, 0, "O"), option(quiz2, 1, "X")));
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(10L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(4L);

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
        assertThat(first.answer()).isEqualTo(1);
        assertThat(first.answerText()).isEqualTo("X");
        assertThat(first.earnedPoint()).isEqualTo(5L);
        assertThat(first.submittedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 9, 30));

        QuizSubmissionItemResponse second = response.submissions().content().get(1);
        assertThat(second.quizId()).isEqualTo(10L);
        assertThat(second.correct()).isFalse();
        assertThat(second.myOptionText()).isEqualTo("오답 보기");
        assertThat(second.answerText()).isEqualTo("정답 보기");
        assertThat(second.earnedPoint()).isZero();
    }

    @Test
    @DisplayName("제출 이력이 0건이면 정답률은 NaN이 아니라 0.0이고 보기 조회를 하지 않는다")
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
}
