package com.skhynix.user.oauth.dto;

/**
 * 소셜 인증 응답이 클라이언트에게 "다음에 무엇을 해야 하는지" 알려 주는 값.
 *
 * <p>⚠ {@link #EMAIL_INPUT_REQUIRED}·{@link #EMAIL_VERIFICATION_REQUIRED}·{@link #SIGNUP_REQUIRED}
 * 는 <b>전부 200 이다.</b> 정상 흐름의 한 단계를 4xx 로 표현하면 프론트의 공통 인터셉터가 그것을
 * 오류로 삼켜, 사용자에게는 "로그인 실패"만 보이고 다음 단계 화면이 영영 안 열린다.
 */
public enum OauthAuthStatus {

    /** 토큰 쌍이 실려 있다. 더 할 일이 없다. */
    LOGIN,

    /** 이메일이 확정됐고 그 이메일의 계정이 없다 — 닉네임을 받아 소셜 가입으로 간다. */
    SIGNUP_REQUIRED,

    /** 기존 계정과 합쳐지는 갈래인데 어느 한쪽 이메일이 미검증이다 — 인증번호로 소유를 증명해야 한다. */
    EMAIL_VERIFICATION_REQUIRED,

    /** provider 가 쓸 수 있는 이메일을 주지 않았다 — 사용자가 직접 적고 인증번호로 증명한다. */
    EMAIL_INPUT_REQUIRED
}
