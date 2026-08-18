package com.skhynix.user.cleanup.service;

/**
 * 계정 1건 처리의 결과 — 회차 요약 로그의 수치는 이 값들을 더한 것이다.
 *
 * <p>{@code accountRemoved} 가 따로 있는 이유: 이관·정리는 정상적으로 끝났는데 지울 {@code users}
 * 행이 이미 없는 경우가 있다(다른 파드가 먼저 처리). 그건 실패가 아니라 <b>이미 끝난 일</b>이므로
 * 실패 건수에 넣지 않고, 그렇다고 이 회차의 삭제 건수로 세지도 않는다.
 */
public record AccountEraseResult(int chatroomsTransferred, int chatsTransferred,
        int cancelledLikesDeleted, boolean accountRemoved) {
}
