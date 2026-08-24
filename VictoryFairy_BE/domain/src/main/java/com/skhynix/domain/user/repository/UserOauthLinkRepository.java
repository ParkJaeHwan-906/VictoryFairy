package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.domain.user.entity.UserOauthLink;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserOauthLinkRepository extends JpaRepository<UserOauthLink, Long> {

    /**
     * 신원 해석 1순위 — 이미 이 소셜 신원을 쓰던 계정을 찾는다.
     *
     * <p>여기서 찾히면 <b>이메일은 보지 않는다</b>. provider 쪽에서 이메일을 바꾼 사용자가 재인증할 때
     * 이메일 대조로 떨어지면 남남이 되어 버리기 때문이다.
     *
     * <p>탈퇴 여부를 거르지 않는 것은 의도다. 호출자는 찾은 계정이 탈퇴 상태면 "못 찾음"이 아니라
     * 409(이메일 점유)로 답해야 하는데, 여기서 걸러 버리면 그 구분이 사라져 <b>탈퇴한 본인에게 신규
     * 가입을 권하고</b> 그 가입이 다시 email UNIQUE 로 실패하는 막다른 길이 된다.
     */
    Optional<UserOauthLink> findByProviderAndProviderUserId(OauthProvider provider,
            String providerUserId);

    /**
     * 이 계정에 그 provider 연동이 이미 있는가.
     *
     * <p>{@link #findByProviderAndProviderUserId}가 비어 있는데 이것이 {@code true}라면, 그 계정에 붙어
     * 있는 것은 <b>같은 provider 의 다른 식별자</b>라는 뜻이다(같은 식별자였다면 위에서 찾혔다).
     * 그대로 INSERT 하면 {@code uk_users_oauth_link_account_provider} 위반이므로 호출자가 먼저 거절한다.
     */
    boolean existsByUserAccount_IdAndProvider(Long userAccountId, OauthProvider provider);

    /**
     * 계정에 붙어 있는 연동 전부. 아래 일괄 해제 직전에 "무엇을 끊는지" 남기려는 호출자용이다
     * (연동 목록을 사용자에게 노출하는 API 는 이번 범위에 없다).
     */
    List<UserOauthLink> findAllByUserAccount_Id(Long userAccountId);

    /**
     * 계정에 붙은 연동을 전부 끊는다 — 미검증이던 계정이 이메일 코드 인증으로 <b>검증됨으로 승격되는
     * 순간</b> 부르는, 선점 계정을 되찾기 위한 경로다. 시스템 내부 동작이며 사용자에게 여는 기능이 아니다.
     *
     * <p>끊는 이유는 승격 이전의 연동이 <b>진짜 주인의 것인지 선점자의 것인지 구분할 수단이 없기</b>
     * 때문이다. 남겨 두면 주인이 합류한 뒤에도 선점자가 자기 소셜로 계속 그 계정에 들어온다. 대가로
     * 정상 사용자도 자기 연동을 한 번 잃고 다음 로그인 때 코드 인증을 1회 더 하지만, 그때는 계정이
     * 이미 검증됨이라 반복되지 않는다.
     *
     * <p>⚠ <b>새 연동 행을 persist 한 뒤에 부르면 방금 만든 그 행까지 지워진다.</b> 이 벌크 삭제는
     * {@code users_oauth_link} 를 건드리므로 Hibernate 가 실행 직전 대기 중인 INSERT 를 자동 flush 하고,
     * 그 INSERT 도 같은 계정 소속이라 그대로 삭제 대상에 들어간다(트랜잭션은 성공하고 연동만 사라진다 —
     * 조용한 실패다). <b>반드시 해제 먼저, 새 연동 생성은 그 다음</b>이다.
     *
     * <p>엔티티를 읽지 않고 지운다. 지울지 말지의 판단은 이미 끝난 상태이고 이 경로가 읽어야 할 값이
     * 없다({@code UserRepository.deleteUserById} 와 같은 계열).
     *
     * @return 끊긴 연동 수. 0 이면 원래 연동이 없던 계정이라는 뜻이다(예외가 아니라 정상 경로).
     */
    @Modifying
    @Query("delete from UserOauthLink l where l.userAccount.id = :userAccountId")
    int deleteAllByUserAccountId(@Param("userAccountId") Long userAccountId);
}
