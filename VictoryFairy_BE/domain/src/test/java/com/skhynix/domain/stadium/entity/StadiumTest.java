package com.skhynix.domain.stadium.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Stadium}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: 이 모듈({@code domain})은 현재 {@code testImplementation}으로
 * {@code spring-boot-starter-data-jpa-test}·H2·Testcontainers 중 어느 것도 갖고 있지 않고,
 * 로컬 {@code docker-compose.yml}의 mysql 컨테이너도 떠 있지 않다(확인: {@code docker compose ps}
 * 결과 없음, {@code docker ps -a}도 컨테이너 없음, {@code .env} 파일 자체가 없음). 따라서
 * {@code @DataJpaTest}로 {@code save}/{@code findById}/{@code findByName} 라운드트립,
 * nullable 컬럼, FK, enum 영속화를 검증하는 원래 요청 범위는 이 세션에서 실행할 수 없어 보류했다.
 * H2 또는 Testcontainers 도입 여부 결정이 필요하다(자세한 내용은 최종 보고 참고). 이 클래스는
 * DB 없이도 확인 가능한 {@link Stadium#builder()} 배선만 다룬다.
 */
class StadiumTest {

    @Test
    @DisplayName("Stadium.builder()로 생성하면 name이 그대로 설정되고, 영속화 전이므로 id/생성·수정시각은 null이다")
    void builder_setsName_andLeavesPersistenceManagedFieldsNull() {
        // when
        Stadium stadium = Stadium.builder()
                .name("잠실야구장")
                .build();

        // then
        assertThat(stadium.getName()).isEqualTo("잠실야구장");
        assertThat(stadium.getId()).isNull();
        assertThat(stadium.getCreatedAt()).isNull();
        assertThat(stadium.getUpdatedAt()).isNull();
    }
}
