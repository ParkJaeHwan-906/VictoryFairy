package com.skhynix.user.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NicknameChangeCooldownResponse}(429 쿨다운 거절 응답의 {@code data})의 렌더링 형식을 검증한다.
 * 요구사항: {@code docs/requirements/user/profile-edit.md} USER-PE-47.
 *
 * <p>이 클래스가 특히 지키는 것은 <b>초가 0이어도 {@code :00}이 생략되지 않는다</b>는 사실이다 —
 * {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}을 썼다면 {@code 2026-09-16T14:03+09:00}처럼 초 자체가
 * 사라져 값의 자릿수가 시각마다 달라진다. 프로덕션 코드가 명시 패턴({@code yyyy-MM-dd'T'HH:mm:ssXXX})을
 * 쓰는 이유가 바로 이 함정이다.
 */
class NicknameChangeCooldownResponseTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("[USER-PE-47] 레코드 컴포넌트(=JSON 키)는 정확히 nextChangeableAt 하나다")
    void record_hasExactlyOneComponent_nextChangeableAt() {
        RecordComponent[] components = NicknameChangeCooldownResponse.class.getRecordComponents();
        List<String> componentNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toList());

        assertThat(componentNames).containsExactly("nextChangeableAt");
    }

    @Test
    @DisplayName("[USER-PE-47] 초가 0인 시각도 \":00\"이 생략되지 않고 그대로 렌더링된다")
    void of_secondsAreZero_doesNotTruncateSecondsField() {
        // given: 2026-09-16T14:03:00+09:00 (초=0)
        Instant instant = Instant.parse("2026-09-16T05:03:00Z");

        // when
        NicknameChangeCooldownResponse response = NicknameChangeCooldownResponse.of(instant, SEOUL);

        // then
        assertThat(response.nextChangeableAt()).isEqualTo("2026-09-16T14:03:00+09:00");
    }

    @Test
    @DisplayName("[USER-PE-47] 초가 0이 아닌 시각은 그 초 값 그대로 렌더링된다(대조군)")
    void of_nonZeroSeconds_rendersActualSecondsValue() {
        // given: 2026-09-16T14:03:21+09:00
        Instant instant = Instant.parse("2026-09-16T05:03:21Z");

        // when
        NicknameChangeCooldownResponse response = NicknameChangeCooldownResponse.of(instant, SEOUL);

        // then
        assertThat(response.nextChangeableAt()).isEqualTo("2026-09-16T14:03:21+09:00");
    }

    @Test
    @DisplayName("[USER-PE-49] 렌더링은 Asia/Seoul 오프셋(+09:00)을 포함한다 — 절대 시각은 같아도 존 표기가 없으면 "
            + "어느 시간대인지 응답만으로 알 수 없다")
    void of_includesOffset() {
        Instant instant = Instant.parse("2026-09-16T05:03:00Z");

        NicknameChangeCooldownResponse response = NicknameChangeCooldownResponse.of(instant, SEOUL);

        assertThat(response.nextChangeableAt()).endsWith("+09:00");
    }
}
