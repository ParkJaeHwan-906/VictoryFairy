package com.skhynix.quiz.quizroom.service;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.account;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.options;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.quiz;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.room;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomAnswer;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomOptionResponse;
import com.skhynix.quiz.quizroom.dto.ResultQuizResponse;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답 저장 트랜잭션 단위. 검사 순서(404 방 -> 404 문항 -> 400 보기 -> 409 중복)와 결과 조립을 고정한다.
 * 계정 행 락 직렬화(QUIZ-CQR-61)의 실제 동시성은 DB 가 필요해 여기서 검증하지 못한다 - 락을 가장 먼저 잡는 순서만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class QuizRoomAnswerRecorderTest {

    private static final Long USER_ID = 7L;
    private static final long ROOM_ID = 1L;
    // 파드 JVM 이 UTC 여도 KST 로 찍혀야 하므로 UTC 03:00 = KST 12:00 으로 고정한다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private QuizRoomRepository roomRepository;
    @Mock
    private QuizRoomQuizRepository quizRepository;
    @Mock
    private QuizRoomQuizOptionRepository optionRepository;
    @Mock
    private QuizRoomAnswerRepository answerRepository;
    @Mock
    private QuizRoomService roomService;

    private QuizRoomAnswerRecorder recorder;

    private final UserAccount solver = account(USER_ID, "풀이자", null);
    private final UserAccount author = account(1L, "작성자", null);
    private QuizRoom room;
    private QuizRoomQuiz q1;
    private QuizRoomQuiz q2;
    private QuizRoomQuiz q3;

    @BeforeEach
    void setUp() {
        recorder = new QuizRoomAnswerRecorder(userAccountRepository, roomRepository, quizRepository,
                optionRepository, answerRepository, roomService, CLOCK);
        room = room(ROOM_ID, author, 3, 0);
        q1 = quiz(11L, room, QuizRoomQuizType.MULTIPLE_CHOICE, "문제1", 0);
        q2 = quiz(12L, room, QuizRoomQuizType.MULTIPLE_CHOICE, "문제2", 2);
        q3 = quiz(13L, room, QuizRoomQuizType.OX, "문제3", 1);
    }

    private void stubLockAndRoom() {
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(solver));
        given(roomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    }

    private void stubQuizAndValidOption(QuizRoomQuiz quiz, int option) {
        given(quizRepository.findByIdAndRoom_Id(quiz.getId(), ROOM_ID)).willReturn(Optional.of(quiz));
        given(optionRepository.existsByQuiz_IdAndNo(quiz.getId(), option)).willReturn(true);
    }

    private static void assertCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---- 진행 중 응답 ----

    @Test
    @DisplayName("[QUIZ-CQR-50] 미답 문항이 남아 있으면 답을 저장하고 {answeredCount, total, result:null} 을 반환한다")
    void record_notLastAnswer_savesAndReturnsNullResult() {
        stubLockAndRoom();
        stubQuizAndValidOption(q1, 0);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(1L);

        AnswerResponse response = recorder.record(USER_ID, ROOM_ID, 11L, 0);

        assertThat(response).isEqualTo(new AnswerResponse(1, 3, null));
        ArgumentCaptor<QuizRoomAnswer> captor = ArgumentCaptor.forClass(QuizRoomAnswer.class);
        verify(answerRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserAccount()).isSameAs(solver);
        assertThat(captor.getValue().getRoom()).isSameAs(room);
        assertThat(captor.getValue().getQuiz()).isSameAs(q1);
        assertThat(captor.getValue().getOptionNo()).isZero();
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 12, 0));
        verifyNoInteractions(roomService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-53] 마지막이 아닌 답의 응답 타입은 result 가 null 이라 정답·정오를 실을 수 없다")
    void record_notLastAnswer_resultIsNull() {
        stubLockAndRoom();
        stubQuizAndValidOption(q2, 1);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(2L);

        AnswerResponse response = recorder.record(USER_ID, ROOM_ID, 12L, 1);

        assertThat(response.result()).isNull();
        assertThat(response.answeredCount()).isEqualTo(2);
    }

    // ---- 결과 (마지막 답) ----

    @Test
    @DisplayName("[QUIZ-CQR-51, QUIZ-CQR-52] 마지막 문항에 답하면 result 에 correctCount, total, 생성 순서의 문항별 {myOption, answer, correct} 를 싣는다")
    void record_lastAnswer_returnsGradedResultInCreationOrder() {
        stubLockAndRoom();
        stubQuizAndValidOption(q3, 1);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(3L);
        given(answerRepository.findAllByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(List.of(
                answerOf(q3, 1), answerOf(q1, 1), answerOf(q2, 2)));
        given(roomService.optionsByQuiz(ROOM_ID)).willReturn(Map.of(
                11L, List.of(new QuizRoomOptionResponse(0, "a"), new QuizRoomOptionResponse(1, "b")),
                12L, List.of(new QuizRoomOptionResponse(0, "c"), new QuizRoomOptionResponse(1, "d"),
                        new QuizRoomOptionResponse(2, "e")),
                13L, List.of(new QuizRoomOptionResponse(0, "O"), new QuizRoomOptionResponse(1, "X"))));
        given(quizRepository.findAllByRoom_IdOrderByIdAsc(ROOM_ID)).willReturn(List.of(q1, q2, q3));

        AnswerResponse response = recorder.record(USER_ID, ROOM_ID, 13L, 1);

        assertThat(response.answeredCount()).isEqualTo(3);
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.result().total()).isEqualTo(3);
        assertThat(response.result().correctCount()).isEqualTo(2);
        List<ResultQuizResponse> quizzes = response.result().quizzes();
        assertThat(quizzes).extracting(ResultQuizResponse::quizId).containsExactly(11L, 12L, 13L);
        assertThat(quizzes).extracting(ResultQuizResponse::type).containsExactly("객관식", "객관식", "O/X");
        assertThat(quizzes).extracting(ResultQuizResponse::question).containsExactly("문제1", "문제2", "문제3");
        assertThat(quizzes).extracting(ResultQuizResponse::myOption).containsExactly(1, 2, 1);
        assertThat(quizzes).extracting(ResultQuizResponse::answer).containsExactly(0, 2, 1);
        assertThat(quizzes).extracting(ResultQuizResponse::correct).containsExactly(false, true, true);
        assertThat(quizzes.get(0).options()).containsExactly(new QuizRoomOptionResponse(0, "a"),
                new QuizRoomOptionResponse(1, "b"));
        assertThat(quizzes.stream().filter(ResultQuizResponse::correct).count())
                .isEqualTo(response.result().correctCount());
    }

    @Test
    @DisplayName("[QUIZ-CQR-54] 문항을 어떤 순서로 답해도 결과는 마지막 답(개수 기준)의 응답에 실리고 결과 문항 순서는 생성 순서다")
    void record_outOfOrderAnswers_resultAttachedToLastByCount() {
        stubLockAndRoom();
        stubQuizAndValidOption(q2, 2);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(3L);
        given(answerRepository.findAllByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(List.of(
                answerOf(q3, 0), answerOf(q1, 0), answerOf(q2, 2)));
        given(roomService.optionsByQuiz(ROOM_ID)).willReturn(Map.of());
        given(quizRepository.findAllByRoom_IdOrderByIdAsc(ROOM_ID)).willReturn(List.of(q1, q2, q3));

        AnswerResponse response = recorder.record(USER_ID, ROOM_ID, 12L, 2);

        assertThat(response.result()).isNotNull();
        assertThat(response.result().quizzes()).extracting(ResultQuizResponse::quizId).containsExactly(11L, 12L, 13L);
        assertThat(response.result().correctCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[QUIZ-CQR-51] 문항 1개짜리 방은 첫 답이 곧 마지막 답이라 바로 결과가 나온다")
    void record_singleQuizRoom_firstAnswerReturnsResult() {
        QuizRoom single = room(ROOM_ID, author, 1, 0);
        QuizRoomQuiz only = quiz(11L, single, QuizRoomQuizType.OX, "하나", 0);
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(solver));
        given(roomRepository.findById(ROOM_ID)).willReturn(Optional.of(single));
        stubQuizAndValidOption(only, 1);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(1L);
        given(answerRepository.findAllByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(List.of(answerOf(only, 1)));
        given(roomService.optionsByQuiz(ROOM_ID)).willReturn(Map.of());
        given(quizRepository.findAllByRoom_IdOrderByIdAsc(ROOM_ID)).willReturn(List.of(only));

        AnswerResponse response = recorder.record(USER_ID, ROOM_ID, 11L, 1);

        assertThat(response.result().correctCount()).isZero();
        assertThat(response.result().total()).isEqualTo(1);
        assertThat(response.result().quizzes().get(0).correct()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-63] 작성자 본인이 자기 방을 풀어도 거절하지 않고 저장한다")
    void record_authorCanSolveOwnRoom() {
        UserAccount authorSolver = author;
        given(userAccountRepository.findWithLockById(1L)).willReturn(Optional.of(authorSolver));
        given(roomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
        stubQuizAndValidOption(q1, 0);
        given(answerRepository.existsByUserAccount_IdAndQuiz_Id(1L, 11L)).willReturn(false);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(1L, ROOM_ID)).willReturn(1L);

        AnswerResponse response = recorder.record(1L, ROOM_ID, 11L, 0);

        assertThat(response.answeredCount()).isEqualTo(1);
        verify(answerRepository).saveAndFlush(any(QuizRoomAnswer.class));
    }

    // ---- 거절과 검사 순서 ----

    @Test
    @DisplayName("[QUIZ-CQR-56] 없는 방은 QUIZ_ROOM_NOT_FOUND 이고 아무것도 저장하지 않는다")
    void record_roomNotFound() {
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(solver));
        given(roomRepository.findById(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(USER_ID, 999999L, 11L, 0))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_ROOM_NOT_FOUND));
        verify(answerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-57] 그 방의 문항이 아니면(다른 방·오늘의 퀴즈 id) QUIZ_NOT_FOUND")
    void record_quizNotInRoom() {
        stubLockAndRoom();
        given(quizRepository.findByIdAndRoom_Id(99L, ROOM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 99L, 0))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_NOT_FOUND));
        verify(answerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-58] 그 문항에 없는 보기 번호는 QUIZ_OPTION_NOT_FOUND")
    void record_optionNotInQuiz() {
        stubLockAndRoom();
        given(quizRepository.findByIdAndRoom_Id(11L, ROOM_ID)).willReturn(Optional.of(q1));
        given(optionRepository.existsByQuiz_IdAndNo(11L, 3)).willReturn(false);

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, 3))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_OPTION_NOT_FOUND));
        verify(answerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-58] option 이 null(누락)이면 보기 존재 조회 없이 QUIZ_OPTION_NOT_FOUND")
    void record_nullOption() {
        stubLockAndRoom();
        given(quizRepository.findByIdAndRoom_Id(11L, ROOM_ID)).willReturn(Optional.of(q1));

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, null))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_OPTION_NOT_FOUND));
        verify(optionRepository, never()).existsByQuiz_IdAndNo(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("[QUIZ-CQR-59] 이미 답한 문항은 QUIZ_ALREADY_SUBMITTED 이고 저장된 답을 바꾸지 않는다")
    void record_alreadyAnswered() {
        stubLockAndRoom();
        stubQuizAndValidOption(q1, 2);
        given(answerRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, 11L)).willReturn(true);

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, 2))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_ALREADY_SUBMITTED));
        verify(answerRepository, never()).saveAndFlush(any());
        verify(answerRepository, never()).delete(any());
        verify(answerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-60] 이미 답한 문항에 없는 보기 번호를 보내면 409 가 아니라 400 이다 (보기 검사가 중복 검사보다 먼저)")
    void record_alreadyAnsweredWithInvalidOption_isOptionNotFound() {
        stubLockAndRoom();
        given(quizRepository.findByIdAndRoom_Id(11L, ROOM_ID)).willReturn(Optional.of(q1));
        given(optionRepository.existsByQuiz_IdAndNo(11L, 9)).willReturn(false);

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, 9))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_OPTION_NOT_FOUND));
        verify(answerRepository, never()).existsByUserAccount_IdAndQuiz_Id(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-60] 없는 방 + 없는 보기 번호는 404 (방 검사가 보기 검사보다 먼저, 문항·보기는 조회도 안 한다)")
    void record_roomNotFoundBeatsInvalidOption() {
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.of(solver));
        given(roomRepository.findById(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(USER_ID, 999999L, 11L, 99))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_ROOM_NOT_FOUND));
        verifyNoInteractions(quizRepository, optionRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-60] 없는 문항 + 없는 보기 번호는 404 QUIZ_NOT_FOUND (문항 검사가 보기 검사보다 먼저)")
    void record_quizNotFoundBeatsInvalidOption() {
        stubLockAndRoom();
        given(quizRepository.findByIdAndRoom_Id(99L, ROOM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 99L, 99))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_NOT_FOUND));
        verifyNoInteractions(optionRepository);
    }

    // ---- 동시성 / 락 / 격리 ----

    @Test
    @DisplayName("[QUIZ-CQR-61] 계정 행 락(findWithLockById)을 트랜잭션 맨 앞에서, 방·문항 조회와 중복 검사보다 먼저 잡는다")
    void record_acquiresAccountLockFirst() {
        stubLockAndRoom();
        stubQuizAndValidOption(q1, 0);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(1L);

        recorder.record(USER_ID, ROOM_ID, 11L, 0);

        InOrder order = inOrder(userAccountRepository, roomRepository, quizRepository, answerRepository);
        order.verify(userAccountRepository).findWithLockById(USER_ID);
        order.verify(roomRepository).findById(ROOM_ID);
        order.verify(quizRepository).findByIdAndRoom_Id(11L, ROOM_ID);
        order.verify(answerRepository).existsByUserAccount_IdAndQuiz_Id(USER_ID, 11L);
        order.verify(answerRepository).saveAndFlush(any(QuizRoomAnswer.class));
    }

    @Test
    @DisplayName("[QUIZ-CQR-61] 유일 제약(UNIQUE) 위반이 나면 500 이 아니라 409 QUIZ_ALREADY_SUBMITTED 로 번역한다")
    void record_uniqueViolation_translatesTo409() {
        stubLockAndRoom();
        stubQuizAndValidOption(q1, 0);
        given(answerRepository.saveAndFlush(any(QuizRoomAnswer.class)))
                .willThrow(new DataIntegrityViolationException("uk_quiz_room_answers_account_quiz"));

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, 0))
                .satisfies(e -> assertCode(e, ErrorCode.QUIZ_ALREADY_SUBMITTED));
    }

    @Test
    @DisplayName("[QUIZ-CQR-61] 계정이 없으면(락 조회 실패) UNAUTHENTICATED 이고 방 조회까지 가지 않는다")
    void record_unknownAccount_isUnauthenticated() {
        given(userAccountRepository.findWithLockById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(USER_ID, ROOM_ID, 11L, 0))
                .satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-61] record 는 쓰기 트랜잭션이다 (락 + 저장이 한 경계)")
    void record_isWriteTransaction() throws Exception {
        Transactional tx = QuizRoomAnswerRecorder.class
                .getMethod("record", Long.class, long.class, long.class, Integer.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-62, QUIZ-CQR-102] 풀이는 point·bq·quiz_users_submit·quizzes_like·Redis 투표 집계 협력자를 갖지 않는다 (생성자 의존성 고정)")
    void recorder_dependsOnlyOnQuizRoomRepositories() {
        List<String> dependencies = Arrays.stream(QuizRoomAnswerRecorder.class.getDeclaredConstructors()[0]
                .getParameters()).map(Parameter::getType).map(Class::getSimpleName).toList();

        assertThat(dependencies).containsExactlyInAnyOrder("UserAccountRepository", "QuizRoomRepository",
                "QuizRoomQuizRepository", "QuizRoomQuizOptionRepository", "QuizRoomAnswerRepository",
                "QuizRoomService", "Clock");
    }

    @Test
    @DisplayName("[QUIZ-CQR-62] 답을 저장해도 계정 엔티티의 point 는 변하지 않는다")
    void record_doesNotChangeAccountPoint() {
        stubLockAndRoom();
        stubQuizAndValidOption(q1, 0);
        given(answerRepository.countByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(1L);
        long before = solver.getPoint();

        recorder.record(USER_ID, ROOM_ID, 11L, 0);

        assertThat(solver.getPoint()).isEqualTo(before);
    }

    private QuizRoomAnswer answerOf(QuizRoomQuiz quiz, int option) {
        return QuizRoomAnswer.builder().userAccount(solver).room(room).quiz(quiz).optionNo(option).build();
    }
}
