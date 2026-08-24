package com.skhynix.user.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param redirectUri 인가코드를 받을 때 쓴 주소. ⚠ 검증 애노테이션을 붙이지 않은 것은 의도다 —
 *                    비어 있을 때도 "허용되지 않은 리다이렉트 주소"로 답해야 하는데, 여기에
 *                    {@code @NotBlank} 를 걸면 그 경우만 일반 검증 400 으로 갈라진다
 */
public record OauthLoginRequest(@NotBlank String code, String redirectUri) {
}
