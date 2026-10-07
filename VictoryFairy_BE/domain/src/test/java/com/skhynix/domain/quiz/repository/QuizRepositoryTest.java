package com.skhynix.domain.quiz.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link QuizRepository}의 편성 쿼터 네이티브 SQL 텍스트를 리플렉션으로 고정하는 회귀 가드.
 *
 * <p><b>DB 라운드트립이 아니다.</b> {@code domain} 모듈은 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers 어느 것도 갖고 있지 않고 로컬 MySQL 컨테이너도 떠 있지 않다
 * ({@code StadiumTest}·{@code QuizTest} javadoc과 같은 사정) — 그래서 "맞대결 문항이 양팀 쿼터
 * 호출 모두의 대상이면서도 실제 UPDATE는 1회만 먹는지"는 이 테스트로 직접 재현되지 않는다. 대신
 * {@code @Query} 애너테이션의 SQL 문자열이 그 멱등성·가드 조건을 여전히 포함하는지만 고정한다 —
 * 누군가 쿼리를 고치다 조건을 빠뜨리면 이 테스트가 바로 깨진다.
 */
class QuizRepositoryTest {

    @Test
    @DisplayName("publishCommonFromPool의 네이티브 쿼리는 quiz_date IS NULL, team_id IS NULL, "
            + "미정산 PREDICTION 배제 조건을 모두 포함한다(팀 쿼터 풀과 섞이지 않는다)")
    void publishCommonFromPool_nativeQuery_isScopedToTeamlessPool() throws NoSuchMethodException {
        Method method = QuizRepository.class.getMethod("publishCommonFromPool",
                java.time.LocalDate.class, int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("quiz_date IS NULL")
                .contains("team_id IS NULL")
                .contains("settlement_metric IS NULL")
                .contains("answer IS NOT NULL");
    }

    @Test
    @DisplayName("publishFromPoolForTeam의 네이티브 쿼리는 team_id = :teamId OR opponent_team_id = "
            + ":teamId 조건을 포함한다 — 맞대결 문항이 양팀 쿼터 호출 모두의 대상이 된다")
    void publishFromPoolForTeam_nativeQuery_matchesBothTeamAndOpponentTeam()
            throws NoSuchMethodException {
        Method method = QuizRepository.class.getMethod("publishFromPoolForTeam",
                java.time.LocalDate.class, Long.class, int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("team_id = :teamId")
                .contains("opponent_team_id = :teamId");
    }

    @Test
    @DisplayName("publishFromPoolForTeam의 네이티브 쿼리는 quiz_date IS NULL 가드를 포함한다 — "
            + "맞대결 문항이 한 팀 호출에서 스탬프된 뒤에는 다른 팀 호출의 대상에서 자연히 빠져 "
            + "실제 quiz_date 갱신이 1회로 제한된다(멱등성의 근거)")
    void publishFromPoolForTeam_nativeQuery_guardsAlreadyPublishedRowsForIdempotency()
            throws NoSuchMethodException {
        Method method = QuizRepository.class.getMethod("publishFromPoolForTeam",
                java.time.LocalDate.class, Long.class, int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .contains("quiz_date IS NULL")
                .contains("settlement_metric IS NULL")
                .contains("answer IS NOT NULL");
    }
}
