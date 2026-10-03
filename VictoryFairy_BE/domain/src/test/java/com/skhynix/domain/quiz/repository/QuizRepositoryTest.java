package com.skhynix.domain.quiz.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link QuizRepository#publishFromPool}의 네이티브 SQL 텍스트를 리플렉션으로 고정하는 회귀 가드.
 *
 * <p><b>DB 라운드트립이 아니다.</b> {@code domain} 모듈은 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers 어느 것도 갖고 있지 않고 로컬 MySQL 컨테이너도 떠 있지 않다
 * ({@code StadiumTest}·{@code QuizTest} javadoc과 같은 사정) — 그래서 "미정산 PREDICTION이 실제로
 * UPDATE 에서 빠지는지"는 이 테스트로 검증되지 않는다. 대신 {@code @Query} 애너테이션의 SQL 문자열이
 * 가드 조건({@code settlement_metric IS NULL OR answer IS NOT NULL})을 여전히 포함하는지만 고정한다
 * — 누군가 쿼리를 고치다 그 조건을 빠뜨리면 이 테스트가 바로 깨진다.
 */
class QuizRepositoryTest {

    @Test
    @DisplayName("publishFromPool의 네이티브 쿼리는 quiz_date IS NULL 외에도 "
            + "미정산 PREDICTION(answer가 아직 없는 settlement_metric 행)을 걸러내는 조건을 포함한다")
    void publishFromPool_nativeQuery_guardsUnsettledPredictionOutOfPool() throws NoSuchMethodException {
        Method publishFromPool = QuizRepository.class.getMethod("publishFromPool",
                java.time.LocalDate.class, int.class);
        Query query = publishFromPool.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("quiz_date IS NULL")
                .contains("settlement_metric IS NULL")
                .contains("answer IS NOT NULL");
    }
}
