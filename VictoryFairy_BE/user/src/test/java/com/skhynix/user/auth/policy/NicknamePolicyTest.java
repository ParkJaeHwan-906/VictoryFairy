package com.skhynix.user.auth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link NicknamePolicy#findViolation(String)}에 대한 순수 단위 테스트. 스프링 컨텍스트 없이
 * 정책 판정 함수만 검증한다({@code docs/requirements/user/nickname-policy.md} USER-NICK-7~11).
 */
class NicknamePolicyTest {

    // ---------- USER-NICK-7: 길이 경계 ----------

    @ParameterizedTest(name = "[{index}] \"{0}\" (길이 {1}) -> 통과")
    @MethodSource("boundaryValidLengths")
    @DisplayName("[USER-NICK-7] 길이 1자 또는 10자인 닉네임은 길이 규칙을 통과한다")
    void findViolation_boundaryLength_passes(String nickname, int length) {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(nickname).hasSize(length);
        assertThat(violation).isEmpty();
    }

    private static Stream<Arguments> boundaryValidLengths() {
        return Stream.of(
                Arguments.of("가", 1),
                Arguments.of("가나다라마바사아자차", 10)
        );
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" (길이 {1}) -> 길이 위반")
    @MethodSource("boundaryInvalidLengths")
    @DisplayName("[USER-NICK-7] 길이가 1자 미만이거나 10자를 초과하면 길이 위반 메시지를 반환한다")
    void findViolation_outOfBoundaryLength_returnsLengthViolation(String nickname, int length) {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(nickname).hasSize(length);
        assertThat(violation).contains(NicknamePolicy.LENGTH_MESSAGE);
    }

    private static Stream<Arguments> boundaryInvalidLengths() {
        return Stream.of(
                Arguments.of("", 0),
                Arguments.of("가나다라마바사아자차카", 11)
        );
    }

    // ---------- USER-NICK-8: 문자 화이트리스트 ----------

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" -> 통과")
    @MethodSource("whitelistedNicknames")
    @DisplayName("[USER-NICK-8] 한글 완성형·호환 자모 낱자·영문·숫자로만 이뤄진 닉네임은 문자 구성 규칙을 통과한다")
    void findViolation_whitelistedCharacters_passes(String description, String nickname) {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(violation).isEmpty();
    }

    private static Stream<Arguments> whitelistedNicknames() {
        return Stream.of(
                Arguments.of("한글 완성형", "가나다"),
                Arguments.of("호환 자모 낱자(자음)", "ㄱ"),
                Arguments.of("호환 자모 낱자(모음)", "ㅏ"),
                Arguments.of("영문", "abcABC"),
                Arguments.of("숫자", "123456"),
                Arguments.of("완성형+낱자+영문+숫자 혼합(요구사항 예시)", "ㄱㅏ힣aZ9"),
                Arguments.of("한글+영문+숫자 혼합(요구사항 예시)", "길동gil9")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" -> 문자 구성 위반")
    @MethodSource("nonWhitelistedNicknames")
    @DisplayName("[USER-NICK-8] 허용 문자 외의 문자가 하나라도 포함되면 문자 구성 위반 메시지를 반환한다")
    void findViolation_nonWhitelistedCharacters_returnsPatternViolation(String description, String nickname) {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(violation).contains(NicknamePolicy.PATTERN_MESSAGE);
    }

    private static Stream<Arguments> nonWhitelistedNicknames() {
        return Stream.of(
                Arguments.of("이모지(요구사항 예시)", "굿🎉"), // "굿🎉"
                Arguments.of("특수문자", "hi!"),
                Arguments.of("공백 포함", "a b")
        );
    }

    // ---------- USER-NICK-9: 길이·문자 동시 위반 시 길이 우선 ----------

    @Test
    @DisplayName("[USER-NICK-9] 길이(11자)와 문자 구성을 동시에 위반해도 길이 위반 메시지 1개만 반환한다")
    void findViolation_lengthAndPatternBothViolated_returnsLengthMessageOnly() {
        // given: 11자, 전부 특수문자(화이트리스트 밖)
        String nickname = "!@#$%^&*()!";
        assertThat(nickname).hasSize(11);

        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then: 길이 메시지만 반환되고 문자 구성 메시지는 아니다
        assertThat(violation).contains(NicknamePolicy.LENGTH_MESSAGE);
        assertThat(violation.get()).isNotEqualTo(NicknamePolicy.PATTERN_MESSAGE);
    }

    // ---------- USER-NICK-10: null/빈 문자열 ----------

    @Test
    @DisplayName("[USER-NICK-10] 닉네임이 null이면 예외 없이 길이 위반으로 처리한다")
    void findViolation_null_returnsLengthViolationWithoutException() {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(null);

        // then
        assertThat(violation).contains(NicknamePolicy.LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("[USER-NICK-10] 닉네임이 빈 문자열이면 길이 위반으로 처리한다")
    void findViolation_emptyString_returnsLengthViolation() {
        // when
        Optional<String> violation = NicknamePolicy.findViolation("");

        // then
        assertThat(violation).contains(NicknamePolicy.LENGTH_MESSAGE);
    }

    // ---------- USER-NICK-11: 공백-only ----------

    @Test
    @DisplayName("[USER-NICK-11] 공백 문자로만 이뤄진 닉네임은 길이는 통과하지만 문자 구성 위반으로 거부된다")
    void findViolation_whitespaceOnly_returnsPatternViolation() {
        // given: 공백 3칸(길이는 1~10 범위 안)
        String nickname = "   ";
        assertThat(nickname).hasSize(3);

        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(violation).contains(NicknamePolicy.PATTERN_MESSAGE);
    }

    @Test
    @DisplayName("[USER-NICK-11] 선행 공백이 포함된 닉네임은 문자 구성 위반으로 거부된다")
    void findViolation_leadingWhitespace_returnsPatternViolation() {
        // when
        Optional<String> violation = NicknamePolicy.findViolation(" 홍길동");

        // then
        assertThat(violation).contains(NicknamePolicy.PATTERN_MESSAGE);
    }
}
