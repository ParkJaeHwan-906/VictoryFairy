package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import java.util.List;
import java.util.Map;

public record QuizResponse(
        Long id,
        String type,
        String question,
        String difficulty,
        Double point,
        boolean preferred,
        List<TodayOptionResponse> options) {

    /**
     * {@code /today} 전용 보기 항목 — 상세·결산이 쓰는 {@link OptionResponse} 에 투표 수가 붙은 형태다.
     *
     * <p>{@code voteCount} 는 <b>서빙 시점의 근사 스냅샷</b>이고 항상 실린다(0 이어도 생략하지 않는다).
     * 값을 못 읽은 경우(Redis 장애·키 부재·TTL 만료·값 파싱 실패)도 0 이라, 응답만으로는
     * "아무도 안 골랐다"와 "못 읽었다"를 구별할 수 없다 — 알고 택한 제약이고 관측은 WARN 로그로만 한다.
     */
    public record TodayOptionResponse(int no, String text, long voteCount) {

        /**
         * @param voteCounts 보기 번호(0-based) → 투표 수. <b>{@code no} 와 같은 0-based 축</b>이라
         *                   여기서 {@code +1}/{@code -1} 을 하면 전 보기가 한 칸씩 밀린 채 200 으로 나간다.
         */
        static TodayOptionResponse from(QuizOption option, Map<Integer, Long> voteCounts) {
            int no = option.getOption();
            return new TodayOptionResponse(no, option.getContents(),
                    voteCounts.getOrDefault(no, 0L));
        }
    }

    public static QuizResponse of(Quiz quiz, List<QuizOption> options, boolean preferred,
            Map<Integer, Long> voteCounts) {
        Map<Integer, Long> votes = voteCounts == null ? Map.of() : voteCounts;
        return new QuizResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getScore(),
                preferred,
                options.stream().map(option -> TodayOptionResponse.from(option, votes)).toList());
    }
}
