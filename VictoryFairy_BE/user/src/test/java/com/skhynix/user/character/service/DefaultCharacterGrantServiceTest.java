package com.skhynix.user.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.character.entity.Character;
import com.skhynix.domain.character.entity.CharacterItem;
import com.skhynix.domain.character.entity.ItemType;
import com.skhynix.domain.character.entity.UserCharacterInventory;
import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import com.skhynix.domain.character.repository.CharacterItemRepository;
import com.skhynix.domain.character.repository.CharacterRepository;
import com.skhynix.domain.character.repository.UserCharacterInventoryRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.character.policy.DefaultCharacterPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link DefaultCharacterGrantService} 단위 테스트. 고정하려는 계약은 둘이다 — <b>지급되는 것은 켜진
 * 채로 들어온다</b>, 그리고 <b>시드가 없어도 예외를 던지지 않는다</b>(후자가 깨지면 꾸미기 데이터 누락이
 * 회원가입 전체를 500 으로 세운다).
 */
@ExtendWith(MockitoExtension.class)
class DefaultCharacterGrantServiceTest {

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private CharacterItemRepository characterItemRepository;

    @Mock
    private UserCharacterInventoryRepository characterInventoryRepository;

    @Mock
    private UserCharacterItemInventoryRepository itemInventoryRepository;

    @InjectMocks
    private DefaultCharacterGrantService defaultCharacterGrantService;

    private static UserAccount account() {
        UserAccount account = UserAccount.builder().nickname("nick").password("encoded").build();
        ReflectionTestUtils.setField(account, "id", 1L);
        return account;
    }

    private static Character character() {
        Character character = Character.builder()
                .name(DefaultCharacterPolicy.CHARACTER_NAME)
                .img("characters/victory-fairy.svg")
                .build();
        ReflectionTestUtils.setField(character, "id", 1L);
        return character;
    }

    private static CharacterItem basicCloth() {
        ItemType cloth = ItemType.builder().name("의상").build();
        ReflectionTestUtils.setField(cloth, "id", 1L);
        CharacterItem item = CharacterItem.builder()
                .character(character())
                .itemType(cloth)
                .name("기본 의상")
                .displayImg("stores/cloth/basic.svg")
                .usingImg("items/cloth/basic.svg")
                .price(100L)
                .build();
        ReflectionTestUtils.setField(item, "id", 10L);
        return item;
    }

    private static CharacterItem redCap() {
        ItemType hat = ItemType.builder().name("모자").build();
        ReflectionTestUtils.setField(hat, "id", 2L);
        CharacterItem item = CharacterItem.builder()
                .character(character())
                .itemType(hat)
                .name("레드 캡")
                .displayImg("stores/head/cap-red.svg")
                .usingImg("items/head/cap-red.svg")
                .price(100L)
                .build();
        ReflectionTestUtils.setField(item, "id", 11L);
        return item;
    }

    private List<UserCharacterItemInventory> savedItemRows(int times) {
        ArgumentCaptor<UserCharacterItemInventory> captor =
                ArgumentCaptor.forClass(UserCharacterItemInventory.class);
        verify(itemInventoryRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("[USER-CS-9] 지급 대상 이름은 '기본 의상'·'레드 캡' 두 개다")
    void policy_itemNames_areBasicClothAndRedCap() {
        assertThat(DefaultCharacterPolicy.ITEM_NAMES).containsExactly("기본 의상", "레드 캡");
    }

    @Test
    @DisplayName("[USER-CS-9] 기본 캐릭터와 기본 의상·레드 캡을 각각 켜진 상태로 지급한다")
    void grantDefaults_seedPresent_savesAllActive() {
        UserAccount account = account();
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.of(character()));
        given(characterItemRepository.findByName("기본 의상")).willReturn(Optional.of(basicCloth()));
        given(characterItemRepository.findByName("레드 캡")).willReturn(Optional.of(redCap()));

        defaultCharacterGrantService.grantDefaults(account);

        ArgumentCaptor<UserCharacterInventory> characterRow =
                ArgumentCaptor.forClass(UserCharacterInventory.class);
        verify(characterInventoryRepository).save(characterRow.capture());
        assertThat(characterRow.getValue().isActive()).isTrue();
        assertThat(characterRow.getValue().getUserAccount()).isSameAs(account);

        List<UserCharacterItemInventory> rows = savedItemRows(2);
        assertThat(rows).extracting(r -> r.getCharacterItem().getName())
                .containsExactly("기본 의상", "레드 캡");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.isActive()).isTrue();
            assertThat(r.getUserAccount()).isSameAs(account);
        });
    }

    @Test
    @DisplayName("[USER-CS-12] 기본 캐릭터 시드가 없으면 예외 없이 건너뛰고 아이템 두 개 지급은 계속한다")
    void grantDefaults_characterSeedMissing_skipsWithoutThrowing() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.empty());
        given(characterItemRepository.findByName("기본 의상")).willReturn(Optional.of(basicCloth()));
        given(characterItemRepository.findByName("레드 캡")).willReturn(Optional.of(redCap()));

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository, never()).save(any());
        assertThat(savedItemRows(2)).extracting(r -> r.getCharacterItem().getName())
                .containsExactly("기본 의상", "레드 캡");
    }

    @Test
    @DisplayName("[USER-CS-12] 모든 시드가 없어도 예외를 던지지 않는다 — 백필이 다음 기동에 채운다")
    void grantDefaults_allSeedsMissing_doesNotThrow() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.empty());
        given(characterItemRepository.findByName("기본 의상")).willReturn(Optional.empty());
        given(characterItemRepository.findByName("레드 캡")).willReturn(Optional.empty());

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository, never()).save(any());
        verify(itemInventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-CS-12] 레드 캡 시드만 없으면 기본 의상은 지급되고 예외는 나지 않는다")
    void grantDefaults_redCapSeedMissing_grantsBasicClothOnly() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.of(character()));
        given(characterItemRepository.findByName("기본 의상")).willReturn(Optional.of(basicCloth()));
        given(characterItemRepository.findByName("레드 캡")).willReturn(Optional.empty());

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository).save(any(UserCharacterInventory.class));
        List<UserCharacterItemInventory> rows = savedItemRows(1);
        assertThat(rows.get(0).getCharacterItem().getName()).isEqualTo("기본 의상");
        assertThat(rows.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("[USER-CS-12] 기본 의상 시드만 없으면 레드 캡은 지급되고 예외는 나지 않는다")
    void grantDefaults_basicClothSeedMissing_grantsRedCapOnly() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.of(character()));
        given(characterItemRepository.findByName("기본 의상")).willReturn(Optional.empty());
        given(characterItemRepository.findByName("레드 캡")).willReturn(Optional.of(redCap()));

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository).save(any(UserCharacterInventory.class));
        List<UserCharacterItemInventory> rows = savedItemRows(1);
        assertThat(rows.get(0).getCharacterItem().getName()).isEqualTo("레드 캡");
        assertThat(rows.get(0).isActive()).isTrue();
    }
}
