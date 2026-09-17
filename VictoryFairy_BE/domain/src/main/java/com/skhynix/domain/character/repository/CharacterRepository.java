package com.skhynix.domain.character.repository;

import com.skhynix.domain.character.entity.Character;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    // 가입 지급이 id 가 아니라 이름으로 대상을 찾는다 — 시드가 AUTO_INCREMENT 로 만들어 환경마다 id 가
    // 다르므로, 상수로 박으면 로컬에서는 맞고 운영에서만 엉뚱한 캐릭터가 지급된다.
    Optional<Character> findByName(String name);
}
