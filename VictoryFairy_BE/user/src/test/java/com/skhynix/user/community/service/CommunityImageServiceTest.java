package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.community.dto.CommunityImageResponse;
import com.skhynix.user.profileimage.service.ProfileImageUploader;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link CommunityImageService} - 프로필과 같은 판정(파트, 크기, 형식)을 실제 {@link ProfileImageUploader} 로 태우되
 * 거절 코드가 커뮤니티 것인지 확인한다. USER-CM-140, 142~144, 158~159.
 */
@ExtendWith(MockitoExtension.class)
class CommunityImageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    private static final int FIVE_MIB = 5 * 1024 * 1024;

    @Mock
    private ProfileImageStorage storage;

    private CommunityImageService service;

    @BeforeEach
    void setUp() {
        service = new CommunityImageService(new ProfileImageUploader(storage));
    }

    private static byte[] jpeg(int length) {
        byte[] b = new byte[length];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("[AC-CM-140-1, AC-CM-158-1, AC-CM-159-1] 유효한 PNG는 temp/{uuid}.png 로 판정된 Content-Type과 함께 저장되고 EP가 반환된다(원본 파일명 미사용)")
    void upload_validPng_storesUnderTempWithServerGeneratedName() {
        MockMultipartFile file = new MockMultipartFile("image", "../../etc/passwd", "text/plain", PNG);

        CommunityImageResponse response = service.upload(file);

        assertThat(response.imageUrl()).matches("temp/[0-9a-f-]{36}\\.png");
        verify(storage).put(eq(response.imageUrl()), eq(PNG), eq("image/png"));
    }

    @Test
    @DisplayName("[AC-CM-143-2] 5MiB 정확히는 통과한다(경계)")
    void upload_exactlyFiveMiB_passes() {
        MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg", jpeg(FIVE_MIB));

        assertThat(service.upload(file).imageUrl()).startsWith("temp/").endsWith(".jpg");
    }

    @Test
    @DisplayName("[AC-CM-143-1] 5MiB + 1B는 413 COMMUNITY_IMAGE_TOO_LARGE이고 객체가 만들어지지 않는다")
    void upload_overFiveMiB_throwsCommunityTooLarge() {
        MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg", jpeg(FIVE_MIB + 1));

        assertThatThrownBy(() -> service.upload(file))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_IMAGE_TOO_LARGE));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("[AC-CM-142-1] 확장자만 .png인 텍스트와 GIF는 400 INVALID_COMMUNITY_IMAGE_FORMAT이다(매직 넘버 판정)")
    void upload_wrongMagicNumber_throwsCommunityInvalidFormat() {
        MockMultipartFile text = new MockMultipartFile("image", "a.png", "image/png", "hello world".getBytes());
        MockMultipartFile gif = new MockMultipartFile("image", "a.gif", "image/gif", "GIF89a......".getBytes());

        assertThatThrownBy(() -> service.upload(text))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_FORMAT));
        assertThatThrownBy(() -> service.upload(gif))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_FORMAT));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-144-1] 파트가 없거나(null) 0바이트 파일이면 400 COMMUNITY_IMAGE_REQUIRED다")
    void upload_missingOrEmpty_throwsCommunityRequired() {
        assertThatThrownBy(() -> service.upload(null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_IMAGE_REQUIRED));
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("image", "a.png", "image/png", new byte[0])))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_IMAGE_REQUIRED));
        verifyNoInteractions(storage);
    }
}
