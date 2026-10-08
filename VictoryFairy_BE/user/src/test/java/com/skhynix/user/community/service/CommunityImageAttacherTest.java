package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CommunityImageAttacher} — 요청 imageUrls 를 최종 EP 로 바꾼다. 검증 순서(상한 → 중복·null → temp 모양 →
 * S3 존재)와 "검증을 전부 끝낸 뒤 이동"이 계약이다. 요구사항: {@code docs/requirements/user/community.md}
 * USER-CM-147~153.
 */
@ExtendWith(MockitoExtension.class)
class CommunityImageAttacherTest {

    private static final String TEMP_A = "temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.jpg";
    private static final String TEMP_B = "temp/0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d.png";
    private static final String TEMP_C = "temp/11111111-2222-4333-8444-555555555555.webp";
    private static final String PERM_A = "community/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.jpg";
    private static final String PERM_B = "community/0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d.png";
    private static final String OLD = "community/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.jpg";

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private CommunityImageAttacher attacher;

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("[AC-CM-29-1] 빈 배열은 빈 결과이고 S3를 전혀 부르지 않는다")
    void attach_emptyRequest_returnsEmptyWithoutStorage() {
        assertThat(attacher.attach(List.of(), Set.of(), 5)).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-147-1, AC-CM-147-2, AC-CM-61-1] 임시 EP는 community/ 접두 + 같은 파일명으로 복사되고 원본은 삭제되며, 결과는 요청 순서다")
    void attach_tempEndpoints_movedToCommunityPrefixKeepingOrder() {
        given(storage.exists(TEMP_A)).willReturn(true);
        given(storage.exists(TEMP_B)).willReturn(true);

        List<String> result = attacher.attach(List.of(TEMP_A, TEMP_B), Set.of(), 5);

        assertThat(result).containsExactly(PERM_A, PERM_B);
        InOrder order = inOrder(storage);
        order.verify(storage).copy(TEMP_A, PERM_A);
        order.verify(storage).delete(TEMP_A);
        order.verify(storage).copy(TEMP_B, PERM_B);
        order.verify(storage).delete(TEMP_B);
    }

    @Test
    @DisplayName("[AC-CM-87-1, AC-CM-61-2] 기존 확정 EP는 이동·조회 없이 유지되고 새 임시 EP만 이동한다(요청 순서 유지)")
    void attach_existingKeptAndNewMoved() {
        given(storage.exists(TEMP_A)).willReturn(true);

        List<String> result = attacher.attach(List.of(OLD, TEMP_A), Set.of(OLD), 5);

        assertThat(result).containsExactly(OLD, PERM_A);
        verify(storage).copy(TEMP_A, PERM_A);
        verify(storage, never()).exists(OLD);
        verify(storage, never()).delete(OLD);
    }

    @Test
    @DisplayName("[AC-CM-152-1] 게시글 상한 5를 넘는 6개 요청은 400 COMMUNITY_IMAGE_LIMIT_EXCEEDED이고 S3를 조회하지 않는다")
    void attach_overLimit_throwsBeforeAnyStorageCall() {
        List<String> six = Arrays.asList(TEMP_A, TEMP_B, TEMP_C, "x1", "x2", "x3");

        assertThatThrownBy(() -> attacher.attach(six, Set.of(), 5))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-152-2] 게시글 상한 정확히 5개는 통과한다(경계)")
    void attach_exactlyAtLimit_passes() {
        List<String> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add("temp/0000000" + i + "-0000-4000-8000-000000000000.jpg");
        }
        five.forEach(e -> given(storage.exists(e)).willReturn(true));

        assertThat(attacher.attach(five, Set.of(), 5)).hasSize(5).allMatch(e -> e.startsWith("community/"));
    }

    @Test
    @DisplayName("[AC-CM-152-3] 댓글·답글 상한 3을 넘는 4개 요청은 400 COMMUNITY_IMAGE_LIMIT_EXCEEDED다")
    void attach_commentOverLimit_throws() {
        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A, TEMP_B, TEMP_C, "temp/x"), Set.of(), 3))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-153-1] 같은 EP가 두 번 있으면 400 INVALID_COMMUNITY_IMAGE_ENDPOINT이고 이동은 시작되지 않는다")
    void attach_duplicateEndpoint_throwsInvalidEndpoint() {
        given(storage.exists(TEMP_A)).willReturn(true);

        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A, TEMP_A), Set.of(), 5))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verify(storage, never()).copy(anyString(), anyString());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("[AC-CM-149-1] null 원소와 빈 문자열은 400 INVALID_COMMUNITY_IMAGE_ENDPOINT다")
    void attach_nullOrEmptyElement_throwsInvalidEndpoint() {
        assertThatThrownBy(() -> attacher.attach(Arrays.asList((String) null), Set.of(), 5))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        assertThatThrownBy(() -> attacher.attach(List.of(""), Set.of(), 5))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-149-1] 프로필 EP·경로 조작·전체 URL·모양이 다른 temp 값은 S3 조회 없이 400이다")
    void attach_badShapes_throwInvalidEndpointWithoutStorageCall() {
        for (String bad : List.of(
                "user-profile-img/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.jpg",
                "../x",
                "temp/../x.jpg",
                "https://victoryfairy.com/" + TEMP_A,
                "/" + TEMP_A,
                "temp/not-a-uuid.jpg",
                "temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.gif")) {
            assertThatThrownBy(() -> attacher.attach(List.of(bad), Set.of(), 5))
                    .as(bad)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(codeOf(t))
                            .isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        }
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-149-2] 작성 시(기존 EP 없음)에는 community/ 값 자체가 400이다")
    void attach_communityEndpointOnCreate_throwsInvalidEndpoint() {
        assertThatThrownBy(() -> attacher.attach(List.of(PERM_A), Set.of(), 5))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("[AC-CM-151-1] 수정 요청이 그 글의 것이 아닌 다른 글의 확정 EP를 싣고 오면 400이다(남의 이미지 가로채기 방지)")
    void attach_foreignCommunityEndpointOnUpdate_throwsInvalidEndpoint() {
        assertThatThrownBy(() -> attacher.attach(List.of(PERM_B), Set.of(OLD), 5))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    @DisplayName("[AC-CM-150-1] 모양은 맞지만 버킷에 없는 임시 EP는 모양 오류와 같은 400(같은 문구)이다")
    void attach_missingObject_throwsSameInvalidEndpoint() {
        given(storage.exists(TEMP_A)).willReturn(false);

        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A), Set.of(), 5))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT.getMessage())
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    @DisplayName("[AC-CM-30-3] 두 번째 원소가 잘못이면 첫 번째 원소도 이동되지 않는다 — 검증을 전부 끝낸 뒤에 이동을 시작한다")
    void attach_laterElementInvalid_noEarlierElementMoved() {
        given(storage.exists(TEMP_A)).willReturn(true);
        given(storage.exists(TEMP_B)).willReturn(false);

        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A, TEMP_B), Set.of(), 5))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));
        verify(storage, never()).copy(anyString(), anyString());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("S3 존재 확인이 저장소 장애로 예외를 올리면 400이 아니라 그 예외가 그대로 전파된다(500)")
    void attach_storageFailureOnExists_propagates() {
        given(storage.exists(TEMP_A)).willThrow(new IllegalStateException("s3 down"));

        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A), Set.of(), 5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[AC-CM-154-1] S3 복사 실패는 그대로 전파된다(이미지만 포기하지 않는다)")
    void attach_copyFails_propagates() {
        given(storage.exists(TEMP_A)).willReturn(true);
        willThrow(new IllegalStateException("copy failed")).given(storage).copy(TEMP_A, PERM_A);

        assertThatThrownBy(() -> attacher.attach(List.of(TEMP_A), Set.of(), 5))
                .isInstanceOf(IllegalStateException.class);
        verify(storage, never()).delete(TEMP_A);
    }

    @Test
    @DisplayName("원본(temp) 삭제 실패는 이동 성공으로 본다 — 확정 EP가 그대로 반환된다(temp 정리가 회수)")
    void attach_tempDeleteFails_stillReturnsPermanentEndpoint() {
        given(storage.exists(TEMP_A)).willReturn(true);
        willThrow(new IllegalStateException("delete failed")).given(storage).delete(TEMP_A);

        assertThat(attacher.attach(List.of(TEMP_A), Set.of(), 5)).containsExactly(PERM_A);
    }
}
