package com.skhynix.user.character.policy;

/**
 * 가입할 때 무상으로 지급되고 곧바로 켜지는 기본 세트.
 *
 * <p>id 가 아니라 <b>이름</b>으로 지목한다 — 두 행 모두 시드({@code character-asset-init.sql})가
 * AUTO_INCREMENT 로 만들기 때문에 환경마다 id 가 다르다. id 를 상수로 박으면 로컬에서는 맞고 운영에서만
 * 엉뚱한 아이템이 지급된다.
 *
 * <p>⚠ 이 값을 바꾸면 시드의 이름과 <b>반드시 함께</b> 바꿔야 한다. 어긋나면 지급 경로가 대상을 못 찾아
 * 조용히 건너뛴다({@code DefaultCharacterGrantService} 참고).
 */
public final class DefaultCharacterPolicy {

    /** 유일한 캐릭터. */
    public static final String CHARACTER_NAME = "승리요정";

    /** 기본 의상 — 상점에도 전시되지만 가격과 무관하게 처음부터 지급·착용된다(사용자 확정). */
    public static final String ITEM_NAME = "기본 의상";

    private DefaultCharacterPolicy() {
    }
}
