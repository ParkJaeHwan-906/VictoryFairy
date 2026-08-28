package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.CharacterItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterItemRepository extends JpaRepository<CharacterItem, Long> {

    /**
     * 상점 전체 카탈로그. 응답이 부위명({@code itemType.name})을 싣기 때문에 {@code @EntityGraph} 로 함께
     * 가져온다 — 빼면 아이템 수만큼 SELECT 가 더 나간다(23행이면 1 + 23회).
     *
     * <p>정렬을 못 박는 것은 상점 진열 순서를 결정론적으로 만들기 위해서다. 정렬이 없으면 순서가 실행
     * 계획에 달려 있어 같은 요청이 배포마다 다르게 보인다.
     *
     * <p>1차 키가 <b>부위</b>인 것은 상점이 부위별로 묶여 보이기 때문이다. id 만으로 정렬하면 시드의
     * INSERT ... SELECT 가 조인 결과 순서대로 AUTO_INCREMENT 를 매겨(= UNION ALL 을 적은 순서가 아니다)
     * 의상·모자·소품이 뒤섞인 채 나간다 — 실제로 그렇게 나갔던 것을 보고 고친 정렬이다.
     */
    @EntityGraph(attributePaths = "itemType")
    List<CharacterItem> findAllByOrderByItemType_IdAscIdAsc();

    /** 가입 시 기본 아이템을 지급하는 경로용 — id 상수를 피하는 이유는 캐릭터 쪽과 같다. */
    Optional<CharacterItem> findByName(String name);
}
