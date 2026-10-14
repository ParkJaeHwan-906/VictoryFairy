package com.skhynix.quiz.quizroom.dto;

import java.util.List;

/** 풀이용 문항. 정답·분포 키가 없는 것이 계약이다 — 결과 전용 타입({@link ResultQuizResponse})과 합치지 말 것. */
public record RoomQuizResponse(Long quizId, String type, String question, List<QuizRoomOptionResponse> options) {
}
