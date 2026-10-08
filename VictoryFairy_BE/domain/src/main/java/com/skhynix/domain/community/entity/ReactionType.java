package com.skhynix.domain.community.entity;

/**
 * 게시글·댓글에 남길 수 있는 반응. "없음"은 값이 아니라 <b>반응 행의 부재</b>로 표현한다 —
 * 그래서 여기에 NONE 이 없다.
 *
 * <p>⚠ ORDINAL 저장(LIKE=0, DISLIKE=1) — 선언 순서를 바꾸면 저장된 값의 뜻이 뒤집힌다.
 */
public enum ReactionType {
    LIKE,
    DISLIKE
}
