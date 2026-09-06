package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link ProfileImageUploader} — 두 업로드 경로가 공유하는 검증 + 저장. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-2, 5, 35~47.
 */
@ExtendWith(MockitoExtension.class)
class ProfileImageUploaderTest {

    private static final byte[] JPEG_SOI = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private ProfileImageUploader uploader;

    private static byte[] jpegBytes(int totalLength) {
        byte[] content = new byte[totalLength];
        System.arraycopy(JPEG_SOI, 0, content, 0, JPEG_SOI.length);
        return content;
    }

    private static byte[] pngBytes(int totalLength) {
        byte[] content = new byte[totalLength];
        System.arraycopy(PNG_SIGNATURE, 0, content, 0, PNG_SIGNATURE.length);
        return content;
    }

    // ---------- USER-PI-5: 파트 존재 ----------

    @Test
    @DisplayName("[USER-PI-5] 이미지 파트가 null이면 400 PROFILE_IMAGE_REQUIRED다")
    void validate_nullImage_throwsProfileImageRequired() {
        assertThatThrownBy(() -> uploader.validate(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_IMAGE_REQUIRED);
    }

    @Test
    @DisplayName("[USER-PI-5] 이미지 파트가 0바이트이면 400 PROFILE_IMAGE_REQUIRED다")
    void validate_emptyImage_throwsProfileImageRequired() {
        MockMultipartFile empty = new MockMultipartFile("image", "a.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> uploader.validate(empty))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_IMAGE_REQUIRED);
    }

    // ---------- USER-PI-38, 39: 크기 경계 ----------

    @Test
    @DisplayName("[USER-PI-38] 5MiB 정확히인 이미지는 크기 검증을 통과한다(경계값)")
    void validate_exactlyMaxSize_passesSizeCheck() {
        MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg",
                jpegBytes(ProfileImagePolicy.MAX_SIZE_BYTES));

        ProfileImageContent content = uploader.validate(file);

        assertThat(content.bytes()).hasSize(ProfileImagePolicy.MAX_SIZE_BYTES);
    }

    @Test
    @DisplayName("[USER-PI-38, 39] 5MiB + 1바이트인 이미지는 413 PROFILE_IMAGE_TOO_LARGE다")
    void validate_oneByteOverMaxSize_throwsProfileImageTooLarge() {
        MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg",
                jpegBytes(ProfileImagePolicy.MAX_SIZE_BYTES + 1));

        assertThatThrownBy(() -> uploader.validate(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("크기 초과 파일은 형식이 유효(JPEG 매직 넘버)해도 크기 검증이 먼저 걸린다"
            + "(검증 순서: 존재 → 크기 → 형식)")
    void validate_oversizedValidFormat_stillThrowsTooLargeNotFormatError() {
        MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg",
                jpegBytes(ProfileImagePolicy.MAX_SIZE_BYTES + 100));

        assertThatThrownBy(() -> uploader.validate(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_IMAGE_TOO_LARGE);
    }

    // ---------- USER-PI-35~37: 형식 판정 ----------

    @Test
    @DisplayName("[USER-PI-35] JPEG·PNG·WEBP 매직 넘버는 형식 판정을 통과한다")
    void validate_allowedFormats_pass() {
        MockMultipartFile jpeg = new MockMultipartFile("image", "a.jpg", "image/jpeg", jpegBytes(100));
        MockMultipartFile png = new MockMultipartFile("image", "a.png", "image/png", pngBytes(100));

        assertThat(uploader.validate(jpeg).format())
                .isEqualTo(com.skhynix.user.profileimage.policy.ProfileImageFormat.JPEG);
        assertThat(uploader.validate(png).format())
                .isEqualTo(com.skhynix.user.profileimage.policy.ProfileImageFormat.PNG);
    }

    @Test
    @DisplayName("[USER-PI-36, 37] 실행 파일을 a.png + image/png로 위장해도 선두 바이트가 아니라서 "
            + "400 INVALID_PROFILE_IMAGE_FORMAT이다 — 확장자·Content-Type은 판정 근거가 아니다")
    void validate_disguisedExecutable_throwsInvalidFormatRegardlessOfFilenameAndContentType() {
        byte[] elfMagic = {0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00};
        MockMultipartFile disguised = new MockMultipartFile("image", "a.png", "image/png", elfMagic);

        assertThatThrownBy(() -> uploader.validate(disguised))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT);
    }

    @Test
    @DisplayName("[USER-PI-44] JPEG 바이트를 x.png라는 이름으로 올려도 저장되는 EP의 확장자는 실제 판정된 "
            + "형식(.jpg)을 따른다 — 원본 파일명의 확장자를 그대로 쓰지 않는다")
    void upload_jpegBytesWithPngFilename_storesWithJpgExtensionNotPng() {
        MockMultipartFile mismatched = new MockMultipartFile("image", "x.png", "image/png", jpegBytes(50));

        String endpoint = uploader.upload(mismatched, ProfileImagePolicy.TEMP_PREFIX);

        assertThat(endpoint).endsWith(".jpg").doesNotEndWith(".png");
        verify(storage).put(eq(endpoint), any(byte[].class), eq("image/jpeg"));
    }

    @Test
    @DisplayName("[USER-PI-36] GIF·PDF 등 허용 목록 밖 형식은 400 INVALID_PROFILE_IMAGE_FORMAT이다")
    void validate_gifFormat_throwsInvalidFormat() {
        MockMultipartFile gif = new MockMultipartFile("image", "a.gif", "image/gif", "GIF89a".getBytes());

        assertThatThrownBy(() -> uploader.validate(gif))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT);
    }

    // ---------- store: 파일명·Content-Type ----------

    @Test
    @DisplayName("[USER-PI-41, 44, 46] 저장 시 EP는 서버 생성 파일명 + 판정된 확장자이고, "
            + "storage.put에는 판정된 Content-Type이 그대로 전달된다(원본 파일명·요청 Content-Type 미반영)")
    void upload_storesWithGeneratedKeyAndDetectedContentType() {
        // given: 원본 파일명이 경로 조작을 시도하고, 요청 Content-Type이 실제 형식과 다르다
        MockMultipartFile file = new MockMultipartFile(
                "image", "../../etc/passwd.exe", "application/octet-stream", pngBytes(50));

        // when
        String endpoint = uploader.upload(file, ProfileImagePolicy.TEMP_PREFIX);

        // then
        assertThat(endpoint).startsWith(ProfileImagePolicy.TEMP_PREFIX).endsWith(".png");
        assertThat(endpoint).doesNotContain("etc").doesNotContain("passwd");
        verify(storage).put(eq(endpoint), any(byte[].class), eq("image/png"));
    }

    @Test
    @DisplayName("[USER-PI-47] 저장되는 바이트는 업로드된 바이트와 동일하다(변형 없음)")
    void upload_storesBytesUnchanged() {
        byte[] original = pngBytes(200);
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", original);

        uploader.upload(file, ProfileImagePolicy.PERMANENT_PREFIX);

        var bytesCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(any(), bytesCaptor.capture(), any());
        assertThat(Arrays.equals(bytesCaptor.getValue(), original)).isTrue();
    }

    @Test
    @DisplayName("검증 실패 시 store가 호출되지 않는다(객체 미생성)")
    void validate_failure_neverCallsStore() {
        MockMultipartFile invalid = new MockMultipartFile("image", "a.gif", "image/gif", "GIF89a".getBytes());

        assertThatThrownBy(() -> uploader.upload(invalid, ProfileImagePolicy.TEMP_PREFIX));

        verify(storage, never()).put(any(), any(), any());
    }
}
