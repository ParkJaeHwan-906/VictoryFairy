package com.skhynix.user.auth.dto;

import java.util.List;

/**
 * {@code SOCIAL_ACCOUNT_ONLY} 409 에 실리는 안내 데이터 — 사용자가 실제로 누를 수 있는 버튼이 무엇인지
 * 알려 준다. 문구만으로는 "소셜로 가입했다"까지만 전해지고 <b>어느</b> 소셜인지는 여전히 사용자가
 * 세 개를 다 눌러 봐야 알 수 있다.
 *
 * <p>provider 이름을 노출하는 것이 여기서만 허용되는 이유는, 이 응답을 받았다는 사실 자체가 이미
 * "그 이메일은 가입돼 있다"를 말하고 있기 때문이다(자체 가입 발송은 원래부터 409 로 가입 사실을
 * 알려 왔다). 새로 새는 정보는 provider 이름뿐이다.
 *
 * @param providers 경로 변수와 같은 소문자({@code kakao}·{@code naver}·{@code google})
 */
public record SocialAccountHintResponse(List<String> providers) {
}
