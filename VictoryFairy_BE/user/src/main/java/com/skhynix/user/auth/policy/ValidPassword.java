package com.skhynix.user.auth.policy;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호가 {@link PasswordPolicy}를 만족하는지 검증한다.
 *
 * <p>단일 애노테이션인 이유: {@code @NotBlank}/{@code @Size}/{@code @Pattern}을 겹쳐 걸면 길이·구성을
 * 동시에 위반하는 입력에서 위반이 2개 생겨 {@code GlobalExceptionHandler}의 {@code Map<필드명,메시지>}
 * put 순서가 비결정적이라 응답 메시지가 호출마다 달라진다. 이 제약이 {@link PasswordPolicy}에 판정을
 * 위임해 위반을 항상 1개로 유지하고, 사전 검사 API와 같은 판정 함수를 공유한다.
 *
 * <p>{@code null}·빈 문자열도 이 제약이 책임진다 — {@code @NotBlank}를 같이 걸지 말 것(이유는
 * {@link PasswordValidator} 참고).
 *
 * <p>실제 메시지는 위반 종류에 따라 {@link PasswordValidator}가 런타임에 채운다. {@link #message()}
 * 기본값은 채우지 못한 예외적 상황의 안전망일 뿐이다.
 */
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
