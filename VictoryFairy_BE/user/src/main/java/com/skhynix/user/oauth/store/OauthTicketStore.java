package com.skhynix.user.oauth.store;

import java.util.Optional;

/**
 * 티켓과 그 티켓에 결부된 인증번호 상태의 저장소.
 *
 * <p>메서드 모양은 {@code EmailVerificationStore} 를 따르되 <b>키가 이메일이 아니라 티켓</b>이다.
 * 이것이 이 인터페이스의 핵심이고, 회원가입용 전역 인증완료 상태({@code email:verify:verified:*})를
 * 만드는 메서드가 여기 없는 이유이기도 하다 — 소셜 인증 결과가 자체 가입 경로의 상태와 섞이면
 * "소셜 로그인 한 번으로 그 이메일의 자체 가입 선행조건이 충족되는" 문이 열린다.
 *
 * <p>쿨다운도 이메일이 아니라 티켓 단위다. 그래야 티켓 하나로 여러 주소에 연속 발송하는 것을 막는다
 * (이메일 단위였다면 주소만 바꿔 가며 60초 제한을 통과할 수 있다).
 */
public interface OauthTicketStore {

    /** @return 발급된 티켓 문자열(추측 불가한 난수). 유효기간은 구현이 정한다(10분). */
    String issue(OauthTicket ticket);

    Optional<OauthTicket> find(String ticket);

    /** 티켓과 거기 딸린 인증번호 상태를 모두 지운다(1회용 소비). */
    void consume(String ticket);

    /**
     * 인증번호와 <b>이번에 발송한 주소</b>를 함께 저장한다. 둘을 한 번에 쓰는 이유는 입력 티켓의 인증
     * 대상이 "가장 최근 발송 요청의 이메일"이기 때문이다 — 따로 두면 오타 후 재발송했을 때 이전 주소로
     * 인증이 통과하는 창이 생긴다.
     */
    void saveCode(String ticket, String code, String targetEmail);

    Optional<String> findCode(String ticket);

    Optional<String> findTargetEmail(String ticket);

    /** 인증번호·시도 카운터·대상 주소를 함께 삭제한다. */
    void invalidateCode(String ticket);

    /** 1 증가시키고 증가 후 값을 반환한다. */
    int incrementAttempts(String ticket);

    int getAttempts(String ticket);

    void startCooldown(String ticket);

    boolean isCoolingDown(String ticket);
}
