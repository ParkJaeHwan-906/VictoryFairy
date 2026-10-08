package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@link AuthService}의 {@code DUMMY_PASSWORD_HASH} 상수 자체의 성질(USER-LAE-3, USER-LAE-5,
 * USER-LAE-13)을 검증한다. 이 값은 {@code private static final}이라 리플렉션으로 읽는다 — 접근 제어자를
 * 넓히면 "코드 상수 1개"라는 계약(USER-LAE-13)과 무관하게 캡슐화가 약해지므로 프로덕션 코드는 그대로 둔다.
 */
class AuthServiceDummyHashTest {

    private String readDummyPasswordHash() throws NoSuchFieldException, IllegalAccessException {
        Field field = AuthService.class.getDeclaredField("DUMMY_PASSWORD_HASH");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    @DisplayName("[USER-LAE-3] 더미 해시는 BCrypt cost 10 형식(^\\$2[aby]\\$10\\$)을 만족한다")
    void dummyHash_matchesBCryptCost10Pattern() throws Exception {
        // when
        String dummyHash = readDummyPasswordHash();

        // then
        assertThat(dummyHash).matches("^\\$2[aby]\\$10\\$.*");
    }

    @Test
    @DisplayName("[USER-LAE-3] 실제 BCryptPasswordEncoder로 더미 해시를 대상으로 matches()를 호출해도 "
            + "\"does not look like BCrypt\" 경고 없이(즉 형식 인식 실패 예외 없이) 정상적으로 boolean을 반환한다")
    void dummyHash_recognizedAsValidBCryptFormatByRealEncoder() throws Exception {
        // given
        String dummyHash = readDummyPasswordHash();
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder();

        // when & then: 형식이 어긋나면 matches()가 예외를 던진다(IllegalArgumentException 계열) —
        // 형식이 올바르면 예외 없이 boolean만 돌아온다.
        assertThatCode(() -> realEncoder.matches("아무 원문 비밀번호", dummyHash))
                .doesNotThrowAnyException();
        assertThat(realEncoder.matches("아무 원문 비밀번호", dummyHash)).isFalse();
    }

    @Test
    @DisplayName("[USER-LAE-13] 더미 해시는 같은 프로세스 안에서 읽을 때마다 동일한 값이다 — 요청마다 "
            + "런타임에 새로 인코딩하는 값이 아니라 코드에 고정된 상수 1개다")
    void dummyHash_isStableConstantAcrossReads() throws Exception {
        // when
        String first = readDummyPasswordHash();
        String second = readDummyPasswordHash();

        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("[USER-LAE-5] 더미 해시 값은 정상적인 회원가입 비밀번호 인코딩 결과와 절대 같을 수 없다 "
            + "— 원문을 모르는 임의의 코드 상수이므로, 같은 원문을 실제로 인코딩한 값과 문자열이 다르다")
    void dummyHash_neverEqualsFreshlyEncodedRealPassword() throws Exception {
        // given
        String dummyHash = readDummyPasswordHash();
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder();

        // when: 실제 계정 생성 경로와 동일하게 비밀번호를 인코딩한다
        String freshlyEncoded = realEncoder.encode("SomeRealPassw0rd!");

        // then: BCrypt는 매 인코딩마다 랜덤 salt를 써서 같은 원문도 다른 해시를 내므로, 애초에 더미
        // 해시 문자열과 우연히도 같아질 수 없다(상수 노출이 로그인 가능한 값의 노출로 이어지지 않는
        // 근거 중 하나).
        assertThat(freshlyEncoded).isNotEqualTo(dummyHash);
    }
}
