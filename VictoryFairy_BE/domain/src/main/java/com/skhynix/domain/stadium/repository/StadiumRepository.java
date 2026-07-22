package com.skhynix.domain.stadium.repository;

import com.skhynix.domain.stadium.entity.Stadium;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StadiumRepository extends JpaRepository<Stadium, Long> {

    Optional<Stadium> findByName(String name);
}
