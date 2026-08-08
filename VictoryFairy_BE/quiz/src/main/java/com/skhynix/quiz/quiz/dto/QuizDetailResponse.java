package com.skhynix.quiz.quiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDate;
import java.util.List;

/**
 * 퀴즈 단건 상세. 목록({@link QuizResponse})과 달리 <b>제출 뒤에는 정답을 싣는다</b> — 복기
 * 화면은 정답 없이는 성립하지 않고, 제출이 끝난 사용자에게 정답은 더 이상 비밀이 아니다.
 *
 * <p><b>{@code NON_NULL} — 미제출 응답에는 {@code myOption}/{@code correct}/{@code answer} 키
 * 자체가 없다.</b> {@code answer: null}로라도 키가 실리면 "제출하면 여기에 정답이 온다"는 형태가
 * 드러나고, 직렬화 설정이 바뀌는 순간 값까지 새는 한 줄 사고와의 거리가 가까워진다. 문자열
 * {@code "answer"} 부재는 컨트롤러 테스트가 본문 전체 검색으로 고정한다.
 *
 * @param submitted 내 제출 존재 여부. 아래 세 필드의 유무와 논리적으로 동치지만, FE 가 "미제출"을
 *     키 부재 검사로 판정하게 하지 않으려고 명시 플래그로 둔다
 * @param myOption 내가 고른 보기 번호(제출 시에만)
 * @param correct 내 제출의 정오(제출 시에만) — 제출 시점 확정값({@code QuizUserSubmit.isAnswer})
 * @param answer 정답 보기 번호(제출 시에만)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuizDetailResponse(
        Long id,
        String type,
        String question,
        String difficulty,
        Double point,
        LocalDate quizDate,
        List<QuizResponse.OptionResponse> options,
        boolean submitted,
        Integer myOption,
        Boolean correct,
        Integer answer) {

    public static QuizDetailResponse unsubmitted(Quiz quiz, List<QuizOption> options) {
        return of(quiz, options, false, null, null, null);
    }

    public static QuizDetailResponse submitted(Quiz quiz, List<QuizOption> options,
            QuizUserSubmit submit) {
        return of(quiz, options, true,
                submit.getSubmitOption().getOption(), submit.isAnswer(), quiz.getAnswer());
    }

    private static QuizDetailResponse of(Quiz quiz, List<QuizOption> options, boolean submitted,
            Integer myOption, Boolean correct, Integer answer) {
        return new QuizDetailResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getScore(),
                quiz.getQuizDate(),
                options.stream().map(QuizResponse.OptionResponse::from).toList(),
                submitted,
                myOption,
                correct,
                answer);
    }
}
