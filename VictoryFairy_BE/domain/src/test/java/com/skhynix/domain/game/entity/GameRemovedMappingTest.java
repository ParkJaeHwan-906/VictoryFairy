package com.skhynix.domain.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.game.repository.GameRepository;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link Game#isRemoved()} 매핑과, 사용자 조회가 사라진 경기를 거르는지 본다. domain 모듈엔 DB 가 없어
 * 쿼리를 실행하지 못하므로 선언(애노테이션·JPQL)만 검증한다.
 */
class GameRemovedMappingTest {

    @Test
    @DisplayName("removed 는 NOT NULL DEFAULT 0 컬럼이고 새 Game 은 false 다")
    void removed_mapsToNotNullDefaultZero() throws Exception {
        Field field = Game.class.getDeclaredField("removed");
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(boolean.class);
        assertThat(column.name()).isEqualTo("removed");
        assertThat(column.nullable()).isFalse();
        assertThat(column.columnDefinition()).contains("DEFAULT 0");
        assertThat(Game.builder().build().isRemoved()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc",
            "findAllByTeamAndGameDateRange",
            "findActiveByNaverGameId",
            "findWithStatusByNaverGameId",
            "findWithDetailsByNaverGameId"})
    @DisplayName("사용자에게 경기를 내주는 조회는 removed = false 조건을 건다")
    void userFacingQueries_excludeRemoved(String methodName) {
        Method method = Arrays.stream(GameRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst().orElseThrow();

        assertThat(method.getAnnotation(Query.class)).isNotNull();
        assertThat(method.getAnnotation(Query.class).value()).contains("g.removed = false");
    }
}
