package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.community.dto.CommentItemResponse;
import com.skhynix.user.community.dto.CommentRequest;
import com.skhynix.user.community.dto.CommentUpdateRequest;
import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 게시글·댓글 command 지휘자의 <b>단계 순서</b> 계약: 선행 검사(DB) -> 이미지 검증·이동(S3) -> 쓰기(트랜잭션) ->
 * 빠진 객체 삭제(커밋 뒤). 어느 단계에서 거절되든 그 뒤 단계는 실행되지 않는다(USER-CM-30, 82, 87, 96, 155).
 */
@ExtendWith(MockitoExtension.class)
class CommunityCommandServiceTest {

    private static final Long ME = 10L;

    @Mock
    private CommunityPostWriter postWriter;
    @Mock
    private CommunityCommentWriter commentWriter;
    @Mock
    private CommunityImageAttacher attacher;
    @Mock
    private CommunityImageEraser eraser;

    private static PostRequest postRequest(List<String> images) {
        return new PostRequest(2L, "제목", "본문", images);
    }

    // ---------- 게시글 ----------

    @Nested
    class PostCommand {

        private CommunityPostCommandService service() {
            return new CommunityPostCommandService(postWriter, attacher, eraser);
        }

        @Test
        @DisplayName("[AC-CM-30-2] create: 카테고리 선행 검사 -> 이미지 attach(기존 EP 없음, 상한 5) -> 저장 순이다")
        void create_runsStagesInContractOrder() {
            given(attacher.attach(List.of("temp/x"), Set.of(), 5)).willReturn(List.of("community/x"));
            given(postWriter.create(ME, 2L, "제목", "본문", List.of("community/x"))).willReturn(9L);

            Long id = service().create(ME, postRequest(List.of("temp/x")));

            assertThat(id).isEqualTo(9L);
            InOrder order = inOrder(postWriter, attacher);
            order.verify(postWriter).prepareCreate(2L);
            order.verify(attacher).attach(List.of("temp/x"), Set.of(), 5);
            order.verify(postWriter).create(ME, 2L, "제목", "본문", List.of("community/x"));
        }

        @Test
        @DisplayName("[AC-CM-29-1] create: imageUrls가 null(키 생략)이면 빈 배열로 attach 한다")
        void create_nullImageUrls_treatedAsEmpty() {
            given(attacher.attach(List.of(), Set.of(), 5)).willReturn(List.of());

            service().create(ME, postRequest(null));

            verify(attacher).attach(List.of(), Set.of(), 5);
        }

        @Test
        @DisplayName("[AC-CM-30-2] create: 카테고리 404면 이미지 검증(S3)에 가지 않고 저장도 없다")
        void create_categoryMissing_neverReachesImagesOrSave() {
            willThrow(new BusinessException(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND)).given(postWriter)
                    .prepareCreate(2L);

            assertThatThrownBy(() -> service().create(ME, postRequest(List.of("temp/x"))))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(attacher);
            verify(postWriter, never()).create(any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("[AC-CM-30-3] create: 이미지 검증이 400이면 저장은 호출되지 않는다")
        void create_imageRejected_neverSaves() {
            given(attacher.attach(anyList(), anySet(), anyInt()))
                    .willThrow(new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT));

            assertThatThrownBy(() -> service().create(ME, postRequest(List.of("bad"))))
                    .isInstanceOf(BusinessException.class);
            verify(postWriter, never()).create(any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("[AC-CM-87-1, AC-CM-155-1] update: 선행 검사 결과(기존 EP)를 attach에 넘기고, 저장 뒤에 빠진 EP를 지운다")
        void update_passesExistingToAttach_andErasesRemovedAfterWrite() {
            PostDetailResponse detail = mock(PostDetailResponse.class);
            given(postWriter.prepareUpdate(ME, 1L, 2L)).willReturn(List.of("community/a", "community/b"));
            given(attacher.attach(List.of("community/b", "temp/c"), Set.of("community/a", "community/b"), 5))
                    .willReturn(List.of("community/b", "community/c"));
            given(postWriter.update(ME, 1L, 2L, "제목", "본문", List.of("community/b", "community/c")))
                    .willReturn(new CommunityPostWriter.UpdateResult(detail, List.of("community/a")));

            PostDetailResponse result = service().update(ME, 1L, postRequest(List.of("community/b", "temp/c")));

            assertThat(result).isSameAs(detail);
            InOrder order = inOrder(postWriter, attacher, eraser);
            order.verify(postWriter).prepareUpdate(ME, 1L, 2L);
            order.verify(attacher).attach(any(), any(), eq(5));
            order.verify(postWriter).update(eq(ME), eq(1L), eq(2L), any(), any(), anyList());
            order.verify(eraser).eraseQuietly(List.of("community/a"));
        }

        @Test
        @DisplayName("[AC-CM-82-1, AC-CM-82-2] update: 선행 검사(403/404/410)에서 거절되면 이미지 검증·저장·삭제 모두 일어나지 않는다")
        void update_prepareRejected_nothingElseRuns() {
            willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR)).given(postWriter)
                    .prepareUpdate(ME, 1L, 2L);

            assertThatThrownBy(() -> service().update(ME, 1L, postRequest(List.of())))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(attacher, eraser);
            verify(postWriter, never()).update(any(), any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("[AC-CM-155-2] update: 저장(트랜잭션)이 실패하면 빠진 EP 삭제는 호출되지 않는다 - 커밋 전에 지우지 않는다")
        void update_writeFails_neverErases() {
            given(postWriter.prepareUpdate(ME, 1L, 2L)).willReturn(List.of("community/a"));
            given(attacher.attach(anyList(), anySet(), anyInt())).willReturn(List.of());
            given(postWriter.update(any(), any(), any(), any(), any(), anyList()))
                    .willThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> service().update(ME, 1L, postRequest(List.of())))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(eraser);
        }

        @Test
        @DisplayName("[AC-CM-96-1] delete: 삭제 트랜잭션이 돌려준 EP들을 그 뒤에 best-effort로 지운다")
        void delete_erasesReturnedEndpointsAfterWrite() {
            given(postWriter.delete(ME, 1L)).willReturn(List.of("community/p", "community/c"));

            service().delete(ME, 1L);

            InOrder order = inOrder(postWriter, eraser);
            order.verify(postWriter).delete(ME, 1L);
            order.verify(eraser).eraseQuietly(List.of("community/p", "community/c"));
        }

        @Test
        @DisplayName("[AC-CM-92-1, AC-CM-93-1] delete: 403/404면 삭제 객체 정리는 호출되지 않는다")
        void delete_rejected_neverErases() {
            given(postWriter.delete(ME, 1L)).willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR));

            assertThatThrownBy(() -> service().delete(ME, 1L)).isInstanceOf(BusinessException.class);
            verifyNoInteractions(eraser);
        }
    }

    // ---------- 댓글 ----------

    @Nested
    class CommentCommand {

        private CommunityCommentCommandService service() {
            return new CommunityCommentCommandService(commentWriter, attacher, eraser);
        }

        @Test
        @DisplayName("[AC-CM-152-3, AC-CM-114-3] create: 댓글·답글 상한은 3이고 단계 순서는 선행 검사(글·부모) -> attach -> 저장이다")
        void create_usesCommentLimitAndStageOrder() {
            CommentRequest request = new CommentRequest("ㅊㅋ", List.of("temp/x"), 5L);
            given(attacher.attach(List.of("temp/x"), Set.of(), 3)).willReturn(List.of("community/x"));
            given(commentWriter.create(ME, 1L, 5L, "ㅊㅋ", List.of("community/x"))).willReturn(70L);

            Long id = service().create(ME, 1L, request);

            assertThat(id).isEqualTo(70L);
            InOrder order = inOrder(commentWriter, attacher);
            order.verify(commentWriter).prepareCreate(1L, 5L);
            order.verify(attacher).attach(any(), any(), eq(3));
            order.verify(commentWriter).create(eq(ME), eq(1L), eq(5L), any(), anyList());
        }

        @Test
        @DisplayName("[AC-CM-116-1] create: 부모 규칙 위반(400 DEPTH 등)이면 이미지 검증·저장이 일어나지 않는다")
        void create_parentRuleViolation_stopsBeforeImages() {
            willThrow(new BusinessException(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED)).given(commentWriter)
                    .prepareCreate(1L, 6L);

            assertThatThrownBy(() -> service().create(ME, 1L, new CommentRequest("c", List.of(), 6L)))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(attacher);
            verify(commentWriter, never()).create(any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("update: 선행 검사 -> attach(기존 EP, 상한 3) -> 저장 -> 빠진 EP 삭제 순이다")
        void update_stageOrder() {
            CommentItemResponse item = mock(com.skhynix.user.community.dto.CommentResponse.class);
            given(commentWriter.prepareUpdate(ME, 5L)).willReturn(List.of("community/a"));
            given(attacher.attach(List.of(), Set.of("community/a"), 3)).willReturn(List.of());
            given(commentWriter.update(ME, 5L, "수정", List.of()))
                    .willReturn(new CommunityCommentWriter.UpdateResult(item, List.of("community/a")));

            CommentItemResponse result = service().update(ME, 5L, new CommentUpdateRequest("수정", null));

            assertThat(result).isSameAs(item);
            InOrder order = inOrder(commentWriter, attacher, eraser);
            order.verify(commentWriter).prepareUpdate(ME, 5L);
            order.verify(attacher).attach(any(), any(), eq(3));
            order.verify(commentWriter).update(ME, 5L, "수정", List.of());
            order.verify(eraser).eraseQuietly(List.of("community/a"));
        }

        @Test
        @DisplayName("update: 이미지 검증이 거절하면 저장도 삭제도 없다")
        void update_imageRejected_nothingElse() {
            given(commentWriter.prepareUpdate(ME, 5L)).willReturn(List.of());
            given(attacher.attach(anyList(), anySet(), anyInt()))
                    .willThrow(new BusinessException(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED));

            assertThatThrownBy(() -> service().update(ME, 5L, new CommentUpdateRequest("c", List.of("a"))))
                    .isInstanceOf(BusinessException.class);
            verify(commentWriter, never()).update(any(), any(), any(), anyList());
            verifyNoInteractions(eraser);
        }

        @Test
        @DisplayName("[AC-CM-112-4] delete: 삭제 트랜잭션이 돌려준 이미지 EP를 그 뒤에 지운다")
        void delete_erasesAfterWrite() {
            given(commentWriter.delete(ME, 5L)).willReturn(List.of("community/a"));

            service().delete(ME, 5L);

            InOrder order = inOrder(commentWriter, eraser);
            order.verify(commentWriter).delete(ME, 5L);
            order.verify(eraser).eraseQuietly(List.of("community/a"));
        }

        @Test
        @DisplayName("delete: 거절(403/404)이면 정리가 호출되지 않는다")
        void delete_rejected_neverErases() {
            given(commentWriter.delete(ME, 5L)).willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR));

            assertThatThrownBy(() -> service().delete(ME, 5L)).isInstanceOf(BusinessException.class);
            verify(eraser, never()).eraseQuietly(anyCollection());
        }
    }
}
