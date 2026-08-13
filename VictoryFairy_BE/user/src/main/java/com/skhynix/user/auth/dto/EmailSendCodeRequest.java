package com.skhynix.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailSendCodeRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email
) {
}
