package com.skhynix.quiz.quizroom.dto;

import java.util.List;

/** SSE {@code votes} 이벤트 본문. 보기는 0표까지 전부, 번호 오름차순. */
public record VotesPayload(Long quizId, List<OptionCount> options) {

    public record OptionCount(int no, long count) {
    }
}
