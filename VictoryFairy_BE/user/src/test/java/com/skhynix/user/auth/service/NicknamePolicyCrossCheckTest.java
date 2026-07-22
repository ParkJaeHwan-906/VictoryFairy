package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * [USER-NICK-1] "같은 닉네임 X에 대해 사전 검사 API가 valid:false + 메시지 M을 내면, 같은 X로 signup 시에도
 * 400 응답의 data.nickname이 정확히 M이다"를 판정 함수 수준에서 고정한다.
 *
 * <p>{@code AuthService}(중복 검사는 항상 미점유로 가정 — 정책 위반 닉네임은 어차피 중복 검사를 타지 않는다,
 * USER-NICK-13)의 {@link AuthService#validateNickname(String)} 결과와, {@code SignupRequest.nickname}에
 * 대한 Bean Validation({@code @ValidNickname})의 위반 메시지를 같은 입력에 대해 나란히 비교한다. 두 경로
 * 모두 {@link NicknamePolicy#findViolation(String)}에 위임하므로 항상 일치해야 한다.
 *
 * <p>HTTP 레이어(정확한 상태 코드·JSON shape)는 {@code AuthControllerNicknameValidateTest}와
 * {@code AuthControllerSignupNicknameTest}가 각각 담당한다 — 이 클래스는 스프링 컨텍스트 없이 "판정
 * 함수가 문자 그대로 같은가"만 순수하게 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NicknamePolicyCrossCheckTest {

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

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private SignupRequest requestWithNickname(String nickname) {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, nickname, "abc123!@");
    }

    private Set<ConstraintViolation<SignupRequest>> nicknameViolations(String nickname) {
        return validator.validate(requestWithNickname(nickname)).stream()
                .filter(v -> v.getPropertyPath().toString().equals("nickname"))
                .collect(Collectors.toSet());
    }

    private static Stream<Arguments> nicknames() {
        return Stream.of(
                Arguments.of("길이 위반(11자)", "가나다라마바사아자차카"),
                Arguments.of("문자 구성 위반", "hi!"),
                Arguments.of("공백-only(문자 구성 위반)", "   "),
                Arguments.of("길이·구성 동시 위반(길이 우선)", "!@#$%^&*()!"),
                Arguments.of("null", null),
                Arguments.of("빈 문자열", "")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: nickname=\"{1}\" -> validate와 signup의 위반 메시지가 일치한다")
    @MethodSource("nicknames")
    @DisplayName("[USER-NICK-1] 정책 위반 닉네임은 validate의 message와 signup의 Bean Validation 메시지가 문자 그대로 일치한다")
    void policyViolatingNickname_validateAndSignupBeanValidation_agreeOnMessage(String description, String nickname) {
        // when: validate 파이프라인(정책 위반이므로 중복 검사는 타지 않는다 — existsByNickname 스텁 불필요)
        NicknameValidationResponse validateResult = authService.validateNickname(nickname);

        // then: 정책 위반이므로 valid:false
        assertThat(validateResult.valid()).isFalse();

        // when: signup의 Bean Validation
        Set<ConstraintViolation<SignupRequest>> violations = nicknameViolations(nickname);

        // then: 위반이 정확히 1개이며, 그 메시지가 validate의 메시지와 문자 그대로 같다
        assertThat(violations).hasSize(1);
        String signupMessage = violations.iterator().next().getMessage();
        assertThat(signupMessage).isEqualTo(validateResult.message());
    }

    @ParameterizedTest(name = "[{index}] nickname=\"{0}\" -> validate와 signup 모두 통과한다")
    @MethodSource("validNicknames")
    @DisplayName("[USER-NICK-1, USER-NICK-4] 정책을 만족하는 닉네임은 validate가 valid:true이고 signup의 "
            + "Bean Validation도 nickname 위반을 만들지 않는다")
    void policyPassingNickname_validateAndSignupBeanValidation_bothPass(String nickname) {
        // given: 정책은 통과하므로 중복 검사(existsByNickname)까지 도달한다 — 미점유로 스텁
        given(userAccountRepository.existsByNickname(nickname)).willReturn(false);

        // when
        NicknameValidationResponse validateResult = authService.validateNickname(nickname);
        Set<ConstraintViolation<SignupRequest>> violations = nicknameViolations(nickname);

        // then
        assertThat(validateResult.valid()).isTrue();
        assertThat(violations).isEmpty();
    }

    private static Stream<String> validNicknames() {
        return Stream.of("길동gil9", "ㄱㅏ힣aZ9", "가");
    }
}
