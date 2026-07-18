package com.skhynix.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
    @DisplayName("[USER-EMV-14] 이미 가입된 이메일로 발송을 요청하면 인증번호를 발송하지 않고 DUPLICATE_EMAIL을 던진다")
    void sendCode_alreadyRegisteredEmail_throwsDuplicateEmailWithoutSending() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.sendCode(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verifyNoInteractions(emailSender);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("[USER-EMV-5] 쿨다운(60초) 이내에 재발송을 요청하면 인증번호를 발송하지 않고 EMAIL_SEND_COOLDOWN을 던진다")
    void sendCode_withinCooldown_throwsCooldownWithoutSending() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
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
