package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.entity.UserRefreshToken;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService}를 협력 객체 전부 목으로 대체해 단위로 검증한다. 특히 탈퇴 계정이 login·reissue·
 * signup 세 지점에서 어떻게 취급되는지(요구사항 {@code docs/requirements/user/withdraw.md})와, signup이
 * {@code users_bq} 행을 함께 만드는지(요구사항 {@code docs/requirements/user/me-profile.md}
 * USER-ME-23~25)에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserRefreshTokenRepository userRefreshTokenRepository;

    @Mock
    private UserBqRepository userBqRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private com.skhynix.user.profileimage.service.SignupProfileImageService signupProfileImageService;

    @InjectMocks
    private AuthService authService;

    private UserAccount activeAccountWithPassword(String encodedPassword) {
        User user = User.builder()
                .name("홍길동")
                .tel("01012345678")
                .email("test@example.com")
                .gender(Gender.MALE)
                .build();
        return UserAccount.builder()
                .user(user)
                .nickname("nickname")
                .password(encodedPassword)
                .build();
    }

    // ---------- login ----------

    @Test
    @DisplayName("[USER-WD-8] 탈퇴한 계정의 이메일(활성 계정으로 조회되지 않음)로 로그인하면 "
            + "비밀번호 검사 없이 미가입 이메일과 동일하게 INVALID_CREDENTIALS를 던진다")
    void login_emailNotFoundAmongActiveAccounts_throwsInvalidCredentialsWithoutCheckingPassword() {
        // given: findByUser_EmailAndExitAtIsNull은 탈퇴 계정과 미가입 이메일을 구분하지 않고
        // 둘 다 Optional.empty()로 응답한다(UserAccountRepository Javadoc).
        LoginRequest request = new LoginRequest("withdrawn@example.com", "CorrectPassw0rd!");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(request.email()))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // 응답이 미가입 이메일과 완전히 동일하려면 비밀번호 비교조차 수행되지 않아야 한다.
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("활성 계정 + 올바른 비밀번호로 로그인하면 토큰이 발급된다")
    void login_activeAccountCorrectPassword_returnsTokens() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        LoginRequest request = new LoginRequest("test@example.com", "rawPassword1!");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(request.email()))
                .willReturn(Optional.of(account));
        given(passwordEncoder.matches(request.password(), account.getPassword())).willReturn(true);
        given(tokenProvider.createAccessToken(account.getUid())).willReturn("access-token");
        given(tokenProvider.createRefreshToken(account.getUid())).willReturn("refresh-token");
        given(tokenProvider.getExpiration("refresh-token")).willReturn(LocalDateTime.now().plusDays(14));

        // when
        TokenResponse response = authService.login(request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("활성 계정이지만 비밀번호가 틀리면 INVALID_CREDENTIALS를 던진다")
    void login_activeAccountWrongPassword_throwsInvalidCredentials() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        LoginRequest request = new LoginRequest("test@example.com", "wrongPassword1!");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(request.email()))
                .willReturn(Optional.of(account));
        given(passwordEncoder.matches(request.password(), account.getPassword())).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    // ---------- reissue ----------

    @Test
    @DisplayName("[USER-WD-3, USER-WD-7] 탈퇴한 계정 소유의(아직 만료 전인, 즉 탈퇴 직전에 발급된) refresh "
            + "토큰으로 재발급을 요청하면 EXPIRED_REFRESH_TOKEN을 던지고 새 토큰을 발급하지 않는다 — "
            + "탈퇴 직전 발급 토큰의 refresh가 401이어야 한다는 USER-WD-3의 인수 기준은 이 검사와 동일하다")
    void reissue_withdrawnAccountRefreshToken_throwsExpiredRefreshToken() {
        // given
        UserAccount withdrawnAccount = activeAccountWithPassword("encoded");
        withdrawnAccount.withdraw(LocalDateTime.now().minusMinutes(1));
        String refreshToken = "refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(withdrawnAccount)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().plusDays(1)) // 아직 만료 전 — 탈퇴 검사가 별도로 막아야 함
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));

        // when & then
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);

        verify(tokenProvider, never()).createAccessToken(anyString());
        verify(userRefreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료된 refresh 토큰(저장된 expiredAt이 과거)으로 재발급을 요청하면 EXPIRED_REFRESH_TOKEN을 던진다")
    void reissue_expiredStoredToken_throwsExpiredRefreshToken() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        String refreshToken = "refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().minusMinutes(1))
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));

        // when & then
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("활성 계정의 유효한 refresh 토큰으로 재발급을 요청하면 새 토큰 쌍을 발급한다")
    void reissue_activeAccountValidToken_returnsNewTokens() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        String refreshToken = "old-refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));
        given(tokenProvider.createAccessToken(account.getUid())).willReturn("new-access-token");
        given(tokenProvider.createRefreshToken(account.getUid())).willReturn("new-refresh-token");
        given(tokenProvider.getExpiration("new-refresh-token")).willReturn(LocalDateTime.now().plusDays(14));

        // when
        TokenResponse response = authService.reissue(refreshToken);

        // then
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        verify(userRefreshTokenRepository).expireValidTokens(eq(account), any());
    }

    // ---------- reissue: 비밀번호 변경 기준 시각 대조 (USER-ATI-20, 22, 13) ----------

    @Test
    @DisplayName("[USER-ATI-20 ①] 비밀번호 변경 전에 발급된(iat가 기준 시각보다 앞선 초) refresh 토큰으로 "
            + "재발급을 요청하면, 저장된 토큰 행이 아직 만료 전이어도 EXPIRED_REFRESH_TOKEN을 던지고 새 "
            + "토큰을 발급하지 않는다 — 만료·탈퇴에 이어 세 번째 차단 지점이다")
    void reissue_refreshIssuedBeforePasswordChangeBaseline_throwsExpiredRefreshToken() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        long baseline = 1_755_400_000L;
        account.changePassword("encoded-new", baseline); // 비밀번호 변경으로 기준 시각 기록
        String refreshToken = "old-refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().plusDays(1)) // 아직 만료 전 — iat 대조가 별도로 막아야 함
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));
        given(tokenProvider.getIssuedAtEpochSecond(refreshToken)).willReturn(baseline - 1);

        // when & then
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);

        verify(tokenProvider, never()).createAccessToken(anyString());
        verify(userRefreshTokenRepository, never()).save(any());
        // USER-ATI-13: 대조에 쓰는 계정은 refresh 행을 통해 이미 로딩돼 있어 추가 조회가 없다
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    @DisplayName("[USER-ATI-20 ②, 결정 근거 3] expireValidTokens를 빠져나가 아직 만료 전인 행(옛 비밀번호로 "
            + "진행 중이던 로그인이 비밀번호 변경 직후 INSERT한 레이스 시나리오)도 iat 대조로 막힌다")
    void reissue_rowSurvivedExpireValidTokensButIssuedBeforeBaseline_stillThrowsExpiredRefreshToken() {
        // given: expireValidTokens 이후 만들어진 것처럼 expiredAt이 충분히 먼 미래인 행
        UserAccount account = activeAccountWithPassword("encoded");
        long baseline = 1_755_400_000L;
        account.changePassword("encoded-new", baseline);
        String refreshToken = "race-refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().plusDays(14))
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));
        // 옛 비밀번호로 로그인 중 발급된 토큰이라 iat가 기준 시각보다 한참 앞선다
        given(tokenProvider.getIssuedAtEpochSecond(refreshToken)).willReturn(baseline - 3600);

        // when & then
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("[USER-ATI-22, USER-ATI-8] 비밀번호 변경 응답으로 즉시 받은 refresh 토큰(iat가 기준 시각과 "
            + "정확히 같은 초)으로 재발급을 요청하면 성공한다 — 자기 자신에게 거부되지 않는다")
    void reissue_refreshIssuedExactlyAtBaseline_succeeds() {
        // given
        UserAccount account = activeAccountWithPassword("encoded");
        long baseline = 1_755_400_000L;
        account.changePassword("encoded-new", baseline);
        String refreshToken = "just-issued-refresh-token";
        UserRefreshToken stored = UserRefreshToken.builder()
                .userAccount(account)
                .refreshToken(refreshToken)
                .expiredAt(LocalDateTime.now().plusDays(14))
                .build();
        given(tokenProvider.validateToken(refreshToken)).willReturn(true);
        given(tokenProvider.isRefreshToken(refreshToken)).willReturn(true);
        given(userRefreshTokenRepository.findByRefreshToken(refreshToken)).willReturn(Optional.of(stored));
        given(tokenProvider.getIssuedAtEpochSecond(refreshToken)).willReturn(baseline);
        given(tokenProvider.createAccessToken(account.getUid())).willReturn("new-access-token");
        given(tokenProvider.createRefreshToken(account.getUid())).willReturn("new-refresh-token");
        given(tokenProvider.getExpiration("new-refresh-token")).willReturn(LocalDateTime.now().plusDays(14));

        // when
        TokenResponse response = authService.reissue(refreshToken);

        // then
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        verifyNoInteractions(userAccountRepository);
    }

    // ---------- signup ----------

    private SignupRequest signupRequest() {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, "nickname",
                "abc123!@", null);
    }

    @Test
    @DisplayName("[USER-WD-10] 탈퇴한 계정이 점유했던 이메일이라도 existsByEmail이 true라면(탈퇴 여부를 구분하지 "
            + "않으므로) 회원가입은 DUPLICATE_EMAIL로 거절된다")
    void signup_emailAlreadyOccupiedRegardlessOfWithdrawal_throwsDuplicateEmail() {
        // given: existsByEmail은 exit_at을 구분하지 않는 쿼리라, 탈퇴 계정이 점유한 이메일도 true로 잡힌다.
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-WD-11] 탈퇴한 계정이 점유했던 전화번호라도 existsByTel이 true라면 회원가입은 DUPLICATE_TEL로 거절된다")
    void signup_telAlreadyOccupiedRegardlessOfWithdrawal_throwsDuplicateTel() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_TEL);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-WD-12] 탈퇴한 계정이 점유했던 닉네임이라도 existsByNickname이 true라면 "
            + "회원가입은 DUPLICATE_NICKNAME으로 거절된다")
    void signup_nicknameAlreadyOccupiedRegardlessOfWithdrawal_throwsDuplicateNickname() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("이메일·전화번호·닉네임이 전부 미사용이면 회원가입에 성공하고 인코딩된 비밀번호로 계정을 저장한다")
    void signup_allUnique_encodesPasswordAndSavesAccount() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        authService.signup(request);

        // then: 저장되는 UserAccount의 비밀번호가 원문이 아니라 인코딩된 값이어야 한다.
        var accountCaptor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(accountCaptor.getValue().getNickname()).isEqualTo(request.nickname());
    }

    @Test
    @DisplayName("[USER-EMV-18] 회원가입에 성공하면 해당 이메일의 인증완료 상태를 소비(제거)한다")
    void signup_allUnique_consumesEmailVerifiedState() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        authService.signup(request);

        // then
        verify(emailVerificationService).consumeVerified(request.email());
    }

    @Test
    @DisplayName("[USER-EMV-16] 이메일 인증완료 상태가 아니면(미인증) 형식·중복 검사보다 먼저 EMAIL_NOT_VERIFIED로 "
            + "가입을 거부하고, 어떤 중복 검사도 조회하지 않는다")
    void signup_emailNotVerified_throwsEmailNotVerifiedBeforeDuplicateChecks() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        verifyNoInteractions(userRepository);
        verify(userAccountRepository, never()).existsByNickname(anyString());
        verify(userAccountRepository, never()).save(any());
    }

    // ---------- signup: 프로필 이미지 연계 (USER-PI-52 ~ 60) ----------

    @Test
    @DisplayName("[USER-PI-52, 53] profileImgUrl이 주어지고 이동에 성공하면 저장되는 계정의 "
            + "profileImgUrl이 temp/ 원본이 아니라 이동 후 반환된 영구 EP다")
    void signup_withProfileImage_movesAndStoresPermanentEndpoint() {
        // given
        SignupRequest request = new SignupRequest("홍길동", "01012345678", "test@example.com",
                Gender.MALE, "nickname", "abc123!@",
                "temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        String movedEndpoint = "user-profile-img/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.jpg";
        given(signupProfileImageService.moveToPermanent(request.profileImgUrl()))
                .willReturn(movedEndpoint);

        // when
        authService.signup(request);

        // then
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProfileImgUrl()).isEqualTo(movedEndpoint);
    }

    @Test
    @DisplayName("[USER-PI-57, 58] 이동에 실패하면(moveToPermanent가 null 반환) 가입은 그대로 성공하고 "
            + "profileImgUrl은 null로 저장되며, UserBq 저장과 이메일 인증 소비는 그대로 일어난다")
    void signup_profileImageMoveFails_stillSucceedsWithNullProfileImgUrl() {
        // given
        SignupRequest request = new SignupRequest("홍길동", "01012345678", "test@example.com",
                Gender.MALE, "nickname", "abc123!@",
                "temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        // 저장소 장애로 이동 실패 — 예외 없이 null을 돌려준다(SignupProfileImageService의 계약).
        given(signupProfileImageService.moveToPermanent(request.profileImgUrl())).willReturn(null);

        // when
        authService.signup(request);

        // then: 계정은 저장됐고 이미지 컬럼만 null, 부수효과(UserBq·이메일 인증 소비)는 그대로 일어난다
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProfileImgUrl()).isNull();
        verify(userBqRepository, times(1)).save(any(UserBq.class));
        verify(emailVerificationService).consumeVerified(request.email());
    }

    @Test
    @DisplayName("[USER-PI-55, 56] 못 쓰는 EP(형태 위반·객체 없음)로 이동이 예외를 던지면 가입 트랜잭션이 "
            + "그 예외로 실패하고 계정·UserBq 어느 쪽도 저장되지 않는다")
    void signup_invalidProfileImageEndpoint_propagatesExceptionAndDoesNotSaveAccount() {
        // given
        SignupRequest request = new SignupRequest("홍길동", "01012345678", "test@example.com",
                Gender.MALE, "nickname", "abc123!@", "user-profile-img/other-account.jpg");
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(signupProfileImageService.moveToPermanent(request.profileImgUrl()))
                .willThrow(new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT));

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);

        verify(userRepository, never()).save(any());
        verify(userAccountRepository, never()).save(any());
        verify(userBqRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-PI-51] profileImgUrl이 null이면 이미지 이동을 시도하지 않고(호출은 하되 인자가 "
            + "null) 계정의 profileImgUrl도 null로 남는다 — 기존 가입 클라이언트 하위 호환")
    void signup_nullProfileImgUrl_leavesAccountProfileImgUrlNull() {
        // given
        SignupRequest request = signupRequest(); // profileImgUrl == null
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(signupProfileImageService.moveToPermanent(null)).willReturn(null);

        // when
        authService.signup(request);

        // then
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProfileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[USER-PI-59] 이미지 검증은 검사 순서의 마지막이라, 중복 닉네임으로 먼저 실패하면 "
            + "이미지 이동 시도(moveToPermanent) 자체가 일어나지 않는다")
    void signup_duplicateNickname_neverAttemptsProfileImageMove() {
        // given
        SignupRequest request = new SignupRequest("홍길동", "01012345678", "test@example.com",
                Gender.MALE, "nickname", "abc123!@", "temp/whatever.jpg");
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        verifyNoInteractions(signupProfileImageService);
    }

    @Test
    @DisplayName("[USER-EMV-17] 인증완료 상태가 만료(30분 경과)돼 저장소에 키가 없는 이메일도 "
            + "미인증과 동일하게 EMAIL_NOT_VERIFIED로 가입을 거부한다(만료·미인증 동일 응답)")
    void signup_emailVerificationExpired_throwsSameEmailNotVerifiedAsNeverVerified() {
        // given: 서비스 계층에서 만료는 isEmailVerified()==false로 흡수되므로 미인증과 구분되지 않는다.
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        verify(emailVerificationService, never()).consumeVerified(anyString());
    }

    // ---------- signup: users_bq 행 생성 (USER-ME-23 ~ 25) ----------

    @Test
    @DisplayName("[USER-ME-23] 회원가입에 성공하면 users_bq 행을 방금 저장된 계정 인스턴스를 참조하는 "
            + "UserBq로, bqScore=0인 채로 정확히 1회 저장한다")
    void signup_allUnique_savesUsersBqRowReferencingSavedAccountWithZeroScore() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(userRepository.existsByTel(request.tel())).willReturn(false);
        given(userAccountRepository.existsByNickname(request.nickname())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        authService.signup(request);

        // then: 저장된 계정 인스턴스와 users_bq 행이 참조하는 계정 인스턴스가 동일해야 한다
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        UserAccount savedAccount = accountCaptor.getValue();

        ArgumentCaptor<UserBq> bqCaptor = ArgumentCaptor.forClass(UserBq.class);
        verify(userBqRepository, times(1)).save(bqCaptor.capture());
        UserBq savedBq = bqCaptor.getValue();

        assertThat(savedBq.getUserAccount()).isSameAs(savedAccount);
        assertThat(savedBq.getBqScore()).isEqualTo(0L);
    }

    @Test
    @DisplayName("[USER-ME-25] 중복 이메일로 가입이 DUPLICATE_EMAIL로 실패하면 users_bq 저장은 시도되지 않는다")
    void signup_duplicateEmail_doesNotSaveUsersBqRow() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(true);
        given(userRepository.existsByEmail(request.email())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verify(userBqRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-ME-25] 이메일 미인증으로 가입이 EMAIL_NOT_VERIFIED로 실패하면 users_bq 저장은 시도되지 않는다")
    void signup_emailNotVerified_doesNotSaveUsersBqRow() {
        // given
        SignupRequest request = signupRequest();
        given(emailVerificationService.isEmailVerified(request.email())).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        verify(userBqRepository, never()).save(any());
    }
}
