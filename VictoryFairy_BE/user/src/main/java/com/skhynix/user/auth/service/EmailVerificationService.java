package com.skhynix.user.auth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.store.EmailVerificationStore;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** 인증번호 1건당 허용 검증 실패 횟수. 이 값에 도달하면 이후 시도를 차단하고 재발송을 요구한다. */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationStore store;
    private final UserRepository userRepository;
    private final EmailSender emailSender;

    public void sendCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (store.isCoolingDown(email)) {
            throw new BusinessException(ErrorCode.EMAIL_SEND_COOLDOWN);
        }

        String code = generateCode();
        store.invalidateCode(email); // 재발송: 이전 코드/시도 무효화
        store.saveCode(email, code); // TTL 5분
        store.startCooldown(email);  // TTL 60초

        emailSender.sendVerificationCode(email, code);
    }

    public void verify(String email, String code) {
        String stored = store.findCode(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPIRED_VERIFICATION_CODE));

        // 직전까지 누적된 실패가 한도에 도달했으면 정답이라도 차단하고 코드를 무효화한다.
        if (store.getAttempts(email) >= MAX_ATTEMPTS) {
            store.invalidateCode(email);
            throw new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }

        if (!stored.equals(code)) {
            // 실패 시 시도 카운터만 올린다 — 5회까지는 INVALID, 6번째 시도부터 EXCEEDED로 응답한다
            // (5회째 실패를 곧바로 차단하지 않는다).
            store.incrementAttempts(email);
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        store.invalidateCode(email);
        store.markVerified(email);
    }

    /** signup 선행 조건 조회 — 이메일 인증완료 여부. 키 부재(미인증·만료)는 동일하게 false. */
    public boolean isEmailVerified(String email) {
        return store.isVerified(email);
    }

    /** 인증완료 상태 소비 — 가입 성공 시 1회용으로 제거한다. */
    public void consumeVerified(String email) {
        store.consumeVerified(email);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
