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
        String password,

        /*
         * 선택 입력. 가입 전에 POST /api/auth/profile-image 로 올려 받은 temp/ EP 를 그대로 싣는다.
         * 생략·null 이면 이미지 없는 계정이 되고, 기존 가입 클라이언트는 손대지 않아도 그대로 동작한다.
         *
         * 검증 애노테이션을 걸지 않은 것은 빠뜨린 것이 아니다. 접두·형태·길이(255)·객체 존재를 한
         * 주체(ProfileImagePolicy + SignupProfileImageService)가 판정해 모두 같은
         * INVALID_PROFILE_IMAGE_ENDPOINT 로 응답한다 — @Size 를 함께 걸면 같은 사유가 필드 메시지
         * 형식의 400 과 코드 형식의 400 두 갈래로 나뉘고, 다른 필드와 동시에 위반될 때 응답 메시지가
         * 비결정적이 된다(GlobalExceptionHandler 가 Map 에 put 하는 구조).
         */
        String profileImgUrl
) {
}
