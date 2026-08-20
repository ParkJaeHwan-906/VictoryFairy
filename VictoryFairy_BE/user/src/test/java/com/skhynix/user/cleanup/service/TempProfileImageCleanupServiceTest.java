package com.skhynix.user.cleanup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import com.skhynix.user.profileimage.storage.StoredObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TempProfileImageCleanupService} — temp/ 정리 한 회차. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-80~92.
 */
@ExtendWith(MockitoExtension.class)
class TempProfileImageCleanupServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-18T19:00:00Z"); // KST 익일 04:00

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private TempProfileImageCleanupService service;

    @Test
    @DisplayName("[USER-PI-81] 목록 조회는 temp/ 접두로만 한정한다")
    void removeExpiredTempImages_listsOnlyTempPrefix() {
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of());

        service.removeExpiredTempImages(BASE_TIME);

        verify(storage).list(ProfileImagePolicy.TEMP_PREFIX);
    }

    @Test
    @DisplayName("[USER-PI-82] 마지막 수정 + 24시간 <= 기준 시각인 객체(정확히 25시간 전)는 삭제된다")
    void removeExpiredTempImages_objectOlderThan24Hours_isDeleted() {
        // given: 기준 시각보다 25시간 전 — 24시간 보존 기간을 넘었다
        StoredObject old = new StoredObject("temp/old.jpg", BASE_TIME.minus(Duration.ofHours(25)));
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of(old));

        int deleted = service.removeExpiredTempImages(BASE_TIME);

        assertThat(deleted).isEqualTo(1);
        verify(storage).delete("temp/old.jpg");
    }

    @Test
    @DisplayName("[USER-PI-82] 23시간 전 객체는 아직 보존 기간(24시간) 안이라 삭제되지 않는다"
            + " — 가입 화면을 열어 둔 사용자를 보호하는 여유")
    void removeExpiredTempImages_objectYoungerThan24Hours_isNotDeleted() {
        StoredObject recent = new StoredObject("temp/recent.jpg", BASE_TIME.minus(Duration.ofHours(23)));
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of(recent));

        int deleted = service.removeExpiredTempImages(BASE_TIME);

        assertThat(deleted).isZero();
        verify(storage, never()).delete("temp/recent.jpg");
    }

    @Test
    @DisplayName("[USER-PI-82] 정확히 24시간 전 객체는 경계값으로 삭제 대상이다(<=)")
    void removeExpiredTempImages_objectExactly24HoursOld_isDeleted() {
        StoredObject boundary = new StoredObject("temp/boundary.jpg", BASE_TIME.minus(Duration.ofHours(24)));
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of(boundary));

        int deleted = service.removeExpiredTempImages(BASE_TIME);

        assertThat(deleted).isEqualTo(1);
        verify(storage).delete("temp/boundary.jpg");
    }

    @Test
    @DisplayName("[USER-PI-85] 대상 3건 중 2번째 삭제가 실패해도 1·3번째는 삭제되고 회차는 계속된다"
            + "(한 객체의 실패가 그 객체에서 끝난다)")
    void removeExpiredTempImages_oneObjectFails_othersStillDeleted_countContinues() {
        // given
        Instant expired = BASE_TIME.minus(Duration.ofHours(30));
        StoredObject first = new StoredObject("temp/first.jpg", expired);
        StoredObject second = new StoredObject("temp/second.jpg", expired);
        StoredObject third = new StoredObject("temp/third.jpg", expired);
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of(first, second, third));
        // 특정 키에만 예외를 던지고 나머지는 성공시켜야 하므로, 인자별로 다른 스텁을 걸지 않고
        // any() 하나에 조건부 동작을 실어 스트릭트 스터빙(PotentialStubbingProblem)을 피한다.
        org.mockito.Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("temp/second.jpg".equals(key)) {
                throw new RuntimeException("delete failed");
            }
            return null;
        }).when(storage).delete(org.mockito.ArgumentMatchers.anyString());

        // when
        int deleted = service.removeExpiredTempImages(BASE_TIME);

        // then
        assertThat(deleted).isEqualTo(2);
        verify(storage).delete("temp/first.jpg");
        verify(storage).delete("temp/second.jpg");
        verify(storage).delete("temp/third.jpg");
    }

    @Test
    @DisplayName("[USER-PI-84] storage.list가 temp/만 요청하므로 user-profile-img/ 객체는 회차 대상에 애초에 없다"
            + "(목록 자체가 없으니 delete도 호출되지 않는다)")
    void removeExpiredTempImages_neverListsOrDeletesPermanentPrefix() {
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of());

        service.removeExpiredTempImages(BASE_TIME);

        verify(storage, never()).list(eq(ProfileImagePolicy.PERMANENT_PREFIX));
        verify(storage, times(1)).list(ProfileImagePolicy.TEMP_PREFIX);
    }

    @Test
    @DisplayName("[USER-PI-91] 0건이어도 예외 없이 정상 종료하고 삭제 건수 0을 돌려준다")
    void removeExpiredTempImages_noExpiredObjects_returnsZeroWithoutError() {
        given(storage.list(ProfileImagePolicy.TEMP_PREFIX)).willReturn(List.of());

        int deleted = service.removeExpiredTempImages(BASE_TIME);

        assertThat(deleted).isZero();
    }
}
