package com.skhynix.user.oauth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.entity.UserOauthLink;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.domain.user.repository.UserOauthLinkRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.oauth.policy.SocialAccountPolicy;
import com.skhynix.user.oauth.store.OauthTicket;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 신규 가입의 트랜잭션 단위.
 *
 * <p>티켓 조회·소비(Redis)와 클래스를 나눈 이유는 {@code ExpiredAccountEraser}·{@code QuizLikeToggler}
 * 와 같다 — 같은 빈 안에서 부르면 프록시를 타지 않아 {@code @Transactional} 이 아예 안 걸린다.
 * 티켓 소비를 이 트랜잭션 밖(성공 이후)에 두는 것도 의도다: 닉네임 중복으로 거절된 요청이 티켓을
 * 태워 버리면 사용자가 다른 닉네임으로 재시도할 길이 없어진다.
 */
@Service
@RequiredArgsConstructor
public class OauthAccountWriter {

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserBqRepository userBqRepository;
    private final UserOauthLinkRepository userOauthLinkRepository;
    private final AuthService authService;
    private final Clock clock;

    /**
     * 소셜 신규 가입 — {@code users}·{@code users_account}·{@code users_bq}·연동 행이 <b>한 트랜잭션</b>
     * 에서 만들어진다(자체 가입이 {@code UserBq} 를 함께 만드는 것과 같은 이유: 하나라도 빠지면
     * "계정은 있는데 행이 없는" 상태가 남는다).
     */
    @Transactional
    public TokenResponse createAccount(OauthTicket ticket, String nickname) {
        // 티켓 발급과 이 요청 사이에 그 이메일이 점유될 수 있다(탈퇴 계정 포함 — email UNIQUE 는
        // 탈퇴를 구분하지 않는다). 검사 순서는 자체 가입과 같게 이메일 → 닉네임이다.
        if (userRepository.existsByEmail(ticket.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userAccountRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        // 자체 가입의 빌더가 아니라 전용 팩터리다 — 미검증 계정을 만들 수 있는 유일한 길이고,
        // name·tel·gender 는 NULL 로 남는다.
        User user = userRepository.save(User.socialSignup(ticket.email(), ticket.emailVerified()));

        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .user(user)
                .nickname(nickname)
                .password(SocialAccountPolicy.LOCKED_PASSWORD)
                .build());

        userBqRepository.save(UserBq.builder()
                .userAccount(account)
                .build());

        userOauthLinkRepository.save(UserOauthLink.builder()
                .userAccount(account)
                .provider(ticket.provider())
                .providerUserId(ticket.providerUserId())
                .build());

        return authService.issueTokens(account, LocalDateTime.now(clock));
    }
}
