package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.UserCharacterInventory;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCharacterInventoryRepository
        extends JpaRepository<UserCharacterInventory, Long> {

    /**
     * 지금 쓰고 있는 캐릭터 행을 캐릭터까지 함께 가져온다.
     *
     * <p>반환형이 {@code Optional} 인 것이 "한 계정에 켜진 캐릭터는 최대 하나"라는 정책이 드러나는 유일한
     * 자리다 — 스키마가 막지 못하므로 깨진 데이터가 여기서 예외로 드러난다. {@code findFirst} 로 바꾸지 말 것.
     */
    @EntityGraph(attributePaths = "character")
    Optional<UserCharacterInventory> findWithCharacterByUserAccount_IdAndActiveIsTrue(
            Long userAccountId);

    boolean existsByUserAccount_IdAndCharacter_Id(Long userAccountId, Long characterId);
}
