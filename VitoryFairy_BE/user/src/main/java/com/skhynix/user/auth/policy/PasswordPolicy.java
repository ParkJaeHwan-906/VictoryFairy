package com.skhynix.user.auth.policy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 비밀번호 정책의 <b>단일 출처</b>.
 *
 * <p>회원가입({@code SignupRequest}의 Bean Validation 애노테이션)과 사전 검사 API
 * ({@code POST /api/auth/password/validate})가 같은 정책을 각자 하드코딩해 어긋나는 것을 막는다.
 * 정책을 바꿀 일이 생기면 <b>이 클래스만</b> 수정하면 양쪽에 동시에 반영된다.
 *
 * <p>여기의 상수는 모두 컴파일 타임 상수(constant variable)라 애노테이션 속성에 그대로 쓸 수 있다.
 * {@link #LENGTH_MESSAGE} 역시 상수 변수만으로 이뤄진 연결 표현식이므로 상수로 취급된다(JLS 15.29).
 */
public final class PasswordPolicy {

    /** 최소 길이(포함). */
    public static final int MIN_LENGTH = 8;

    /** 최대 길이(포함). */
    public static final int MAX_LENGTH = 12;

    /** 영문·숫자·특수문자를 각각 1자 이상 포함해야 한다(선행 탐색 3개 + 전체 매치). */
    public static final String REGEX =
            "(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+=\\-\\[\\]{};:'\",.<>/?\\\\|`~]).+";

    /** 길이 규칙 위반 메시지. */
    public static final String LENGTH_MESSAGE = "비밀번호는 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.";

    /** 문자 구성 규칙 위반 메시지. */
    public static final String PATTERN_MESSAGE = "비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다.";

    /** 모든 규칙을 만족했을 때의 메시지. */
    public static final String VALID_MESSAGE = "사용 가능한 비밀번호입니다.";

    /**
     * {@link #REGEX}를 미리 컴파일해 재사용한다. Hibernate Validator의 {@code @Pattern}과 동일하게
     * {@code Matcher#matches()}(전체 매치) 의미로 사용해야 판정 결과가 회원가입 검증과 일치한다.
     */
    private static final Pattern COMPILED_PATTERN = Pattern.compile(REGEX);

    private PasswordPolicy() {
    }

    /**
     * 비밀번호가 정책을 만족하는지 판정한다.
     *
     * <p>여러 규칙을 동시에 위반해도 메시지는 <b>1개만</b> 반환하며, <b>길이 위반이 우선</b>한다.
     * {@code null}·빈 문자열은 길이 위반으로 처리한다(예외를 던지지 않는다).
     *
     * @param password 검사할 비밀번호(널 허용)
     * @return 위반 시 해당 규칙의 메시지, 통과 시 {@link Optional#empty()}
     */
    public static Optional<String> findViolation(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return Optional.of(LENGTH_MESSAGE);
        }
        if (!COMPILED_PATTERN.matcher(password).matches()) {
            return Optional.of(PATTERN_MESSAGE);
        }
        return Optional.empty();
    }
}
