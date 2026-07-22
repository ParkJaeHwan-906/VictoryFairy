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
 * <p><b>왜 단일 애노테이션인가</b>: 예전에는 {@code @NotBlank + @Size + @Pattern}을 겹쳐 걸었는데,
 * 길이와 구성을 동시에 위반하는 입력(예: {@code "abc"})은 위반이 2개 생성됐다. 이때
 * {@code GlobalExceptionHandler}는 {@code Map<필드명, 메시지>}에 {@code put}하므로 마지막 하나만
 * 살아남고, 그 순회 순서는 보장되지 않아 <b>같은 요청인데 응답 메시지가 호출마다 달라졌다</b>.
 * 이 제약은 {@link PasswordPolicy#findViolation(String)}에 판정을 위임해 <b>위반을 항상 정확히
 * 1개만</b> 만들므로, 비결정성이 원인에서 사라지고 사전 검사 API
 * ({@code POST /api/auth/password/validate})와 문자 그대로 같은 함수를 공유하게 된다.
 *
 * <p><b>{@code null}·빈 문자열도 이 제약이 책임진다</b>: {@code @NotBlank}를 함께 걸지 말 것.
 * 자세한 이유는 {@link PasswordValidator}의 Javadoc 참고.
 *
 * <p>실제 응답 메시지는 위반 종류(길이/구성)에 따라 달라지므로 {@link #message()}가 아니라
 * {@link PasswordValidator}가 런타임에 채운다. 아래 기본값은 검증기가 메시지를 채우지 못한
 * 예외적인 경우를 위한 안전망일 뿐이다.
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
