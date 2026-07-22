package com.skhynix.user.auth.policy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 닉네임 정책의 <b>단일 출처</b>. {@link PasswordPolicy}를 그대로 미러링한다.
 *
 * <p>회원가입({@code SignupRequest}의 Bean Validation 애노테이션)과 사전 검사 API
 * ({@code POST /api/auth/nickname/validate})가 같은 정책을 각자 하드코딩해 어긋나는 것을 막는다.
 * 정책을 바꿀 일이 생기면 <b>이 클래스만</b> 수정하면 양쪽에 동시에 반영된다.
 *
 * <p>이 클래스가 책임지는 것은 <b>순수 정책(허용 문자·길이)</b>뿐이다. 닉네임 <b>중복</b>은 DB를
 * 조회해야 하므로 여기서 판정하지 않고 서비스 계층(사전 검사 API의 2단 파이프라인)이 담당한다.
 *
 * <p>여기의 상수는 모두 컴파일 타임 상수(constant variable)라 애노테이션 속성에 그대로 쓸 수 있다.
 * {@link #LENGTH_MESSAGE} 역시 상수 변수만으로 이뤄진 연결 표현식이므로 상수로 취급된다(JLS 15.29).
 */
public final class NicknamePolicy {

    /** 최소 길이(포함). */
    public static final int MIN_LENGTH = 1;

    /** 최대 길이(포함). */
    public static final int MAX_LENGTH = 10;

    /**
     * 허용 문자 화이트리스트: 한글 완성형(가–힣)·호환 자모 낱자(ㄱ–ㅎ, ㅏ–ㅣ)·영문·숫자만.
     * 공백·특수문자·이모지는 전부 거부된다. 허용 문자는 모두 BMP라 {@code String.length()}(UTF-16
     * code unit) 1개가 인식 1자와 일치한다.
     */
    public static final String REGEX = "[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+";

    /** 길이 규칙 위반 메시지. */
    public static final String LENGTH_MESSAGE = "닉네임은 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.";

    /** 문자 구성 규칙 위반 메시지. */
    public static final String PATTERN_MESSAGE = "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.";

    /** 모든 규칙을 만족했을 때의 메시지. */
    public static final String VALID_MESSAGE = "사용 가능한 닉네임입니다.";

    /**
     * {@link #REGEX}를 미리 컴파일해 재사용한다. Hibernate Validator의 {@code @Pattern}과 동일하게
     * {@code Matcher#matches()}(전체 매치) 의미로 사용해야 판정 결과가 회원가입 검증과 일치한다.
     */
    private static final Pattern COMPILED_PATTERN = Pattern.compile(REGEX);

    private NicknamePolicy() {
    }

    /**
     * 닉네임이 정책(길이·문자 구성)을 만족하는지 판정한다. <b>중복은 검사하지 않는다.</b>
     *
     * <p>여러 규칙을 동시에 위반해도 메시지는 <b>1개만</b> 반환하며, <b>길이 위반이 우선</b>한다.
     * {@code null}·빈 문자열은 길이 위반으로 처리한다(예외를 던지지 않는다). 공백만으로 이뤄진
     * 문자열은 길이는 통과하더라도 화이트리스트 밖이라 문자 구성 위반으로 걸린다.
     *
     * @param nickname 검사할 닉네임(널 허용)
     * @return 위반 시 해당 규칙의 메시지, 통과 시 {@link Optional#empty()}
     */
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
