package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.ReactionType;

/** 반응 변경 결과. {@code myReaction} 은 취소 뒤 {@code null}. 카운트는 변경 반영 후 값이다. */
public record ReactionResponse(ReactionType myReaction, long likeCount, long dislikeCount) {
}
