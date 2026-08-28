package com.skhynix.user.character.policy;

/**
 * 가입할 때 무상으로 지급되고 곧바로 켜지는 기본 세트.
 *
 * <p>id 가 아니라 <b>이름</b>으로 지목한다 — 두 행 모두 시드가 AUTO_INCREMENT 로 만들어 환경마다 id 가
 * 다르다. ⚠ 이 값을 바꾸면 시드({@code infra/sql/character-asset-init.sql})의 이름도 반드시 함께 바꿔야
 * 한다. 어긋나면 지급 경로가 대상을 못 찾아 조용히 건너뛴다.
 */
public final class DefaultCharacterPolicy {

    public static final String CHARACTER_NAME = "승리요정";

    public static final String ITEM_NAME = "기본 의상";

    private DefaultCharacterPolicy() {
    }
}
