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
        message.setSubject("승리요정 : 이메일 인증번호 안내");
        message.setText("""
                승리를 기다리는 모든 순간 더 재미있게!
                아래 인증번호를 입력하여 이메일 인증을 완료해주세요.

                인증번호 : %s

                인증번호는 발송 후 5분간 유효합니다.""".formatted(code));
        mailSender.send(message);
    }
}
