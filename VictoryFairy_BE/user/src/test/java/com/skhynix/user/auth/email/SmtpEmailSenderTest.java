package com.skhynix.user.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * {@link SmtpEmailSender}가 실제로 만드는 {@link SimpleMailMessage} 본문·제목을 검증한다.
 *
 * <p>USER-EMV-32(안내 메일 본문에 인증번호가 없어야 한다)는 이 개정의 <b>단일 실패점</b>이다 — 포트
 * 시그니처가 코드를 인자로 받지 않아 구조적으로도 막혀 있지만, 실수로 다른 값을 본문에 실었을 때
 * ({@code String.format} 등으로 우연히 6자리 숫자가 섞여 들어가는 경우까지) 잡아내려면 실제 본문
 * 문자열에 대한 정규식 검사가 필요하다.
 */
class SmtpEmailSenderTest {

    private static final String EMAIL = "user@example.com";

    private JavaMailSender mailSender;
    private SmtpEmailSender smtpEmailSender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        smtpEmailSender = new SmtpEmailSender(mailSender);
    }

    @Test
    @DisplayName("[USER-EMV-2] 인증번호 메일 본문에는 발송한 6자리 코드가 그대로 담긴다")
    void sendVerificationCode_bodyContainsTheCode() {
        // when
        smtpEmailSender.sendVerificationCode(EMAIL, "123456");

        // then
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly(EMAIL);
        assertThat(sent.getText()).contains("123456");
    }

    @Test
    @DisplayName("[USER-EMV-32] '이미 사용 중' 안내 메일 본문에는 6자리 숫자 패턴(\\d{6})이 전혀 나타나지 "
            + "않는다 — 이 정규식 검사가 깨지면 남의 가입 이메일로 인증이 통과 가능해진다")
    void sendAlreadyRegisteredNotice_bodyHasNoSixDigitCode() {
        // when
        smtpEmailSender.sendAlreadyRegisteredNotice(EMAIL);

        // then
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly(EMAIL);
        assertThat(sent.getText()).doesNotContainPattern("\\d{6}");
    }

    @Test
    @DisplayName("[USER-EMV-37] 인증번호 메일과 안내 메일은 제목이 서로 다르다")
    void twoMailKinds_haveDifferentSubjects() {
        // when
        smtpEmailSender.sendVerificationCode(EMAIL, "123456");
        smtpEmailSender.sendAlreadyRegisteredNotice(EMAIL);

        // then
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(captor.capture());
        java.util.List<SimpleMailMessage> sentMessages = captor.getAllValues();

        assertThat(sentMessages).hasSize(2);
        assertThat(sentMessages.get(0).getSubject()).isNotEqualTo(sentMessages.get(1).getSubject());
    }

    @Test
    @DisplayName("인증번호 메일 제목은 현행 문구를 유지한다(요구사항 문서 미기재 — USER-EMV-37 회귀 방지용 경계 확인)")
    void sendVerificationCode_subjectUnchanged() {
        // when
        smtpEmailSender.sendVerificationCode(EMAIL, "123456");

        // then
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("승리요정 : 이메일 인증번호 안내");
    }
}
