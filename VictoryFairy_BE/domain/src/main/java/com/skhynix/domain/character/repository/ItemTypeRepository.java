package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.ItemType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemTypeRepository extends JpaRepository<ItemType, Long> {

    Optional<ItemType> findByName(String name);
}
