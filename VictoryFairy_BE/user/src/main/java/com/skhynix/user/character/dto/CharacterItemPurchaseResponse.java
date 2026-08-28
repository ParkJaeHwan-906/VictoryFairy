package com.skhynix.user.character.dto;

/**
 * 구매 결과. 잔액을 함께 돌려주는 이유는 비밀번호 변경이 토큰을 돌려주는 것과 같다 — 이 요청이 잔액을
 * 바꿨으므로 그 자리에서 최신값을 주지 않으면 클라이언트가 {@code GET /users/me} 를 한 번 더 쳐야 한다.
 *
 * <p>구매한 아이템은 <b>착용되지 않은 상태</b>로 들어온다. 사는 것과 입는 것은 다른 행위이고,
 * 자동 착용은 이미 입고 있던 같은 부위 아이템을 사용자 의사와 무관하게 벗긴다.
 */
public record CharacterItemPurchaseResponse(Long characterItemId, long remainingPoint) {
}
