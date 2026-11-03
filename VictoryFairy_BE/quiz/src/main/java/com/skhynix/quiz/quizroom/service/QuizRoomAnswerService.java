package com.skhynix.quiz.quizroom.service;

import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 답 제출 파사드. 트랜잭션은 {@link QuizRoomAnswerRecorder} 가 열고 닫는다 — 이 클래스가 트랜잭션을 열면 분포를
 * 커밋 전에 읽게 된다(그 이유는 Recorder javadoc). 두 클래스를 합치지 말 것.
 *
 * <p>분포 이벤트는 제출자를 포함한 그 방의 모든 구독자에게 간다(아직 안 답한 구독자 포함 — 결정 7). 전달 실패는 삼킨다:
 * 답은 이미 커밋됐고 응답도 그대로 나간다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizRoomAnswerService {

    public static final String VOTES_EVENT = "votes";

    private final QuizRoomAnswerRecorder recorder;
    private final QuizRoomVoteReader voteReader;
    private final RealtimeEventPublisher eventPublisher;

    public AnswerResponse answer(Long userAccountId, long roomId, long quizId, Integer optionNo) {
        // 거절(400·404·409)은 여기서 예외로 빠져 아래 발행에 닿지 않는다
        AnswerResponse response = recorder.record(userAccountId, roomId, quizId, optionNo);
        try {
            VotesPayload votes = voteReader.readQuiz(quizId);
            eventPublisher.publish(QuizRoomSseRegistry.topic(roomId), new RealtimeEvent(VOTES_EVENT, votes, null));
        } catch (Exception e) {
            log.warn("퀴즈방 투표 분포 발행 실패 roomId={} quizId={}", roomId, quizId, e);
        }
        return response;
    }
}
