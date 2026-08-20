package com.skhynix.user.oauth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.auth.email.EmailSender;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import com.skhynix.user.oauth.store.OauthTicketType;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소셜 인증 티켓에 결부된 인증번호 절차.
 *
 * <p>기존 {@code EmailVerificationService} 를 재사용하지 못하는 이유는 정책이 달라서가 아니라
 * <b>대상이 다르기</b> 때문이다. 그쪽은 "아직 계정이 없어야 하는 이메일"을 다뤄 이미 가입된 주소에
 * 409 를 주고, 여기서 인증하는 주소는 <b>계정이 이미 있을 수도 있는</b> 이메일이다(링크 티켓은 항상
 * 그렇다). 판정 정책(60초 쿨다운·5회 시도·1회용 소비)은 그대로 따르되 두 가지가 다르다:
 *
 * <ul>
 *   <li>키가 이메일이 아니라 <b>티켓</b>이다 — 결과가 회원가입용 전역 인증완료 상태를 만들지 않는다.</li>
 *   <li>발송 응답이 <b>계정 존재 여부로 갈리지 않는다</b> — 여기서 409 를 주면 아무나 주소만 적어
 *       가입 이력을 캐낼 수 있는 계정 열거기가 된다.</li>
 * </ul>
 *
 * <p>사용자가 남의 이메일을 적는 것 자체는 막지 않는다. 막히는 지점은 <b>그 주소의 메일함을 못 여는
 * 것</b>이며, 그래서 시도 한도와 발송 쿨다운이 이 경로에도 예외 없이 적용된다.
 */
@Service
@RequiredArgsConstructor
public class OauthEmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OauthTicketStore ticketStore;
    private final EmailSender emailSender;

    /**
     * @param bodyEmail 입력 티켓에서만 쓰인다. ⚠ 링크 티켓에서 이 값을 받아들이면 남의 계정 이메일로
     *                  인증번호를 보내는 우회로가 열려 링크 티켓 보호가 통째로 무너진다
     */
    public void sendCode(String ticketToken, OauthTicket ticket, String bodyEmail) {
        String target = resolveTarget(ticket, bodyEmail);

        // 쿨다운은 이메일이 아니라 티켓 단위다 — 주소만 바꿔 가며 연속 발송하는 것을 막는다.
        if (ticketStore.isCoolingDown(ticketToken)) {
            throw new BusinessException(ErrorCode.EMAIL_SEND_COOLDOWN);
        }

        String code = generateCode();
        ticketStore.invalidateCode(ticketToken); // 재발송: 이전 코드·시도·대상 주소 무효화
        ticketStore.saveCode(ticketToken, code, target);
        ticketStore.startCooldown(ticketToken);

        // 계정 존재 여부를 보지 않는다(위 javadoc 참고).
        emailSender.sendVerificationCode(target, code);
    }

    /**
     * 인증번호 대조 후 성공하면 티켓을 소비하고 <b>소유가 증명된 이메일</b>을 돌려준다.
     *
     * <p>실패는 티켓을 태우지 않는다 — 오타 한 번에 소셜 로그인부터 다시 하게 만들지 않기 위해서다.
     *
     * @return 인증을 통과한 주소. 입력 티켓이면 가장 최근 발송 요청의 주소다
     */
    public String verifyAndConsume(String ticketToken, String code) {
        String stored = ticketStore.findCode(ticketToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPIRED_VERIFICATION_CODE));
        // 대상 주소는 코드와 같은 TTL 로 함께 저장·삭제되므로 코드가 있는데 이것만 없을 수는 없다.
        String target = ticketStore.findTargetEmail(ticketToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPIRED_VERIFICATION_CODE));

        // 직전까지 누적된 실패가 한도에 도달했으면 정답이라도 차단하고 코드를 무효화한다.
        if (ticketStore.getAttempts(ticketToken) >= EmailVerificationService.MAX_ATTEMPTS) {
            ticketStore.invalidateCode(ticketToken);
            throw new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }

        if (!stored.equals(code)) {
            // 실패 시 카운터만 올린다 — 5회까지는 INVALID, 6번째 시도부터 EXCEEDED 다(자체 인증과 동일).
            ticketStore.incrementAttempts(ticketToken);
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        ticketStore.consume(ticketToken);
        return target;
    }

    private String resolveTarget(OauthTicket ticket, String bodyEmail) {
        if (ticket.type() == OauthTicketType.LINK) {
            return ticket.email();
        }
        if (bodyEmail == null || bodyEmail.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        return bodyEmail;
    }

    // 자릿수·형식은 자체 이메일 인증과 같아야 한다(사용자가 보는 메일이 같은 템플릿이다).
    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
