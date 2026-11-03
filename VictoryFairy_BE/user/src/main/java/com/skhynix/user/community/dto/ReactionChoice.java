package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.ReactionType;

/**
 * 요청이 지정하는 반응 상태. 저장 enum({@link ReactionType})에는 없는 {@code NONE}(취소)이 있다 —
 * 취소는 행 삭제라 저장값이 아니다. 범위 밖 문자열은 역직렬화 실패 → handleNotReadable 의 400.
 */
public enum ReactionChoice {
    LIKE(ReactionType.LIKE),
    DISLIKE(ReactionType.DISLIKE),
    NONE(null);

    private final ReactionType type;

    ReactionChoice(ReactionType type) {
        this.type = type;
    }

    /** 저장할 반응. NONE 이면 {@code null}. */
    public ReactionType type() {
        return type;
    }
}
