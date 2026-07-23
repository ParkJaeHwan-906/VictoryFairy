package com.skhynix.user.auth.email;

/**
 * 인증번호 메일 발송 추상화. 운영은 SMTP 실발송({@link SmtpEmailSender}), 개발/테스트는 로그 대체
 * ({@link LogEmailSender})로 구현이 갈린다({@code @Profile}로 분리). 발송이 일어난다는 계약까지가
 * 이 인터페이스의 책임이고, 본문 문구·템플릿은 구현 재량이다.
 */
public interface EmailSender {

    void sendVerificationCode(String email, String code);
}
