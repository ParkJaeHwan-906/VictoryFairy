package com.skhynix.user.oauth.policy;

/**
 * 소셜로만 만들어진 계정의 예약값 — <b>단일 출처</b>다({@code UnknownAccountPolicy} 와 같은 구조).
 */
public final class SocialAccountPolicy {

    /**
     * 소셜 전용 계정의 {@code password}. <b>BCrypt 패턴이 아닌</b> placeholder 라
     * {@code BCryptPasswordEncoder.matches()} 가 어떤 원문에도 예외 없이 {@code false} 를 낸다
     * ({@code chat-init.sql} SYSTEM 계정·{@code UnknownAccountPolicy.LOCKED_PASSWORD} 선례).
     *
     * <p>그 성질이 두 계약을 동시에 만든다: 이 계정 이메일로 자체 로그인하면 401(미가입 이메일과 응답이
     * 같다), 비밀번호 변경은 현재 비밀번호를 무엇으로 적어도 400 이다.
     *
     * <p>⚠ 여기에 진짜 해시를 넣거나 랜덤 문자열을 BCrypt 로 인코딩해 넣지 말 것 — 그 순간 이 계정은
     * "아무도 모르는 비밀번호로 로그인 가능한 계정"이 되고, 무엇보다 위 두 계약이 조용히 깨진다.
     * 소셜 계정에 비밀번호를 부여하는 기능(비밀번호 설정)은 이번 범위 밖이다.
     */
    public static final String LOCKED_PASSWORD = "SOCIAL-NO-LOGIN";

    private SocialAccountPolicy() {
    }
}
