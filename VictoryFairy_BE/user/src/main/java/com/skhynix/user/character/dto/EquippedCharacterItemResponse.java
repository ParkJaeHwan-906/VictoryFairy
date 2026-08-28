package com.skhynix.user.character.dto;

import com.skhynix.domain.character.entity.UserCharacterItemInventory;

/**
 * {@code GET /users/me} 가 싣는, 지금 착용 중인 아이템 한 개.
 *
 * <p>{@code imgUrl} 은 상점 진열용이 아니라 <b>착용용</b>({@code character_items.using_img})이다 —
 * 캐릭터 이미지 위에 그대로 겹쳐 그리도록 좌표계가 맞춰져 있다.
 *
 * <p>{@code itemType} 을 함께 싣는 이유는 분류 표시가 아니라 <b>겹치는 순서</b> 때문이다. 부위를 모르면
 * 클라이언트가 모자와 의상 중 무엇을 위에 그릴지 정할 수 없다.
 */
public record EquippedCharacterItemResponse(String itemType, String imgUrl) {

    public static EquippedCharacterItemResponse from(UserCharacterItemInventory inventory) {
        return new EquippedCharacterItemResponse(
                inventory.getCharacterItem().getItemType().getName(),
                inventory.getCharacterItem().getUsingImg());
    }
}
