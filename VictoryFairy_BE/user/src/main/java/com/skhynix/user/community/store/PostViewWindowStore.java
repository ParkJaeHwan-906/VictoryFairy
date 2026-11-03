package com.skhynix.user.community.store;

/**
 * (게시글, 계정) 조회 창 — 조회수 증가 뒤 5분 동안 같은 계정의 재조회를 세지 않기 위한 고정 창.
 */
public interface PostViewWindowStore {

    /**
     * 창이 없으면 새로 열고 참, 이미 열려 있으면 거짓. <b>열려 있는 창의 만료를 건드리지 않는다</b>
     * (고정 창 — 슬라이딩이면 5분 안에 계속 보는 사용자의 재조회가 영영 안 세인다).
     *
     * @throws RuntimeException 저장소에 닿지 못했을 때 — 호출자가 "세지 않음"으로 흡수한다
     */
    boolean tryOpen(Long postId, Long userAccountId);
}
