package com.skhynix.domain.quizroom.repository;

/** 문항×보기별 답 수. 답이 0인 보기는 행이 없으므로 호출자가 보기 목록으로 0을 채운다. */
public record QuizRoomVoteCountView(Long quizId, Integer optionNo, Long count) {
}
