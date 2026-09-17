package com.skhynix.user.auth.policy;

import java.util.Optional;
import java.util.regex.Pattern;

// 닉네임 정책의 단일 출처 — 다른 곳에 다시 적지 말 것.
public final class NicknamePolicy {

    public static final int MIN_LENGTH = 1;

    public static final int MAX_LENGTH = 10;

    /** 허용 문자가 모두 BMP라 {@code String.length()}(UTF-16 code unit) 1개가 인식 1자와 일치한다. */
    public static final String REGEX = "[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+";

    public static final String LENGTH_MESSAGE = "닉네임은 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.";

    public static final String PATTERN_MESSAGE = "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.";

    public static final String VALID_MESSAGE = "사용 가능한 닉네임입니다.";

    // Hibernate Validator @Pattern과 동일하게 전체 매치(matches()) 의미로 써야 회원가입 검증과 일치한다.
    private static final Pattern COMPILED_PATTERN = Pattern.compile(REGEX);

    private NicknamePolicy() {
    }

    // 여러 규칙을 동시에 위반해도 메시지는 1개만 반환한다 — 위반이 2개면 응답 메시지가 비결정적이 된다.
    public static Optional<String> findViolation(String nickname) {
        if (nickname == null || nickname.length() < MIN_LENGTH || nickname.length() > MAX_LENGTH) {
            return Optional.of(LENGTH_MESSAGE);
        }
        if (!COMPILED_PATTERN.matcher(nickname).matches()) {
            return Optional.of(PATTERN_MESSAGE);
        }
        return Optional.empty();
    }
}
