package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.Character;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    /**
     * 이름으로 캐릭터를 찾는다. 가입 시 기본 캐릭터를 지급하는 경로가 이것을 쓴다 — id 를 상수로 박으면
     * 시드가 만든 AUTO_INCREMENT 값에 코드가 묶여, 환경마다 id 가 다른 순간 엉뚱한 캐릭터를 지급한다.
     *
     * <p>반환형이 {@code Optional} 이라 같은 이름이 두 행이면 조회가 예외로 죽는다 —
     * {@code uk_characters_name} 이 그 상태를 만들어지는 순간 막는다.
     */
    Optional<Character> findByName(String name);
}
