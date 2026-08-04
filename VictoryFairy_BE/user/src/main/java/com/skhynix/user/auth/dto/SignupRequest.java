package com.skhynix.user.auth.dto;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.user.auth.policy.ValidNickname;
import com.skhynix.user.auth.policy.ValidPassword;
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

        // NicknamePolicy가 단일 출처 — @NotBlank·@Size·@Pattern을 겹쳐 걸지 말 것(ValidNickname 참고).
        @ValidNickname
        String nickname,

        // PasswordPolicy가 단일 출처 — @NotBlank·@Size·@Pattern을 겹쳐 걸지 말 것(ValidPassword 참고).
        @ValidPassword
        String password
) {
}
