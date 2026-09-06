package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.CharacterItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterItemRepository extends JpaRepository<CharacterItem, Long> {

    /**
     * 상점 전체 카탈로그. {@code @EntityGraph} 를 빼면 응답이 싣는 부위명 때문에 아이템 수만큼 SELECT 가
     * 더 나간다.
     *
     * <p>1차 정렬 키가 <b>부위</b>인 것은 상점이 부위별로 묶여 보이기 때문이다. id 만으로 정렬하면 시드의
     * {@code INSERT ... SELECT} 가 조인 결과 순서대로 AUTO_INCREMENT 를 매겨 부위가 뒤섞인 채 나간다.
     */
    @EntityGraph(attributePaths = "itemType")
    List<CharacterItem> findAllByOrderByItemType_IdAscIdAsc();

    /** 가입 지급용 — id 상수를 피하는 이유는 {@code CharacterRepository.findByName} 과 같다. */
    Optional<CharacterItem> findByName(String name);
}
