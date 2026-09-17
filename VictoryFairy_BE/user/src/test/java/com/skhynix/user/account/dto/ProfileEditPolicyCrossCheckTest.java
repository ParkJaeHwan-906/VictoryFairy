package com.skhynix.user.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.user.auth.policy.PasswordPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * [USER-PE-11, USER-PE-24] 프로필 수정 경로(닉네임·비밀번호)의 형식 위반 메시지가
 * {@code POST /api/auth/nickname/validate}·{@code /password/validate}가 내는 메시지와 문자 그대로
 * 동일한지 스프링 컨텍스트 없이 판정 함수 수준에서 고정한다.
 *
 * <p>{@code NicknameUpdateRequest.nickname}은 {@code @ValidNickname}(→ {@link NicknamePolicy#findViolation}),
 * {@code PasswordUpdateRequest.newPassword}는 {@code @ValidPassword}(→ {@link PasswordPolicy#findViolation})에
 * 위임한다. 두 validate 엔드포인트도 각각 같은 정책 메서드에 위임하므로(닉네임은
 * {@code AuthService.findNicknamePolicyViolation}을 통해, 비밀번호는 {@code AuthController}가
 * {@code PasswordPolicy.findViolation}을 직접 호출) 같은 입력에 대해 항상 같은 메시지가 나와야 한다.
 * 이 클래스는 그 "같은 정책 메서드를 공유한다"는 전제를 Bean Validation 결과로 직접 검증한다.
 *
 * <p>HTTP 레이어(정확한 상태 코드·JSON shape)는 각 컨트롤러 슬라이스 테스트가 담당한다 — 이 클래스는
 * "위반이 항상 정확히 1개이며 메시지가 문자 그대로 같은가"만 순수하게 검증한다.
 */
class ProfileEditPolicyCrossCheckTest {

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

    // ---------- 닉네임 (USER-PE-11) ----------

    private Set<ConstraintViolation<NicknameUpdateRequest>> nicknameViolations(String nickname) {
        return validator.validate(new NicknameUpdateRequest(nickname));
    }

    private static Stream<Arguments> violatingNicknames() {
        return Stream.of(
                Arguments.of("길이 위반(11자)", "가나다라마바사아자차카"),
                Arguments.of("문자 구성 위반", "hi!"),
                Arguments.of("공백-only(문자 구성 위반)", "   "),
                Arguments.of("길이·구성 동시 위반(길이 우선)", "!@#$%^&*()!"),
                Arguments.of("null(USER-PE-14)", (String) null),
                Arguments.of("빈 문자열", "")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: nickname=\"{1}\"")
    @MethodSource("violatingNicknames")
    @DisplayName("[USER-PE-11, USER-PE-14, USER-PE-33] 닉네임 형식 위반은 위반이 정확히 1개이며, 그 메시지가 "
            + "NicknamePolicy.findViolation(=/nickname/validate가 내는 메시지)과 문자 그대로 같다")
    void violatingNickname_updateRequestValidation_matchesNicknamePolicyMessageExactly(
            String description, String nickname) {
        // given: /nickname/validate가 실제로 반환할 메시지
        Optional<String> policyMessage = NicknamePolicy.findViolation(nickname);
        assertThat(policyMessage).isPresent();

        // when
        Set<ConstraintViolation<NicknameUpdateRequest>> violations = nicknameViolations(nickname);

        // then: 위반이 정확히 1개이고 메시지가 문자 그대로 일치
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo(policyMessage.get());
    }

    @ParameterizedTest(name = "[{index}] nickname=\"{0}\"")
    @MethodSource("passingNicknames")
    @DisplayName("[USER-PE-11] 정책을 만족하는 닉네임은 NicknameUpdateRequest에도 위반이 없다")
    void passingNickname_updateRequestValidation_hasNoViolation(String nickname) {
        assertThat(NicknamePolicy.findViolation(nickname)).isEmpty();
        assertThat(nicknameViolations(nickname)).isEmpty();
    }

    private static Stream<String> passingNicknames() {
        return Stream.of("길동gil9", "ㄱㅏ힣aZ9", "가");
    }

    // ---------- 비밀번호 (USER-PE-24) ----------

    private Set<ConstraintViolation<PasswordUpdateRequest>> newPasswordViolations(String newPassword) {
        return validator.validate(new PasswordUpdateRequest("Old1234!", newPassword)).stream()
                .filter(v -> v.getPropertyPath().toString().equals("newPassword"))
                .collect(Collectors.toSet());
    }

    private static Stream<Arguments> violatingPasswords() {
        return Stream.of(
                Arguments.of("길이 위반(3자)", "a1!"),
                Arguments.of("문자 구성 위반(특수문자 없음)", "abcdefgh"),
                Arguments.of("문자 구성 위반(숫자 없음)", "abcdefg!"),
                Arguments.of("null(USER-PE-25)", (String) null),
                Arguments.of("빈 문자열", "")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: newPassword=\"{1}\"")
    @MethodSource("violatingPasswords")
    @DisplayName("[USER-PE-24, USER-PE-25, USER-PE-26] 새 비밀번호 형식 위반은 위반이 정확히 1개이며, 그 메시지가 "
            + "PasswordPolicy.findViolation(=/password/validate가 내는 메시지)과 문자 그대로 같다")
    void violatingPassword_updateRequestValidation_matchesPasswordPolicyMessageExactly(
            String description, String newPassword) {
        // given: /password/validate가 실제로 반환할 메시지
        Optional<String> policyMessage = PasswordPolicy.findViolation(newPassword);
        assertThat(policyMessage).isPresent();

        // when
        Set<ConstraintViolation<PasswordUpdateRequest>> violations = newPasswordViolations(newPassword);

        // then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo(policyMessage.get());
    }

    @ParameterizedTest(name = "[{index}] newPassword=\"{0}\"")
    @MethodSource("passingPasswords")
    @DisplayName("[USER-PE-24] 정책을 만족하는 새 비밀번호는 PasswordUpdateRequest에도 newPassword 위반이 없다")
    void passingPassword_updateRequestValidation_hasNoViolation(String newPassword) {
        assertThat(PasswordPolicy.findViolation(newPassword)).isEmpty();
        assertThat(newPasswordViolations(newPassword)).isEmpty();
    }

    private static Stream<String> passingPasswords() {
        return Stream.of("New1234!", "abcDEF12@#");
    }
}
