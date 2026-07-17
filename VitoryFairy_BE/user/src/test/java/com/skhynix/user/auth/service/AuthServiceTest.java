package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserRefreshToken;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService}를 협력 객체 전부 목으로 대체해 단위로 검증한다. 특히 탈퇴 계정이 login·reissue·
 * signup 세 지점에서 어떻게 취급되는지(요구사항 {@code docs/requirements/user/withdraw.md})에 집중한다.
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
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

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

    // ---------- signup ----------

    private SignupRequest signupRequest() {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, "nickname", "abc123!@");
    }

    @Test
    @DisplayName("[USER-WD-10] 탈퇴한 계정이 점유했던 이메일이라도 existsByEmail이 true라면(탈퇴 여부를 구분하지 "
            + "않으므로) 회원가입은 DUPLICATE_EMAIL로 거절된다")
    void signup_emailAlreadyOccupiedRegardlessOfWithdrawal_throwsDuplicateEmail() {
        // given: existsByEmail은 exit_at을 구분하지 않는 쿼리라, 탈퇴 계정이 점유한 이메일도 true로 잡힌다.
        SignupRequest request = signupRequest();
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
}
