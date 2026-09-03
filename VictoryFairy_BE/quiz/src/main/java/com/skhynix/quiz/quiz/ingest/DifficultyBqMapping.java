package com.skhynix.quiz.quiz.ingest;

import java.util.Map;

/**
 * 난이도 → bq 배점 매핑표. <b>앱의 유일한 정의</b>이며, 같은 규칙의 다른 사본은 마이그레이션 백필
 * ({@code infra/sql/migrate-quiz-point-bq.sql} 의 CASE) 하나뿐이다 — SQL 이 이 코드를 부를 수 없어
 * 어쩔 수 없이 둘인 것이고, 값이 갈리면 <b>같은 난이도의 문제가 적재 경로냐 백필이냐에 따라 다른
 * 배점을 갖는다</b>(조용히 갈리는 회귀라 한쪽을 고치면 반드시 다른 쪽도 고칠 것).
 *
 * <p>표에 없는 난이도·null 은 <b>null 을 돌려준다</b>(예외 아님) — 사람이 쓴 퀴즈나 파이프라인이
 * 새 난이도를 먼저 내보내는 경우가 있고, 그때 적재를 실패시키면 배점 하나 때문에 문제 전체를
 * 잃는다. 배점 없는 문제는 적립 0 으로 정상 동작한다.
 *
 * <p>치역은 {@code {1,2,3,4}} 다. <b>5 는 예약값</b>이라 어떤 난이도에도 부여하지 않는다 — 상위
 * 난이도가 신설되면 그때 이 표에 들어온다.
 */
final class DifficultyBqMapping {

    private static final Map<String, Integer> BQ_BY_DIFFICULTY = Map.of(
            "EASY", 1,
            "MEDIUM", 2,
            "HARD", 3,
            "EXPERT", 4);

    private DifficultyBqMapping() {
    }

    static Integer bqOf(String difficulty) {
        return difficulty == null ? null : BQ_BY_DIFFICULTY.get(difficulty);
    }
}
