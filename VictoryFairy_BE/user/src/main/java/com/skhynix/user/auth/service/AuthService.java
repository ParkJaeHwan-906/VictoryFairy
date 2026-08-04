package com.skhynix.user.auth.service;

import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.entity.UserRefreshToken;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.websupport.jwt.JwtTokenProvider;
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
    private final UserBqRepository userBqRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public Long signup(SignupRequest request) {
        // 검사 순서: 형식(@Valid, 400) → 이메일 인증완료 여부(EMAIL_NOT_VERIFIED) → 중복(409).
        // 인증완료 상태(USER-EMV-15)가 선행 조건이며, 미인증/만료(키 부재)는 EMAIL_NOT_VERIFIED로 거부한다.
        if (!emailVerificationService.isEmailVerified(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
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

        // 계정과 같은 트랜잭션에서 bq 행을 만든다(USER-ME-23/24) — 별도 커밋·비동기로 빼면 "계정은
        // 있는데 bq 행이 없는" 상태가 생긴다(USER-ME-25/30).
        userBqRepository.save(UserBq.builder()
                .userAccount(account)
                .build());

        // 가입 성공 시 인증완료 상태를 소비(1회용) — 같은 이메일 재가입 시 재인증을 강제한다(USER-EMV-18).
        emailVerificationService.consumeVerified(request.email());

        return account.getId();
    }

    /**
     * 닉네임 사전 검사(2단 파이프라인: 정책 → 중복). 정책 위반이면 중복(DB) 검사를 생략하고 즉시
     * 반환한다. 항상 예외 없이 {@link NicknameValidationResponse}로 반환한다(컨트롤러가 200 고정).
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
     * 닉네임 중복 <b>단독</b> 검사. {@link #validateNickname(String)}와 달리 정책 검사 없이
     * {@link #isNicknameDuplicated(String)}만 호출한다 — 정책 위반이지만 미점유인 닉네임에도
     * {@code valid:true}를 반환할 수 있다("사용 가능"은 중복 아님을 뜻할 뿐 가입 가능 보장이 아니다).
     */
    public NicknameValidationResponse checkNicknameDuplicate(String nickname) {
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
        // login은 permitAll이라 필터를 안 타 탈퇴 여부를 여기서 판정한다. exit_at is null 조건으로
        // 탈퇴 계정을 미가입 이메일과 같은 경로(INVALID_CREDENTIALS)로 흡수해 가입 이력을 노출하지 않는다.
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

        // refresh는 permitAll이라 필터를 안 타 탈퇴 여부를 여기서 판정한다. 탈퇴가 유효 토큰을 모두
        // 만료시키므로 보통 위 만료 검사에 먼저 걸리지만, 탈퇴와 로그인이 동시에 일어나면 탈퇴 직후
        // 발급된 토큰이 살아남을 수 있어 계정 상태로 한 번 더 막는다(계정 상태 비노출을 위해 같은
        // EXPIRED_REFRESH_TOKEN을 쓴다).
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
