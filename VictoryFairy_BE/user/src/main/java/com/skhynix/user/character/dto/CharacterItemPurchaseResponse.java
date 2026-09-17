package com.skhynix.user.character.dto;

/**
 * 구매 결과. 잔액을 함께 돌려주는 것은 이 요청이 잔액을 바꿨기 때문이다 — 안 주면 클라이언트가
 * {@code GET /users/me} 를 한 번 더 쳐야 한다.
 */
public record CharacterItemPurchaseResponse(Long characterItemId, long remainingPoint) {
}
