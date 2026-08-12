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
 * <p>좋아요 두 필드도 같은 방식으로 다룬다 — 좋아요는 <b>내가 푼 문제에만</b> 가능하므로 미제출 상세에
 * 실리면 누를 수 없는 버튼의 재료만 내려보내는 셈이다. 그래서 {@code boolean}/{@code long}이 아니라
 * 래퍼 타입이다(원시 타입이면 {@code false}/{@code 0}이 강제로 실려 키를 내릴 수가 없다).
 *
 * @param submitted <b>내가 답을 냈는지</b>. ⚠ 행의 존재가 아니라 <b>답의 존재</b>가 기준이다 —
 *     행은 {@code /today}로 받는 순간 생기므로 행 유무로 판정하면 아직 안 푼 문제에 정답이 실린다.
 *     아래 세 필드의 유무와 논리적으로 동치지만, FE 가 "미제출"을 키 부재 검사로 판정하게 하지
 *     않으려고 명시 플래그로 둔다
 * @param expired 받아 놓고 <b>시한(받은 시각 + 8분)을 넘긴</b> 상태인지. 답한 문제는 항상 false 이고,
 *     받은 적 없는 문제도 false 다(둘의 구분은 이 응답의 몫이 아니다). {@code (submitted, expired)}
 *     조합이 곧 화면 상태다: {@code (false,false)}=진행 중 · {@code (true,*)}=답함 ·
 *     {@code (false,true)}=시한 초과(제출하면 403). 저장된 플래그가 아니라 <b>조회 시각 기준 계산</b>이라
 *     같은 문제가 8분 전후로 다르게 나온다
 * @param myOption 내가 고른 보기 번호(제출 시에만)
 * @param correct 내 제출의 정오(제출 시에만) — 제출 시점 확정값({@code QuizUserSubmit.isAnswer})
 * @param answer 정답 보기 번호(제출 시에만)
 * @param liked 내 현재 좋아요 상태(제출 시에만)
 * @param likeCount 그 문제의 좋아요 수(제출 시에만) — 취소된 좋아요는 세지 않는다
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
        boolean expired,
        Integer myOption,
        Boolean correct,
        Integer answer,
        Boolean liked,
        Long likeCount) {

    /**
     * 아직 답이 없는 상태 — <b>받은 적 없음과 진행 중과 시한 초과를 모두 담는다.</b> 앞의 둘은
     * {@code expired = false} 로 같은 모양이 된다(구분이 필요한 쪽은 {@code /today} 목록이다).
     */
    public static QuizDetailResponse unsubmitted(Quiz quiz, List<QuizOption> options,
            boolean expired) {
        return of(quiz, options, false, expired, null, null, null, null, null);
    }

    /** 답을 낸 상태. 시한은 이미 소진됐고 되돌아갈 수 없으므로 {@code expired} 는 항상 false 다. */
    public static QuizDetailResponse submitted(Quiz quiz, List<QuizOption> options,
            QuizUserSubmit submit, QuizLikeResponse like) {
        return of(quiz, options, true, false,
                submit.getSubmitOption().getOption(), submit.isAnswer(), quiz.getAnswer(),
                like.liked(), like.likeCount());
    }

    private static QuizDetailResponse of(Quiz quiz, List<QuizOption> options, boolean submitted,
            boolean expired, Integer myOption, Boolean correct, Integer answer, Boolean liked,
            Long likeCount) {
        return new QuizDetailResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getScore(),
                quiz.getQuizDate(),
                options.stream().map(QuizResponse.OptionResponse::from).toList(),
                submitted,
                expired,
                myOption,
                correct,
                answer,
                liked,
                likeCount);
    }
}
