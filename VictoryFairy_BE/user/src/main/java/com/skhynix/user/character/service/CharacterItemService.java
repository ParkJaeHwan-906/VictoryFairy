package com.skhynix.user.character.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.character.entity.CharacterItem;
import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import com.skhynix.domain.character.repository.CharacterItemRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.character.dto.CharacterItemActiveResponse;
import com.skhynix.user.character.dto.CharacterItemPurchaseResponse;
import com.skhynix.user.character.dto.CharacterItemResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterItemService {

    private final CharacterItemRepository characterItemRepository;
    private final UserCharacterItemInventoryRepository inventoryRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 상점 + 인벤토리 통합 목록. 카탈로그 전체를 돌려주고 보유·착용 여부만 계정별로 채운다 — 두 화면이
     * 같은 목록을 쓰므로 "안 산 것만"으로 좁히지 말 것(좁히면 인벤토리 화면이 이 API 로 못 그려진다).
     *
     * <p>SELECT 3회로 닫는다: 카탈로그(부위 포함) + 보유 id + 착용 id. 아이템마다 보유 여부를 묻는
     * 모양으로 바꾸면 카탈로그 크기만큼 쿼리가 늘어난다.
     */
    public List<CharacterItemResponse> findAll(Long userAccountId) {
        List<CharacterItem> catalog = characterItemRepository.findAllByOrderByItemType_IdAscIdAsc();
        Set<Long> owned = new HashSet<>(inventoryRepository.findOwnedCharacterItemIds(userAccountId));
        Set<Long> active = new HashSet<>(inventoryRepository.findActiveCharacterItemIds(userAccountId));

        return catalog.stream()
                .map(item -> CharacterItemResponse.of(
                        item, owned.contains(item.getId()), active.contains(item.getId())))
                .toList();
    }

    /**
     * 아이템 구매 — 포인트 차감과 보유 행 생성이 한 트랜잭션이다.
     *
     * <p>계정 행을 <b>가장 먼저</b> 잠근다({@code findWithLockById}). 동시 구매가 없다는 것이 사용자
     * 전제이지만, 같은 계정으로 구매와 퀴즈 적립이 겹치면 잠금 없이는 한쪽 갱신이 통째로 유실된다
     * (적립 경로가 이미 같은 잠금을 요구한다). 잠금을 트랜잭션 맨 앞에 두는 것은 잠금 순서를 고정해
     * 교착을 피하기 위해서다.
     *
     * <p>검사 순서는 <b>존재 → 중복 보유 → 잔액</b>이다. 잔액을 먼저 보면 이미 가진 아이템을 다시
     * 사려는 사용자에게 "포인트가 부족합니다"가 나가 원인을 오해하게 된다.
     */
    @Transactional
    public CharacterItemPurchaseResponse purchase(Long userAccountId, Long characterItemId) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
        // 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다(UserProfileService 와 동일).
        UserAccount account = userAccountRepository.findWithLockById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        CharacterItem item = characterItemRepository.findById(characterItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARACTER_ITEM_NOT_FOUND));

        if (inventoryRepository.existsByUserAccount_IdAndCharacterItem_Id(userAccountId, characterItemId)) {
            throw new BusinessException(ErrorCode.CHARACTER_ITEM_ALREADY_OWNED);
        }
        if (account.getPoint() < item.getPrice()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }

        account.deductPoint(item.getPrice());
        inventoryRepository.save(UserCharacterItemInventory.builder()
                .userAccount(account)
                .characterItem(item)
                // 산 아이템은 꺼진 채로 들어온다 — 근거는 CharacterItemPurchaseResponse 주석 참고.
                .active(false)
                .build());

        return new CharacterItemPurchaseResponse(item.getId(), account.getPoint());
    }

    /**
     * 착용 on/off 토글.
     *
     * <p>대상은 (계정, 아이템)으로만 찾는다 — 인벤토리 행 id 를 받지 않는 이유는 그것이 남의 행을 가리킬
     * 수 있는 식별자이기 때문이다. 이 조회 자체가 소유권 검사를 겸한다.
     *
     * <p>켜는 경우에만 같은 부위의 기존 착용을 끈다. <b>끄는 요청은 그것만 하고 끝낸다</b> — 끄면서 다른
     * 아이템을 대신 켜면 사용자가 요청하지 않은 착용이 생긴다.
     */
    @Transactional
    public CharacterItemActiveResponse toggleActive(Long userAccountId, Long characterItemId) {
        UserCharacterItemInventory target = inventoryRepository
                .findByUserAccount_IdAndCharacterItem_Id(userAccountId, characterItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARACTER_ITEM_NOT_OWNED));

        if (target.isActive()) {
            target.deactivate();
            return new CharacterItemActiveResponse(characterItemId, false);
        }

        Long itemTypeId = target.getCharacterItem().getItemType().getId();
        // 이미 켜진 행이 곧 target 인 경우는 위 분기에서 걸러졌으므로 여기서 나오는 행은 항상 다른 행이다.
        inventoryRepository.findActiveByUserAccountIdAndItemTypeId(userAccountId, itemTypeId)
                .ifPresent(UserCharacterItemInventory::deactivate);
        target.activate();

        // 두 변경은 같은 트랜잭션의 더티 체킹으로 함께 flush 된다 — 사이에 다른 요청이 "부위에 둘이
        // 켜진" 상태를 볼 수 없다. 순서를 위해 flush 를 끼워 넣지 말 것(UNIQUE 가 걸린 조합이 아니다).
        return new CharacterItemActiveResponse(characterItemId, true);
    }
}
