package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.store.ProfileImageUploadLimitStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link TempProfileImageService} — 비인증 임시 업로드의 appId 한도. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-20~34.
 */
@ExtendWith(MockitoExtension.class)
class TempProfileImageServiceTest {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Mock
    private ProfileImageUploader uploader;

    @Mock
    private ProfileImageUploadLimitStore limitStore;

    @InjectMocks
    private TempProfileImageService service;

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("image", "a.png", "image/png", PNG_BYTES);
    }

    // ---------- USER-PI-23: appId 필수 ----------

    @Test
    @DisplayName("[USER-PI-23] appId가 null이면 400 INVALID_APP_ID이고 이미지 검증·카운터 증가·저장 "
            + "어느 것도 시도하지 않는다")
    void upload_nullAppId_throwsInvalidAppId_andSkipsEverythingElse() {
        assertThatThrownBy(() -> service.upload(null, pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_APP_ID);

        verify(uploader, never()).validate(any());
        verify(limitStore, never()).increment(any());
    }

    @Test
    @DisplayName("[USER-PI-23] appId가 공백뿐이면 400 INVALID_APP_ID다")
    void upload_blankAppId_throwsInvalidAppId() {
        assertThatThrownBy(() -> service.upload("   ", pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_APP_ID);
    }

    // ---------- USER-PI-26: 검증이 증가보다 앞 ----------

    @Test
    @DisplayName("[USER-PI-26] 형식 위반으로 이미지 검증이 실패하면 카운터를 증가시키지 않는다"
            + "(검증이 증가보다 앞이라는 순서 계약의 직접 증거)")
    void upload_imageValidationFails_doesNotIncrementCounter() {
        given(uploader.validate(any())).willThrow(
                new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT));

        assertThatThrownBy(() -> service.upload("app-1", pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT);

        verify(limitStore, never()).increment(any());
    }

    // ---------- USER-PI-27~30: 한도 경계(10 허용, 11 거절) ----------

    @Test
    @DisplayName("[USER-PI-27] 증가 후 값이 10이면(10번째 성공) 200으로 저장까지 진행한다 — 경계: 10회까지 허용")
    void upload_tenthUpload_succeeds() {
        given(limitStore.increment("app-1")).willReturn(10);
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX)))
                .willReturn("temp/generated.png");

        ProfileImageResponse response = service.upload("app-1", pngFile());

        assertThat(response.profileImgUrl()).isEqualTo("temp/generated.png");
    }

    @Test
    @DisplayName("[USER-PI-27, 28] 증가 후 값이 11이면(11번째) 429 PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED이고 "
            + "객체를 저장하지 않는다")
    void upload_eleventhUpload_throwsLimitExceeded_andDoesNotStore() {
        given(limitStore.increment("app-1")).willReturn(11);

        assertThatThrownBy(() -> service.upload("app-1", pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED);

        verify(uploader, never()).store(any(), any());
    }

    // ---------- USER-PI-33: Redis 장애 fail-closed ----------

    @Test
    @DisplayName("[USER-PI-33] Redis 조회·증가가 실패하면(RuntimeException) 업로드를 거절하고 저장을 "
            + "시도하지 않는다 — fail-closed")
    void upload_redisIncrementFails_rejectsUpload_failClosed() {
        given(limitStore.increment("app-1")).willThrow(new IllegalStateException("Redis down"));

        assertThatThrownBy(() -> service.upload("app-1", pngFile()))
                .isInstanceOf(RuntimeException.class);

        verify(uploader, never()).store(any(), any());
    }

    // ---------- USER-PI-20, 32: 저장 위치·appId 비노출 ----------

    @Test
    @DisplayName("[USER-PI-20] 성공한 업로드는 temp/ 접두로 저장된다")
    void upload_success_storesUnderTempPrefix() {
        given(limitStore.increment("app-1")).willReturn(1);
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX)))
                .willReturn("temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.png");

        ProfileImageResponse response = service.upload("app-1", pngFile());

        assertThat(response.profileImgUrl()).startsWith(ProfileImagePolicy.TEMP_PREFIX);
    }

    @Test
    @DisplayName("[USER-PI-32] appId 값 자체가 응답 EP 어디에도 그대로 실리지 않는다"
            + "(uploader.store에는 접두만 전달되고 appId는 전달되지 않는다)")
    void upload_success_doesNotLeakAppIdIntoStoredKey() {
        String appId = "super-secret-app-id-value";
        given(limitStore.increment(appId)).willReturn(1);
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX)))
                .willReturn("temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.png");

        ProfileImageResponse response = service.upload(appId, pngFile());

        assertThat(response.profileImgUrl()).doesNotContain(appId);
    }

    // ---------- USER-PI-116: 증가는 저장보다 앞선다(순서 고정) ----------

    @Test
    @DisplayName("[USER-PI-116] 카운터 증가가 S3 저장보다 먼저 호출된다 — 동시성 게이트라 순서가 뒤집히면 "
            + "한도가 뚫린다")
    void upload_success_incrementsCounterBeforeStoring() {
        given(limitStore.increment("app-1")).willReturn(1);
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX)))
                .willReturn("temp/generated.png");

        service.upload("app-1", pngFile());

        InOrder inOrder = inOrder(limitStore, uploader);
        inOrder.verify(limitStore).increment("app-1");
        inOrder.verify(uploader).store(any(), eq(ProfileImagePolicy.TEMP_PREFIX));
    }

    // ---------- USER-PI-117: 저장 실패 시 카운터 환불 ----------

    @Test
    @DisplayName("[USER-PI-117] S3 저장이 실패하면 refund가 정확히 1회 호출되고 원래 저장 실패 예외가 "
            + "그대로(가려지지 않고) 전파된다")
    void upload_storeFails_refundsOnceAndPropagatesOriginalStorageException() {
        given(limitStore.increment("app-1")).willReturn(1);
        RuntimeException storageFailure = new RuntimeException("S3 down");
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX))).willThrow(storageFailure);

        assertThatThrownBy(() -> service.upload("app-1", pngFile())).isSameAs(storageFailure);

        verify(limitStore, times(1)).refund("app-1");
    }

    // ---------- USER-PI-120, 121: 환불 자체가 실패해도 원인 예외가 우선 ----------

    @Test
    @DisplayName("[USER-PI-120] 환불(refund) 자체가 예외를 던져도 사용자에게는 원래의 저장 실패 예외가 "
            + "그대로 나간다 — 환불 실패가 원인 예외를 가리지 않는다")
    void upload_storeFailsAndRefundAlsoFails_originalStorageExceptionStillPropagates() {
        given(limitStore.increment("app-1")).willReturn(1);
        RuntimeException storageFailure = new RuntimeException("S3 down");
        given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX))).willThrow(storageFailure);
        doThrow(new IllegalStateException("Redis down too")).when(limitStore).refund("app-1");

        assertThatThrownBy(() -> service.upload("app-1", pngFile())).isSameAs(storageFailure);
    }

    @Test
    @DisplayName("[USER-PI-121] 환불이 실패하면 그 사실을 ERROR 로그로 남긴다(재시도는 하지 않는다"
            + " — best-effort)")
    void upload_storeFailsAndRefundAlsoFails_logsRefundFailureAsError() {
        Logger logger = (Logger) LoggerFactory.getLogger(TempProfileImageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            given(limitStore.increment("app-1")).willReturn(1);
            RuntimeException storageFailure = new RuntimeException("S3 down");
            given(uploader.store(any(), eq(ProfileImagePolicy.TEMP_PREFIX))).willThrow(storageFailure);
            doThrow(new IllegalStateException("Redis down too")).when(limitStore).refund("app-1");

            assertThatThrownBy(() -> service.upload("app-1", pngFile())).isSameAs(storageFailure);

            assertThat(appender.list)
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("환불"));
            // refund는 딱 1회만 시도된다 — 실패해도 같은 요청 안에서 재시도하지 않는다
            verify(limitStore, times(1)).refund("app-1");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---------- USER-PI-26: 증가 이전에 거절되는 경로는 refund를 부르지 않는다 ----------

    @Test
    @DisplayName("[USER-PI-26] appId가 null이면(증가 이전 차단) refund를 호출하지 않는다")
    void upload_nullAppId_doesNotCallRefund() {
        assertThatThrownBy(() -> service.upload(null, pngFile()))
                .isInstanceOf(BusinessException.class);

        verify(limitStore, never()).refund(any());
    }

    @Test
    @DisplayName("[USER-PI-26] appId가 공백뿐이면(증가 이전 차단) refund를 호출하지 않는다")
    void upload_blankAppId_doesNotCallRefund() {
        assertThatThrownBy(() -> service.upload("   ", pngFile()))
                .isInstanceOf(BusinessException.class);

        verify(limitStore, never()).refund(any());
    }

    @Test
    @DisplayName("[USER-PI-26] 형식 위반으로 이미지 검증이 실패하면(증가 이전 차단) refund를 호출하지 않는다")
    void upload_imageValidationFails_doesNotCallRefund() {
        given(uploader.validate(any())).willThrow(
                new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT));

        assertThatThrownBy(() -> service.upload("app-1", pngFile()))
                .isInstanceOf(BusinessException.class);

        verify(limitStore, never()).refund(any());
    }

    @Test
    @DisplayName("[USER-PI-26] 한도 초과로 거절되면(증가 이후지만 저장 실패가 아님) refund를 호출하지 않는다"
            + " — 이미 넘긴 창의 값이 늘어도 판정은 같다")
    void upload_limitExceeded_doesNotCallRefund() {
        given(limitStore.increment("app-1")).willReturn(11);

        assertThatThrownBy(() -> service.upload("app-1", pngFile()))
                .isInstanceOf(BusinessException.class);

        verify(limitStore, never()).refund(any());
    }
}
