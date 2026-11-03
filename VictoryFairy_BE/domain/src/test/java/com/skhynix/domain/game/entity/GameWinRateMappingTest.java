package com.skhynix.domain.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Game}의 승률 컬럼 매핑이 수동 ALTER 의 {@code DECIMAL(4,1) NULL} 과 일치하는지 본다
 * (USER-GWR-14 중 엔티티 쪽). DB 가 없어 실제 DDL 생성은 확인하지 못하고 애노테이션 선언만 검증한다 —
 * "기동 시 ddl-auto=update 가 alter 를 내지 않는다"는 실제 DB 로만 확인된다.
 */
class GameWinRateMappingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"homeWinRate, home_win_rate", "awayWinRate, away_win_rate"})
    @DisplayName("[USER-GWR-14] 승률 필드는 BigDecimal 이고 DECIMAL(4,1) NULL 컬럼에 매핑된다")
    void winRateField_mapsToDecimal41Nullable(String fieldName, String columnName) throws Exception {
        Field field = Game.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(BigDecimal.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.precision()).isEqualTo(4);
        assertThat(column.scale()).isEqualTo(1);
        assertThat(column.nullable()).isTrue();
    }

    @Test
    @DisplayName("[USER-GWR-14] 승률은 수집기 소유라 @Builder 생성자에 파라미터가 없고 새 Game 의 값은 null 이다")
    void winRate_notInBuilder_defaultsToNull() {
        boolean builderHasWinRate = Arrays.stream(Game.builder().getClass().getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase().contains("winrate"));
        Game game = Game.builder().build();

        assertThat(builderHasWinRate).isFalse();
        assertThat(game.getHomeWinRate()).isNull();
        assertThat(game.getAwayWinRate()).isNull();
    }
}
