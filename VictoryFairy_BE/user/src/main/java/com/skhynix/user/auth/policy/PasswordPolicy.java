package com.skhynix.user.auth.policy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 비밀번호 정책의 <b>단일 출처</b>. 회원가입과 사전 검사 API가 같은 정책을 각자 하드코딩해 어긋나는 것을
 * 막는다 — 정책을 바꾸려면 이 클래스만 고치면 된다.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    public static final int MAX_LENGTH = 12;

    /** 영문·숫자·특수문자를 각각 1자 이상 포함해야 한다(선행 탐색 3개 + 전체 매치). */
    public static final String REGEX =
            "(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+=\\-\\[\\]{};:'\",.<>/?\\\\|`~]).+";

    public static final String LENGTH_MESSAGE = "비밀번호는 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.";

    public static final String PATTERN_MESSAGE = "비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다.";

    public static final String VALID_MESSAGE = "사용 가능한 비밀번호입니다.";

    // Hibernate Validator @Pattern과 동일하게 전체 매치(matches()) 의미로 써야 회원가입 검증과 일치한다.
    private static final Pattern COMPILED_PATTERN = Pattern.compile(REGEX);

    private PasswordPolicy() {
    }

    /**
     * 여러 규칙을 동시에 위반해도 메시지는 1개만 반환하며, 길이 위반이 우선한다. {@code null}·빈
     * 문자열은 길이 위반으로 처리한다(예외 없음).
     *
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
