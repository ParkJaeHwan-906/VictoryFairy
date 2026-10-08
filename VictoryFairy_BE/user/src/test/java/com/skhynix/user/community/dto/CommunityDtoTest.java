package com.skhynix.user.community.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.user.community.support.CommunityFixtures;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** 응답 키 집합(계약)·정렬 정의·상태 우선순위 등 커뮤니티 DTO 의 순수 로직. */
class CommunityDtoTest {

    private static Set<String> keysOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    // ---------- 키 집합 ----------

    @Test
    @DisplayName("[AC-CM-41-1, AC-CM-41-2] PostSummary 키는 정확히 11개이고 content 키가 없다")
    void postSummary_keySet() {
        assertThat(keysOf(PostSummaryResponse.class)).containsExactlyInAnyOrder(
                "postId", "categoryId", "categoryName", "title", "thumbnailUrl", "author", "viewCount",
                "likeCount", "dislikeCount", "commentCount", "createdAt");
    }

    @Test
    @DisplayName("[AC-CM-66-1] MyPostSummary 키는 PostSummary 11개 + blinded다 - 두 record 가 어긋나면 이 테스트가 깨진다")
    void myPostSummary_isPostSummaryPlusBlinded() {
        Set<String> expected = new java.util.HashSet<>(keysOf(PostSummaryResponse.class));
        expected.add("blinded");

        assertThat(keysOf(MyPostSummaryResponse.class)).isEqualTo(expected).hasSize(12);
    }

    @Test
    @DisplayName("[AC-CM-60-1, AC-CM-64-1] PostDetail 키는 정확히 15개이고 comments 키가 없다")
    void postDetail_keySet() {
        assertThat(keysOf(PostDetailResponse.class)).containsExactlyInAnyOrder(
                "postId", "categoryId", "categoryName", "title", "content", "imageUrls", "author",
                "viewCount", "likeCount", "dislikeCount", "commentCount", "myReaction", "isAuthor",
                "createdAt", "updatedAt");
    }

    @Test
    @DisplayName("[AC-CM-218-1, AC-CM-106-2] ReplyResponse 키는 정확히 12개이고 replies·viewCount 키가 없다")
    void replyResponse_keySet() {
        assertThat(keysOf(ReplyResponse.class)).containsExactlyInAnyOrder(
                "commentId", "parentCommentId", "status", "content", "imageUrls", "author", "likeCount",
                "dislikeCount", "myReaction", "isAuthor", "createdAt", "updatedAt");
    }

    @Test
    @DisplayName("[AC-CM-106-1, AC-CM-106-2] CommentResponse 키는 ReplyResponse 12개 + replies이고 viewCount 키가 없다")
    void commentResponse_isReplyKeysPlusReplies() {
        Set<String> expected = new java.util.HashSet<>(keysOf(ReplyResponse.class));
        expected.add("replies");

        assertThat(keysOf(CommentResponse.class)).isEqualTo(expected).hasSize(13);
        assertThat(keysOf(CommentResponse.class)).doesNotContain("viewCount");
    }

    @Test
    @DisplayName("[AC-CM-11-2, AC-CM-4-1] CategoryResponse {id,name,teamId}, AuthorResponse {nickname,profileImgUrl} - 계정 id·uid 없음")
    void categoryAndAuthor_keySets() {
        assertThat(keysOf(CategoryResponse.class)).containsExactlyInAnyOrder("id", "name", "teamId");
        assertThat(keysOf(AuthorResponse.class)).containsExactlyInAnyOrder("nickname", "profileImgUrl");
    }

    @Test
    @DisplayName("[AC-CM-6-1] PageResponse 키는 정확히 6개다")
    void pageResponse_keySet() {
        assertThat(keysOf(PageResponse.class)).containsExactlyInAnyOrder(
                "content", "page", "size", "totalElements", "totalPages", "hasNext");
    }

    @Test
    @DisplayName("[AC-CM-120-1, AC-CM-2-1] 반응 응답은 {myReaction, likeCount, dislikeCount}, 요청 DTO 어디에도 계정 식별자 필드가 없다")
    void reactionAndRequests_haveNoAccountIdentifierFields() {
        assertThat(keysOf(ReactionResponse.class)).containsExactlyInAnyOrder("myReaction", "likeCount", "dislikeCount");
        for (Class<?> request : List.of(PostRequest.class, CommentRequest.class, CommentUpdateRequest.class,
                ReactionRequest.class)) {
            assertThat(keysOf(request)).as(request.getSimpleName())
                    .noneMatch(k -> k.toLowerCase().contains("account") || k.equalsIgnoreCase("uid"));
        }
    }

    @Test
    @DisplayName("[AC-CM-5-1] 생성 응답은 postId / commentId 정수 하나뿐이다")
    void idResponses_keySets() {
        assertThat(keysOf(PostIdResponse.class)).containsExactly("postId");
        assertThat(keysOf(CommentIdResponse.class)).containsExactly("commentId");
        assertThat(keysOf(CommunityImageResponse.class)).containsExactly("imageUrl");
    }

    // ---------- 정렬 ----------

    @Test
    @DisplayName("[AC-CM-42-1, AC-CM-43-1, AC-CM-44-1] 정렬은 latest=id desc, likes=likeCount desc,id desc, views=viewCount desc,id desc다(싫어요는 정렬에 없음)")
    void postSort_definitions() {
        assertThat(PostSort.latest.sort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(PostSort.likes.sort()).isEqualTo(
                Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("id")));
        assertThat(PostSort.views.sort()).isEqualTo(
                Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("id")));
    }

    @Test
    @DisplayName("[AC-CM-45-1] sort 값은 소문자 3개뿐이다(latest/likes/views) - 범위 밖 값은 enum 바인딩 실패로 400")
    void postSort_exactlyThreeLowercaseValues() {
        assertThat(Arrays.stream(PostSort.values()).map(Enum::name)).containsExactly("latest", "likes", "views");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PostSort.valueOf("hot"));
    }

    // ---------- 반응 선택 ----------

    @Test
    @DisplayName("[AC-CM-122-1, AC-CM-126-1] ReactionChoice: LIKE/DISLIKE는 저장 타입에 대응하고 NONE은 null(취소 = 행 삭제)이며 값은 이 셋뿐이다")
    void reactionChoice_mapping() {
        assertThat(ReactionChoice.LIKE.type()).isEqualTo(ReactionType.LIKE);
        assertThat(ReactionChoice.DISLIKE.type()).isEqualTo(ReactionType.DISLIKE);
        assertThat(ReactionChoice.NONE.type()).isNull();
        assertThat(ReactionChoice.values()).hasSize(3);
    }

    // ---------- 댓글 상태 우선순위 ----------

    @Test
    @DisplayName("[AC-CM-215-1] CommentStatus.of: 정상=VISIBLE, 블라인드=BLINDED, 삭제=DELETED, 삭제+블라인드=DELETED")
    void commentStatus_priority() {
        CommunityPost post = CommunityFixtures.post(1L, CommunityFixtures.category(1L, "c"),
                CommunityFixtures.account(1L, "a"));
        var visible = CommunityFixtures.comment(1L, post, null, post.getUserAccount());
        var blinded = CommunityFixtures.comment(2L, post, null, post.getUserAccount());
        blinded.blind();
        var deleted = CommunityFixtures.comment(3L, post, null, post.getUserAccount());
        deleted.delete(LocalDateTime.of(2026, 10, 1, 0, 0));
        var both = CommunityFixtures.comment(4L, post, null, post.getUserAccount());
        both.blind();
        both.delete(LocalDateTime.of(2026, 10, 1, 0, 0));

        assertThat(CommentStatus.of(visible)).isEqualTo(CommentStatus.VISIBLE);
        assertThat(CommentStatus.of(blinded)).isEqualTo(CommentStatus.BLINDED);
        assertThat(CommentStatus.of(deleted)).isEqualTo(CommentStatus.DELETED);
        assertThat(CommentStatus.of(both)).isEqualTo(CommentStatus.DELETED);
    }

    // ---------- 요청 DTO ----------

    @Test
    @DisplayName("[AC-CM-29-1] imageUrls가 null(키 생략)이면 imageUrlsOrEmpty는 빈 리스트, 값이 있으면 그대로다")
    void requests_imageUrlsOrEmpty() {
        assertThat(new PostRequest(1L, "t", "c", null).imageUrlsOrEmpty()).isEmpty();
        assertThat(new CommentRequest("c", null, null).imageUrlsOrEmpty()).isEmpty();
        assertThat(new CommentUpdateRequest("c", null).imageUrlsOrEmpty()).isEmpty();
        assertThat(new PostRequest(1L, "t", "c", List.of("a", "b")).imageUrlsOrEmpty()).containsExactly("a", "b");
    }

    // ---------- 페이지 ----------

    @Test
    @DisplayName("[AC-CM-6-1, AC-CM-7-1] PageResponse.of는 Page 메타(page/size/total/totalPages/hasNext)를 그대로 옮기고 매핑 함수를 적용한다")
    void pageResponse_copiesPageMetaAndMapsContent() {
        PageImpl<Integer> page = new PageImpl<>(List.of(1, 2), PageRequest.of(0, 2), 5);

        PageResponse<String> response = PageResponse.of(page, n -> "n" + n);

        assertThat(response.content()).containsExactly("n1", "n2");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("PageResponse.of(page, content)는 마지막 페이지에서 hasNext=false다")
    void pageResponse_lastPageHasNoNext() {
        PageImpl<Integer> page = new PageImpl<>(List.of(5), PageRequest.of(2, 2), 5);

        assertThat(PageResponse.of(page, List.of("x")).hasNext()).isFalse();
    }
}
