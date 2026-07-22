package com.skhynix.domain.user.entity;

/**
 * 성별. DB에는 ORDINAL(선언 순서)로 저장되므로 순서를 바꾸면 안 된다.
 * MALE = 0, FEMALE = 1
 */
public enum Gender {
    MALE,
    FEMALE
}
