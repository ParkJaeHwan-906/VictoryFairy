package com.skhynix.user.auth.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 mock 발송 구현. {@code prod}가 아닌 모든 프로파일에서 로딩돼 실제 메일을 보내지 않고
 * 인증번호를 로그로만 남긴다(발송 계약 검증은 이 경계에서). SMTP 계정 없이 dev에서 코드 확인이 가능하다.
 */
@Component
@Profile("!prod")
@Slf4j
public class LogEmailSender implements EmailSender {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("[MOCK-EMAIL] 인증번호 발송 to={} code={}", email, code);
    }
}
