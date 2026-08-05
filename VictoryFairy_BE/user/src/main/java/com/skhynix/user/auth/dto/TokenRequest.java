package com.skhynix.user.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(

        @NotBlank
        String refreshToken
) {
}
