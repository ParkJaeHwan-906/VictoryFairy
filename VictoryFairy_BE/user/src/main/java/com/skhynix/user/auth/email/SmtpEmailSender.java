package com.skhynix.user.auth.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 운영용 SMTP 실발송 구현. {@code prod} 프로파일에서만 로딩되며, JavaMailSender는
 * {@code spring.mail.*} 설정이 있을 때 Boot가 자동 구성한다(운영 설정은 application-prod.yaml).
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Override
    public void sendVerificationCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[VictoryFairy] 이메일 인증번호 안내");
        message.setText("인증번호는 [" + code + "] 입니다. 5분 이내에 입력해 주세요.");
        mailSender.send(message);
    }
}
