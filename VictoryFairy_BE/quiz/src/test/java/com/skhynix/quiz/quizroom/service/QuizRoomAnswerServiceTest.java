package com.skhynix.quiz.quizroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizRoomAnswerServiceTest {

    private static final VotesPayload VOTES = new VotesPayload(11L,
            List.of(new VotesPayload.OptionCount(0, 1), new VotesPayload.OptionCount(1, 2)));

    @Mock
    private QuizRoomAnswerRecorder recorder;
    @Mock
    private QuizRoomVoteReader voteReader;
    @Mock
    private RealtimeEventPublisher eventPublisher;

    @InjectMocks
    private QuizRoomAnswerService service;

    @Test
    @DisplayName("[QUIZ-CQR-90] 답이 커밋되면 갱신된 분포를 quiz-room:{roomId} 토픽에 votes 이벤트로, 제외 대상 없이(제출자 포함) 발행한다")
    void answer_publishesVotesToRoomTopicIncludingSubmitter() {
        AnswerResponse response = new AnswerResponse(1, 3, null);
        given(recorder.record(7L, 5L, 11L, 1)).willReturn(response);
        given(voteReader.readQuiz(11L)).willReturn(VOTES);

        AnswerResponse returned = service.answer(7L, 5L, 11L, 1);

        assertThat(returned).isSameAs(response);
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq("quiz-room:5"), event.capture());
        assertThat(event.getValue().name()).isEqualTo("votes");
        assertThat(event.getValue().data()).isEqualTo(VOTES);
        assertThat(event.getValue().excludeUserAccountId()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-CQR-90] 분포는 커밋(recorder 반환) 이후에 새로 읽는다 - 트랜잭션 안에서 센 값을 쓰지 않는다")
    void answer_readsVotesAfterCommit() {
        given(recorder.record(7L, 5L, 11L, 1)).willReturn(new AnswerResponse(1, 3, null));
        given(voteReader.readQuiz(11L)).willReturn(VOTES);

        service.answer(7L, 5L, 11L, 1);

        InOrder order = inOrder(recorder, voteReader, eventPublisher);
        order.verify(recorder).record(7L, 5L, 11L, 1);
        order.verify(voteReader).readQuiz(11L);
        order.verify(eventPublisher).publish(anyString(), any(RealtimeEvent.class));
    }

    @Test
    @DisplayName("[QUIZ-CQR-94] 400·404·409 로 거절되면 예외가 그대로 전파되고 votes 를 읽지도 발행하지도 않는다")
    void answer_rejected_neverPublishes() {
        for (ErrorCode code : List.of(ErrorCode.QUIZ_ROOM_NOT_FOUND, ErrorCode.QUIZ_NOT_FOUND,
                ErrorCode.QUIZ_OPTION_NOT_FOUND, ErrorCode.QUIZ_ALREADY_SUBMITTED)) {
            willThrow(new BusinessException(code)).given(recorder).record(anyLong(), anyLong(), anyLong(), any());

            assertThatThrownBy(() -> service.answer(7L, 5L, 11L, 1)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(code));
        }

        verifyNoInteractions(voteReader, eventPublisher);
    }

    @Test
    @DisplayName("[QUIZ-CQR-96] 이벤트 발행이 실패해도 답 제출은 정상 응답을 반환한다 (저장은 되돌리지 않는다)")
    void answer_publishFailure_isSwallowed() {
        AnswerResponse response = new AnswerResponse(1, 3, null);
        given(recorder.record(7L, 5L, 11L, 1)).willReturn(response);
        given(voteReader.readQuiz(11L)).willReturn(VOTES);
        willThrow(new IllegalStateException("redis down")).given(eventPublisher).publish(anyString(), any());

        assertThatCode(() -> service.answer(7L, 5L, 11L, 1)).doesNotThrowAnyException();
        assertThat(service.answer(7L, 5L, 11L, 1)).isSameAs(response);
    }

    @Test
    @DisplayName("[QUIZ-CQR-96] 분포 재조회가 실패해도 답 제출은 정상 응답이고 발행은 하지 않는다")
    void answer_voteReadFailure_isSwallowed() {
        AnswerResponse response = new AnswerResponse(3, 3, null);
        given(recorder.record(7L, 5L, 11L, 1)).willReturn(response);
        given(voteReader.readQuiz(11L)).willThrow(new IllegalStateException("db slow"));

        assertThat(service.answer(7L, 5L, 11L, 1)).isSameAs(response);
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-62, QUIZ-CQR-102] 답 제출 파사드는 퀴즈방 recorder·분포 리더·발행기 외에 의존하지 않는다 (point·bq·기존 퀴즈 집계 비접촉)")
    void answerService_hasOnlyQuizRoomCollaborators() {
        assertThat(java.util.Arrays.stream(QuizRoomAnswerService.class.getDeclaredConstructors()[0]
                .getParameterTypes()).map(Class::getSimpleName)).containsExactly("QuizRoomAnswerRecorder",
                "QuizRoomVoteReader", "RealtimeEventPublisher");
    }
}
