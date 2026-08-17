package com.skhynix.user.account.dto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 닉네임 변경 쿨다운 거절(429) 응답의 {@code data}. 키는 정확히 이 하나다 — 남은 기간·epoch 숫자 같은
 * 값을 나란히 싣지 않는다("언제 풀리는지" 하나면 클라이언트가 나머지를 계산할 수 있다).
 *
 * <p>성공 응답이 아니라 <b>실패 응답</b>에 실리는 유일한 도메인 DTO다.
 */
public record NicknameChangeCooldownResponse(String nextChangeableAt) {

    /**
     * 오프셋을 포함한 ISO-8601 표기를 쓴다 — 오프셋이 없으면 응답만 봐서는 그 시각이 어느 존인지 알 수
     * 없고, 파드가 UTC라 9시간 어긋나 보이는 문제가 이 저장소에 이미 있다.
     *
     * <p>{@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}을 쓰지 않는 이유: 초가 0이면 {@code :00}을 통째로
     * 생략해({@code 2026-09-16T14:03+09:00}) 값의 자릿수가 시각에 따라 달라진다.
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public static NicknameChangeCooldownResponse of(Instant nextChangeableAt, ZoneId zone) {
        return new NicknameChangeCooldownResponse(FORMATTER.format(nextChangeableAt.atZone(zone)));
    }
}
