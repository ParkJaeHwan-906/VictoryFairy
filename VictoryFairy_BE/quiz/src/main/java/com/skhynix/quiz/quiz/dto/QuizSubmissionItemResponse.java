package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import com.skhynix.quiz.quiz.dto.QuizResponse.OptionResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결산 목록의 문제 한 건. 이미 <b>받은</b> 문제이므로 정답(번호)을 공개한다 — 감춰야 하는 쪽은 제출 전
 * 조회({@code QuizResponse})다.
 *
 * <p>⚠ <b>항목이 곧 "답한 문제"는 아니다.</b> 행이 출제 시점에 생기므로 이 목록에는 답 없는 항목
 * (진행 중이거나 시한 초과)도 실린다 — 감출 수 없다: 이닝 요약의 {@code total}이 그 행을 세는데 목록에서
 * 빼면 총건수와 항목 수가 어긋난다. 그래서 {@code myOption}이 null 일 수 있고({@code int}가 아니라
 * {@code Integer}), {@code expired}가 그 상태를 구분한다.
 *
 * <p>{@code correct}는 {@code QuizUserSubmit.isAnswer}(제출 시점 확정 판정)를 그대로 싣는다 —
 * {@code myOption == answer}로 재계산하지 않는다. 정답이 사후 정정되면 둘이 어긋날 수 있는데, 그때
 * 보존해야 하는 것은 포인트를 준 근거인 당시 판정이다(엔티티 javadoc 의 규칙을 응답까지 관철).
 * 같은 이유로 {@code earnedPoint}도 그 판정과 현재 배점으로 계산한 표시용 값이다 — 적립 원장이
 * 따로 없어 배점이 사후 수정되면 실제 적립액과 다를 수 있음을 감수한 근사치다.
 *
 * <p>좋아요 두 필드는 상세({@link QuizDetailResponse})와 달리 <b>항상 실린다</b>(원시 타입) — 좋아요가
 * 받은 문제 전체에 허용되므로 이 화면엔 좋아요 불가 항목이 없다.
 *
 * @param options 그 문제의 보기 전체(번호 오름차순). {@code /today} 와 <b>같은 모양·같은 의미</b>가
 *     되도록 {@link OptionResponse} 를 그대로 재사용한다. 이 배열이 생기면서 종전의
 *     {@code myOptionText}·{@code answerText} 는 삭제됐다 — 클라이언트가 번호로 찾을 수 있는 문자열을
 *     응답에 두 번 싣지 않는다. {@code quizDate} 도 함께 삭제됐다(경기 축 조회에서 출제일은 의미가 없다)
 * @param myOption   내가 고른 보기 번호. <b>답하지 않은 항목이면 null</b>
 * @param expired    답 없이 시한(받은 시각 + 8분)을 넘겼는지. 답한 항목은 항상 false 다
 * @param submittedAt 답을 낸 시각 = 행의 {@code updated_at}. ⚠ <b>{@code created_at} 이 아니다</b> —
 *     행이 출제 시점에 생기므로 {@code created_at} 은 "받은 시각"이고, 그대로 실으면 이 필드가 최대
 *     8분 어긋난다. 답 없는 항목은 낸 시각 자체가 없어 받은 시각이 그대로 남는다(출제 INSERT 가 두
 *     값을 같은 시각으로 찍는다)
 * @param liked      내 현재 좋아요 상태
 * @param likeCount  그 문제의 좋아요 수(취소된 좋아요는 세지 않는다)
 */
public record QuizSubmissionItemResponse(
        Long quizId,
        String question,
        String type,
        String difficulty,
        List<OptionResponse> options,
        Integer myOption,
        boolean correct,
        boolean expired,
        int answer,
        long earnedPoint,
        LocalDateTime submittedAt,
        boolean liked,
        long likeCount) {

    /**
     * {@code submit}은 {@code quiz}·{@code quiz.quizType}·{@code submitOption}이 fetch join 으로 로딩된
     * 상태여야 한다({@code findGameSubmissions}) — 아니면 여기 접근이 N+1 이거나 트랜잭션 밖
     * {@code LazyInitializationException}이다. {@code submitOption}만 <b>left</b> join 이라 null 일 수 있다.
     *
     * <p>{@code options}·{@code like}는 <b>미리 모아둔 값</b>을 받는다 — 여기서 조회하면 항목 수만큼
     * 쿼리가 는다({@code quiz_id IN (...)} 2쿼리 방식이 계약이다). {@code expired}도 마찬가지로 호출부가
     * 계산해 넘긴다(DTO 가 시계를 읽지 않는다).
     */
    public static QuizSubmissionItemResponse from(QuizUserSubmit submit, List<QuizOption> options,
            QuizLikeResponse like, boolean expired) {
        Quiz quiz = submit.getQuiz();
        // 답이 없으면 isAnswer 는 "아직 채점 안 됨"의 false 라 적립도 0 이다 — 오답과 같은 표시가 된다.
        // score 는 nullable(사람이 쓴 퀴즈)이라 없으면 0 으로 센다(예외로 만들지 않는다).
        long earnedPoint = submit.isAnswer() && quiz.getScore() != null
                ? Math.round(quiz.getScore())
                : 0L;
        QuizOption myOption = submit.getSubmitOption();
        return new QuizSubmissionItemResponse(
                quiz.getId(),
                quiz.getContent(),
                quiz.getQuizType().getName(),
                quiz.getDifficulty(),
                options.stream().map(OptionResponse::from).toList(),
                myOption == null ? null : myOption.getOption(),
                submit.isAnswer(),
                expired,
                quiz.getAnswer(),
                earnedPoint,
                submit.getUpdatedAt(),
                like.liked(),
                like.likeCount());
    }
}
