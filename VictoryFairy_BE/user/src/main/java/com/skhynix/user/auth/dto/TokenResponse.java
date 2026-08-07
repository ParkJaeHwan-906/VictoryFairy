package com.skhynix.user.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
