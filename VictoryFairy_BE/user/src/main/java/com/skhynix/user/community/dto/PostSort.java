package com.skhynix.user.community.dto;

import org.springframework.data.domain.Sort;

/**
 * 전체 목록의 정렬 축. 상수가 소문자인 것은 실수가 아니다 — 쿼리 파라미터 값({@code ?sort=likes})을 스프링의
 * 기본 enum 변환({@code Enum.valueOf}, 대소문자 구분)이 그대로 받게 하기 위해서다. 범위 밖 값은 변환 실패
 * → {@code MethodArgumentTypeMismatchException} → 기존 handleTypeMismatch 의 400 이라 신규 코드가 없다.
 *
 * <p>동률은 전부 {@code id} 내림차순(최신)이다. {@code latest} 가 {@code createdAt} 이 아니라 {@code id} 인 것은
 * 같은 뜻이면서 PK 정렬이라 filesort 가 없기 때문이다.
 */
public enum PostSort {
    latest(Sort.by(Sort.Direction.DESC, "id")),
    likes(Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "id"))),
    views(Sort.by(Sort.Direction.DESC, "viewCount").and(Sort.by(Sort.Direction.DESC, "id")));

    private final Sort sort;

    PostSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
