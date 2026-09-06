package com.skhynix.user.oauth.dto;

import com.skhynix.user.auth.policy.ValidNickname;
import jakarta.validation.constraints.NotBlank;

/**
 * 소셜 가입 요청 — 받는 것은 티켓과 닉네임 <b>둘뿐</b>이다.
 *
 * <p>이메일은 티켓에 실린 값으로 고정된다. 본문에 {@code email} 을 실어도 무시되며, 그 규칙이 없으면
 * 남의 이메일로 계정을 만들 수 있다. 비밀번호도 받지 않는다(소셜 전용 계정은 잠긴 값으로 태어난다).
 *
 * <p>{@code name}·{@code tel}·{@code gender} 를 받지 않는 것은 3사가 주지 않아서가 아니라 <b>아무도
 * 읽지 않는 값을 위해 마찰을 만들지 않기로</b> 해서다 — 세 컬럼은 NULL 로 저장된다. ⚠ 자체 회원가입
 * ({@code SignupRequest}) 의 세 필드 검증은 <b>그대로 필수</b>다. 컬럼이 느슨해져 DB 가 더 이상 막지
 * 않으므로 그 애노테이션이 유일한 방어선이다.
 *
 * <p>닉네임 검증을 {@code @ValidNickname} 으로 자체 가입과 같은 제약에 맡기는 이유는 <b>위반 메시지가
 * 문자 그대로 같아야</b> 하기 때문이다(같은 값에 {@code /auth/nickname/validate} 가 돌려주는 문구와도
 * 같다). 서비스에서 따로 판정하면 두 벌이 되어 언젠가 갈라진다.
 */
public record OauthSignupRequest(@NotBlank String ticket, @ValidNickname String nickname) {
}
