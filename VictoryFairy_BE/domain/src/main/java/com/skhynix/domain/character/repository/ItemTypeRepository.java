package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.ItemType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemTypeRepository extends JpaRepository<ItemType, Long> {

    /** 부위명으로 찾는다. 이름을 자연키처럼 쓰는 이유는 {@code CharacterRepository.findByName} 과 같다. */
    Optional<ItemType> findByName(String name);
}
