package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService#checkNicknameDuplicate(String)}의 중복 <b>단독</b> 검사 동작을 협력 객체를 목으로
 * 대체해 단위로 검증한다.
 *
 * <p>{@code validateNickname()}과 달리 정책(길이·문자) 검사를 전혀 수행하지 않고 오직
 * {@code isNicknameDuplicated()}(= {@code UserAccountRepository.existsByNickname}) 결과만으로
 * 판정한다는 것이 이 메서드의 핵심 계약이다. 정책 판정 로직 자체는 {@code NicknamePolicyTest}가,
 * 정책→중복 2단 파이프라인은 {@code AuthServiceValidateNicknameTest}가 담당하므로 여기서는 다루지
 * 않는다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceCheckNicknameDuplicateTest {

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

    @Test
    @DisplayName("미점유 닉네임은 valid:true와 통과 메시지를 반환하고 existsByNickname을 1회 호출한다")
    void checkNicknameDuplicate_notDuplicated_returnsPassed() {
        // given
        String nickname = "길동gil9";
        given(userAccountRepository.existsByNickname(nickname)).willReturn(false);

        // when
        NicknameValidationResponse response = authService.checkNicknameDuplicate(nickname);

        // then
        assertThat(response.valid()).isTrue();
        assertThat(response.message()).isEqualTo(NicknamePolicy.VALID_MESSAGE);
        verify(userAccountRepository).existsByNickname(nickname);
    }

    @Test
    @DisplayName("점유(중복) 닉네임은 valid:false와 중복 메시지를 반환한다")
    void checkNicknameDuplicate_duplicated_returnsViolatedWithDuplicateMessage() {
        // given
        String nickname = "이미있음";
        given(userAccountRepository.existsByNickname(nickname)).willReturn(true);

        // when
        NicknameValidationResponse response = authService.checkNicknameDuplicate(nickname);

        // then
        assertThat(response.valid()).isFalse();
        assertThat(response.message()).isEqualTo(ErrorCode.DUPLICATE_NICKNAME.getMessage());
        verify(userAccountRepository).existsByNickname(nickname);
    }

    /**
     * 이 엔드포인트가 {@code validateNickname()}과 다른 핵심 지점: 정책(길이·문자) 위반 입력이라도
     * 정책 자체를 보지 않으므로 미점유이기만 하면 valid:true(passed)를 반환한다. "사용 가능"은 중복이
     * 아니라는 뜻일 뿐 가입 가능 보장이 아니라는 의도된 동작을 명시적으로 고정한다.
     */
    @Test
    @DisplayName("정책 위반이지만 미점유인 입력(\"hi!\")은 정책을 보지 않으므로 valid:true를 반환한다")
    void checkNicknameDuplicate_policyViolatingButNotDuplicated_stillReturnsPassed() {
        // given: "hi!"는 NicknamePolicy상 문자 구성 위반이지만, checkNicknameDuplicate는 정책을 안 본다.
        String policyViolatingNickname = "hi!";
        given(userAccountRepository.existsByNickname(policyViolatingNickname)).willReturn(false);

        // when
        NicknameValidationResponse response = authService.checkNicknameDuplicate(policyViolatingNickname);

        // then: 정책 위반임에도 미점유이므로 passed() 그대로 반환된다.
        assertThat(response.valid()).isTrue();
        assertThat(response.message()).isEqualTo(NicknamePolicy.VALID_MESSAGE);
        verify(userAccountRepository).existsByNickname(policyViolatingNickname);
    }

    /**
     * 탈퇴 닉네임도 재가입 불가 정책에 따라 {@code existsByNickname}이 exit_at을 거르지 않아 true를
     * 반환한다 — 이 메서드는 그 결과를 그대로 중복으로 판정해 전달한다.
     */
    @Test
    @DisplayName("탈퇴 계정이 점유한 닉네임도 existsByNickname이 true를 주면 중복으로 처리한다")
    void checkNicknameDuplicate_withdrawnAccountNickname_treatedAsDuplicated() {
        // given
        String withdrawnAccountNickname = "탈퇴자닉네임";
        given(userAccountRepository.existsByNickname(withdrawnAccountNickname)).willReturn(true);

        // when
        NicknameValidationResponse response = authService.checkNicknameDuplicate(withdrawnAccountNickname);

        // then
        assertThat(response.valid()).isFalse();
        assertThat(response.message()).isEqualTo(ErrorCode.DUPLICATE_NICKNAME.getMessage());
        verify(userAccountRepository).existsByNickname(withdrawnAccountNickname);
    }
}
