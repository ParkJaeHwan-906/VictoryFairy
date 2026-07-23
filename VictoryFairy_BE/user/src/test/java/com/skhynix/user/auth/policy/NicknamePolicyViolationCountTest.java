package com.skhynix.user.auth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.user.auth.dto.SignupRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 회귀 고정: {@link SignupRequest#nickname()}에 대한 Bean Validation 위반은 입력이 무엇이든
 * 항상 정확히 0개 또는 1개여야 한다({@code docs/requirements/user/nickname-policy.md} USER-NICK-9).
 *
 * <p>{@link PasswordPolicyViolationCountTest}를 그대로 미러링한다. 누군가 나중에
 * {@code SignupRequest.nickname}에 {@code @NotBlank}·{@code @Size}·{@code @Pattern}을 다시
 * 겹쳐 걸면 이 테스트가 위반 개수 불변조건 위반으로 실패해야 한다.
 */
class NicknamePolicyViolationCountTest {

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
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(requestWithNickname(nickname));
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals("nickname"))
                .collect(Collectors.toSet());
    }

    private static Stream<Arguments> violatingNicknames() {
        return Stream.of(
                Arguments.of("길이·구성 동시 위반(과거 password 버그와 동일 유형)", "!@#$%^&*()!"),
                Arguments.of("null", null),
                Arguments.of("빈 문자열", ""),
                Arguments.of("길이만 위반(구성은 충족, 11자)", "가나다라마바사아자차카"),
                Arguments.of("구성만 위반(길이는 충족)", "hi!")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: nickname=\"{1}\" -> violation 정확히 1개")
    @MethodSource("violatingNicknames")
    @DisplayName("[USER-NICK-9] 정책 위반 입력은 nickname 필드에서 violation을 정확히 1개만 생성한다")
    void invalidNickname_producesExactlyOneViolation(String description, String nickname) {
        // when
        Set<ConstraintViolation<SignupRequest>> violations = nicknameViolations(nickname);

        // then
        assertThat(violations).hasSize(1);
    }

    @ParameterizedTest(name = "[{index}] nickname=\"{0}\" -> violation 0개")
    @ValueSource(strings = {"길동gil9", "ㄱㅏ힣aZ9", "가"})
    @DisplayName("정책을 만족하는 닉네임은 violation을 생성하지 않는다")
    void validNickname_producesNoViolation(String nickname) {
        // when
        Set<ConstraintViolation<SignupRequest>> violations = nicknameViolations(nickname);

        // then
        assertThat(violations).isEmpty();
    }
}
