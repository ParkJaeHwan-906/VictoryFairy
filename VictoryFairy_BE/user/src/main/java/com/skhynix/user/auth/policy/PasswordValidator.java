package com.skhynix.user.auth.policy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Optional;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

/**
 * {@link ValidPassword}의 검증기. 판정·메시지는 전부 {@link PasswordPolicy#findViolation(String)}에
 * 위임한다 — 정책을 여기서 다시 하드코딩하지 말 것.
 *
 * <p>{@code @NotBlank}를 겹쳐 걸지 않고 이 제약 하나가 {@code null}/{@code ""}까지 책임진다
 * ({@link PasswordPolicy#findViolation}이 이미 null·빈 문자열을 길이 위반으로 처리해 예외가 없다).
 * 겹치면 signup·validate 두 경로에서 위반이 2개가 되어 메시지가 다시 어긋난다.
 *
 * <p><b>주의: 위반 메시지를 애노테이션 템플릿에 직접 넣지 말 것.</b> Hibernate Validator가 템플릿 안의
 * {@code {...}}를 리소스 번들 키로 해석해, 정책 문구와 우연히 겹치는 키가 있으면 조용히 다른 문구로
 * 치환된다(실측: {@code {jakarta.validation.constraints.NotNull.message}}가 들어가면 "널이어서는
 * 안됩니다"로 치환됨). 그래서 문구는 메시지 파라미터로 넘기고 템플릿은 고정 플레이스홀더만 쓴다. EL
 * 해석은 기본값이 NONE이라 지금은 안전하지만 레벨을 올리면 메시지 파라미터도 못 막으니 정책 메시지에
 * {@code ${...}}는 넣지 말 것.
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
