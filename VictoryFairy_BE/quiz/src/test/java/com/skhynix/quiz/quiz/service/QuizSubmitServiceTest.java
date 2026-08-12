package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore;
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore.Ticket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link QuizSubmitService} 단위 테스트 — 협력 객체 전부 Mockito 목, DB·Spring 컨텍스트 없음.
 *
 * <p>따라서 UNIQUE 제약의 실제 중재(동시 INSERT 중 한쪽 실패)와 {@code findWithLockById}의 실제
 * 행 잠금은 여기서 검증되지 않는다 — "서비스가 락 조회를 골랐고, 제약 위반을 409로 접는다"는 계약만
 * 고정한다. JPQL fetch join 의 실동작도 마찬가지로 리포지토리 목 뒤에 있다.
 *
 * <p><b>티켓 삭제는 커밋 후({@code afterCommit}) 콜백으로 등록된다</b>(실제 트랜잭션 매니저 없음).
 * {@code TransactionSynchronizationManager.registerSynchronization}은 동기화가 활성화돼 있지 않으면
 * {@code IllegalStateException}을 던지므로, 성공 경로 테스트가 이 지점에 도달하려면
 * {@code initSynchronization()}이 선행돼야 한다({@link #activateTransactionSynchronization()}). 커밋
 * 시점을 직접 검증하려는 테스트는 등록된 동기화를 수동으로 {@code afterCommit()}해 콜백을 트리거한다.
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

    @Mock
    private QuizSubmissionTicketStore ticketStore;

    @InjectMocks
    private QuizSubmitService quizSubmitService;

    @BeforeEach
    void activateTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void deactivateTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** 커밋 후 삭제 콜백에 등록된 동기화를 모두 강제 실행한다(실제 트랜잭션 매니저가 없어 수동 트리거). */
    private static void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static Ticket ticket(Integer inning) {
        return new QuizSubmissionTicketStore.Ticket(inning);
    }

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
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
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
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
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
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
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
        // [AC-INN-33-1] 404 판정은 티켓 유무와 무관하다 — 존재하지 않는 문제는 티켓 조회 자체가 없다
        verify(ticketStore, never()).find(anyLong(), anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 문제는 404 QUIZ_NOT_FOUND다")
    void submit_missingQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));

        verify(quizUserSubmitRepository, never()).save(any());
        verify(ticketStore, never()).find(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-INN-33-3] 티켓은 있으나 그 문제에 실재하지 않는 보기 번호는 400 QUIZ_OPTION_NOT_FOUND다"
            + "(403이 400보다 앞이라 도달)")
    void submit_missingOption_throwsQuizOptionNotFound() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 9))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 9))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizUserSubmitRepository, never()).save(any());
        // [AC-INN-22-1] 400 실패는 티켓을 태우지 않는다 — 오타 한 번으로 문제를 못 풀게 되면 안 된다
        verify(ticketStore, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-INN-33-2] 이미 제출한 문제는 여전히 409 QUIZ_ALREADY_SUBMITTED다 — 최초 제출이 "
            + "티켓을 지워 재제출 시점엔 티켓이 없지만, 409 검사가 403보다 앞이라 응답이 바뀌지 않는다"
            + "(검증 순서 회귀의 핵심 AC)")
    void submit_alreadySubmitted_throwsConflict() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        verify(quizOptionRepository, never()).findFirstByQuiz_IdAndOptionOrderByIdAsc(anyLong(), anyInt());
        verify(quizUserSubmitRepository, never()).save(any());
        // 409 검사가 403(티켓) 검사보다 앞선다 — 순서가 바뀌면 이 호출이 발생해 403으로 새어 나간다
        verify(ticketStore, never()).find(anyLong(), anyLong());
        verify(ticketStore, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-INN-39-1] 삭제 실패로 티켓이 남아 있어도 이미 제출한 문제 재제출은 여전히 409다 "
            + "— 티켓은 제출의 필요조건일 뿐 충분조건이 아니며 409 검사가 먼저 접는다")
    void submit_alreadySubmittedWithLeftoverTicket_stillReturnsConflict() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);
        // 티켓이 남아 있다고 스텁해도(leniently) 409 검사가 앞서 있어 이 스텁까지 도달하지 않는다
        lenient().when(ticketStore.find(USER_ID, QUIZ_ID)).thenReturn(Optional.of(ticket(6)));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        verify(ticketStore, never()).find(anyLong(), anyLong());
    }

    @Test
    @DisplayName("동시 제출 race로 UNIQUE 위반이 나면 500이 아니라 409 QUIZ_ALREADY_SUBMITTED로 접는다")
    void submit_uniqueViolationOnSave_mapsToConflict() {
        Quiz quiz = publishedQuiz(0, 10.0);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option(quiz, 0, "정답 보기")));
        given(userAccountRepository.findWithLockById(USER_ID))
                .willReturn(Optional.of(account(0L)));
        willThrow(new DataIntegrityViolationException("uk_quiz_users_submit_account_quiz"))
                .given(quizUserSubmitRepository).save(any(QuizUserSubmit.class));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        // [AC-INN-22-2] 409(UNIQUE race)도 티켓을 지우지 않는다 — 실패는 티켓을 소모하지 않는 것이 계약
        verify(ticketStore, never()).delete(anyLong(), anyLong());
    }

    // ---------- 제출 자격 증명(티켓) — QUIZ-INN-18~40 ----------

    @Test
    @DisplayName("[AC-INN-18-1,18-2] 티켓의 이닝 값을 그대로 제출 기록의 inning에 저장한다")
    void submit_withTicket_savesTicketInningOnRecord() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));

        quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        ArgumentCaptor<QuizUserSubmit> captor = ArgumentCaptor.forClass(QuizUserSubmit.class);
        verify(quizUserSubmitRepository).save(captor.capture());
        assertThat(captor.getValue().getInning()).isEqualTo(6);
    }

    @Test
    @DisplayName("[AC-INN-18-4,40-3] 이닝 미상 티켓(inning=null)으로 제출해도 200이고 기록의 inning은 "
            + "NULL이다 — 이닝 확보 실패가 제출을 막지 않는다")
    void submit_withUnknownInningTicket_returns200AndSavesNullInning() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(null)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        assertThat(response.correct()).isTrue();
        ArgumentCaptor<QuizUserSubmit> captor = ArgumentCaptor.forClass(QuizUserSubmit.class);
        verify(quizUserSubmitRepository).save(captor.capture());
        assertThat(captor.getValue().getInning()).isNull();
    }

    @Test
    @DisplayName("[AC-INN-29-1,29-2,29-3,30-1] 티켓이 없으면(/today 미경유 또는 시한 8분 경과) "
            + "403 QUIZ_SUBMIT_NOT_ALLOWED다 — 두 경우가 같은 응답으로 구분 불가능하다")
    void submit_noTicket_throwsSubmitNotAllowed() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("[AC-INN-31-1,31-2,31-3,31-4,33-4] 403으로 거절된 제출은 아무 흔적도 남기지 않는다 — "
            + "제출 저장·계정 락·보기 조회·Redis 쓰기가 전부 일어나지 않는다")
    void submit_noTicket_leavesNoTraceOnRejection() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOf(BusinessException.class);

        verify(quizUserSubmitRepository, never()).save(any());
        verify(userAccountRepository, never()).findWithLockById(anyLong());
        verify(quizOptionRepository, never()).findFirstByQuiz_IdAndOptionOrderByIdAsc(anyLong(), anyInt());
        verify(ticketStore, never()).delete(anyLong(), anyLong());
        verify(ticketStore, never()).issue(anyLong(), any());
    }

    @Test
    @DisplayName("[AC-INN-37-1,37-2,37-3] 제출 중 티켓 조회가 실패하면(Redis 장애) 예외가 그대로 "
            + "전파되고(403이 아니다) 제출 행도 생기지 않는다 — 장애와 자격 미달은 다른 사실이다")
    void submit_ticketLookupFails_propagatesInsteadOf403() {
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(publishedQuiz(0, 10.0)));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID))
                .willThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .isInstanceOf(RedisConnectionFailureException.class)
                .isNotInstanceOf(BusinessException.class);

        verify(quizUserSubmitRepository, never()).save(any());
        verify(userAccountRepository, never()).findWithLockById(anyLong());
    }

    @Test
    @DisplayName("[AC-INN-26-3] 티켓 조회는 계정 행 락보다 먼저 끝난다 — 락 구간에 Redis 접근이 없다")
    void submit_checksTicketBeforeAccountLock() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));

        quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        InOrder inOrder = inOrder(ticketStore, userAccountRepository);
        inOrder.verify(ticketStore).find(USER_ID, QUIZ_ID);
        inOrder.verify(userAccountRepository).findWithLockById(USER_ID);
    }

    @Test
    @DisplayName("[AC-INN-21-1,21-2,21-3] 제출 성공 시 티켓 삭제는 커밋 이후에만 일어나고, 그 문제의 "
            + "티켓 정확히 하나만 지운다")
    void submit_success_deletesTicketOnlyAfterCommit() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));

        quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        // 커밋 전(=submit() 호출 직후)에는 아직 지워지지 않았다 — afterCommit 콜백을 아직 트리거하지 않았다
        verify(ticketStore, never()).delete(anyLong(), anyLong());

        triggerAfterCommit();

        verify(ticketStore, times(1)).delete(USER_ID, QUIZ_ID);
    }

    @Test
    @DisplayName("[AC-INN-23-1,23-2,23-3] 커밋 후 티켓 삭제가 실패해도 예외를 삼킨다 — 응답은 이미 "
            + "정상 반환된 뒤라 TTL 만료에 정리를 맡긴다")
    void submit_ticketDeleteFailsAfterCommit_swallowsException() {
        Quiz quiz = publishedQuiz(0, 10.0);
        QuizOption option = option(quiz, 0, "정답 보기");
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);
        given(ticketStore.find(USER_ID, QUIZ_ID)).willReturn(Optional.of(ticket(6)));
        given(quizOptionRepository.findFirstByQuiz_IdAndOptionOrderByIdAsc(QUIZ_ID, 0))
                .willReturn(Optional.of(option));
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(account(0L)));
        willThrow(new RedisConnectionFailureException("redis down"))
                .given(ticketStore).delete(USER_ID, QUIZ_ID);

        QuizSubmitResponse response = quizSubmitService.submit(USER_ID, QUIZ_ID, 0);

        // 응답은 이미 정상 반환됐다(삭제는 별도의 커밋 후 콜백이라 응답 자체엔 영향이 없다)
        assertThat(response.correct()).isTrue();
        assertThatCode(QuizSubmitServiceTest::triggerAfterCommit).doesNotThrowAnyException();
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
        assertThat(first.answer()).isEqualTo(1);
        assertThat(first.answerText()).isEqualTo("X");
        assertThat(first.earnedPoint()).isEqualTo(5L);
        assertThat(first.submittedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 9, 30));
        assertThat(first.liked()).isTrue();
        assertThat(first.likeCount()).isEqualTo(7L);

        QuizSubmissionItemResponse second = response.submissions().content().get(1);
        assertThat(second.quizId()).isEqualTo(10L);
        assertThat(second.correct()).isFalse();
        assertThat(second.myOptionText()).isEqualTo("오답 보기");
        assertThat(second.answerText()).isEqualTo("정답 보기");
        assertThat(second.earnedPoint()).isZero();
        // AC-LIKE-36: liked=true 행만 세므로, 켜진 적 없는(또는 group by 에서 빠진) 문제는 0으로 흡수된다
        assertThat(second.liked()).isFalse();
        assertThat(second.likeCount()).isZero();
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
                            LocalDateTime.of(2026, 8, 8, 9, 0));
                })
                .toList();
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(submits, PageRequest.of(0, 20), 20));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(20L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(20L);
        given(quizLikeService.likesOf(eq(USER_ID), any())).willReturn(Map.of());

        quizSubmitService.getHistory(USER_ID, 0);

        verify(quizLikeService, times(1)).likesOf(eq(USER_ID), any());
    }

    @Test
    @DisplayName("[AC-LIKE-35-2] 이력 항목이 1건이어도 좋아요 조립 호출은 1건이다"
            + "(20건일 때와 호출 횟수가 같다 — N+1 이면 1건일 때만 우연히 통과할 수 있어 별도로 고정한다)")
    void getHistory_oneItem_callsLikesOfExactlyOnceSameAsTwentyItems() {
        UserAccount account = account(0L);
        Quiz quiz = quiz(1L, 0, 10.0, LocalDate.of(2026, 8, 8));
        QuizUserSubmit submit = submitOf(account, quiz, option(quiz, 0, "정답 보기"), true,
                LocalDateTime.of(2026, 8, 8, 9, 0));
        given(quizUserSubmitRepository.findHistoryByUserAccountId(USER_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(submit), PageRequest.of(0, 20), 1));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(any()))
                .willReturn(List.of());
        given(quizUserSubmitRepository.countByUserAccount_Id(USER_ID)).willReturn(1L);
        given(quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(USER_ID)).willReturn(1L);
        given(quizLikeService.likesOf(eq(USER_ID), any())).willReturn(Map.of());

        quizSubmitService.getHistory(USER_ID, 0);

        verify(quizLikeService, times(1)).likesOf(eq(USER_ID), any());
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
