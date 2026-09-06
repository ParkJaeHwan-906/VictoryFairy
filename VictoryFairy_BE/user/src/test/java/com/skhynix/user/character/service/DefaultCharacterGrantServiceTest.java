package com.skhynix.user.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
                .name(DefaultCharacterPolicy.ITEM_NAME)
                .displayImg("stores/cloth/basic.svg")
                .usingImg("items/cloth/basic.svg")
                .price(100L)
                .build();
        ReflectionTestUtils.setField(item, "id", 10L);
        return item;
    }

    @Test
    @DisplayName("기본 캐릭터와 기본 의상을 각각 켜진 상태로 지급한다")
    void grantDefaults_seedPresent_savesBothActive() {
        UserAccount account = account();
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.of(character()));
        given(characterItemRepository.findByName(DefaultCharacterPolicy.ITEM_NAME))
                .willReturn(Optional.of(basicCloth()));

        defaultCharacterGrantService.grantDefaults(account);

        ArgumentCaptor<UserCharacterInventory> characterRow =
                ArgumentCaptor.forClass(UserCharacterInventory.class);
        verify(characterInventoryRepository).save(characterRow.capture());
        assertThat(characterRow.getValue().isActive()).isTrue();
        assertThat(characterRow.getValue().getUserAccount()).isSameAs(account);

        ArgumentCaptor<UserCharacterItemInventory> itemRow =
                ArgumentCaptor.forClass(UserCharacterItemInventory.class);
        verify(itemInventoryRepository).save(itemRow.capture());
        assertThat(itemRow.getValue().isActive()).isTrue();
        assertThat(itemRow.getValue().getUserAccount()).isSameAs(account);
    }

    @Test
    @DisplayName("기본 캐릭터 시드가 없으면 예외 없이 건너뛰고 아이템 지급은 계속한다 — 가입을 막지 않는다")
    void grantDefaults_characterSeedMissing_skipsWithoutThrowing() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.empty());
        given(characterItemRepository.findByName(DefaultCharacterPolicy.ITEM_NAME))
                .willReturn(Optional.of(basicCloth()));

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository, never()).save(any());
        verify(itemInventoryRepository).save(any(UserCharacterItemInventory.class));
    }

    @Test
    @DisplayName("두 시드가 모두 없어도 예외를 던지지 않는다 — 백필이 다음 기동에 채운다")
    void grantDefaults_allSeedsMissing_doesNotThrow() {
        given(characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME))
                .willReturn(Optional.empty());
        given(characterItemRepository.findByName(DefaultCharacterPolicy.ITEM_NAME))
                .willReturn(Optional.empty());

        defaultCharacterGrantService.grantDefaults(account());

        verify(characterInventoryRepository, never()).save(any());
        verify(itemInventoryRepository, never()).save(any());
    }
}
