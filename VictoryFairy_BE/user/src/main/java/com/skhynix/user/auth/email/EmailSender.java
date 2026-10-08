package com.skhynix.user.auth.email;

/**
 * 이메일 발송 포트.
 *
 * <p>메서드가 둘인 이유는 {@code send-code} 가 <b>응답이 아니라 메일 본문에서</b> 갈리기 때문이다
 * (USER-EMV-19·21·26). 가입 여부는 200 응답으로는 구분되지 않고, 그 주소의 메일함 주인만 어느
 * 갈래인지 알 수 있다 — 갈래를 표현하는 자리가 곧 이 포트다.
 */
public interface EmailSender {

    /** 갈래 1(미가입) — 인증번호를 담은 메일. */
    void sendVerificationCode(String email, String code);

    /**
     * 갈래 2(가입 이력 있음) — "이미 사용 중인 이메일"임을 알리는 메일(USER-EMV-21).
     *
     * <p>⚠ 인증번호를 <b>인자로도 받지 않는다</b>. 갈래 2도 코드를 저장하지만(USER-EMV-22) 그 값이
     * 메일로 나가는 순간 남의 가입 이메일로 인증이 통과된다(USER-EMV-32) — 시그니처가 그 사고를
     * 애초에 불가능하게 막는다.
     */
    void sendAlreadyRegisteredNotice(String email);
}
