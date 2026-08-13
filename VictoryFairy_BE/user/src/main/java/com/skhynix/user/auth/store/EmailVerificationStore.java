package com.skhynix.user.auth.store;

import java.util.Optional;

public interface EmailVerificationStore {

    void saveCode(String email, String code);

    Optional<String> findCode(String email);

    /** 인증번호와 시도 카운터를 함께 삭제한다. */
    void invalidateCode(String email);

    /** 1 증가시키고 증가 후 값을 반환한다. */
    int incrementAttempts(String email);

    int getAttempts(String email);

    void startCooldown(String email);

    boolean isCoolingDown(String email);

    void markVerified(String email);

    boolean isVerified(String email);

    void consumeVerified(String email);
}
