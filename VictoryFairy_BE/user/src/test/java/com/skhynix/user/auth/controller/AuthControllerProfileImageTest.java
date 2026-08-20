package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.service.TempProfileImageService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /auth/profile-image}(가입 전 임시 업로드) — 이 저장소에서 인증 없이 쓰기가 되는 유일한
 * 경로. 요구사항: {@code docs/requirements/user/profile-image.md} USER-PI-1~34, 100, 102.
 *
 * <p>슬라이스 구성은 {@code AuthControllerSignupTest}와 동일한 패턴(컨텍스트 자동 병합 우회 +
 * {@code SecurityConfig} 실제 임포트)을 따른다 — 이 경로가 정말로 {@code /auth/**}의 기존 permitAll에
 * 자연히 걸리는지(SecurityConfig 무수정)를 서비스 목이 아니라 필터 레벨에서 확인하는 것이 핵심이다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerProfileImageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private TempProfileImageService tempProfileImageService;

    private MockMultipartFile imagePart() {
        return new MockMultipartFile("image", "a.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("[USER-PI-21, 100] Authorization 헤더 없이 호출해도 200이다"
            + "(/auth/** permitAll에 자연히 걸린다 — SecurityConfig 무수정)")
    void uploadTempProfileImage_noAuthorizationHeader_returns200() throws Exception {
        // given
        given(tempProfileImageService.upload(eq("app-1"), any()))
                .willReturn(new ProfileImageResponse("temp/generated.png"));

        // when & then
        mockMvc.perform(multipart("/auth/profile-image")
                        .file(imagePart())
                        .param("appId", "app-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImgUrl").value("temp/generated.png"));
    }

    @Test
    @DisplayName("[USER-PI-22] 유효한 access 토큰이 함께 와도 동작이 달라지지 않는다"
            + "(계정 컬럼 갱신 없음 — 이 컨트롤러 슬라이스 관점에서는 여전히 같은 서비스 호출로만 보인다)")
    void uploadTempProfileImage_withValidToken_stillDelegatesToTempService() throws Exception {
        // given
        given(tempProfileImageService.upload(eq("app-1"), any()))
                .willReturn(new ProfileImageResponse("temp/generated.png"));

        // when & then: 토큰 유무와 무관하게 같은 임시 서비스가 호출된다(별도 인증 분기 없음)
        mockMvc.perform(multipart("/auth/profile-image")
                        .file(imagePart())
                        .param("appId", "app-1")
                        .header("Authorization", "Bearer some-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImgUrl").value("temp/generated.png"));

        verify(tempProfileImageService).upload(eq("app-1"), any());
    }

    @Test
    @DisplayName("[USER-PI-2] 이미지 파트 이름이 image가 아니라 file이면 400 PROFILE_IMAGE_REQUIRED다"
            + "(required=false로 받아 컨트롤러가 서비스에 null을 넘기고 서비스가 400으로 판정)")
    void uploadTempProfileImage_wrongPartName_returns400() throws Exception {
        MockMultipartFile wrongPartName = new MockMultipartFile("file", "a.png", "image/png",
                new byte[]{1, 2, 3});
        given(tempProfileImageService.upload(eq("app-1"), org.mockito.ArgumentMatchers.isNull()))
                .willThrow(new BusinessException(ErrorCode.PROFILE_IMAGE_REQUIRED));

        mockMvc.perform(multipart("/auth/profile-image")
                        .file(wrongPartName)
                        .param("appId", "app-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PROFILE_IMAGE_REQUIRED.getMessage()));
    }

    @Test
    @DisplayName("[USER-PI-23] appId 파트가 없으면 400 INVALID_APP_ID다")
    void uploadTempProfileImage_missingAppId_returns400() throws Exception {
        given(tempProfileImageService.upload(org.mockito.ArgumentMatchers.isNull(), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_APP_ID));

        mockMvc.perform(multipart("/auth/profile-image").file(imagePart()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_APP_ID.getMessage()));
    }

    @Test
    @DisplayName("[USER-PI-27] 서비스가 429 PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED를 던지면 그대로 429가 나간다")
    void uploadTempProfileImage_limitExceeded_returns429() throws Exception {
        given(tempProfileImageService.upload(eq("app-1"), any()))
                .willThrow(new BusinessException(ErrorCode.PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED));

        mockMvc.perform(multipart("/auth/profile-image")
                        .file(imagePart())
                        .param("appId", "app-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED.getMessage()));
    }

    @Test
    @DisplayName("[USER-PI-1] JSON 본문으로 호출하면(multipart가 아님) 415가 난다")
    void uploadTempProfileImage_jsonBody_returns415() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/auth/profile-image")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
