package com.skhynix.user.auth.service;

import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserRefreshToken;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @Transactional
    public Long signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByTel(request.tel())) {
            throw new BusinessException(ErrorCode.DUPLICATE_TEL);
        }
        if (userAccountRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        User user = userRepository.save(User.builder()
                .name(request.name())
                .tel(request.tel())
                .email(request.email())
                .gender(request.gender())
                .build());

        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .user(user)
                .nickname(request.nickname())
                .password(passwordEncoder.encode(request.password()))
                .build());

        return account.getId();
    }

    /**
     * 닉네임 사전 검사(2단 파이프라인). <b>정책 검사 → 중복 검사</b> 순서로 수행하며, 세 결과 모두
     * 예외 없이 {@link NicknameValidationResponse}로 반환한다(컨트롤러가 항상 200으로 응답).
     *
     * <p>단계는 각각 독립된 판정 메서드로 분리돼 있고({@link #findNicknamePolicyViolation(String)},
     * {@link #isNicknameDuplicated(String)}), 이 메서드는 그 둘을 순서대로 호출하는 오케스트레이션만
     * 담당한다. <b>정책이 위반이면 즉시 반환하고 중복(DB) 검사는 수행하지 않는다</b>(우선순위:
     * 길이 → 문자 → 중복). 정책을 통과했을 때만 중복 검사로 넘어간다.
     */
    public NicknameValidationResponse validateNickname(String nickname) {
        Optional<String> policyViolation = findNicknamePolicyViolation(nickname);
        if (policyViolation.isPresent()) {
            return NicknameValidationResponse.violated(policyViolation.get());
        }
        if (isNicknameDuplicated(nickname)) {
            return NicknameValidationResponse.violated(ErrorCode.DUPLICATE_NICKNAME.getMessage());
        }
        return NicknameValidationResponse.passed();
    }

    /**
     * 1단계: 정책 검사(순수, DB 미조회). {@link NicknamePolicy#findViolation(String)}에 위임한다.
     * signup 검증({@code @ValidNickname})과 문자 그대로 같은 함수를 공유한다.
     *
     * @return 위반 시 정책 위반 메시지, 통과 시 {@link Optional#empty()}
     */
    public Optional<String> findNicknamePolicyViolation(String nickname) {
        return NicknamePolicy.findViolation(nickname);
    }

    /**
     * 2단계: 중복 검사(DB 조회). signup과 동일한 {@code existsByNickname}을 재사용해 두 경로의 중복
     * 판정이 어긋나지 않게 한다. 이 메서드는 {@code exit_at}을 거르지 않아 탈퇴 닉네임도 점유로 잡는다.
     */
    public boolean isNicknameDuplicated(String nickname) {
        return userAccountRepository.existsByNickname(nickname);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // login은 permitAll이라 JwtAuthenticationFilter를 타지 않으므로 탈퇴 여부를 여기서 판정한다.
        // 조회 자체가 활성 계정만 대상으로 하므로 탈퇴 계정은 미가입 이메일과 동일한 경로(비밀번호
        // 검사 없이 INVALID_CREDENTIALS)로 흡수된다 — 가입 이력을 노출하지 않기 위한 의도된 동작이다.
        UserAccount account = userAccountRepository.findByUser_EmailAndExitAtIsNull(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), account.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return issueTokens(account);
    }

    @Transactional
    public TokenResponse reissue(String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        UserRefreshToken stored = userRefreshTokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN));

        if (!stored.getExpiredAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }

        // refresh는 permitAll이라 JwtAuthenticationFilter를 타지 않으므로 탈퇴 여부를 여기서 판정한다.
        // 탈퇴가 유효 토큰을 모두 만료시키므로 보통은 위 만료 검사에 먼저 걸리지만, 탈퇴와 로그인이
        // 동시에 일어나면 탈퇴 직후 발급된 토큰이 살아남을 수 있어 계정 상태로 한 번 더 막는다.
        // account는 아래 issueTokens가 어차피 초기화하므로 이 검사로 추가 조회가 생기지는 않는다.
        // 만료와 응답이 같아야 하므로(계정 상태를 노출하지 않는다) 같은 EXPIRED_REFRESH_TOKEN을 쓴다.
        UserAccount account = stored.getUserAccount();
        if (account.isWithdrawn()) {
            throw new BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }

        // issueTokens가 이 토큰을 포함한 account의 유효 토큰을 모두 만료시킨 뒤 새로 발급한다.
        return issueTokens(account);
    }

    @Transactional
    public void logout(String refreshToken) {
        userRefreshTokenRepository.findByRefreshToken(refreshToken)
                .ifPresent(userRefreshTokenRepository::delete);
    }

    private TokenResponse issueTokens(UserAccount account) {
        // 유저당 유효 refresh token 1개 유지: 기존 유효 토큰을 즉시 만료시킨 뒤 발급
        userRefreshTokenRepository.expireValidTokens(account, LocalDateTime.now());

        String accessToken = tokenProvider.createAccessToken(account.getUid());
        String refreshToken = tokenProvider.createRefreshToken(account.getUid());

        userRefreshTokenRepository.save(UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(tokenProvider.getExpiration(refreshToken))
                .build());

        return new TokenResponse(accessToken, refreshToken);
    }
}
