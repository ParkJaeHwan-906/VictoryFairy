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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;

/**
 * 회귀 고정: {@link SignupRequest#password()}에 대한 Bean Validation 위반은 입력이 무엇이든
 * 항상 정확히 0개 또는 1개여야 한다.
 *
 * <p>이 계약이 깨졌던 원인은 과거 {@code @NotBlank + @Size + @Pattern}을 겹쳐 걸었던 것이었다.
 * 길이와 구성을 동시에 위반하는 입력({@code "abc"})은 위반이 2개 생성됐고,
 * {@code GlobalExceptionHandler}가 {@code Map<필드명, 메시지>}에 {@code put}하는 구조라 어느
 * 메시지가 최종 응답에 남을지 순회 순서에 따라 달라졌다(비결정적 응답).
 *
 * <p>{@code @WebMvcTest} 없이 {@link Validation#buildDefaultValidatorFactory()}로 만든 순수
 * {@link Validator}만 사용해 스프링 컨텍스트 없이 검증한다. 누군가 나중에
 * {@code SignupRequest.password}에 {@code @Size}·{@code @Pattern}·{@code @NotBlank}를 다시
 * 겹쳐 걸면 이 테스트가 위반 개수 불변조건 위반으로 실패해야 한다.
 */
class PasswordPolicyViolationCountTest {

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

    private SignupRequest requestWithPassword(String password) {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, "nickname", password);
    }

    private Set<ConstraintViolation<SignupRequest>> passwordViolations(String password) {
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(requestWithPassword(password));
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals("password"))
                .collect(Collectors.toSet());
    }

    private static Stream<Arguments> violatingPasswords() {
        return Stream.of(
                Arguments.of("길이·구성 동시 위반(과거 버그의 원인)", "abc"),
                Arguments.of("null", null),
                Arguments.of("빈 문자열", ""),
                Arguments.of("길이만 위반(구성은 충족)", "ab1!234"),
                Arguments.of("구성만 위반(길이는 충족)", "abcdefg!")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: password=\"{1}\" -> violation 정확히 1개")
    @MethodSource("violatingPasswords")
    @DisplayName("정책 위반 입력은 password 필드에서 violation을 정확히 1개만 생성한다")
    void invalidPassword_producesExactlyOneViolation(String description, String password) {
        // when
        Set<ConstraintViolation<SignupRequest>> violations = passwordViolations(password);

        // then
        assertThat(violations).hasSize(1);
    }

    @ParameterizedTest(name = "[{index}] password=\"{0}\" -> violation 0개")
    @ValueSource(strings = {"abc123!@", "Abcd1234#"})
    @DisplayName("정책을 만족하는 비밀번호는 violation을 생성하지 않는다")
    void validPassword_producesNoViolation(String password) {
        // when
        Set<ConstraintViolation<SignupRequest>> violations = passwordViolations(password);

        // then
        assertThat(violations).isEmpty();
    }
}
