package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 풀이 이력 한 건. 이미 제출한 문제이므로 정답(번호·텍스트)을 공개한다 — 감춰야 하는 쪽은 제출 전
 * 조회({@code QuizResponse})다.
 *
 * <p>{@code correct}는 {@code QuizUserSubmit.isAnswer}(제출 시점 확정 판정)를 그대로 싣는다 —
 * {@code myOption == answer}로 재계산하지 않는다. 정답이 사후 정정되면 둘이 어긋날 수 있는데, 그때
 * 보존해야 하는 것은 포인트를 준 근거인 당시 판정이다(엔티티 javadoc 의 규칙을 응답까지 관철).
 * 같은 이유로 {@code earnedPoint}도 그 판정과 현재 배점으로 계산한 표시용 값이다 — 적립 원장이
 * 따로 없어 배점이 사후 수정되면 실제 적립액과 다를 수 있음을 감수한 근사치다.
 *
 * @param quizDate   출제일. 미편성 문제는 제출 자체가 안 되므로(404) 이력에선 사실상 항상 값이 있다
 * @param answerText 정답 보기 텍스트. 보기 행이 삭제·교체돼 번호가 실재하지 않으면 null 일 수 있다
 */
public record QuizSubmissionItemResponse(
        Long quizId,
        String question,
        String type,
        String difficulty,
        LocalDate quizDate,
        int myOption,
        String myOptionText,
        boolean correct,
        int answer,
        String answerText,
        long earnedPoint,
        LocalDateTime submittedAt) {

    /**
     * {@code submit}은 {@code quiz}·{@code quiz.quizType}·{@code submitOption}이 fetch join 으로 로딩된
     * 상태여야 한다({@code findHistoryByUserAccountId}) — 아니면 여기 접근이 N+1 이거나 트랜잭션 밖
     * {@code LazyInitializationException}이다.
     */
    public static QuizSubmissionItemResponse from(QuizUserSubmit submit, String answerText) {
        Quiz quiz = submit.getQuiz();
        long earnedPoint = submit.isAnswer() && quiz.getScore() != null
                ? Math.round(quiz.getScore())
                : 0L;
        return new QuizSubmissionItemResponse(
                quiz.getId(),
                quiz.getContent(),
                quiz.getQuizType().getName(),
                quiz.getDifficulty(),
                quiz.getQuizDate(),
                submit.getSubmitOption().getOption(),
                submit.getSubmitOption().getContents(),
                submit.isAnswer(),
                quiz.getAnswer(),
                answerText,
                earnedPoint,
                submit.getCreatedAt());
    }
}
