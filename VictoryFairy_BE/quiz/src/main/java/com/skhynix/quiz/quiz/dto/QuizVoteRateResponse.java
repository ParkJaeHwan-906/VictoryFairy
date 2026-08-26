package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.quiz.quiz.dto.QuizResponse.TodayOptionResponse;
import java.util.List;
import java.util.Map;

/**
 * 아직 답하지 않은 문제의 <b>보기별 투표 수</b>.
 *
 * <p>보기 항목은 {@code /today} 가 쓰는 {@link TodayOptionResponse} 를 <b>그대로 재사용한다</b> —
 * 같은 문제의 같은 값을 두 경로가 내보내는데 모양이 갈리면, 폴링으로 갱신되는 화면이 처음 받은 것과
 * 다른 스키마를 다시 파싱해야 한다. 여기서 백분율로 가공하지 않는 것도 같은 이유다: 분모를 서버가
 * 정하는 순간 {@code /today} 의 개수와 이 응답의 비율이 같은 화면에서 서로 어긋날 수 있다.
 *
 * <p>값의 출처가 RDB 가 아니라 Redis 집계라 <b>근사 스냅샷</b>이고, 못 읽었을 때도 전 보기 0 으로
 * 나간다(응답 스키마는 늘 한 모양).
 */
public record QuizVoteRateResponse(Long quizId, List<TodayOptionResponse> options) {

    /**
     * @param voteCounts 보기 번호(0-based) → 투표 수. <b>빠진 보기는 0 으로 채운다</b> — 이 맵은
     *                   Redis 장애·키 부재·TTL 만료로 부분적일 수 있는데, 없는 자리를 생략해 버리면
     *                   같은 문제인데 요청마다 보기 개수가 달라진다
     */
    public static QuizVoteRateResponse of(Long quizId, List<QuizOption> options,
            Map<Integer, Long> voteCounts) {
        Map<Integer, Long> votes = voteCounts == null ? Map.of() : voteCounts;
        return new QuizVoteRateResponse(quizId, options.stream()
                .map(option -> TodayOptionResponse.from(option, votes))
                .toList());
    }
}
