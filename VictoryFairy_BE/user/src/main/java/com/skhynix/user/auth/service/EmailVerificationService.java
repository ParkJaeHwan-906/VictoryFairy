package com.skhynix.user.auth.service;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.SocialAccountHintResponse;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.store.EmailVerificationStore;
import com.skhynix.user.oauth.service.SocialOnlyAccountInspector;
import java.security.SecureRandom;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /**
     * 인증번호 1건당 허용 검증 실패 횟수. 이 값에 도달하면 이후 시도를 차단하고 재발송을 요구한다.
     *
     * <p>{@code public} 인 이유는 소셜 로그인의 티켓 단위 인증({@code OauthEmailVerificationService})이
     * 같은 한도를 따라야 하기 때문이다 — 값을 복제하면 언젠가 한쪽만 바뀌어 두 경로의 정책이 갈라진다.
     */
    public static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationStore store;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final SocialOnlyAccountInspector socialOnlyAccountInspector;

    public void sendCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
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

    /**
     * 이메일 점유 거절을 <b>사용자가 할 수 있는 일</b>에 맞춰 가른다.
     *
     * <p>소셜로만 가입된 계정은 비밀번호가 잠긴 값이라 자체 로그인이 영원히 성립하지 않는다. 그런
     * 사용자에게 "이미 사용 중인 이메일입니다"만 주면 남이 쓰는 주소로 오해하고 실제 입구(소셜 버튼)를
     * 못 찾는다 — 계정을 되찾을 다른 경로도 없다(비밀번호 재설정 API 자체가 없다).
     *
     * <p>⚠ 이 세분화를 로그인({@code INVALID_CREDENTIALS})으로 가져가지 말 것. 그쪽은 계정 존재를
     * 감추는 계약이라 같은 안내를 붙이면 계정 열거가 된다. 여기서만 되는 이유는 이 응답이 원래부터
     * 409 로 가입 사실을 알려 왔기 때문이다.
     */
    private BusinessException duplicateEmail(String email) {
        List<String> providers = socialOnlyAccountInspector.loginableProviders(email);
        if (providers.isEmpty()) {
            return new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        return new BusinessDataException(ErrorCode.SOCIAL_ACCOUNT_ONLY,
                new SocialAccountHintResponse(providers));
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
