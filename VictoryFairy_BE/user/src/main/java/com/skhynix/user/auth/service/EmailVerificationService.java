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

    /**
     * 인증번호 발송 — <b>가입 여부와 무관하게 200</b>(USER-EMV-19·20).
     *
     * <p>종전에는 가입된 이메일에 409(`DUPLICATE_EMAIL`·`SOCIAL_ACCOUNT_ONLY`)를 돌려줬는데, 이
     * 경로가 인증 없이 열려 있어 주소만 바꿔 가며 상태코드만 읽으면 가입자 명부가 그대로 열람됐다.
     * 개정의 핵심은 정보를 없애는 것이 아니라 <b>옮기는 것</b>이다 — 응답은 두 갈래가 같고, 갈림은
     * 그 주소의 메일함 주인만 볼 수 있는 메일 본문에서만 일어난다.
     *
     * <p>⚠ <b>갈래 2도 갈래 1과 똑같이 난수 코드를 저장한다</b>(USER-EMV-22·33). 저장을 건너뛰면
     * 이어지는 {@code verify} 가 "코드 없음(EXPIRED)" 과 "코드 틀림(INVALID)" 으로 갈려 요청 2회로
     * 열거가 그대로 성립한다 — send-code 만 200 으로 맞추는 건 열거기를 한 단계 뒤로 미루는 것일
     * 뿐이다. 그 코드가 메일로 나가지 않아(USER-EMV-32) 사용자가 알 수 없고, 설령 맞혀 인증완료
     * 상태를 만들어도 signup 이 409 `DUPLICATE_EMAIL` 로 막는다(USER-EMV-31·36).
     *
     * <p>두 갈래의 작업량도 같게 둔다(USER-EMV-30): 존재 조회 1 + 코드 생성 1 + 저장 1 + 쿨다운 1 +
     * 메일 1. 한쪽에만 추가 조회를 붙이면 응답시간이 갈려 같은 열거가 부채널로 되살아난다.
     */
    public void sendCode(String email) {
        // 쿨다운을 가장 먼저 본다 — 두 갈래 공통이고(USER-EMV-27·28), 429 갈래에서 불필요한 DB 조회를
        // 만들지 않는다. 어느 갈래든 여기서 막히면 메일은 0통이다.
        if (store.isCoolingDown(email)) {
            throw new BusinessException(ErrorCode.EMAIL_SEND_COOLDOWN);
        }

        // 가입 여부는 이제 '거절 사유'가 아니라 '어떤 메일을 보낼지'만 정한다. 자체 가입·소셜 전용·
        // 탈퇴 점유를 가르지 않는 이유는 users.email 이 그 셋을 모두 점유된 것으로 보기 때문이다.
        boolean alreadyRegistered = userRepository.existsByEmail(email);

        String code = generateCode();
        store.invalidateCode(email); // 재발송: 이전 코드/시도 무효화
        store.saveCode(email, code); // TTL 5분
        store.startCooldown(email);  // TTL 60초

        if (alreadyRegistered) {
            emailSender.sendAlreadyRegisteredNotice(email); // 본문에 code 를 싣지 않는다
        } else {
            emailSender.sendVerificationCode(email, code);
        }
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
