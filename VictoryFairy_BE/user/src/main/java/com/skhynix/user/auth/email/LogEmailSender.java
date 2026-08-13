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
}
