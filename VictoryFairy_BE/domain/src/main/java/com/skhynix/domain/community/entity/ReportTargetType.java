package com.skhynix.domain.community.entity;

/**
 * 신고 대상의 종류. 댓글과 답글은 한 테이블이라 COMMENT 하나로 둘 다 가리킨다.
 *
 * <p>⚠ ORDINAL 저장(POST=0, COMMENT=1) — 선언 순서를 바꾸면 저장된 값의 뜻이 뒤집힌다.
 */
public enum ReportTargetType {
    POST,
    COMMENT
}
