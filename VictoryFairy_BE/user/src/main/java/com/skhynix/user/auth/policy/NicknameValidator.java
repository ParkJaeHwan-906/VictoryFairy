package com.skhynix.user.auth.policy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Optional;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

/**
 * {@link ValidNickname}의 검증기. {@link PasswordValidator}를 그대로 미러링한다({@code null} 처리,
 * EL 인젝션 방지를 위한 메시지 파라미터 사용 이유 등은 {@link PasswordValidator} 참고). 판정·메시지는
 * {@link NicknamePolicy#findViolation(String)}에 위임한다.
 */
public class NicknameValidator implements ConstraintValidator<ValidNickname, String> {

    /** 위반 메시지를 실어 나르는 메시지 파라미터 이름. */
    private static final String VIOLATION_PARAMETER = "violation";

    /** 실제 문구 대신 이 고정 템플릿만 해석되게 한다. */
    private static final String VIOLATION_TEMPLATE = "{" + VIOLATION_PARAMETER + "}";

    @Override
    public boolean isValid(String nickname, ConstraintValidatorContext context) {
        Optional<String> violation = NicknamePolicy.findViolation(nickname);
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
