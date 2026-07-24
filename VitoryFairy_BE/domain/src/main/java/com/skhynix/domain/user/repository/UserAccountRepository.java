package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUser_Email(String email);

    boolean existsByNickname(String nickname);
}
