package com.skhynix.chat.global.validation;

import jakarta.validation.Validator;
import java.lang.reflect.Method;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 컨트롤러 진입 전 {@code @Valid} 대신, 서비스가 정한 시점에 본문을 검증한다.
 *
 * <p>전송 판정 순서가 404 → 400 이라서다(CHAT-GC-51). {@code @Valid} 는 핸들러 호출 전에 돌아 400 이 먼저 나간다.
 * 실패하면 {@code @Valid} 와 같은 {@link MethodArgumentNotValidException} 을 던져, 공유 GlobalExceptionHandler 가
 * 같은 본문({@code data.<필드>} 에 위반 메시지)으로 응답하게 한다.
 */
@Component
public class RequestBodyValidator {

    private final SpringValidatorAdapter validator;

    public RequestBodyValidator(Validator validator) {
        this.validator = new SpringValidatorAdapter(validator);
    }

    public void validate(Object body, MethodParameter parameter) throws MethodArgumentNotValidException {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(body, "request");
        validator.validate(body, result);
        if (result.hasErrors()) {
            throw new MethodArgumentNotValidException(parameter, result);
        }
    }

    /**
     * 예외 메시지(getMessage)가 파라미터 위치를 읽으므로 실제 메서드 파라미터가 필요하다. null 이면 NPE.
     */
    public static MethodParameter parameterOf(Class<?> type, String methodName, int index, Class<?>... paramTypes) {
        try {
            Method method = type.getMethod(methodName, paramTypes);
            return new MethodParameter(method, index);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("검증 대상 메서드를 찾을 수 없다: " + type.getName() + "#" + methodName, e);
        }
    }
}
