package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUser_Email(String email);

    /**
     * {@code uid}로 내부 PK만 조회한다.
     *
     * <p>InnoDB secondary index는 PK를 품으므로 {@code uid} unique 인덱스는 물리적으로 이미
     * {@code (uid, id)}다. {@code id}만 SELECT하면 클러스터드 인덱스 북마크 조회 없이
     * 인덱스만으로 끝난다(커버링 인덱스). 엔티티 하이드레이션·영속성 컨텍스트 적재도 없다.
     * 파생 쿼리 이름 규칙으로는 이 형태가 보장되지 않아 {@code @Query}로 명시한다.
     */
    @Query("select ua.id from UserAccount ua where ua.uid = :uid")
    Optional<Long> findIdByUid(@Param("uid") String uid);

    boolean existsByNickname(String nickname);
}
