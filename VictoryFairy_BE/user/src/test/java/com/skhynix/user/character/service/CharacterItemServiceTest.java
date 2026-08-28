package com.skhynix.user.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.character.entity.Character;
import com.skhynix.domain.character.entity.CharacterItem;
import com.skhynix.domain.character.entity.ItemType;
import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import com.skhynix.domain.character.repository.CharacterItemRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.character.dto.CharacterItemActiveResponse;
import com.skhynix.user.character.dto.CharacterItemPurchaseResponse;
import com.skhynix.user.character.dto.CharacterItemResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link CharacterItemService} 를 협력 객체 전부 목으로 대체해 단위로 검증한다(DB·스프링 컨텍스트 없음).
 *
 * <p>엔티티는 {@code id} 에 setter 가 없어 {@link ReflectionTestUtils#setField} 로 채운다
 * ({@code UserProfileServiceTest} 와 같은 패턴).
 *
 * <p><b>범위 밖</b>: 계정 행 비관적 잠금이 실제로 동시 갱신을 직렬화하는지는 목으로 증명할 수 없다 —
 * 여기서는 잠금 조회 메서드를 쓴다는 것까지만 고정한다(일반 {@code findById} 로 갈아끼우면 이 테스트가
 * 스텁 불일치로 깨진다).
 */
@ExtendWith(MockitoExtension.class)
class CharacterItemServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private CharacterItemRepository characterItemRepository;

    @Mock
    private UserCharacterItemInventoryRepository inventoryRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private CharacterItemService characterItemService;

    private static ItemType itemTypeOf(Long id, String name) {
        ItemType itemType = ItemType.builder().name(name).build();
        ReflectionTestUtils.setField(itemType, "id", id);
        return itemType;
    }

    private static CharacterItem itemOf(Long id, ItemType itemType, String name, long price) {
        Character character = Character.builder()
                .name("승리요정")
                .img("characters/victory-fairy.svg")
                .build();
        ReflectionTestUtils.setField(character, "id", 1L);
        CharacterItem item = CharacterItem.builder()
                .character(character)
                .itemType(itemType)
                .name(name)
                .displayImg("stores/cloth/" + id + ".svg")
                .usingImg("items/cloth/" + id + ".svg")
                .price(price)
                .build();
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static UserAccount accountWithPoint(long point) {
        UserAccount account = UserAccount.builder().nickname("nick").password("encoded").build();
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        ReflectionTestUtils.setField(account, "point", point);
        return account;
    }

    private static UserCharacterItemInventory inventoryOf(CharacterItem item, boolean active) {
        return UserCharacterItemInventory.builder()
                .userAccount(accountWithPoint(0L))
                .characterItem(item)
                .active(active)
                .build();
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록은 카탈로그 전체를 돌려주고, 산 것과 안 산 것을 having 으로만 구분한다")
    void findAll_returnsWholeCatalogWithHavingFlag() {
        ItemType cloth = itemTypeOf(1L, "의상");
        CharacterItem owned = itemOf(10L, cloth, "기본 의상", 100L);
        CharacterItem notOwned = itemOf(11L, cloth, "레드 라인 유니폼", 100L);
        given(characterItemRepository.findAllByOrderByItemType_IdAscIdAsc()).willReturn(List.of(owned, notOwned));
        given(inventoryRepository.findOwnedCharacterItemIds(ACCOUNT_ID)).willReturn(List.of(10L));
        given(inventoryRepository.findActiveCharacterItemIds(ACCOUNT_ID)).willReturn(List.of(10L));

        List<CharacterItemResponse> result = characterItemService.findAll(ACCOUNT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(10L);
        assertThat(result.get(0).itemType()).isEqualTo("의상");
        assertThat(result.get(0).name()).isEqualTo("기본 의상");
        assertThat(result.get(0).price()).isEqualTo(100L);
        assertThat(result.get(0).having()).isTrue();
        assertThat(result.get(0).active()).isTrue();
        assertThat(result.get(1).having()).isFalse();
        assertThat(result.get(1).active()).isFalse();
    }

    @Test
    @DisplayName("보유했지만 착용하지 않은 아이템은 having=true, active=false 다")
    void findAll_ownedButNotWorn_hasHavingTrueAndActiveFalse() {
        ItemType cloth = itemTypeOf(1L, "의상");
        CharacterItem item = itemOf(10L, cloth, "기본 의상", 100L);
        given(characterItemRepository.findAllByOrderByItemType_IdAscIdAsc()).willReturn(List.of(item));
        given(inventoryRepository.findOwnedCharacterItemIds(ACCOUNT_ID)).willReturn(List.of(10L));
        given(inventoryRepository.findActiveCharacterItemIds(ACCOUNT_ID)).willReturn(List.of());

        List<CharacterItemResponse> result = characterItemService.findAll(ACCOUNT_ID);

        assertThat(result.get(0).having()).isTrue();
        assertThat(result.get(0).active()).isFalse();
    }

    // ---------- 구매 ----------

    @Test
    @DisplayName("포인트가 충분하면 가격만큼 차감하고 꺼진 보유 행을 만든 뒤 잔액을 돌려준다")
    void purchase_enoughPoint_deductsAndSavesInactiveRow() {
        UserAccount account = accountWithPoint(300L);
        CharacterItem item = itemOf(10L, itemTypeOf(1L, "의상"), "레드 라인 유니폼", 100L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(10L)).willReturn(Optional.of(item));
        given(inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(false);

        CharacterItemPurchaseResponse response = characterItemService.purchase(ACCOUNT_ID, 10L);

        assertThat(response.characterItemId()).isEqualTo(10L);
        assertThat(response.remainingPoint()).isEqualTo(200L);
        assertThat(account.getPoint()).isEqualTo(200L);
        verify(inventoryRepository).save(any(UserCharacterItemInventory.class));
    }

    @Test
    @DisplayName("존재하지 않는 아이템이면 404 이고 잔액은 그대로다")
    void purchase_unknownItem_throwsNotFound() {
        UserAccount account = accountWithPoint(300L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> characterItemService.purchase(ACCOUNT_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARACTER_ITEM_NOT_FOUND);
        assertThat(account.getPoint()).isEqualTo(300L);
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 보유한 아이템이면 409 이고 잔액은 그대로다 — 아이템은 영구적으로 한 개만 산다")
    void purchase_alreadyOwned_throwsConflict() {
        UserAccount account = accountWithPoint(300L);
        CharacterItem item = itemOf(10L, itemTypeOf(1L, "의상"), "레드 라인 유니폼", 100L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(10L)).willReturn(Optional.of(item));
        given(inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(true);

        assertThatThrownBy(() -> characterItemService.purchase(ACCOUNT_ID, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARACTER_ITEM_ALREADY_OWNED);
        assertThat(account.getPoint()).isEqualTo(300L);
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("잔액이 가격보다 적으면 400 이고 차감도 보유 행 생성도 일어나지 않는다")
    void purchase_insufficientPoint_throwsBadRequest() {
        UserAccount account = accountWithPoint(50L);
        CharacterItem item = itemOf(10L, itemTypeOf(1L, "의상"), "레드 라인 유니폼", 100L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(10L)).willReturn(Optional.of(item));
        given(inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(false);

        assertThatThrownBy(() -> characterItemService.purchase(ACCOUNT_ID, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_POINT);
        assertThat(account.getPoint()).isEqualTo(50L);
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("잔액이 가격과 정확히 같으면 살 수 있고 잔액은 0이 된다 — 경계에서 거절되는 것은 미만뿐이다")
    void purchase_exactPoint_succeeds() {
        UserAccount account = accountWithPoint(100L);
        CharacterItem item = itemOf(10L, itemTypeOf(1L, "의상"), "레드 라인 유니폼", 100L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(10L)).willReturn(Optional.of(item));
        given(inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(false);

        CharacterItemPurchaseResponse response = characterItemService.purchase(ACCOUNT_ID, 10L);

        assertThat(response.remainingPoint()).isZero();
    }

    @Test
    @DisplayName("이미 보유한 아이템은 잔액이 모자라도 중복 보유로 거절한다 — 검사 순서가 원인 문구를 정한다")
    void purchase_alreadyOwnedAndBroke_reportsOwnedNotInsufficient() {
        UserAccount account = accountWithPoint(0L);
        CharacterItem item = itemOf(10L, itemTypeOf(1L, "의상"), "레드 라인 유니폼", 100L);
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(characterItemRepository.findById(10L)).willReturn(Optional.of(item));
        given(inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(true);

        assertThatThrownBy(() -> characterItemService.purchase(ACCOUNT_ID, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARACTER_ITEM_ALREADY_OWNED);
    }

    // ---------- 착용 토글 ----------

    @Test
    @DisplayName("꺼진 아이템을 켜면 같은 부위에서 켜져 있던 아이템이 꺼진다")
    void toggleActive_turningOn_turnsOffSameTypeItem() {
        ItemType cloth = itemTypeOf(1L, "의상");
        UserCharacterItemInventory target = inventoryOf(itemOf(11L, cloth, "레드 라인 유니폼", 100L), false);
        UserCharacterItemInventory worn = inventoryOf(itemOf(10L, cloth, "기본 의상", 100L), true);
        given(inventoryRepository.findByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 11L))
                .willReturn(Optional.of(target));
        given(inventoryRepository.findActiveByUserAccountIdAndItemTypeId(ACCOUNT_ID, 1L))
                .willReturn(Optional.of(worn));

        CharacterItemActiveResponse response = characterItemService.toggleActive(ACCOUNT_ID, 11L);

        assertThat(response.active()).isTrue();
        assertThat(target.isActive()).isTrue();
        assertThat(worn.isActive()).isFalse();
    }

    @Test
    @DisplayName("같은 부위에 켜진 아이템이 없으면 그냥 켠다")
    void toggleActive_turningOnWithNothingWorn_justTurnsOn() {
        ItemType head = itemTypeOf(2L, "모자");
        UserCharacterItemInventory target = inventoryOf(itemOf(20L, head, "블루 캡", 100L), false);
        given(inventoryRepository.findByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 20L))
                .willReturn(Optional.of(target));
        given(inventoryRepository.findActiveByUserAccountIdAndItemTypeId(ACCOUNT_ID, 2L))
                .willReturn(Optional.empty());

        CharacterItemActiveResponse response = characterItemService.toggleActive(ACCOUNT_ID, 20L);

        assertThat(response.active()).isTrue();
        assertThat(target.isActive()).isTrue();
    }

    @Test
    @DisplayName("켜진 아이템에 대한 요청은 끄기만 하고, 다른 아이템을 대신 켜지 않는다")
    void toggleActive_turningOff_doesNotWearAnythingElse() {
        ItemType cloth = itemTypeOf(1L, "의상");
        UserCharacterItemInventory target = inventoryOf(itemOf(10L, cloth, "기본 의상", 100L), true);
        given(inventoryRepository.findByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 10L))
                .willReturn(Optional.of(target));

        CharacterItemActiveResponse response = characterItemService.toggleActive(ACCOUNT_ID, 10L);

        assertThat(response.active()).isFalse();
        assertThat(target.isActive()).isFalse();
        verify(inventoryRepository, never()).findActiveByUserAccountIdAndItemTypeId(any(), any());
    }

    @Test
    @DisplayName("보유하지 않은 아이템은 카탈로그에 있어도 토글할 수 없다(404)")
    void toggleActive_notOwned_throwsNotOwned() {
        given(inventoryRepository.findByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 11L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> characterItemService.toggleActive(ACCOUNT_ID, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARACTER_ITEM_NOT_OWNED);
    }

    @Test
    @DisplayName("다른 부위의 켜진 아이템은 건드리지 않는다 — 배타 조건의 단위는 부위다")
    void toggleActive_otherTypeStaysOn() {
        ItemType cloth = itemTypeOf(1L, "의상");
        ItemType head = itemTypeOf(2L, "모자");
        UserCharacterItemInventory wornCap = inventoryOf(itemOf(20L, head, "블루 캡", 100L), true);
        UserCharacterItemInventory target = inventoryOf(itemOf(11L, cloth, "레드 라인 유니폼", 100L), false);
        given(inventoryRepository.findByUserAccount_IdAndCharacterItem_Id(ACCOUNT_ID, 11L))
                .willReturn(Optional.of(target));
        // 의상 부위(1L)로만 묻는다 — 모자 부위(2L)는 조회 대상이 아니라 그대로 켜져 있다.
        given(inventoryRepository.findActiveByUserAccountIdAndItemTypeId(ACCOUNT_ID, 1L))
                .willReturn(Optional.empty());

        characterItemService.toggleActive(ACCOUNT_ID, 11L);

        assertThat(wornCap.isActive()).isTrue();
        assertThat(target.isActive()).isTrue();
    }
}
