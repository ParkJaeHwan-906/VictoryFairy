package com.skhynix.user.oauth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.oauth.client.OauthClient;
import com.skhynix.user.oauth.client.OauthClientRegistry;
import com.skhynix.user.oauth.client.OauthUserInfo;
import com.skhynix.user.oauth.dto.OauthAuthResponse;
import com.skhynix.user.oauth.dto.OauthLinkSendCodeRequest;
import com.skhynix.user.oauth.dto.OauthLinkVerifyRequest;
import com.skhynix.user.oauth.dto.OauthLoginRequest;
import com.skhynix.user.oauth.dto.OauthSignupRequest;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import com.skhynix.user.oauth.store.OauthTicketType;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소셜 로그인 4개 경로의 지휘자 — provider 호출·티켓 수명·판정 위임의 순서만 정한다.
 *
 * <p><b>트랜잭션이 없는 것이 의도다.</b> 이 클래스의 첫 일이 외부 HTTP 호출이라, 여기에 트랜잭션을
 * 걸면 DB 커넥션이 provider 응답을 기다리는 동안 묶인다. DB 쓰기는 전부
 * {@link OauthIdentityResolver}·{@link OauthAccountWriter} 안에서 각자의 트랜잭션으로 일어난다.
 */
@Service
@RequiredArgsConstructor
public class OauthAuthService {

    private final OauthClientRegistry clientRegistry;
    private final OauthTicketStore ticketStore;
    private final OauthIdentityResolver identityResolver;
    private final OauthEmailVerificationService emailVerificationService;
    private final OauthAccountWriter accountWriter;

    public OauthAuthResponse authenticate(String provider, OauthLoginRequest request) {
        OauthClient client = clientRegistry.get(provider);

        // ⚠ provider 호출보다 반드시 앞이다. 검증 없이 넘기면 인가코드를 임의 주소로 빼돌리는 경로가
        //   열린다. 서버 고정값으로 대체할 수 없어(웹과 앱이 서로 다른 값을 쓴다) 본문으로 받되
        //   허용 목록과 문자 그대로 대조한다.
        if (!client.allowsRedirectUri(request.redirectUri())) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_REDIRECT_URI);
        }

        OauthUserInfo userInfo = client.authenticate(request.code(), request.redirectUri());
        return identityResolver.resolve(client.provider(), userInfo);
    }

    public void sendLinkCode(OauthLinkSendCodeRequest request) {
        OauthTicket ticket = requireTicket(request.ticket(),
                Set.of(OauthTicketType.LINK, OauthTicketType.INPUT));
        emailVerificationService.sendCode(request.ticket(), ticket, request.email());
    }

    public OauthAuthResponse verifyLinkCode(OauthLinkVerifyRequest request) {
        OauthTicket ticket = requireTicket(request.ticket(),
                Set.of(OauthTicketType.LINK, OauthTicketType.INPUT));

        // 티켓은 여기서 소비된다(인증 성공 = 소비). 아래 해석이 409 로 끝나도 되돌리지 않는다 —
        // 그 409 는 되돌려서 달라질 것이 없는 종착점이다.
        String provenEmail = emailVerificationService.verifyAndConsume(request.ticket(), request.code());

        return identityResolver.resolveWithProvenEmail(ticket.provider(), ticket.providerUserId(),
                provenEmail);
    }

    public TokenResponse signup(OauthSignupRequest request) {
        OauthTicket ticket = requireTicket(request.ticket(), Set.of(OauthTicketType.SIGNUP));

        TokenResponse tokens = accountWriter.createAccount(ticket, request.nickname());

        // 소비는 계정이 실제로 만들어진 뒤다. 앞에 두면 닉네임 중복으로 거절된 요청이 티켓을 태워
        // 사용자가 다른 닉네임으로 재시도할 길을 잃는다(그때 남는 길은 소셜 로그인부터 다시뿐이다).
        ticketStore.consume(request.ticket());
        return tokens;
    }

    /**
     * 티켓 조회 + 종류 대조. 셋을 한 키 공간에 두고 여기서 가르므로, 다른 단계의 티켓을 들고 오면
     * 만료와 같은 응답을 받는다(어느 단계의 티켓이 살아 있는지 탐색으로 알아낼 수 없다).
     */
    private OauthTicket requireTicket(String token, Set<OauthTicketType> expected) {
        OauthTicket ticket = ticketStore.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_OAUTH_TICKET));
        if (!expected.contains(ticket.type())) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TICKET);
        }
        return ticket;
    }
}
