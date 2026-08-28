package com.skhynix.user.character.service;

import com.skhynix.domain.character.entity.Character;
import com.skhynix.domain.character.entity.CharacterItem;
import com.skhynix.domain.character.entity.UserCharacterInventory;
import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import com.skhynix.domain.character.repository.CharacterItemRepository;
import com.skhynix.domain.character.repository.CharacterRepository;
import com.skhynix.domain.character.repository.UserCharacterInventoryRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.character.policy.DefaultCharacterPolicy;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 신규 계정에 기본 캐릭터와 기본 의상을 지급하고 곧바로 켠다.
 *
 * <p>자체 가입({@code AuthService.signup})과 소셜 가입({@code OauthAccountWriter.createAccount}) <b>둘 다</b>
 * 이것을 부른다 — 계정을 만드는 경로가 둘인데 한쪽만 고치면 그쪽으로 가입한 사용자만 캐릭터가 없다
 * ({@code UserBq} 생성이 이미 두 곳에 있는 것과 같은 이유다).
 *
 * <p>별도 {@code @Transactional} 을 걸지 않는다 — 호출자의 가입 트랜잭션에 그대로 참여해야 계정과
 * 인벤토리가 함께 커밋된다. 여기에 {@code REQUIRES_NEW} 를 붙이면 가입이 롤백돼도 인벤토리만 남는다.
 */
@Service
@RequiredArgsConstructor
public class DefaultCharacterGrantService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCharacterGrantService.class);

    private final CharacterRepository characterRepository;
    private final CharacterItemRepository characterItemRepository;
    private final UserCharacterInventoryRepository characterInventoryRepository;
    private final UserCharacterItemInventoryRepository itemInventoryRepository;

    /**
     * <b>기본 세트가 없으면 가입을 막지 않고 건너뛴다.</b> 꾸미기 데이터가 빠졌다고 서비스의 입구인
     * 회원가입 전체를 500 으로 세우는 것은 손해가 훨씬 크다. 조용히 넘어가는 것이 아닌 이유는 두 가지다:
     * 아래 ERROR 로그가 그 사건을 남기고, 시드({@code character-asset-init.sql})의 백필이 매 기동마다
     * {@code users_account} 전 행을 훑어 빠진 행을 채우므로 <b>다음 배포에 자동으로 복구된다</b>.
     *
     * <p>그래서 {@code /users/me} 는 캐릭터가 없는 계정도 200 을 유지해야 한다(응원 구단·bq 점수가
     * 이미 같은 성질이다).
     */
    public void grantDefaults(UserAccount account) {
        Optional<Character> character = characterRepository.findByName(DefaultCharacterPolicy.CHARACTER_NAME);
        if (character.isPresent()) {
            characterInventoryRepository.save(UserCharacterInventory.builder()
                    .userAccount(account)
                    .character(character.get())
                    .active(true)
                    .build());
        } else {
            log.error("기본 캐릭터 시드가 없어 지급을 건너뛴다: name={}",
                    DefaultCharacterPolicy.CHARACTER_NAME);
        }

        Optional<CharacterItem> item = characterItemRepository.findByName(DefaultCharacterPolicy.ITEM_NAME);
        if (item.isPresent()) {
            itemInventoryRepository.save(UserCharacterItemInventory.builder()
                    .userAccount(account)
                    .characterItem(item.get())
                    // 기본 의상만 착용된 채로 지급된다 — 가입 직후 캐릭터가 알몸으로 보이지 않게 하는
                    // 것이 이 true 의 전부다. 구매 경로가 false 로 넣는 것과 대비된다.
                    .active(true)
                    .build());
        } else {
            log.error("기본 아이템 시드가 없어 지급을 건너뛴다: name={}", DefaultCharacterPolicy.ITEM_NAME);
        }
    }
}
