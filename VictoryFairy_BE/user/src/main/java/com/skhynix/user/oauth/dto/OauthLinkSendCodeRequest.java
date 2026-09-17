package com.skhynix.user.oauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param email 입력 티켓에서만 쓰인다. ⚠ 링크 티켓 요청에는 이 값이 없어야 정상이고, 실려 와도
 *              <b>무시된다</b> — 받아들이면 남의 계정 이메일로 인증번호를 보내는 우회로가 열린다.
 *              그래서 {@code @NotBlank} 가 아니라 형식 검사만 건다(링크 티켓은 안 보낸다)
 */
public record OauthLinkSendCodeRequest(@NotBlank String ticket, @Email String email) {
}
