package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import java.util.List;

/**
 * 오늘의 퀴즈 한 건. <b>정답({@code Quiz.answer})은 싣지 않는다</b> — 응답에 실리는 순간 클라이언트
 * 개발자 도구로 바로 보이므로, 채점은 제출 API(후속 작업)가 서버에서 한다. 같은 이유로 근거
 * (evidence)·정답률 같은 사후 정보도 없다.
 *
 * @param type 유형명({@code 객관식} | {@code O/X}) — FE 렌더링 분기(선택지 목록 vs O/X 토글)용
 * @param point 배점. AI 산출물이 아닌 퀴즈(사람 작성)는 null 일 수 있다
 * @param difficulty EASY/MEDIUM/HARD/EXPERT. 마찬가지로 null 가능
 */
public record QuizResponse(
        Long id,
        String type,
        String question,
        String difficulty,
        Double point,
        List<OptionResponse> options) {

    /** 보기 하나. {@code no}는 표기 순서이자 제출 시 보낼 번호(0-기반, O/X 는 0=O·1=X). */
    public record OptionResponse(int no, String text) {

        static OptionResponse from(QuizOption option) {
            return new OptionResponse(option.getOption(), option.getContents());
        }
    }

    public static QuizResponse of(Quiz quiz, List<QuizOption> options) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getScore(),
                options.stream().map(OptionResponse::from).toList());
    }
}
