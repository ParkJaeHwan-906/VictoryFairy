package com.skhynix.user.auth.policy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Optional;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

// 위반 메시지를 애노테이션 템플릿에 직접 넣지 말 것 — Hibernate Validator 가 템플릿 안의 {...} 를
// 리소스 번들 키로 해석해 조용히 다른 문구로 치환한다(실측). 문구는 메시지 파라미터로 넘기고
// 템플릿은 고정 플레이스홀더만 쓴다. 같은 이유로 정책 메시지에 ${...} 도 넣지 말 것.
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
