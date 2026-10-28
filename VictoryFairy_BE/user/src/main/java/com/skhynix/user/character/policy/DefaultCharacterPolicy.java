package com.skhynix.user.character.policy;

import java.util.List;

/**
 * 가입할 때 무상으로 지급되고 곧바로 켜지는 기본 세트(캐릭터 + 의상·모자).
 *
 * <p>id 가 아니라 <b>이름</b>으로 지목한다 — 전부 시드가 AUTO_INCREMENT 로 만들어 환경마다 id 가
 * 다르다. ⚠ 이 값을 바꾸면 시드({@code infra/sql/character-asset-init.sql})의 이름도 반드시 함께 바꿔야
 * 한다. 어긋나면 지급 경로가 대상을 못 찾아 조용히 건너뛴다. {@link #ITEM_NAMES} 의 각 이름은 그 시드
 * Step 3 카탈로그의 name 과 글자 단위로 일치해야 한다.
 *
 * <p>{@link #ITEM_NAMES} 는 서로 <b>다른 부위</b>(의상·모자)여야 한다 — 지급 경로가 전부 켜진 채로 넣으므로
 * 같은 부위가 둘 들어가면 토글 API 가 전제하는 "부위당 하나"가 가입 직후부터 깨진다.
 */
public final class DefaultCharacterPolicy {

    public static final String CHARACTER_NAME = "승리요정";

    public static final List<String> ITEM_NAMES = List.of("기본 의상", "레드 캡");

    private DefaultCharacterPolicy() {
    }
}
