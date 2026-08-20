package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ProfileImageEraser} — 이미지 변경·탈퇴 두 경로가 공유하는 best-effort 삭제. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-71~77, 79.
 */
@ExtendWith(MockitoExtension.class)
class ProfileImageEraserTest {

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private ProfileImageEraser eraser;

    @Test
    @DisplayName("[USER-PI-71] endpoint가 null이면 삭제를 시도하지 않고 false를 돌려준다")
    void eraseQuietly_nullEndpoint_doesNothingAndReturnsFalse() {
        boolean erased = eraser.eraseQuietly(null);

        assertThat(erased).isFalse();
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("[USER-PI-77] 영구 경로(user-profile-img/) 접두가 아닌 값(예: temp/ 또는 다른 값)은 "
            + "DeleteObject를 호출하지 않는다 — 잘못된 값 하나로 남의 객체를 지우지 못하게 하는 안전장치")
    void eraseQuietly_nonPermanentPrefix_doesNotCallDelete() {
        boolean erased = eraser.eraseQuietly("temp/whatever.jpg");

        assertThat(erased).isFalse();
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("영구 경로 EP면 storage.delete를 호출하고 성공 시 true를 돌려준다")
    void eraseQuietly_permanentEndpoint_deletesAndReturnsTrue() {
        boolean erased = eraser.eraseQuietly("user-profile-img/x.png");

        assertThat(erased).isTrue();
        verify(storage).delete("user-profile-img/x.png");
    }

    @Test
    @DisplayName("[USER-PI-72, 73, 76, 79] 삭제가 실패해도(RuntimeException) 예외를 밖으로 던지지 않고 "
            + "false만 돌려준다 — 호출자(업로드·탈퇴)가 그 실패로 실패하면 안 된다")
    void eraseQuietly_deleteThrows_doesNotPropagateException_returnsFalse() {
        willThrow(new RuntimeException("S3 down")).given(storage).delete("user-profile-img/x.png");

        boolean erased = eraser.eraseQuietly("user-profile-img/x.png");

        assertThat(erased).isFalse();
    }
}
