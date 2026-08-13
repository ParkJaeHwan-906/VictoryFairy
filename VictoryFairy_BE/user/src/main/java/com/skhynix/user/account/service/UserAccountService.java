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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;

    // access 토큰은 stateless 라 여기서 폐기할 수 없다 — JwtAuthenticationFilter 가 요청마다 활성 계정을
    // 확인하는 것이 탈퇴 즉시 인증을 끊는 실제 지점이다.
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
