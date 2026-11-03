package com.skhynix.domain.quizroom.repository;

/** 방별 건수. 0건인 방은 행이 없다. */
public record QuizRoomCountView(Long roomId, Long count) {
}
