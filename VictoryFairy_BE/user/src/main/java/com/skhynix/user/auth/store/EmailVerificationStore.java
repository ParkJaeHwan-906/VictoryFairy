package com.skhynix.user.auth.store;

import java.util.Optional;

/**
 * 이메일 인증 상태 저장소(포트). 구현체는 {@link RedisEmailVerificationStore}(키 규약·TTL은 모듈 문서 참고).
 * 형식 검증·정책 판정은 호출하는 서비스가 하고, 이 저장소는 순수 저장/조회만 책임진다.
 */
public interface EmailVerificationStore {

    /** 같은 키가 있으면 덮어써 이전 코드를 무효화한다(재발송 시나리오). */
    void saveCode(String email, String code);

    Optional<String> findCode(String email);

    /** 인증번호와 시도 카운터를 함께 삭제한다. 검증 성공(1회용 소비)·시도 초과 차단·재발송 시 사용. */
    void invalidateCode(String email);

    /** 1 증가시키고 증가 후 값을 반환한다. 최초 증가 시 코드와 동일한 TTL을 설정해 함께 만료되게 한다. */
    int incrementAttempts(String email);

    int getAttempts(String email);

    void startCooldown(String email);

    boolean isCoolingDown(String email);

    void markVerified(String email);

    boolean isVerified(String email);

    /** 가입 성공 시 1회용 소비. */
    void consumeVerified(String email);
}
