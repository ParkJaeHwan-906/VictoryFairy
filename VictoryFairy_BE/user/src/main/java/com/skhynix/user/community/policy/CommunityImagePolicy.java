package com.skhynix.user.community.policy;

import com.skhynix.user.profileimage.policy.ProfileImagePolicy;

/**
 * 커뮤니티 이미지 EP 모양과 첨부 상한의 단일 출처. 임시 접두·크기·허용 형식은 프로필 이미지 정책
 * ({@link ProfileImagePolicy}·{@code ProfileImageFormat})을 그대로 쓴다 — 같은 {@code temp/} 에 올리므로
 * 임시 객체 정리(04:00 스케줄러 + 라이프사이클)도 프로필과 구분 없이 받아 간다.
 */
public final class CommunityImagePolicy {

    /** 글·댓글에 확정된 이미지. 세그먼트 2개({@code community/{uuid}.{ext}}) — CloudFront 패턴과 1:1 이다. */
    public static final String PERMANENT_PREFIX = "community/";

    // ⚠ ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED 문구의 5/3 과 같은 값이어야 한다(:common 은 이 상수를 못 본다).
    public static final int MAX_POST_IMAGES = 5;
    public static final int MAX_COMMENT_IMAGES = 3;

    private CommunityImagePolicy() {
    }

    /**
     * 임시 EP → 확정 EP. 파일명은 그대로 두고 접두만 바꾼다(프로필과 달리 새 UUID 를 만들지 않는다 —
     * 글에 붙기 전 링크가 새어도 공개 게시판이라 감출 것이 없고, 같은 파일명이라 "한 객체는 한 번만
     * 이동된다"가 이름만으로 드러난다).
     */
    public static String toPermanent(String tempEndpoint) {
        return PERMANENT_PREFIX + tempEndpoint.substring(ProfileImagePolicy.TEMP_PREFIX.length());
    }

    /** 삭제 안전장치 — 확정 접두일 때만 DeleteObject 대상으로 인정한다(프로필 쪽과 같은 역할). */
    public static boolean isPermanentEndpoint(String endpoint) {
        return endpoint != null && endpoint.startsWith(PERMANENT_PREFIX);
    }
}
