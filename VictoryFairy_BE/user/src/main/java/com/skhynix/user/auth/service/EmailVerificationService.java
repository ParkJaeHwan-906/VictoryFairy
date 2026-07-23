package com.skhynix.user.auth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.store.EmailVerificationStore;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회원가입용 이메일 소유 검증. 발송(인증번호 메일) → 검증(이메일+인증번호 대조) 2단계로 소유를 확인하고,
 * 검증에 성공한 이메일만 signup을 허용하도록 상태를 남긴다.
 *
 * <p>인증번호·시도횟수·쿨다운·인증완료 상태의 실제 저장은 {@link EmailVerificationStore}(포트)에 위임한다.
 * 이 서비스는 <b>정책 판정</b>(가입 이력·쿨다운·시도 5회 한도·1회용 소비 순서)만 담당하고 저장소 세부는
 * 알지 않는다. 형식 검증(400, Bean Validation)은 컨트롤러 DTO가 끝내므로 여기서는 저장값 대조/정책
 * 위반만 {@link BusinessException}으로 던진다(형식과 대조의 분리).
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** 인증번호 1건당 허용 검증 실패 횟수. 이 값에 도달하면 이후 시도를 차단하고 재발송을 요구한다. */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationStore store;
    private final UserRepository userRepository;
    private final EmailSender emailSender;

    /**
     * 인증번호 발송. 이미 가입된 이메일은 409로 사전 차단(USER-EMV-14), 쿨다운(60초) 내 재요청은
     * 429로 거부한다(USER-EMV-5). 재발송 시 이전 코드·시도 카운터를 무효화하고 새 코드를 저장·발송한다
     * (USER-EMV-1/2/3/6).
     */
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

    /**
     * 인증번호 검증. 유효 코드 없음(미발송/만료/사용됨)은 {@code EXPIRED_VERIFICATION_CODE}, 불일치는
     * {@code INVALID_VERIFICATION_CODE}, 5회 초과 시도는 {@code VERIFICATION_ATTEMPTS_EXCEEDED}로
     * 던진다. 성공 시 코드를 즉시 무효화(1회용)하고 인증완료 상태를 30분 TTL로 저장한다
     * (USER-EMV-8/9/10/11/12/15).
     */
    public void verify(String email, String code) {
        String stored = store.findCode(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPIRED_VERIFICATION_CODE));

        // 직전까지 누적된 실패가 한도에 도달했으면 정답이라도 차단하고 코드를 무효화한다(USER-EMV-12).
        if (store.getAttempts(email) >= MAX_ATTEMPTS) {
            store.invalidateCode(email);
            throw new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }

        if (!stored.equals(code)) {
            // 실패 시 시도 카운터만 올린다. 한도 도달 후 차단은 다음 시도의 상단 사전 체크가 담당한다
            // (5회까지는 INVALID로 응답하고, 6번째 시도부터 EXCEEDED) — 5회째 실패를 곧바로 차단하지 않는다(USER-EMV-12).
            store.incrementAttempts(email);
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        // 성공: 코드/시도 카운터 소비 후 인증완료 상태 저장.
        store.invalidateCode(email);
        store.markVerified(email);
    }

    /**
     * signup 선행 조건 조회 — 이메일이 인증완료 상태(USER-EMV-15)인지 여부. 키 부재(미인증·만료)는
     * 동일하게 false로 흡수된다(USER-EMV-16/17).
     */
    public boolean isEmailVerified(String email) {
        return store.isVerified(email);
    }

    /**
     * 인증완료 상태 소비(USER-EMV-18) — 가입 성공 시 1회용으로 제거한다.
     */
    public void consumeVerified(String email) {
        store.consumeVerified(email);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
