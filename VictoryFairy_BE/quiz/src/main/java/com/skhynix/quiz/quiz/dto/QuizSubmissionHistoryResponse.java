package com.skhynix.quiz.quiz.dto;

import java.util.List;

/**
 * <b>한 경기</b>의 이닝별 풀이 결산 = 전체 요약 + 이닝 배열. 조회 단위가 계정 전체가 아니라 경기
 * 하나이고, 그 안의 순서 축이 페이지 번호가 아니라 이닝이다("경기 하나를 관전하며 이닝마다 문제를
 * 푼다"가 제품의 사용 단위라 읽는 축을 쓰는 축에 맞춘 것).
 *
 * <p><b>{@code summary} 는 이닝 원소들의 합계에서 유도한다</b>({@link #of}) — 별도 count 쿼리로 구하지
 * 않는 이유는 그러면 열거 범위 밖(진행 중인 현재 이닝 등)의 행이 요약에만 잡혀 <b>요약과 목록이
 * 어긋나기</b> 때문이다. 합계로 접으면 그 어긋남이 구조적으로 불가능해진다.
 *
 * <p>{@code innings} 가 비는 경우는 셋이고 <b>응답만으로는 구별되지 않는다</b>: ① 그 경기에 내 기록이
 * 0건 ② 열거에 쓸 이닝 값이 NULL ③ 1회 진행 중이라 결산할 이닝이 아직 없음. 지금은 화면 표시가
 * 같아도 무방하다고 보고 사유 필드를 두지 않는다(좋아요의 은닉과 달리 감출 것이 있어서가 아니라
 * 필요가 아직 없어서다 — 나중에 구분해도 정책이 깨지지 않는다).
 */
public record QuizSubmissionHistoryResponse(Summary summary, List<InningResponse> innings) {

    /**
     * @param accuracy {@code correctCount/total}(0~1). 0건이면 0.0 — NaN 을 JSON 에 실을 수 없어
     *     "정답률 없음"을 0 으로 접는다
     * @param earnedPoint 정답 행의 배점 합. <b>적립 원장이 아니라 표시용 근사치다</b> —
     *     항목 {@code earnedPoint} 와 같은 성질이라 배점이 사후 수정되면 실제 적립액과 어긋날 수 있다
     *     ({@code users_account.point} 를 읽지 않는다)
     */
    public record Summary(long correctCount, long total, double accuracy, long earnedPoint) {
    }

    /** 이닝별 요약. 전체 요약과 같은 산식이되 {@code earnedPoint} 는 담지 않는다(계약 필드 3개). */
    public record InningSummary(long correctCount, long total, double accuracy) {
    }

    /**
     * 이닝 하나. <b>받은 문제가 없는 이닝도 원소로 남는다</b>({@code quizzes: []} + {@code 0/0}) —
     * 배열 길이가 곧 열거 이닝 수여야 FE 가 1~N 슬롯을 고정 축으로 그릴 수 있다. "빈 이닝만 골라
     * 빼는" 것은 채택되지 않았다(빠지는 것은 경기 전체가 0건일 때 배열이 통째로 접히는 경우뿐).
     */
    public record InningResponse(int inning, InningSummary summary,
            List<QuizSubmissionItemResponse> quizzes) {

        public static InningResponse of(int inning, List<QuizSubmissionItemResponse> quizzes) {
            long correctCount = quizzes.stream().filter(QuizSubmissionItemResponse::correct).count();
            return new InningResponse(inning,
                    new InningSummary(correctCount, quizzes.size(),
                            accuracy(correctCount, quizzes.size())),
                    quizzes);
        }
    }

    /**
     * 이닝 원소들만으로 전체 요약까지 만든다 — 호출부가 두 층을 각각 세다가 어긋나게 할 여지를 없앤다.
     * {@code earnedPoint} 를 항목 값의 합으로 구하는 것도 같은 이유다(항목마다 {@code Math.round} 한
     * 값을 더하므로 화면의 항목 합과 총합이 정확히 일치한다 — 합계를 반올림하면 어긋난다).
     */
    public static QuizSubmissionHistoryResponse of(List<InningResponse> innings) {
        long total = innings.stream().mapToLong(inning -> inning.summary().total()).sum();
        long correctCount = innings.stream()
                .mapToLong(inning -> inning.summary().correctCount()).sum();
        long earnedPoint = innings.stream()
                .flatMap(inning -> inning.quizzes().stream())
                .mapToLong(QuizSubmissionItemResponse::earnedPoint)
                .sum();
        return new QuizSubmissionHistoryResponse(
                new Summary(correctCount, total, accuracy(correctCount, total), earnedPoint),
                innings);
    }

    private static double accuracy(long correctCount, long total) {
        return total == 0 ? 0.0 : (double) correctCount / total;
    }
}
