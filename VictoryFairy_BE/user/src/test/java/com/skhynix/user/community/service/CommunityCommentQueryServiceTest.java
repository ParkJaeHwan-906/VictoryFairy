package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.PageResponse;
import com.skhynix.user.community.support.CommunityFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** {@link CommunityCommentQueryService} - 댓글 목록 페이징 위임과 소속 글 404/410. USER-CM-105~107. */
@ExtendWith(MockitoExtension.class)
class CommunityCommentQueryServiceTest {

    private static final Long ME = 10L;

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityCommentRepository commentRepository;
    @Mock
    private CommentResponseAssembler assembler;

    private CommunityCommentQueryService service;

    private final UserAccount author = CommunityFixtures.account(ME, "작성자");
    private CommunityPost post;

    @BeforeEach
    void setUp() {
        service = new CommunityCommentQueryService(postRepository, commentRepository, assembler);
        post = CommunityFixtures.post(1L, CommunityFixtures.category(1L, "자유"), author);
    }

    @Test
    @DisplayName("[AC-CM-103-1 준용, AC-CM-105-4] 글이 없거나 삭제됐으면 404 COMMUNITY_POST_NOT_FOUND이고 댓글 쿼리를 내지 않는다")
    void getComments_postMissing_throws404() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComments(ME, 1L, 0, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(((BusinessException) t).getErrorCode())
                        .isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        verifyNoInteractions(commentRepository, assembler);
    }

    @Test
    @DisplayName("[AC-CM-105-4, AC-CM-177-1] 글이 블라인드면 410 COMMUNITY_POST_BLINDED다")
    void getComments_postBlinded_throws410() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.getComments(ME, 1L, 0, 20))
                .satisfies(t -> assertThat(((BusinessException) t).getErrorCode())
                        .isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        verifyNoInteractions(commentRepository, assembler);
    }

    @Test
    @DisplayName("[AC-CM-105-1, AC-CM-6-1, AC-CM-107-1] 요청한 page/size 그대로(정렬은 쿼리가 소유) 최상위 페이지를 조회하고 조립 결과와 페이지 메타를 싣는다")
    void getComments_delegatesPagingAndWrapsAssembledContent() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        CommunityComment c = CommunityFixtures.comment(5L, post, null, author);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        given(commentRepository.findTopLevelPage(org.mockito.ArgumentMatchers.eq(1L), pageable.capture()))
                .willReturn(new PageImpl<>(List.of(c), PageRequest.of(2, 10), 21));
        CommentResponse assembled = org.mockito.Mockito.mock(CommentResponse.class);
        given(assembler.assemble(List.of(c), ME)).willReturn(List.of(assembled));

        PageResponse<CommentResponse> response = service.getComments(ME, 1L, 2, 10);

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
        assertThat(response.content()).containsExactly(assembled);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-107-1] 댓글 목록 조회는 게시글을 갱신하지 않는다 - 글 리포지토리는 단건 조회 외에 호출되지 않는다")
    void getComments_neverTouchesPostCounters() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(commentRepository.findTopLevelPage(any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        given(assembler.assemble(List.of(), ME)).willReturn(List.of());

        service.getComments(ME, 1L, 0, 20);

        verify(postRepository).findByIdAndDeletedAtIsNull(1L);
        org.mockito.Mockito.verifyNoMoreInteractions(postRepository);
    }
}
