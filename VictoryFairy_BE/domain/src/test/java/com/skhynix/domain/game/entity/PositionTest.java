package com.skhynix.domain.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Position}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * DB 전략 관련 결정은 {@link GameTest} javadoc 참고.
 */
class PositionTest {

    @Test
    @DisplayName("name을 채워 build하면 그대로 보존된다")
    void builder_withName_keepsName() {
        // when
        Position position = Position.builder().name("중").build();

        // then
        assertThat(position.getName()).isEqualTo("중");
    }
}
