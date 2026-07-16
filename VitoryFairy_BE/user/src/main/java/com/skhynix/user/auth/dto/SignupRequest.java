package com.skhynix.user.auth.dto;

import com.skhynix.domain.user.entity.Gender;
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

        @NotBlank
        @Size(max = 100)
        String nickname,

        // 비밀번호 정책은 PasswordPolicy가 단일 출처다. 사전 검사 API와 어긋나지 않도록 판정 자체를
        // PasswordPolicy.findViolation()에 위임하는 @ValidPassword 하나만 건다. @NotBlank·@Size·@Pattern을
        // 겹쳐 걸면 동시 위반 시 메시지가 비결정적으로 뽑히므로 추가하지 말 것(ValidPassword Javadoc 참고).
        @ValidPassword
        String password
) {
}
