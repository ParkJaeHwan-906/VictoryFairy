package com.skhynix.domain.game.entity;

/**
 * 이닝 초/말. DB에는 ORDINAL(선언 순서)로 저장되므로 순서를 바꾸면 안 된다.
 * TOP = 0(초), BOTTOM = 1(말)
 */
public enum InningHalf {
    TOP,
    BOTTOM
}
