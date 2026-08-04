package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Registration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationRepository extends JpaRepository<Registration, Long> {

    /**
     * 특정 날짜의 1군 등록 스냅샷 전체. {@code Team_Id} 로 끊는 이유는
     * {@link PlayerRepository#findAllByTeam_IdOrderByNameAsc} 와 같다 — FK 컬럼 조건만
     * 걸어 {@code Team} 조인을 피한다.
     */
    List<Registration> findAllByRegistrationDate(LocalDate registrationDate);

    List<Registration> findAllByRegistrationDateAndTeam_Id(LocalDate registrationDate, Long teamId);
}
