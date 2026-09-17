package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCharacterItemInventoryRepository
        extends JpaRepository<UserCharacterItemInventory, Long> {

    /**
     * 이 계정의 보유 아이템 id 목록. 아이템 행은 카탈로그 조회가 이미 갖고 있으므로 id 만 꺼내 목록
     * 조회를 SELECT 3회로 닫는다 — 아이템마다 {@code exists} 를 도는 N+1 을 만들지 말 것.
     */
    @Query("select i.characterItem.id from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId")
    List<Long> findOwnedCharacterItemIds(@Param("userAccountId") Long userAccountId);

    /** 착용 중인 아이템 id 목록. 근거는 {@link #findOwnedCharacterItemIds} 와 같다. */
    @Query("select i.characterItem.id from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId and i.active = true")
    List<Long> findActiveCharacterItemIds(@Param("userAccountId") Long userAccountId);

    boolean existsByUserAccount_IdAndCharacterItem_Id(Long userAccountId, Long characterItemId);

    /** 토글 대상. 비어 있으면 미보유이고 그것이 곧 404 근거다. 부위를 바로 이어 읽으므로 함께 가져온다. */
    @EntityGraph(attributePaths = {"characterItem", "characterItem.itemType"})
    Optional<UserCharacterItemInventory> findByUserAccount_IdAndCharacterItem_Id(
            Long userAccountId, Long characterItemId);

    /**
     * 같은 부위에서 이미 켜져 있는 행 — 토글이 켜기 직전에 이것을 꺼서 "부위당 하나"를 지킨다.
     * 부위가 {@code character_items} 에 있어 파생 쿼리로는 길어지므로 JPQL 로 쓴다.
     */
    @Query("select i from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId "
            + "and i.characterItem.itemType.id = :itemTypeId "
            + "and i.active = true")
    Optional<UserCharacterItemInventory> findActiveByUserAccountIdAndItemTypeId(
            @Param("userAccountId") Long userAccountId, @Param("itemTypeId") Long itemTypeId);

    /** 착용 중인 전체 행. 응답이 부위명과 착용용 이미지를 함께 쓰므로 한 번에 가져온다. */
    @EntityGraph(attributePaths = {"characterItem", "characterItem.itemType"})
    List<UserCharacterItemInventory> findAllByUserAccount_IdAndActiveIsTrue(Long userAccountId);
}
