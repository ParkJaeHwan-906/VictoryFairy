package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SignupProfileImageService} — 가입 요청의 temp/ EP를 영구 경로로 옮긴다. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-52~60.
 */
@ExtendWith(MockitoExtension.class)
class SignupProfileImageServiceTest {

    private static final String VALID_TEMP =
            "temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg";

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private SignupProfileImageService service;

    @Test
    @DisplayName("[USER-PI-51] profileImgUrl이 null이면(선택 입력 미사용) 저장소를 전혀 건드리지 않고 "
            + "null을 그대로 돌려준다")
    void moveToPermanent_null_returnsNullWithoutTouchingStorage() {
        String result = service.moveToPermanent(null);

        assertThat(result).isNull();
        verify(storage, never()).exists(anyString());
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-PI-55] temp/ 접두가 아닌 값이면 400 INVALID_PROFILE_IMAGE_ENDPOINT이고 존재 확인을 "
            + "시도하지 않는다(형태 검증이 존재 확인보다 앞)")
    void moveToPermanent_notTempPrefixed_throwsInvalidEndpoint_withoutCheckingExistence() {
        assertThatThrownBy(() -> service.moveToPermanent("user-profile-img/other.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);

        verify(storage, never()).exists(anyString());
    }

    @Test
    @DisplayName("[USER-PI-56] 모양은 유효하지만 그 객체가 버킷에 없으면 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void moveToPermanent_objectDoesNotExist_throwsInvalidEndpoint() {
        given(storage.exists(VALID_TEMP)).willReturn(false);

        assertThatThrownBy(() -> service.moveToPermanent(VALID_TEMP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);

        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-PI-52, 53] 형태·존재 검증을 통과하면 복사 후 원본을 삭제하고, 반환값은 새로 "
            + "생성된 user-profile-img/ 접두 EP다(원본 파일명을 물려받지 않는다)")
    void moveToPermanent_validAndExists_copiesThenDeletesOriginal_returnsNewPermanentEndpoint() {
        given(storage.exists(VALID_TEMP)).willReturn(true);

        String moved = service.moveToPermanent(VALID_TEMP);

        assertThat(moved).startsWith("user-profile-img/").endsWith(".jpg");
        assertThat(moved).isNotEqualTo(VALID_TEMP);
        verify(storage).copy(eq(VALID_TEMP), eq(moved));
        verify(storage).delete(VALID_TEMP);
    }

    @Test
    @DisplayName("[USER-PI-54] 원본 삭제가 실패해도 이동 자체는 성공으로 보고 새 EP를 그대로 돌려준다"
            + "(temp 정리 스케줄러가 남은 원본을 회수한다)")
    void moveToPermanent_originalDeleteFails_stillReturnsNewEndpoint() {
        given(storage.exists(VALID_TEMP)).willReturn(true);
        willThrow(new RuntimeException("delete failed")).given(storage).delete(VALID_TEMP);

        String moved = service.moveToPermanent(VALID_TEMP);

        assertThat(moved).startsWith("user-profile-img/");
    }

    @Test
    @DisplayName("[USER-PI-57] 존재 확인 단계에서 저장소 장애(RuntimeException)가 나면 400이 아니라 "
            + "이동 실패로 다루어 null을 돌려준다 — 저장소 장애가 가입 거절이 되면 안 된다")
    void moveToPermanent_existsCheckThrowsInfraError_returnsNullInsteadOfBadRequest() {
        given(storage.exists(VALID_TEMP)).willThrow(new RuntimeException("S3 unavailable"));

        String result = service.moveToPermanent(VALID_TEMP);

        assertThat(result).isNull();
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-PI-57] 복사 단계에서 저장소 장애가 나면 null을 돌려준다(가입은 그대로 진행)")
    void moveToPermanent_copyThrowsInfraError_returnsNull() {
        given(storage.exists(VALID_TEMP)).willReturn(true);
        willThrow(new RuntimeException("S3 unavailable")).given(storage)
                .copy(eq(VALID_TEMP), anyString());

        String result = service.moveToPermanent(VALID_TEMP);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("[USER-PI-61] 255자를 넘는 profileImgUrl은 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void moveToPermanent_tooLong_throwsInvalidEndpoint() {
        String longEndpoint = "temp/" + "a".repeat(300) + ".jpg";

        assertThatThrownBy(() -> service.moveToPermanent(longEndpoint))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);
    }
}
