package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link AccountProfileImageService} — 인증 업로드의 저장→컬럼 교체→직전 객체 삭제 순서 계약.
 * 요구사항: {@code docs/requirements/user/profile-image.md} USER-PI-10~12, 70~73.
 */
@ExtendWith(MockitoExtension.class)
class AccountProfileImageServiceTest {

    @Mock
    private ProfileImageUploader uploader;

    @Mock
    private AccountProfileImageWriter writer;

    @Mock
    private ProfileImageEraser eraser;

    @InjectMocks
    private AccountProfileImageService service;

    private MockMultipartFile file() {
        return new MockMultipartFile("image", "a.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("[USER-PI-10] 저장은 항상 user-profile-img/ 접두로 이뤄진다(temp/를 경유하지 않는다)")
    void upload_storesUnderPermanentPrefix_neverTemp() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn(null);

        service.upload(1L, file());

        verify(uploader).upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX));
    }

    @Test
    @DisplayName("[USER-PI-11, 12] 저장이 성공하면 그 EP로 컬럼을 교체하고 같은 EP를 응답으로 돌려준다")
    void upload_success_replacesColumnAndReturnsNewEndpointInResponse() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn(null);

        ProfileImageResponse response = service.upload(1L, file());

        assertThat(response.profileImgUrl()).isEqualTo("user-profile-img/new.png");
        verify(writer).replace(1L, "user-profile-img/new.png");
    }

    @Test
    @DisplayName("[USER-PI-70] 직전 EP가 있던 계정이면 writer.replace가 돌려준 직전 값으로 eraser를 부른다")
    void upload_previousImageExisted_erasesPreviousEndpoint() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn("user-profile-img/old.png");

        service.upload(1L, file());

        verify(eraser).eraseQuietly("user-profile-img/old.png");
    }

    @Test
    @DisplayName("[USER-PI-71] 직전 값이 null이면(첫 업로드) eraser에 null을 그대로 넘긴다"
            + "(ProfileImageEraser가 null을 아무 일도 하지 않음으로 흡수한다는 계약에 위임)")
    void upload_noPreviousImage_passesNullToEraser() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn(null);

        service.upload(1L, file());

        verify(eraser).eraseQuietly(null);
    }

    @Test
    @DisplayName("순서는 ①저장 → ②컬럼 교체 → ③직전 객체 삭제다 — 순서가 뒤집히면 컬럼 갱신 실패 시 "
            + "계정이 여전히 가리키는 객체를 지운 상태가 될 수 있다")
    void upload_followsStoreThenReplaceThenEraseOrder() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn("user-profile-img/old.png");

        service.upload(1L, file());

        InOrder inOrder = inOrder(uploader, writer, eraser);
        inOrder.verify(uploader).upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX));
        inOrder.verify(writer).replace(1L, "user-profile-img/new.png");
        inOrder.verify(eraser).eraseQuietly("user-profile-img/old.png");
    }

    @Test
    @DisplayName("[USER-PI-72] 직전 객체 삭제(eraser)가 내부에서 실패로 처리돼도(예외를 던지지 않는 "
            + "eraseQuietly 계약) 업로드 응답은 그대로 새 EP다")
    void upload_eraseQuietlyReturnsFalse_stillReturnsSuccessResponse() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willReturn("user-profile-img/new.png");
        given(writer.replace(1L, "user-profile-img/new.png")).willReturn("user-profile-img/old.png");
        given(eraser.eraseQuietly("user-profile-img/old.png")).willReturn(false);

        ProfileImageResponse response = service.upload(1L, file());

        assertThat(response.profileImgUrl()).isEqualTo("user-profile-img/new.png");
    }

    @Test
    @DisplayName("[USER-PI-7] 저장(uploader.upload) 자체가 실패하면 컬럼 교체(writer)·직전 객체 삭제"
            + "(eraser) 어느 것도 시도되지 않는다 — 저장에 실패한 이미지가 프로필이 되는 일은 없다")
    void upload_storeFails_neverTouchesWriterOrEraser() {
        given(uploader.upload(any(), eq(ProfileImagePolicy.PERMANENT_PREFIX)))
                .willThrow(new RuntimeException("S3 down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(1L, file()))
                .isInstanceOf(RuntimeException.class);

        org.mockito.Mockito.verifyNoInteractions(writer);
        org.mockito.Mockito.verifyNoInteractions(eraser);
    }
}
