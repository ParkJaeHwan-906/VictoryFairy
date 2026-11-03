package com.skhynix.domain.quizroom.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 퀴즈방 문항 유형. ORDINAL 저장이라 <b>선언 순서를 바꾸지 말 것</b>.
 *
 * <p>코드 테이블 {@code quiz_type} 을 FK 로 쓰지 않는 이유: 그 행은 user 앱 시드가 채우고 quiz 앱은 시드를 넣지
 * 않는다. 닫힌 두 값이라 enum 으로 두면 시드 순서에 묶이지 않는다. 표기 문자열은 기존 퀴즈 응답과 같다.
 */
public enum QuizRoomQuizType {

    MULTIPLE_CHOICE("객관식"),
    OX("O/X");

    private final String label;

    QuizRoomQuizType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<QuizRoomQuizType> fromLabel(String label) {
        return Arrays.stream(values()).filter(type -> type.label.equals(label)).findFirst();
    }
}
