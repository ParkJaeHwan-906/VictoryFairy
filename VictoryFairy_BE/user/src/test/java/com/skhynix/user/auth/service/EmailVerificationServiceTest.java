package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.store.EmailVerificationStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link EmailVerificationService}를 협력 객체({@link EmailVerificationStore}·{@link UserRepository}·
 * {@link EmailSender}) 전부 목으로 대체해 정책 판정(발송 순서·쿨다운·시도 5회 한도·1회용 소비)을 단위로
 * 검증한다. {@code docs/requirements/user/email-verification.md}의 요구사항 ID(USER-EMV-*)를
 * {@code @DisplayName}에 접두해 추적 가능하게 한다.
 *
 * <p>2026-09-18 개정(USER-EMV-19~38)으로 send-code의 계정 열거 차단이 적용됐다: {@code existsByEmail}
 * 판정에 따라 갈래 1(가입 이력 없음)·갈래 2(가입 이력 있음 — 자체 가입·소셜 전용·탈퇴 계정 점유 전부 포함)로
 * 나뉘고, 두 갈래 모두 200을 반환하며 차이는 오직 발송되는 메일 종류뿐이다. {@code SocialOnlyAccountInspector}
 * 는 더 이상 이 서비스의 협력자가 아니다(send-code가 더 이상 {@code SOCIAL_ACCOUNT_ONLY}를 던지지 않음).
 *
 * <p>{@link com.skhynix.user.auth.store.RedisEmailVerificationStore}는 스텁이라 실제 Redis TTL(코드
 * 5분·쿨다운 60초·인증완료 30분, USER-EMV-3/15)은 이 테스트로 검증할 수 없다 — 저장소가 "그 TTL을 지킨다"는
 * 계약만 인터페이스 Javadoc으로 확인했고, 서비스가 저장소의 각 메서드를 올바른 시점에 호출하는지까지만
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private EmailVerificationStore store;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailSender emailSender;

    @InjectMocks
    private EmailVerificationService service;

    // ---------- sendCode ----------

    @Test
    @DisplayName("[USER-EMV-1, USER-EMV-2] 미가입·쿨다운 아닌 이메일로 발송을 요청하면 "
            + "6자리 숫자 인증번호를 저장하고 해당 이메일로 발송한다")
    void sendCode_validEmail_savesAndSendsSixDigitCode() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then
        ArgumentCaptor<String> savedCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq(EMAIL), savedCodeCaptor.capture());
        assertThat(savedCodeCaptor.getValue()).matches("^\\d{6}$");

        ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendVerificationCode(eq(EMAIL), sentCodeCaptor.capture());
        assertThat(sentCodeCaptor.getValue()).isEqualTo(savedCodeCaptor.getValue());
    }

    @Test
    @DisplayName("[USER-EMV-6] 발송 시 재발송 시나리오를 대비해 무효화 -> 저장 -> 쿨다운 시작 -> 발송 순서로 처리한다")
    void sendCode_validEmail_invalidatesBeforeSavingNewCode() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then
        InOrder inOrder = Mockito.inOrder(store, emailSender);
        inOrder.verify(store).invalidateCode(EMAIL);
        inOrder.verify(store).saveCode(eq(EMAIL), anyString());
        inOrder.verify(store).startCooldown(EMAIL);
        inOrder.verify(emailSender).sendVerificationCode(eq(EMAIL), anyString());
    }

    @Test
    @DisplayName("[USER-EMV-19, USER-EMV-20, USER-EMV-21] 이미 가입된 이메일(갈래 2)로 발송을 요청해도 "
            + "예외 없이 200으로 반환되고(호출자 관점에서는 정상 종료), DUPLICATE_EMAIL·SOCIAL_ACCOUNT_ONLY "
            + "어느 것도 던지지 않으며 '이미 사용 중' 안내 메일만 발송된다(인증번호 메일은 발송되지 않는다)")
    void sendCode_alreadyRegisteredEmail_returnsWithoutExceptionAndSendsNoticeMail() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when & then: 예외가 없다는 것 자체가 USER-EMV-19/20의 핵심 단언이다.
        service.sendCode(EMAIL);

        verify(emailSender).sendAlreadyRegisteredNotice(EMAIL);
        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-EMV-22, USER-EMV-33] 갈래 2(가입 이력 있음)에도 갈래 1과 동일한 키·규칙으로 "
            + "6자리 인증번호가 saveCode 로 저장된다 — verify 가 EXPIRED 와 INVALID 로 갈리지 않게 하는 "
            + "구조적 장치다")
    void sendCode_alreadyRegisteredEmail_stillSavesSixDigitCode() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then
        ArgumentCaptor<String> savedCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq(EMAIL), savedCodeCaptor.capture());
        assertThat(savedCodeCaptor.getValue()).matches("^\\d{6}$");
    }

    @Test
    @DisplayName("[USER-EMV-20] 소셜 전용 계정 이메일도(existsByEmail이 true인 이상) 갈래 2로 접혀 "
            + "SOCIAL_ACCOUNT_ONLY 없이 동일하게 200과 '이미 사용 중' 안내 메일로 처리된다 — 이 서비스는 "
            + "더 이상 SocialOnlyAccountInspector 를 협력자로 두지 않는다(존재 여부를 아예 조회하지 않는다)")
    void sendCode_socialOnlyEmail_isAbsorbedIntoBranchTwoWithoutDistinguishing() {
        // given: 소셜 전용 계정도 users.email 을 점유하므로 existsByEmail 은 true 다. 서비스가 소셜
        // 전용 여부를 별도로 조회하지 않는다는 사실은 이 테스트가 소셜 관련 협력자를 아예 주입하지 않고도
        // (컴파일 시점에 이미 협력자가 없다) 통과한다는 것 자체로 드러난다.
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then: 갈래 1의 인증번호 메일이 아니라 갈래 2의 안내 메일이 나간다 — DUPLICATE_EMAIL·
        // SOCIAL_ACCOUNT_ONLY 어느 예외도 던지지 않는다(이 호출이 예외 없이 끝났다는 것 자체가 증거).
        verify(emailSender).sendAlreadyRegisteredNotice(EMAIL);
        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-EMV-26] 미가입 이메일(갈래 1)은 종전과 동일하게 인증번호 메일만 발송되고 "
            + "안내 메일은 발송되지 않는다")
    void sendCode_unregisteredEmail_sendsOnlyVerificationCodeMail() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then
        verify(emailSender).sendVerificationCode(eq(EMAIL), anyString());
        verify(emailSender, never()).sendAlreadyRegisteredNotice(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-27, USER-EMV-30] 갈래 1·갈래 2 모두 작업량이 같다: 존재 조회 1 + 코드 저장 1 "
            + "+ 쿨다운 설정 1 + 메일 1(각 갈래 내 정확히 그만큼만) — 한쪽에만 추가 조회가 붙으면 응답시간이 "
            + "갈려 부채널이 되살아난다는 USER-EMV-30 구조 동등성 검사다")
    void sendCode_bothBranches_doExactlyTheSameAmountOfWork() {
        // given: 갈래 1
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);

        // when
        service.sendCode(EMAIL);

        // then: 존재 조회 1 + 코드 저장 1 + 쿨다운 설정 1 + 인증번호 메일 1, 안내 메일은 0
        verify(userRepository, times(1)).existsByEmail(EMAIL);
        verify(store, times(1)).saveCode(eq(EMAIL), anyString());
        verify(store, times(1)).startCooldown(EMAIL);
        verify(emailSender, times(1)).sendVerificationCode(eq(EMAIL), anyString());
        verify(emailSender, never()).sendAlreadyRegisteredNotice(anyString());

        // given: 갈래 2 (다른 이메일로 재현 — 같은 서비스 인스턴스, mock 재사용)
        String registeredEmail = "registered@example.com";
        given(userRepository.existsByEmail(registeredEmail)).willReturn(true);
        given(store.isCoolingDown(registeredEmail)).willReturn(false);

        // when
        service.sendCode(registeredEmail);

        // then: 존재 조회 1 + 코드 저장 1 + 쿨다운 설정 1 + 안내 메일 1, 인증번호 메일은 여전히 0(추가 없음)
        verify(userRepository, times(1)).existsByEmail(registeredEmail);
        verify(store, times(1)).saveCode(eq(registeredEmail), anyString());
        verify(store, times(1)).startCooldown(registeredEmail);
        verify(emailSender, times(1)).sendAlreadyRegisteredNotice(registeredEmail);
        verify(emailSender, never()).sendVerificationCode(eq(registeredEmail), anyString());
    }

    @Test
    @DisplayName("[USER-EMV-28] 갈래 2 이메일도 쿨다운(60초) 이내 재요청이면 갈래 1과 동일하게 429 "
            + "EMAIL_SEND_COOLDOWN 을 던지고 메일을 발송하지 않는다")
    void sendCode_alreadyRegisteredEmail_withinCooldown_throwsCooldownWithoutSending() {
        // given
        given(store.isCoolingDown(EMAIL)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.sendCode(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_COOLDOWN);

        // 쿨다운이 가입 여부 조회보다 먼저이므로(구현 순서), existsByEmail 조차 조회하지 않는다.
        verifyNoInteractions(userRepository);
        verifyNoInteractions(emailSender);
        verify(store, never()).saveCode(anyString(), anyString());
        verify(store, never()).invalidateCode(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-5] 미가입 이메일도 쿨다운(60초) 이내에 재발송을 요청하면 인증번호를 발송하지 않고 EMAIL_SEND_COOLDOWN을 던진다")
    void sendCode_withinCooldown_throwsCooldownWithoutSending() {
        // given
        given(store.isCoolingDown(EMAIL)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.sendCode(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_COOLDOWN);

        verifyNoInteractions(emailSender);
        verify(store, never()).saveCode(anyString(), anyString());
        verify(store, never()).invalidateCode(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-29] 갈래 1·갈래 2의 성공 응답은 예외 없이 동일하게 종료된다는 점에서 "
            + "컨트롤러가 만드는 200 응답 본문이 갈래로 갈릴 신호(리턴값·예외타입 차이)가 없다 — "
            + "sendCode() 의 반환형이 void 이고 두 갈래 모두 정상 반환이라는 사실 자체가 구조적 동등성이다")
    void sendCode_bothBranches_returnVoidWithoutThrowing() {
        // given: 갈래 1
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);
        // when & then: 예외 없이 반환
        org.assertj.core.api.Assertions.assertThatCode(() -> service.sendCode(EMAIL))
                .doesNotThrowAnyException();

        // given: 갈래 2
        String registeredEmail = "registered2@example.com";
        given(userRepository.existsByEmail(registeredEmail)).willReturn(true);
        given(store.isCoolingDown(registeredEmail)).willReturn(false);
        // when & then: 예외 없이 반환
        org.assertj.core.api.Assertions.assertThatCode(() -> service.sendCode(registeredEmail))
                .doesNotThrowAnyException();
    }

    // ---------- verify ----------

    @Test
    @DisplayName("[USER-EMV-11] 저장된 인증번호가 없으면(미발송·만료·이미 사용됨) EXPIRED_VERIFICATION_CODE를 던진다")
    void verify_noStoredCode_throwsExpiredVerificationCode() {
        // given
        given(store.findCode(EMAIL)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.verify(EMAIL, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_VERIFICATION_CODE);

        verify(store, never()).getAttempts(anyString());
        verify(store, never()).incrementAttempts(anyString());
        verify(store, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-12] 이미 5회 실패가 누적된 상태(6번째 시도)로 검증을 시도하면 정답 코드여도 차단하고 코드를 무효화한다")
    void verify_sixthAttemptAfterFiveFailures_blocksEvenWithCorrectCodeAndInvalidatesCode() {
        // given
        given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));
        given(store.getAttempts(EMAIL)).willReturn(5);

        // when & then
        assertThatThrownBy(() -> service.verify(EMAIL, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);

        verify(store).invalidateCode(EMAIL);
        verify(store, never()).markVerified(anyString());
        verify(store, never()).incrementAttempts(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-10] 인증번호가 저장값과 불일치하면 시도 횟수를 증가시키고 INVALID_VERIFICATION_CODE를 던진다")
    void verify_codeMismatchBelowLimit_incrementsAttemptsAndThrowsInvalidCode() {
        // given
        given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));
        given(store.getAttempts(EMAIL)).willReturn(2);
        given(store.incrementAttempts(EMAIL)).willReturn(3);

        // when & then
        assertThatThrownBy(() -> service.verify(EMAIL, "999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(store).incrementAttempts(EMAIL);
        verify(store, never()).invalidateCode(anyString());
        verify(store, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-12] 불일치 시도가 이번 실패로 5회째(한도)에 도달해도 이번 시도는 INVALID_VERIFICATION_CODE를 던진다"
            + "(5회까지는 실패 허용, 차단은 다음 6번째 시도부터)")
    void verify_codeMismatchOnFifthAttempt_stillThrowsInvalidCodeWithoutInvalidatingCode() {
        // given
        given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));
        given(store.getAttempts(EMAIL)).willReturn(4);
        given(store.incrementAttempts(EMAIL)).willReturn(5);

        // when & then
        assertThatThrownBy(() -> service.verify(EMAIL, "999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(store).incrementAttempts(EMAIL);
        verify(store, never()).invalidateCode(anyString());
        verify(store, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-8, USER-EMV-9, USER-EMV-15] 올바른 인증번호로 검증하면 코드를 즉시 무효화(1회용)하고 "
            + "인증완료 상태를 저장한다")
    void verify_correctCode_invalidatesCodeAndMarksVerified() {
        // given
        given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));
        given(store.getAttempts(EMAIL)).willReturn(0);

        // when
        service.verify(EMAIL, "123456");

        // then
        InOrder inOrder = Mockito.inOrder(store);
        inOrder.verify(store).invalidateCode(EMAIL);
        inOrder.verify(store).markVerified(EMAIL);
        verify(store, never()).incrementAttempts(anyString());
    }

    // ---------- verify: 갈래 2(가입 이력 있음) 이메일도 갈래 1과 완전히 동일하게 동작한다 ----------
    // (USER-EMV-34, USER-EMV-35, USER-EMV-36 — verify()는 존재 이력을 전혀 참조하지 않고 store만
    // 본다. 여기서 "갈래 2"는 단지 그 이메일에 가입 이력이 있다는 시나리오 설명일 뿐, 서비스 코드
    // 경로는 갈래 1과 정확히 같다 — 그 자체가 인수 기준이다: 두 갈래를 구분하는 분기가 verify()
    // 어디에도 없다.)

    @Test
    @DisplayName("[USER-EMV-34] 갈래 2 이메일(가입 이력 있음)에 저장된 값과 다른 인증번호로 검증을 "
            + "요청하면 갈래 1과 동일하게 400 INVALID_VERIFICATION_CODE를 던진다(EXPIRED로 갈리지 않는다)")
    void verify_branchTwoEmail_wrongCode_throwsInvalidVerificationCodeSameAsBranchOne() {
        // given: send-code가 갈래 2에도 코드를 저장해 뒀다는 전제(USER-EMV-22)를 store 상태로 재현한다.
        String registeredEmail = "registered@example.com";
        given(store.findCode(registeredEmail)).willReturn(Optional.of("123456"));
        given(store.getAttempts(registeredEmail)).willReturn(0);
        given(store.incrementAttempts(registeredEmail)).willReturn(1);

        // when & then
        assertThatThrownBy(() -> service.verify(registeredEmail, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(store).incrementAttempts(registeredEmail);
    }

    @Test
    @DisplayName("[USER-EMV-35] 갈래 2 이메일도 검증 실패가 5회를 초과하면(6번째 시도) 갈래 1과 동일하게 "
            + "400 VERIFICATION_ATTEMPTS_EXCEEDED를 반환하고 코드를 무효화한다")
    void verify_branchTwoEmail_sixthAttempt_throwsAttemptsExceededSameAsBranchOne() {
        // given
        String registeredEmail = "registered@example.com";
        given(store.findCode(registeredEmail)).willReturn(Optional.of("123456"));
        given(store.getAttempts(registeredEmail)).willReturn(5);

        // when & then
        assertThatThrownBy(() -> service.verify(registeredEmail, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);

        verify(store).invalidateCode(registeredEmail);
        verify(store, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("[USER-EMV-36] 갈래 2 이메일의 저장된 인증번호와 일치하는 검증 요청은 갈래 1과 동일하게 "
            + "코드를 무효화하고 인증완료 상태를 저장한다 — signup은 그 뒤 별도로 DUPLICATE_EMAIL로 막는다")
    void verify_branchTwoEmail_correctCode_marksVerifiedSameAsBranchOne() {
        // given
        String registeredEmail = "registered@example.com";
        given(store.findCode(registeredEmail)).willReturn(Optional.of("123456"));
        given(store.getAttempts(registeredEmail)).willReturn(0);

        // when
        service.verify(registeredEmail, "123456");

        // then
        InOrder inOrder = Mockito.inOrder(store);
        inOrder.verify(store).invalidateCode(registeredEmail);
        inOrder.verify(store).markVerified(registeredEmail);
    }

    // ---------- sendCode: 메일 발송 실패 전파 (USER-EMV-38) ----------

    @Test
    @DisplayName("[USER-EMV-38] 갈래 1에서 메일 발송이 실패하면(sendVerificationCode가 예외를 던짐) "
            + "그 예외가 그대로 전파된다 — 실패를 200으로 삼키지 않는다")
    void sendCode_branchOneMailSendFails_propagatesException() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(store.isCoolingDown(EMAIL)).willReturn(false);
        RuntimeException mailFailure = new RuntimeException("SMTP down");
        org.mockito.Mockito.doThrow(mailFailure).when(emailSender).sendVerificationCode(eq(EMAIL), anyString());

        // when & then
        assertThatThrownBy(() -> service.sendCode(EMAIL)).isSameAs(mailFailure);
    }

    @Test
    @DisplayName("[USER-EMV-38] 갈래 2에서 메일 발송이 실패하면(sendAlreadyRegisteredNotice가 예외를 던짐) "
            + "갈래 1과 동일하게 그 예외가 그대로 전파된다 — 갈래마다 실패 처리가 갈리지 않는다")
    void sendCode_branchTwoMailSendFails_propagatesExceptionSameAsBranchOne() {
        // given
        String registeredEmail = "registered@example.com";
        given(userRepository.existsByEmail(registeredEmail)).willReturn(true);
        given(store.isCoolingDown(registeredEmail)).willReturn(false);
        RuntimeException mailFailure = new RuntimeException("SMTP down");
        org.mockito.Mockito.doThrow(mailFailure).when(emailSender).sendAlreadyRegisteredNotice(registeredEmail);

        // when & then
        assertThatThrownBy(() -> service.sendCode(registeredEmail)).isSameAs(mailFailure);
    }

    // ---------- isEmailVerified / consumeVerified (signup 연동) ----------

    @Test
    @DisplayName("[USER-EMV-16, USER-EMV-17] 인증완료 상태가 없으면(미인증·만료 모두 키 부재로 흡수) false를 반환한다")
    void isEmailVerified_noVerifiedState_returnsFalse() {
        // given
        given(store.isVerified(EMAIL)).willReturn(false);

        // when & then
        assertThat(service.isEmailVerified(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("인증완료 상태(TTL 이내)가 있으면 true를 반환한다")
    void isEmailVerified_verifiedState_returnsTrue() {
        // given
        given(store.isVerified(EMAIL)).willReturn(true);

        // when & then
        assertThat(service.isEmailVerified(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("[USER-EMV-18] consumeVerified 호출 시 저장소의 인증완료 상태 삭제를 위임한다")
    void consumeVerified_delegatesToStore() {
        // when
        service.consumeVerified(EMAIL);

        // then
        verify(store).consumeVerified(EMAIL);
    }
}
