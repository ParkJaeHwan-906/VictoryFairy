package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService#validateNickname(String)}의 정책 → 중복 2단 파이프라인을 협력 객체를 목으로
 * 대체해 단위로 검증한다({@code docs/requirements/user/nickname-policy.md} USER-NICK-3, 10, 12, 13, 14).
 *
 * <p>가장 중요한 계약은 <b>정책(길이·문자) 위반 시 중복(DB) 검사를 수행하지 않는다</b>는 것이다
 * (USER-NICK-13) — {@code verify(userAccountRepository, never()).existsByNickname(...)}로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceValidateNicknameTest {

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

    // ---------- USER-NICK-13: 정책 위반 시 중복 검사 생략 ----------

    private static Stream<Arguments> policyViolatingNicknames() {
        return Stream.of(
                Arguments.of("길이 위반(11자)", "가나다라마바사아자차카", NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("문자 구성 위반", "hi!", NicknamePolicy.PATTERN_MESSAGE),
                Arguments.of("길이·구성 동시 위반 시 길이 메시지 우선", "!@#$%^&*()!", NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("null", null, NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("빈 문자열", "", NicknamePolicy.LENGTH_MESSAGE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: nickname=\"{1}\" -> valid:false, existsByNickname 미호출")
    @MethodSource("policyViolatingNicknames")
    @DisplayName("[USER-NICK-13, USER-NICK-10] 정책(길이·문자) 위반 닉네임은 중복(DB) 검사를 수행하지 않고 정책 위반 메시지를 반환한다")
    void validateNickname_policyViolation_skipsDuplicateCheck(
            String description, String nickname, String expectedMessage) {
        // when
        NicknameValidationResponse response = authService.validateNickname(nickname);

        // then
        assertThat(response.valid()).isFalse();
        assertThat(response.message()).isEqualTo(expectedMessage);
        assertThat(response.message()).isNotEqualTo(ErrorCode.DUPLICATE_NICKNAME.getMessage());

        // USER-NICK-13 핵심: 정책 위반이면 중복 검사(DB 조회)가 아예 수행되지 않는다.
        verify(userAccountRepository, never()).existsByNickname(anyString());
    }

    // ---------- USER-NICK-3: 정책 통과 & 미중복 ----------

    @Test
    @DisplayName("[USER-NICK-3] 정책을 통과하고 중복되지 않은 닉네임은 valid:true와 통과 메시지를 반환한다")
    void validateNickname_policyPassedAndNotDuplicated_returnsPassed() {
        // given
        String nickname = "길동gil9";
        given(userAccountRepository.existsByNickname(nickname)).willReturn(false);

        // when
        NicknameValidationResponse response = authService.validateNickname(nickname);

        // then
        assertThat(response.valid()).isTrue();
        assertThat(response.message()).isEqualTo(NicknamePolicy.VALID_MESSAGE);
        verify(userAccountRepository).existsByNickname(nickname);
    }

    // ---------- USER-NICK-12, USER-NICK-14: 정책 통과 & 중복 ----------

    @Test
    @DisplayName("[USER-NICK-12, USER-NICK-14] 정책을 통과했지만 이미 사용 중인(탈퇴 계정 포함) 닉네임은 "
            + "valid:false와 중복 메시지를 반환하며, existsByNickname으로 중복을 판정한다")
    void validateNickname_policyPassedButDuplicated_returnsViolatedWithDuplicateMessage() {
        // given: existsByNickname은 exit_at을 거르지 않으므로 탈퇴 계정이 점유한 닉네임도 true로 잡힌다.
        String nickname = "탈퇴자닉네임";
        given(userAccountRepository.existsByNickname(nickname)).willReturn(true);

        // when
        NicknameValidationResponse response = authService.validateNickname(nickname);

        // then
        assertThat(response.valid()).isFalse();
        assertThat(response.message()).isEqualTo(ErrorCode.DUPLICATE_NICKNAME.getMessage());
        verify(userAccountRepository).existsByNickname(nickname);
    }

    // ---------- 구현 제약 1: 정책 판정과 중복 판정이 독립 메서드로 분리돼 있다 ----------

    @Test
    @DisplayName("findNicknamePolicyViolation()은 NicknamePolicy.findViolation()에 위임하며 DB를 조회하지 않는다")
    void findNicknamePolicyViolation_delegatesToPolicyWithoutDbAccess() {
        // when
        var violation = authService.findNicknamePolicyViolation("hi!");

        // then
        assertThat(violation).contains(NicknamePolicy.PATTERN_MESSAGE);
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    @DisplayName("isNicknameDuplicated()는 UserAccountRepository.existsByNickname()에 위임한다")
    void isNicknameDuplicated_delegatesToRepository() {
        // given
        given(userAccountRepository.existsByNickname("길동gil9")).willReturn(true);

        // when
        boolean duplicated = authService.isNicknameDuplicated("길동gil9");

        // then
        assertThat(duplicated).isTrue();
        verify(userAccountRepository).existsByNickname("길동gil9");
    }
}
