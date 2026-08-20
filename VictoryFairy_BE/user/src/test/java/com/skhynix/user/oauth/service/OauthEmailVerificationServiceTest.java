package com.skhynix.user.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OauthEmailVerificationService} — 소셜 인증 티켓에 결부된 인증번호 절차. 요구사항:
 * {@code docs/requirements/user/oauth-login.md} USER-OAU-69, 75~80, 92~95.
 *
 * <p>이 클래스가 {@code UserRepository}·{@code UserAccountRepository} 어느 쪽도 협력자로 갖지 않는다는
 * 사실 자체가 USER-OAU-95(발송 응답이 계정 존재 여부로 갈리지 않는다)의 구조적 근거다 — 계정을 조회할
 * 수단이 애초에 없다.
 */
@ExtendWith(MockitoExtension.class)
class OauthEmailVerificationServiceTest {

    private static final String TOKEN = "ticket-token";

    @Mock
    private OauthTicketStore ticketStore;

    @Mock
    private EmailSender emailSender;

    private OauthEmailVerificationService newService() {
        return new OauthEmailVerificationService(ticketStore, emailSender);
    }

    // ---------- sendCode(): LINK 티켓 (USER-OAU-69) ----------

    @Test
    @DisplayName("[USER-OAU-69] LINK 티켓이면 본문 이메일과 무관하게 티켓에 실린 provider 이메일로만 발송한다"
            + "(남의 계정 이메일로 코드를 보내는 우회로 차단)")
    void sendCode_linkTicket_ignoresBodyEmailAndUsesTicketEmail() {
        // given
        OauthTicket ticket = OauthTicket.link(OauthProvider.KAKAO, "kakao-1", "owner@example.com");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(false);
        OauthEmailVerificationService service = newService();

        // when: 본문에 다른 사람의 이메일을 실어 보냈다고 가정
        service.sendCode(TOKEN, ticket, "attacker-supplied@example.com");

        // then
        verify(emailSender).sendVerificationCode(eq("owner@example.com"), anyString());
        verify(emailSender, never()).sendVerificationCode(eq("attacker-supplied@example.com"), anyString());
    }

    // ---------- sendCode(): INPUT 티켓 (USER-OAU-92, 93) ----------

    @Test
    @DisplayName("[USER-OAU-92] INPUT 티켓이면 본문 이메일로 발송한다(입력 티켓만 본문 이메일을 받는 유일한 경우)")
    void sendCode_inputTicket_usesBodyEmail() {
        // given
        OauthTicket ticket = OauthTicket.input(OauthProvider.KAKAO, "kakao-2");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(false);
        OauthEmailVerificationService service = newService();

        // when
        service.sendCode(TOKEN, ticket, "user-typed@example.com");

        // then
        verify(emailSender).sendVerificationCode(eq("user-typed@example.com"), anyString());
    }

    @Test
    @DisplayName("[USER-OAU-93] INPUT 티켓으로 재발송하면 이전 코드·시도·대상주소를 무효화한 뒤 최신 주소로 "
            + "새로 저장한다 — invalidateCode가 saveCode보다 먼저다")
    void sendCode_inputTicket_invalidatesPreviousStateBeforeSavingLatestTarget() {
        // given
        OauthTicket ticket = OauthTicket.input(OauthProvider.NAVER, "naver-1");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(false);
        OauthEmailVerificationService service = newService();

        // when
        service.sendCode(TOKEN, ticket, "corrected@example.com");

        // then
        InOrder order = inOrder(ticketStore, emailSender);
        order.verify(ticketStore).invalidateCode(TOKEN);
        order.verify(ticketStore).saveCode(eq(TOKEN), anyString(), eq("corrected@example.com"));
        order.verify(ticketStore).startCooldown(TOKEN);
        order.verify(emailSender).sendVerificationCode(eq("corrected@example.com"), anyString());
    }

    // ---------- sendCode(): 쿨다운 (USER-OAU-79, 94) ----------

    @Test
    @DisplayName("[USER-OAU-79] 티켓이 쿨다운 중이면 429 EMAIL_SEND_COOLDOWN을 던지고 발송하지 않는다")
    void sendCode_ticketCoolingDown_throwsCooldownWithoutSending() {
        // given
        OauthTicket ticket = OauthTicket.link(OauthProvider.GOOGLE, "google-1", "a@b.com");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(true);
        OauthEmailVerificationService service = newService();

        // when & then
        assertThatThrownBy(() -> service.sendCode(TOKEN, ticket, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_COOLDOWN);

        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-OAU-94] INPUT 티켓의 쿨다운은 이메일이 아니라 티켓 단위다 — 60초 안에 다른 이메일로 "
            + "재발송해도 여전히 429다(티켓 하나를 대량 발송 통로로 쓰는 것을 막는다)")
    void sendCode_inputTicketCoolingDown_blocksEvenWithDifferentBodyEmail() {
        // given
        OauthTicket ticket = OauthTicket.input(OauthProvider.KAKAO, "kakao-3");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(true);
        OauthEmailVerificationService service = newService();

        // when & then: 첫 발송과 완전히 다른 주소를 적어도 쿨다운은 그대로 걸린다
        assertThatThrownBy(() -> service.sendCode(TOKEN, ticket, "second-different-address@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_COOLDOWN);

        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    // ---------- verifyAndConsume(): 실패 갈래 (USER-OAU-76, 77, 78) ----------

    @Test
    @DisplayName("[USER-OAU-76] 코드가 저장돼 있지 않으면(미발송·만료·이미 소비) 400 EXPIRED_VERIFICATION_CODE를 던진다")
    void verifyAndConsume_noStoredCode_throwsExpired() {
        // given
        given(ticketStore.findCode(TOKEN)).willReturn(Optional.empty());
        OauthEmailVerificationService service = newService();

        // when & then
        assertThatThrownBy(() -> service.verifyAndConsume(TOKEN, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_VERIFICATION_CODE);
    }

    @Test
    @DisplayName("코드는 있는데 대상 주소가 없으면(비정상 상태) 동일하게 EXPIRED_VERIFICATION_CODE로 흡수한다")
    void verifyAndConsume_codeWithoutTargetEmail_throwsExpired() {
        // given
        given(ticketStore.findCode(TOKEN)).willReturn(Optional.of("123456"));
        given(ticketStore.findTargetEmail(TOKEN)).willReturn(Optional.empty());
        OauthEmailVerificationService service = newService();

        // when & then
        assertThatThrownBy(() -> service.verifyAndConsume(TOKEN, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_VERIFICATION_CODE);
    }

    @Test
    @DisplayName("[USER-OAU-78] 누적 시도가 이미 한도(EmailVerificationService.MAX_ATTEMPTS)에 도달했으면 "
            + "정답이어도 차단하고 코드를 무효화한다")
    void verifyAndConsume_attemptsAtLimit_throwsExceededAndInvalidatesEvenWithCorrectCode() {
        // given
        given(ticketStore.findCode(TOKEN)).willReturn(Optional.of("123456"));
        given(ticketStore.findTargetEmail(TOKEN)).willReturn(Optional.of("a@b.com"));
        given(ticketStore.getAttempts(TOKEN)).willReturn(EmailVerificationService.MAX_ATTEMPTS);
        OauthEmailVerificationService service = newService();

        // when & then: 정답을 제출해도 차단된다
        assertThatThrownBy(() -> service.verifyAndConsume(TOKEN, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);

        verify(ticketStore).invalidateCode(TOKEN);
        verify(ticketStore, never()).consume(TOKEN);
    }

    @Test
    @DisplayName("[USER-OAU-77] 인증번호가 불일치하면 400 INVALID_VERIFICATION_CODE를 던지고 시도 카운터만 "
            + "증가시킨다(티켓은 소비하지 않아 재시도할 수 있다)")
    void verifyAndConsume_wrongCode_throwsInvalidAndIncrementsAttemptsOnly() {
        // given
        given(ticketStore.findCode(TOKEN)).willReturn(Optional.of("123456"));
        given(ticketStore.findTargetEmail(TOKEN)).willReturn(Optional.of("a@b.com"));
        given(ticketStore.getAttempts(TOKEN)).willReturn(1);
        OauthEmailVerificationService service = newService();

        // when & then
        assertThatThrownBy(() -> service.verifyAndConsume(TOKEN, "999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(ticketStore).incrementAttempts(TOKEN);
        verify(ticketStore, never()).consume(TOKEN);
    }

    // ---------- verifyAndConsume(): 성공 (USER-OAU-70, 71, 75, 93) ----------

    @Test
    @DisplayName("[USER-OAU-75] 인증번호가 일치하면 티켓을 소비하고, 저장된(=가장 최근 발송된) 대상 주소를 "
            + "돌려준다")
    void verifyAndConsume_correctCode_consumesTicketAndReturnsStoredTarget() {
        // given
        given(ticketStore.findCode(TOKEN)).willReturn(Optional.of("123456"));
        given(ticketStore.findTargetEmail(TOKEN)).willReturn(Optional.of("latest@example.com"));
        given(ticketStore.getAttempts(TOKEN)).willReturn(0);
        OauthEmailVerificationService service = newService();

        // when
        String provenEmail = service.verifyAndConsume(TOKEN, "123456");

        // then
        assertThat(provenEmail).isEqualTo("latest@example.com");
        verify(ticketStore).consume(TOKEN);
    }

    @Test
    @DisplayName("발송 코드는 자체 이메일 인증과 같은 형식(6자리 숫자 문자열)이다")
    void sendCode_generatesSixDigitNumericCode() {
        // given
        OauthTicket ticket = OauthTicket.link(OauthProvider.GOOGLE, "g-1", "a@b.com");
        given(ticketStore.isCoolingDown(TOKEN)).willReturn(false);
        OauthEmailVerificationService service = newService();

        // when
        service.sendCode(TOKEN, ticket, null);

        // then
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendVerificationCode(eq("a@b.com"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }
}
