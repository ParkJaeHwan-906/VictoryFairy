package com.skhynix.user.community.dto;

/**
 * 댓글 수정 응답의 공통 타입 — 최상위 댓글이면 {@link CommentResponse}(replies 포함), 답글이면
 * {@link ReplyResponse}(replies 키 없음). 직렬화는 런타임 타입의 필드를 그대로 쓰므로 타입 정보 키가 붙지 않는다.
 */
public sealed interface CommentItemResponse permits CommentResponse, ReplyResponse {
}
