package com.skhynix.user.oauth.dto;

import jakarta.validation.constraints.NotBlank;

public record OauthLinkVerifyRequest(@NotBlank String ticket, @NotBlank String code) {
}
