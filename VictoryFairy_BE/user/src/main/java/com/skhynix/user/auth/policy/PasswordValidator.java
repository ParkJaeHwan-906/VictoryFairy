package com.skhynix.user.auth.policy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Optional;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

/**
 * {@link ValidPassword}의 검증기. 판정도 메시지도 전부 {@link PasswordPolicy#findViolation(String)}에
 * 위임한다. 정규식·길이·메시지를 여기에 다시 하드코딩하면 정책 출처가 둘로 갈라지므로 하지 말 것.
 *
 * <p><b>{@code null}을 통과시키지 않는 이유</b>: {@code ConstraintValidator} 관례는 {@code null}을
 * 통과시켜 {@code @NotBlank}에 맡기는 것이다. 하지만 이 필드에서 그렇게 하면 둘 중 하나가 된다.
 * <ul>
 *   <li>{@code @NotBlank}를 함께 걸면 → {@code null}·{@code ""}에서 위반이 다시 2개가 되어
 *       고치려던 비결정성이 그대로 부활한다.</li>
 *   <li>{@code @NotBlank}만 걸고 여기서 {@code null}을 통과시키면 → 가입 경로는 {@code @NotBlank}
 *       메시지를, 사전 검사 API는 길이 메시지를 내어 두 경로가 또 갈라진다.</li>
 * </ul>
 * 그래서 {@code @NotBlank}를 떼고 이 제약 하나가 {@code null}·{@code ""}까지 전부 책임진다.
 * {@link PasswordPolicy#findViolation(String)}이 이미 둘을 길이 위반으로 처리하므로(예외를 던지지
 * 않는다), 두 경로 모두 길이 메시지로 정확히 일치하고 위반 수도 항상 1개로 유지된다.
 *
 * <p><b>메시지를 템플릿에 직접 넣지 않는 이유</b>: 위반 메시지는 동적이라(길이냐 구성이냐) 애노테이션의
 * 고정 {@code message} 속성으로 표현할 수 없다. 그렇다고 문구를 위반 템플릿에 그대로 넣으면 Hibernate
 * Validator가 그 안의 {@code {...}}를 <b>메시지 파라미터·리소스 번들 키로 해석</b>한다. 번들에 실제로
 * 존재하는 키와 이름이 겹치면 그 자리가 조용히 다른 문구로 치환된다(측정 결과: 문구에
 * {@code {jakarta.validation.constraints.NotNull.message}}가 들어 있으면 "널이어서는 안됩니다"로 바뀐다).
 * 정책 정규식이 {@code {}}를 허용 특수문자로 두고 있어 특수문자 목록 문구를 손보다 보면 중괄호가 섞여
 * 들어올 수 있는 자리다. 반면 메시지 파라미터로 넘긴 값은 이 1차 해석 패스를 타지 않아 문구가 어떻게
 * 바뀌든 리터럴로 남는다(같은 조건에서 원문 그대로 출력됨을 확인).
 *
 * <p>참고로 {@link PasswordPolicy#PATTERN_MESSAGE}에 들어 있는 {@code $}({@code !@#$%^&*})는 현재
 * 문제가 되지 않는다. 사용자 정의 위반 메시지의 EL 해석은 Hibernate Validator 기본값이
 * {@code ExpressionLanguageFeatureLevel.NONE}이라 {@code ${...}}가 평가되지 않기 때문이다. 다만 이
 * 방어선은 EL 레벨 설정에 달려 있고 메시지 파라미터도 EL 패스까지 막아주지는 않으므로(레벨을
 * {@code VARIABLES}로 올리면 두 방식 모두 {@code ${...}}가 평가된다), <b>정책 메시지에
 * {@code ${...}} 형태를 넣지 말 것</b>.
 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    /** 위반 메시지를 실어 나르는 메시지 파라미터 이름. */
    private static final String VIOLATION_PARAMETER = "violation";

    /** 실제 문구 대신 이 고정 템플릿만 해석되게 한다. */
    private static final String VIOLATION_TEMPLATE = "{" + VIOLATION_PARAMETER + "}";

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        Optional<String> violation = PasswordPolicy.findViolation(password);
        if (violation.isEmpty()) {
            return true;
        }

        HibernateConstraintValidatorContext hibernateContext =
                context.unwrap(HibernateConstraintValidatorContext.class);
        hibernateContext.disableDefaultConstraintViolation();
        hibernateContext.addMessageParameter(VIOLATION_PARAMETER, violation.get())
                .buildConstraintViolationWithTemplate(VIOLATION_TEMPLATE)
                .addConstraintViolation();
        return false;
    }
}
