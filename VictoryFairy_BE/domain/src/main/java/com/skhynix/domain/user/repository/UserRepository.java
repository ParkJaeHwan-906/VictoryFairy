package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    boolean existsByTel(String tel);

    /**
     * 예약 계정 부트스트랩 전용 조회 — {@code users} 행은 남아 있는데 {@code users_account} 행만 없는
     * 상태에서 다시 만들려 할 때, email UNIQUE 충돌 대신 기존 행을 재사용하기 위해 필요하다.
     * 일반 인증 경로는 계정({@code users_account})에서 출발하므로 이 메서드를 쓰지 않는다.
     */
    Optional<User> findByEmail(String email);

    /**
     * 계정 본체를 <b>물리적으로</b> 지운다 — 저장소에서 사용자 데이터를 실제로 삭제하는 유일한 경로다
     * (만료 데이터 정리, {@code docs/requirements/user/expired-data-cleanup.md}). 되돌릴 수 없고
     * 아카이브도 남기지 않는다.
     *
     * <p>지우는 행이 {@code users} 하나뿐인 이유: 자식 정리는 애플리케이션이 아니라 <b>DB 제약</b>이
     * 한다. {@code users_account} 가 CASCADE 로 따라 지워지고, 그 아래 토큰·BQ·응원·퀴즈 제출도 다시
     * CASCADE 로, {@code quizzes_like} 는 SET NULL 로 처리된다.
     *
     * <p>⚠ 그래서 이 호출 <b>전에</b> 이관(채팅방·채팅)이 반드시 끝나 있어야 한다.
     * {@code chatrooms.owner_account_id} 는 NO ACTION + NOT NULL 이라 남아 있으면 이 삭제 자체가 FK
     * 위반으로 실패한다(그 실패가 곧 fail-closed 안전망이다 — 이관 없는 삭제는 공용 데이터 소실이다).
     *
     * <p>엔티티 로딩 없는 벌크 삭제인 이유는 성능이 아니라 <b>안전</b>이다. {@code deleteById} 는 행을
     * 먼저 읽어 영속성 컨텍스트에 올리는데, 이 경로가 읽어야 할 개인정보는 하나도 없다.
     *
     * @return 삭제된 행 수. 0 이면 다른 파드가 먼저 지웠다는 뜻이다(예외가 아니라 정상 경로).
     */
    @Modifying
    @Query("delete from User u where u.id = :id")
    int deleteUserById(@Param("id") Long id);
}
