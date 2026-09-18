package com.skhynix.user.auth.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

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

    /**
     * 제목이 인증번호 메일과 다르다(USER-EMV-37). 메일함은 주인만 보므로 제목이 갈려도 열거 경로가
     * 아니다.
     *
     * <p>⚠ 본문에 인증번호를 넣지 말 것(USER-EMV-32). 이 메일을 받는 주소에도 코드가 저장돼 있어
     * (USER-EMV-22) 본문에 싣는 순간 그 계정의 이메일 인증이 통과 가능해진다.
     */
    @Override
    public void sendAlreadyRegisteredNotice(String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("승리요정 : 이미 가입된 이메일 안내");
        message.setText("""
                승리를 기다리는 모든 순간 더 재미있게!
                방금 이 주소로 회원가입 인증이 요청되었습니다.

                이 이메일은 이미 승리요정에 가입되어 있어 새로 가입하실 수 없습니다.
                로그인 화면에서 기존 계정으로 이용해 주세요.

                본인이 요청하지 않으셨다면 이 메일은 무시하셔도 됩니다.""");
        mailSender.send(message);
    }
}
