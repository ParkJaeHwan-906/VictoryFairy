package com.skhynix.user.account.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 생명주기(탈퇴) 담당. 토큰 발급/재발급은 {@code AuthService}가 갖는다.
 *
 * <p>탈퇴가 refresh 토큰 폐기를 포함하지만 {@code AuthService}에 얹지 않은 이유: 탈퇴는 토큰을
 * <b>만들지</b> 않고 <b>지우기만</b> 하므로 {@code AuthService}의 협력자 중 절반
 * ({@code PasswordEncoder}·{@code JwtTokenProvider}·{@code UserRepository})이 필요 없다.
 * 토큰 폐기는 {@code UserRefreshTokenRepository.expireValidTokens}라는 도메인 계약을 통해
 * 공유되므로 로직이 중복되지도 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;

    /**
     * 계정을 탈퇴 처리한다(soft delete). 즉시 완료되며 유예 기간도 취소도 없다.
     *
     * <p>{@code exit_at} 기록과 refresh 토큰 만료를 한 트랜잭션에서 같은 시각으로 처리한다.
     * access 토큰은 stateless라 폐기할 수 없고, 대신 {@code JwtAuthenticationFilter}가 요청마다
     * 활성 계정을 확인하므로 탈퇴 즉시 인증이 끊긴다.
     *
     * @param userAccountId 인증된 요청의 principal({@code JwtAuthenticationFilter}가 uid를 해석해 넣은 내부 PK)
     */
    @Transactional
    public void withdraw(Long userAccountId) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면
        // 인증 근거가 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다.
        UserAccount account = userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        LocalDateTime now = LocalDateTime.now();
        account.withdraw(now);
        userRefreshTokenRepository.expireValidTokens(account, now);
    }
}
