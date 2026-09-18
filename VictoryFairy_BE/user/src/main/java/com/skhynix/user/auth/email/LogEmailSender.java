package com.skhynix.user.auth.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
@Slf4j
public class LogEmailSender implements EmailSender {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("[MOCK-EMAIL] 인증번호 발송 to={} code={}", email, code);
    }

    /**
     * 갈래를 로그 문구로 구분해 남긴다(USER-EMV-21 인수 기준) — 비-prod 에서 어느 갈래로 갔는지
     * 확인할 수 있는 유일한 관측 지점이다. 실메일과 달리 코드를 안 찍는 것이 아니라 <b>받지 못한다</b>
     * (포트 시그니처에 code 가 없다).
     */
    @Override
    public void sendAlreadyRegisteredNotice(String email) {
        log.info("[MOCK-EMAIL] 이미 사용 중 안내 발송 to={}", email);
    }
}
