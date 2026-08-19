package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.QuizOption;

/**
 * 보기 항목의 공통 표현 — <b>투표 수를 싣지 않는</b> 쪽이다. 단건 상세({@code GET /rt/quizzes/{quizId}})와
 * 이닝별 결산({@code GET /rt/quizzes/submissions})이 함께 쓴다.
 *
 * <p>{@code /today} 의 보기는 이 타입이 아니라 {@link QuizResponse.TodayOptionResponse} 다 — 두 응답의
 * 필드 집합이 서로 다르다는 계약(투표 수는 {@code /today} 에만)을 <b>타입으로</b> 갈라 둔 것이므로,
 * "중복이니 합치자"는 정리는 두 응답을 같은 모양으로 만들어 계약을 깬다.
 */
public record OptionResponse(int no, String text) {

    static OptionResponse from(QuizOption option) {
        return new OptionResponse(option.getOption(), option.getContents());
    }
}
