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
     * 이 계정의 보유 아이템 id 목록. 상점 목록이 {@code having} 을 채우는 데 쓴다 — 아이템 행 자체는
     * 카탈로그 조회가 이미 갖고 있으므로 여기서는 id 만 꺼내 SELECT 를 2회로 닫는다(아이템마다
     * {@code exists} 를 도는 N+1 을 만들지 말 것).
     */
    @Query("select i.characterItem.id from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId")
    List<Long> findOwnedCharacterItemIds(@Param("userAccountId") Long userAccountId);

    /** 중복 구매 판정. 스키마의 UNIQUE 와 이중으로 막는다(주석은 엔티티 참고). */
    boolean existsByUserAccount_IdAndCharacterItem_Id(Long userAccountId, Long characterItemId);

    /**
     * 이 계정이 <b>착용 중인</b> 아이템 id 목록. 목록 API 가 {@code active} 를 채우는 데 쓴다 —
     * {@link #findOwnedCharacterItemIds} 와 같은 이유로 엔티티가 아니라 id 만 꺼낸다.
     */
    @Query("select i.characterItem.id from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId and i.active = true")
    List<Long> findActiveCharacterItemIds(@Param("userAccountId") Long userAccountId);

    /**
     * 토글 대상. 보유하지 않은 아이템이면 비어 있고, 그것이 곧 404 근거다.
     *
     * <p>부위를 바로 이어 읽으므로({@code characterItem.itemType.id}) 함께 가져온다 — 빼면 LAZY 프록시
     * 초기화로 SELECT 가 2회 더 나간다.
     */
    @EntityGraph(attributePaths = {"characterItem", "characterItem.itemType"})
    Optional<UserCharacterItemInventory> findByUserAccount_IdAndCharacterItem_Id(
            Long userAccountId, Long characterItemId);

    /**
     * 같은 부위에서 이미 켜져 있는 행. 토글이 켜기 직전에 이것을 꺼서 "부위당 하나"를 지킨다.
     *
     * <p>부위는 이 테이블이 아니라 {@code character_items.item_type_id} 에 있어 파생 쿼리로는 표현이
     * 길어지므로 JPQL 로 쓴다. 반환형이 {@code Optional} 인 근거는 캐릭터 쪽과 같다 — 정책이 깨진
     * 데이터를 조용히 흘려보내지 않는다.
     */
    @Query("select i from UserCharacterItemInventory i "
            + "where i.userAccount.id = :userAccountId "
            + "and i.characterItem.itemType.id = :itemTypeId "
            + "and i.active = true")
    Optional<UserCharacterItemInventory> findActiveByUserAccountIdAndItemTypeId(
            @Param("userAccountId") Long userAccountId, @Param("itemTypeId") Long itemTypeId);

    /**
     * 착용 중인 전체 행({@code /users/me} 가 겹쳐 그릴 이미지 EP 를 싣는다). 응답이 부위명과 이미지를
     * 함께 쓰므로 아이템과 부위를 한 번에 가져온다 — 빼면 착용 아이템 수만큼 SELECT 가 더 나간다.
     */
    @EntityGraph(attributePaths = {"characterItem", "characterItem.itemType"})
    List<UserCharacterItemInventory> findAllByUserAccount_IdAndActiveIsTrue(Long userAccountId);
}
