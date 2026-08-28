package com.skhynix.user.character.dto;

import com.skhynix.domain.character.entity.CharacterItem;

/**
 * 상점 목록의 한 줄. 상점과 인벤토리가 같은 목록이라 카탈로그 전체가 실리고, 보유는 {@code having} 이
 * 착용은 {@code active} 가 구분한다.
 *
 * <p>{@code usingImg} 를 싣지 않는 것은 그것이 캐릭터 위에 겹칠 좌표계라 상점 격자에서는 어긋나기
 * 때문이다 — 필요한 시점({@code GET /users/me})에 그쪽이 준다.
 */
public record CharacterItemResponse(Long id, String itemType, String name, String displayImg,
                                    long price, boolean having, boolean active) {

    public static CharacterItemResponse of(CharacterItem item, boolean having, boolean active) {
        return new CharacterItemResponse(
                item.getId(),
                // itemType 은 @EntityGraph 로 이미 로딩된 상태여야 한다 — 아니면 아이템마다 SELECT 가
                // 한 번씩 더 나간다.
                item.getItemType().getName(),
                item.getName(),
                item.getDisplayImg(),
                item.getPrice(),
                having,
                active);
    }
}
