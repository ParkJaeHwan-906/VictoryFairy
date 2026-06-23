package com.skhynix.user.auth.dto;

import com.skhynix.domain.user.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(

        @NotBlank
        @Size(max = 30)
        String name,

        @NotBlank
        @Pattern(regexp = "\\d{10,11}", message = "전화번호는 숫자 10~11자리여야 합니다.")
        String tel,

        @NotBlank
        @Email
        @Size(max = 100)
        String email,

        @NotNull
        Gender gender,

        @NotBlank
        @Size(max = 100)
        String nickname,

        @NotBlank
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다.")
        String password
) {
}
