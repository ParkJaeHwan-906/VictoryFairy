package com.skhynix.user.auth.policy;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 단일 애노테이션이어야 한다 — @NotBlank/@Size/@Pattern 을 겹쳐 걸면 위반이 2개가 되고,
// GlobalExceptionHandler 의 Map<필드명,메시지> put 순서가 비결정적이라 응답 메시지가 호출마다 달라진다.
// null·빈 문자열도 이 제약 하나가 책임진다.
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "비밀번호가 정책에 맞지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
