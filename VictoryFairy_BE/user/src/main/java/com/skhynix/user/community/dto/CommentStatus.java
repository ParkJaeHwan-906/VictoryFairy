package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityComment;

/**
 * 목록 항목의 상태. {@code DELETED}·{@code BLINDED} 는 <b>자리 표식</b>(본문·작성자가 비고 답글만 사는
 * 최상위 댓글)이며 저장 상태가 아니라 조회 시점의 표현이다. 답글은 항상 {@code VISIBLE} 이다.
 */
public enum CommentStatus {
    VISIBLE,
    DELETED,
    BLINDED;

    // 삭제와 블라인드가 겹치면 DELETED — 작성자의 "치우기"가 신고보다 우선한다
    public static CommentStatus of(CommunityComment comment) {
        if (comment.isDeleted()) {
            return DELETED;
        }
        if (comment.isBlinded()) {
            return BLINDED;
        }
        return VISIBLE;
    }
}
