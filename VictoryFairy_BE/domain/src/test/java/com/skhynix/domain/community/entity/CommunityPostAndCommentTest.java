package com.skhynix.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.user.entity.UserAccount;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CommunityPost}·{@link CommunityComment} 의 전이 메서드 순수 단위 테스트 - 초기값, 수정이 카운터를 건드리지 않음,
 * 삭제의 최초 시각 보존(멱등), 반응 카운터의 0 하한, 댓글의 보이는 상태 판정. 요구사항: USER-CM-21, 85, 93, 124, 130, 113.
 */
class CommunityPostAndCommentTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 2, 0, 0);

    private final UserAccount author = UserAccount.builder().nickname("작성자").password("pw1!").build();
    private final CommunityCategory category = CommunityCategory.builder().name("자유게시판").sortOrder(11).build();

    private CommunityPost post() {
        return CommunityPost.builder().category(category).userAccount(author).title("제목").content("본문").build();
    }

    private CommunityComment comment(CommunityComment parent) {
        return CommunityComment.builder().post(post()).parent(parent).userAccount(author).content("댓글").build();
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---------- 게시글 ----------

    @Test
    @DisplayName("[AC-CM-21-1] 새 게시글은 카운트 4종 0, blinded=false, deletedAt=null로 시작한다")
    void post_startsWithZeroCountersAndNoBlindOrDeletion() {
        CommunityPost post = post();

        assertThat(post.getViewCount()).isZero();
        assertThat(post.getLikeCount()).isZero();
        assertThat(post.getDislikeCount()).isZero();
        assertThat(post.getCommentCount()).isZero();
        assertThat(post.isBlinded()).isFalse();
        assertThat(post.getDeletedAt()).isNull();
        assertThat(post.isDeleted()).isFalse();
        assertThat(post.getTitle()).isEqualTo("제목");
        assertThat(post.getUserAccount()).isSameAs(author);
    }

    @Test
    @DisplayName("[AC-CM-85-1] edit은 카테고리·제목·본문만 바꾸고 카운터·작성자·blinded는 건드리지 않는다")
    void post_edit_replacesOnlyEditableFields() {
        CommunityPost post = post();
        set(post, "viewCount", 10L);
        set(post, "likeCount", 2L);
        CommunityCategory other = CommunityCategory.builder().name("LG 트윈스").sortOrder(1).build();

        LocalDateTime editedAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0);
        post.edit(other, "수정", "수정 본문", editedAt);

        assertThat(post.getCategory()).isSameAs(other);
        assertThat(post.getTitle()).isEqualTo("수정");
        assertThat(post.getContent()).isEqualTo("수정 본문");
        // [AC-CM-86-1] updatedAt 을 쓰는 유일한 경로가 edit 이다(생성 직후는 null, 반응·블라인드·삭제는 안 건드림)
        assertThat(post.getUpdatedAt()).isEqualTo(editedAt);
        assertThat(post.getViewCount()).isEqualTo(10L);
        assertThat(post.getLikeCount()).isEqualTo(2L);
        assertThat(post.getUserAccount()).isSameAs(author);
        assertThat(post.isBlinded()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-93-1] delete는 최초 삭제 시각을 보존한다 - 두 번째 호출은 no-op")
    void post_delete_isIdempotentKeepingFirstTimestamp() {
        CommunityPost post = post();

        post.delete(T1);
        post.delete(T2);

        assertThat(post.isDeleted()).isTrue();
        assertThat(post.getDeletedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("[AC-CM-170-1, AC-CM-172-1] blind는 blinded=true로 바꾸며 본문·카운트·삭제 상태는 그대로다(보존)")
    void post_blind_setsFlagOnly() {
        CommunityPost post = post();
        set(post, "likeCount", 3L);

        post.blind();
        post.blind();

        assertThat(post.isBlinded()).isTrue();
        assertThat(post.getContent()).isEqualTo("본문");
        assertThat(post.getLikeCount()).isEqualTo(3L);
        assertThat(post.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-123-1, AC-CM-130-1] adjustReaction: LIKE는 likeCount만, DISLIKE는 dislikeCount만 증감한다")
    void post_adjustReaction_touchesOnlyMatchingCounter() {
        CommunityPost post = post();

        post.adjustReaction(ReactionType.LIKE, 1);
        post.adjustReaction(ReactionType.LIKE, 1);
        post.adjustReaction(ReactionType.DISLIKE, 1);
        post.adjustReaction(ReactionType.LIKE, -1);

        assertThat(post.getLikeCount()).isEqualTo(1);
        assertThat(post.getDislikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("adjustReaction: 0에서 -1을 해도 음수가 되지 않는다(0 하한) - 게시글·댓글 모두")
    void adjustReaction_neverGoesBelowZero() {
        CommunityPost post = post();
        CommunityComment comment = comment(null);

        post.adjustReaction(ReactionType.LIKE, -1);
        post.adjustReaction(ReactionType.DISLIKE, -5);
        comment.adjustReaction(ReactionType.LIKE, -1);
        comment.adjustReaction(ReactionType.DISLIKE, -1);

        assertThat(post.getLikeCount()).isZero();
        assertThat(post.getDislikeCount()).isZero();
        assertThat(comment.getLikeCount()).isZero();
        assertThat(comment.getDislikeCount()).isZero();
    }

    // ---------- 댓글 ----------

    @Test
    @DisplayName("[AC-CM-108-2] parent가 없으면 최상위 댓글, 있으면 답글(isReply)이다")
    void comment_isReplyDependsOnParent() {
        CommunityComment top = comment(null);
        CommunityComment reply = comment(top);

        assertThat(top.isReply()).isFalse();
        assertThat(reply.isReply()).isTrue();
        assertThat(reply.getParent()).isSameAs(top);
    }

    @Test
    @DisplayName("[AC-CM-113-2] isVisible: 정상만 참이고 삭제·블라인드·둘 다는 거짓이다(commentCount가 세는 대상)")
    void comment_isVisible() {
        CommunityComment normal = comment(null);
        CommunityComment blinded = comment(null);
        blinded.blind();
        CommunityComment deleted = comment(null);
        deleted.delete(T1);
        CommunityComment both = comment(null);
        both.blind();
        both.delete(T1);

        assertThat(normal.isVisible()).isTrue();
        assertThat(blinded.isVisible()).isFalse();
        assertThat(deleted.isVisible()).isFalse();
        assertThat(both.isVisible()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-112-1, AC-CM-210-1] 부모 delete는 자식 상태를 바꾸지 않는다 - 자식 deletedAt은 null 그대로, 재삭제는 최초 시각 보존")
    void comment_deleteParent_doesNotTouchReplies_andKeepsFirstTimestamp() {
        CommunityComment parent = comment(null);
        CommunityComment reply = comment(parent);

        parent.delete(T1);
        parent.delete(T2);

        assertThat(parent.getDeletedAt()).isEqualTo(T1);
        assertThat(reply.isDeleted()).isFalse();
        assertThat(reply.isBlinded()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-211-1] 부모 blind는 자식에 전파되지 않는다")
    void comment_blindParent_doesNotTouchReplies() {
        CommunityComment parent = comment(null);
        CommunityComment reply = comment(parent);

        parent.blind();

        assertThat(parent.isBlinded()).isTrue();
        assertThat(reply.isBlinded()).isFalse();
        assertThat(reply.isVisible()).isTrue();
    }

    @Test
    @DisplayName("[AC-CM-109-2] 댓글 edit은 본문만 바꾼다")
    void comment_edit_changesContentOnly() {
        CommunityComment comment = comment(null);
        set(comment, "likeCount", 4L);

        LocalDateTime editedAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0);
        comment.edit("수정", editedAt);

        assertThat(comment.getContent()).isEqualTo("수정");
        assertThat(comment.getUpdatedAt()).isEqualTo(editedAt);
        assertThat(comment.getLikeCount()).isEqualTo(4L);
        assertThat(comment.getUserAccount()).isSameAs(author);
    }

    @Test
    @DisplayName("[AC-CM-123-1] 반응 엔티티 change는 type만 바꾼다(행 하나가 LIKE에서 DISLIKE로 - 두 상태가 동시에 켜진 순간이 없다)")
    void reaction_change_swapsTypeOnSameRow() {
        CommunityPostReaction row = CommunityPostReaction.builder().userAccount(author).post(post())
                .type(ReactionType.LIKE).build();
        CommunityCommentReaction commentRow = CommunityCommentReaction.builder().userAccount(author)
                .comment(comment(null)).type(ReactionType.DISLIKE).build();

        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0);
        row.change(ReactionType.DISLIKE, changedAt);
        commentRow.change(ReactionType.LIKE, changedAt);

        assertThat(row.getType()).isEqualTo(ReactionType.DISLIKE);
        assertThat(commentRow.getType()).isEqualTo(ReactionType.LIKE);
        // 반응 행의 updated_at 도 Hibernate 가 아니라 호출자의 Clock 값이다
        assertThat(row.getUpdatedAt()).isEqualTo(changedAt);
        assertThat(commentRow.getUpdatedAt()).isEqualTo(changedAt);
    }
}
