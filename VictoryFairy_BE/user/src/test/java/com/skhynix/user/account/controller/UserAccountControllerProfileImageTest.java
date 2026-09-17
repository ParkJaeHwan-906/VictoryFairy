package com.skhynix.user.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.service.AccountProfileImageService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
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
 * {@code POST /users/me/profile-image}(인증 업로드) — 업로드가 곧 변경 확정이다. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-10~17, 101.
 *
 * <p>슬라이스 구성은 {@code UserAccountControllerTest}(탈퇴)와 동일한 패턴이다. 이 슬라이스가 실제
 * {@link SecurityConfig}를 태우므로, "이 경로에 permitAll 줄을 추가하지 않아도 인증 필수 규칙
 * ({@code anyRequest().authenticated()})에 자연히 걸린다"(USER-PI-101)는 계약을 필터 레벨에서
 * 검증한다.
 */
@WebMvcTest(UserAccountController.class)
@ContextConfiguration(classes = UserAccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAccountControllerProfileImageTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAccountService userAccountService;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private UserProfileEditService userProfileEditService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @MockitoBean
    private AccountProfileImageService accountProfileImageService;

    private String stubValidAccessToken(String uid) {
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        return token;
    }

    private MockMultipartFile imagePart() {
        return new MockMultipartFile("image", "a.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("[USER-PI-11, 12, 15] 유효한 access 토큰으로 업로드하면 200과 새 EP를 반환하고, "
            + "토큰 subject가 해석된 내부 id로 서비스가 호출된다(경로·본문 어디에도 계정 식별자가 없다)")
    void uploadProfileImage_validAccessToken_returns200WithNewEndpoint() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(accountProfileImageService.upload(eq(accountId), any()))
                .willReturn(new ProfileImageResponse("user-profile-img/new.png"));

        // when & then
        mockMvc.perform(multipart("/users/me/profile-image")
                        .file(imagePart())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImgUrl").value("user-profile-img/new.png"));

        verify(accountProfileImageService).upload(eq(accountId), any());
    }

    @Test
    @DisplayName("[USER-PI-13, 101] Authorization 헤더 없이 호출하면 401을 반환하고 서비스는 호출되지 않는다"
            + "(SecurityConfig에 permitAll 줄을 추가하지 않아도 anyRequest().authenticated()에 자연히 걸린다)")
    void uploadProfileImage_noAuthorizationHeader_returns401AndDoesNotCallService() throws Exception {
        mockMvc.perform(multipart("/users/me/profile-image").file(imagePart()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(accountProfileImageService);
    }

    @Test
    @DisplayName("[USER-PI-14] refresh 토큰으로 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void uploadProfileImage_refreshToken_returns401AndDoesNotCallService() throws Exception {
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        mockMvc.perform(multipart("/users/me/profile-image")
                        .file(imagePart())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(accountProfileImageService);
    }

    @Test
    @DisplayName("[USER-PI-14] 탈퇴한 계정의 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 호출하면 "
            + "401을 반환하고 서비스는 호출되지 않는다")
    void uploadProfileImage_withdrawnAccountToken_returns401AndDoesNotCallService() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        mockMvc.perform(multipart("/users/me/profile-image")
                        .file(imagePart())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(accountProfileImageService);
    }
}
