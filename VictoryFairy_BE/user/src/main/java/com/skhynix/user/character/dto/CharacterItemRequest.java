package com.skhynix.user.character.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 구매·착용 토글이 함께 쓰는 요청 본문. 대상 계정은 언제나 토큰 주체 본인이라 본문으로 받지 않는다
 * (프로필 수정 경로와 같은 규칙).
 *
 * <p>두 API 가 한 record 를 공유하는 것은 본문이 문자 그대로 같기 때문이다 — 한쪽에만 필드가 생기는
 * 순간 나눠야 한다.
 */
public record CharacterItemRequest(@NotNull Long characterItemId) {
}
