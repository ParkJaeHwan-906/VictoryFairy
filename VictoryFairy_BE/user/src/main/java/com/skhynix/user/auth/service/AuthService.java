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
import com.skhynix.user.character.service.DefaultCharacterGrantService;
import com.skhynix.user.profileimage.service.SignupProfileImageService;
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

    /**
     * 계정을 못 찾은 로그인 요청에도 BCrypt 를 1회 태우기 위한 더미 해시(USER-LAE-2·3·13).
     *
     * <p>실제 계정 비밀번호와 같은 알고리즘·같은 cost(BCrypt 10 — {@code SecurityConfig} 의
     * {@code BCryptPasswordEncoder} 기본값)라서, 미가입·탈퇴 갈래의 응답시간이 가입 계정 갈래와
     * 같은 자릿수가 된다. 기동마다 새로 만들지 않고 코드 상수로 고정하는 이유는 파드마다 값이
     * 달라질 여지를 없애고 기동 시 BCrypt 인코딩 비용을 안 물기 위해서다(USER-LAE-13).
     *
     * <p>⚠ 이 값의 원문 비밀번호는 <b>어디에도 저장되지 않는다</b>(USER-LAE-5, 생성 시점에 버림).
     * 상수를 공개해도 안전한 근거는 두 겹이다: ①어떤 계정의 비밀번호도 이 해시가 아니고
     * ②아래 {@code login} 이 이 검증의 <b>결과를 분기 조건으로 쓰지 않는다</b>(USER-LAE-4).
     * 둘 중 하나라도 깨지면 "상수 노출 = 로그인 가능한 값의 노출"이 된다.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2y$10$Rx4fYJUBGQIDZWIW772nuOHZynkKR4iTN.qNjNn0NhWyPPtY7cQo2";

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final UserBqRepository userBqRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailVerificationService emailVerificationService;
    private final SignupProfileImageService signupProfileImageService;
    private final DefaultCharacterGrantService defaultCharacterGrantService;

    @Transactional
    public Long signup(SignupRequest request) {
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

        // 이미지 검증은 기존 검사들의 '뒤'다. 앞에 두면 인증 안 된 이메일·중복 이메일 요청에도
        // 이미지 오류가 먼저 응답돼 사용자가 진짜 원인을 못 본다(검사 순서: 형식 -> 이메일 인증 ->
        // 중복 -> 이미지).
        // 실패 갈래가 둘이라는 점이 중요하다: 못 쓰는 EP 는 여기서 400 으로 가입을 막고(예외가 이
        // 트랜잭션을 롤백시킨다), 저장소 장애로 인한 이동 실패는 null 을 돌려 가입을 그대로 진행시킨다.
        String profileImgUrl = signupProfileImageService.moveToPermanent(request.profileImgUrl());

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

        // 빌더가 아니라 저장 후 전이로 채운다(빌더 파라미터를 늘리면 기존 가입 호출부의 계약이
        // 흔들린다). 이동에 실패했으면 null 이라 컬럼도 null 로 남는다 - 그때도 아래 UserBq 행 생성과
        // 이메일 인증 소비는 그대로 일어난다.
        if (profileImgUrl != null) {
            account.changeProfileImgUrl(profileImgUrl);
        }

        // 계정과 같은 트랜잭션에서 bq 행을 만든다 — 별도 커밋·비동기로 빼면 "계정은
        // 있는데 bq 행이 없는" 상태가 생긴다.
        userBqRepository.save(UserBq.builder()
                .userAccount(account)
                .build());

        // 시드가 없으면 가입을 막지 않고 건너뛴다 — 근거는 DefaultCharacterGrantService 참고.
        defaultCharacterGrantService.grantDefaults(account);

        // 가입 성공 시 인증완료 상태를 소비(1회용) — 같은 이메일 재가입 시 재인증을 강제한다.
        emailVerificationService.consumeVerified(request.email());

        return account.getId();
    }

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

    public NicknameValidationResponse checkNicknameDuplicate(String nickname) {
        if (isNicknameDuplicated(nickname)) {
            return NicknameValidationResponse.violated(ErrorCode.DUPLICATE_NICKNAME.getMessage());
        }
        return NicknameValidationResponse.passed();
    }

    public Optional<String> findNicknamePolicyViolation(String nickname) {
        return NicknamePolicy.findViolation(nickname);
    }

    // exit_at 을 거르지 않아 탈퇴 계정의 닉네임도 점유로 잡는다(재가입 불가 정책과 일치).
    public boolean isNicknameDuplicated(String nickname) {
        return userAccountRepository.existsByNickname(nickname);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // login은 permitAll이라 필터를 안 타 탈퇴 여부를 여기서 판정한다. exit_at is null 조건으로
        // 탈퇴 계정을 미가입 이메일과 같은 경로(INVALID_CREDENTIALS)로 흡수해 가입 이력을 노출하지 않는다.
        Optional<UserAccount> found = userAccountRepository.findByUser_EmailAndExitAtIsNull(request.email());

        // 계정을 못 찾아도 여기서 빠져나가지 않는다. 곧바로 던지면 BCrypt 를 안 타 미가입·탈퇴 갈래만
        // 수십~수백 ms 빨라지고, 응답 내용으로 감춘 가입 여부가 응답시간으로 새어 나간다(USER-LAE-1·2·6).
        // 그래서 어느 갈래든 matches 는 정확히 1회 돈다 — 계정이 없으면 대상만 더미 해시로 바뀐다.
        String encodedPassword = found.map(UserAccount::getPassword).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatched = passwordEncoder.matches(request.password(), encodedPassword);

        // 판정 순서가 중요하다: 계정 존재를 먼저 보고, 더미 검증 결과는 분기에 쓰지 않는다(USER-LAE-4).
        // 뒤집어 matches 결과부터 보면 더미 해시를 맞히는 순간 계정 없이 토큰이 나간다.
        if (found.isEmpty() || !passwordMatched) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return issueTokens(found.get());
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

        // 비밀번호 변경보다 앞선 초에 발급된 refresh 는 행이 살아 있어도 거절한다. 변경이 유효 토큰을
        // 전부 만료시키므로 보통 위 만료 검사에 먼저 걸리지만, 옛 비밀번호로 진행 중이던 로그인이
        // expireValidTokens 이후에 INSERT 하면 유효한 옛 세션이 남는다 — 바로 위 탈퇴 검사와 정확히
        // 같은 종류의 레이스다. 계정은 위에서 이미 초기화돼 추가 조회는 0회이고, 계정 상태를 드러내지
        // 않으려고 응답도 같은 EXPIRED_REFRESH_TOKEN 을 쓴다.
        if (!account.acceptsTokenIssuedAt(tokenProvider.getIssuedAtEpochSecond(refreshToken))) {
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
        return issueTokens(account, LocalDateTime.now());
    }

    /**
     * 토큰 쌍 발급 — 기존 유효 refresh 토큰을 먼저 만료시킨 뒤 새로 발급한다(계정당 유효 refresh 1개).
     *
     * <p>비밀번호 변경 경로가 같은 절차를 필요로 해 공개한다. 발급 로직을 복제하면 "고쳐야 할 자리"가
     * 두 곳이 되므로 호출로 재사용한다. 호출자가 시각을 넘기는 이유는 {@code withdraw(LocalDateTime)}와
     * 같다 — 같은 트랜잭션의 다른 작업과 시각을 맞추고, {@code Clock} 빈을 가진 호출자가 시간대
     * 단일 출처를 유지할 수 있게 한다.
     */
    @Transactional
    public TokenResponse issueTokens(UserAccount account, LocalDateTime now) {
        // 유저당 유효 refresh token 1개 유지: 기존 유효 토큰을 즉시 만료시킨 뒤 발급
        userRefreshTokenRepository.expireValidTokens(account, now);

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
