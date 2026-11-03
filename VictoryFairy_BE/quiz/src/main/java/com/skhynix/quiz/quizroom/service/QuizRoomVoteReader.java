package com.skhynix.quiz.quizroom.service;

import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomVoteCountView;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표 분포를 DB 에 저장된 답에서 만든다. Redis 집계({@code quiz:votes:*})를 쓰지 않는다 — 그건 오늘의 퀴즈 전용이고,
 * 퀴즈방 풀이가 그 키를 만들거나 바꾸면 안 된다. 보기는 0표까지 전부 번호 오름차순으로 채운다.
 */
@Component
@RequiredArgsConstructor
class QuizRoomVoteReader {

    private final QuizRoomQuizOptionRepository optionRepository;
    private final QuizRoomAnswerRepository answerRepository;

    /** 방의 문항마다 하나씩, 문항 생성 순서대로. 조회 2건(보기·집계)으로 문항 수와 무관하다. */
    @Transactional(readOnly = true)
    public List<VotesPayload> readRoom(long roomId) {
        List<QuizRoomQuizOption> options = optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(roomId);
        Map<Long, Map<Integer, Long>> counts = group(answerRepository.countVotesByRoomId(roomId));
        Map<Long, List<VotesPayload.OptionCount>> byQuiz = new LinkedHashMap<>();
        for (QuizRoomQuizOption option : options) {
            Long quizId = option.getQuiz().getId();
            long count = counts.getOrDefault(quizId, Map.of()).getOrDefault(option.getNo(), 0L);
            byQuiz.computeIfAbsent(quizId, key -> new ArrayList<>())
                    .add(new VotesPayload.OptionCount(option.getNo(), count));
        }
        List<VotesPayload> result = new ArrayList<>(byQuiz.size());
        byQuiz.forEach((quizId, optionCounts) -> result.add(new VotesPayload(quizId, optionCounts)));
        return result;
    }

    @Transactional(readOnly = true)
    public VotesPayload readQuiz(long quizId) {
        Map<Integer, Long> counts = group(answerRepository.countVotesByQuizId(quizId))
                .getOrDefault(quizId, Map.of());
        List<VotesPayload.OptionCount> optionCounts = optionRepository.findAllByQuiz_IdOrderByNoAsc(quizId).stream()
                .map(option -> new VotesPayload.OptionCount(option.getNo(),
                        counts.getOrDefault(option.getNo(), 0L)))
                .toList();
        return new VotesPayload(quizId, optionCounts);
    }

    private static Map<Long, Map<Integer, Long>> group(List<QuizRoomVoteCountView> rows) {
        Map<Long, Map<Integer, Long>> grouped = new HashMap<>();
        for (QuizRoomVoteCountView row : rows) {
            grouped.computeIfAbsent(row.quizId(), key -> new HashMap<>()).put(row.optionNo(), row.count());
        }
        return grouped;
    }
}
