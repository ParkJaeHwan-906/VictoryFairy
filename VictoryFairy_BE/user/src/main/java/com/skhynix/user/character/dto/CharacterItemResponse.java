package com.skhynix.user.character.dto;

import com.skhynix.domain.character.entity.CharacterItem;

/**
 * 상점 목록의 한 줄. <b>상점과 인벤토리가 같은 목록</b>이라 카탈로그 전체가 실리고, 그중 무엇을
 * 보유했는지를 {@code having} 이, 무엇을 착용 중인지를 {@code active} 가 구분한다.
 *
 * <p>{@code displayImg} 만 싣고 {@code usingImg} 는 싣지 않는다 — 착용용 이미지는 캐릭터 위에 겹칠
 * 좌표계라 상점 격자에 그리면 어긋나고, 실제로 필요한 시점({@code GET /users/me})에 그쪽이 준다.
 *
 * <p>{@code price}·{@code active} 는 사용자가 열거한 5개 키에 더한 것이다: 가격 없이는 상점을 그릴 수
 * 없고, 착용 여부 없이는 인벤토리의 토글 상태를 그릴 수 없다(둘 다 이 목록 말고는 알 길이 없다).
 */
public record CharacterItemResponse(Long id, String itemType, String name, String displayImg,
                                    long price, boolean having, boolean active) {

    public static CharacterItemResponse of(CharacterItem item, boolean having, boolean active) {
        return new CharacterItemResponse(
                item.getId(),
                // itemType 은 @EntityGraph 로 이미 로딩된 상태여야 한다 — 아니면 여기서 아이템마다
                // SELECT 가 한 번씩 더 나간다(CharacterItemRepository.findAllByOrderByIdAsc 참고).
                item.getItemType().getName(),
                item.getName(),
                item.getDisplayImg(),
                item.getPrice(),
                having,
                active);
    }
}
