package com.skhynix.domain.community.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 댓글과 답글을 한 테이블에 담는다. {@code parent} 가 {@code null} 이면 최상위 댓글, 아니면 그 댓글의
 * 답글이다. 깊이는 1 로 고정 — 답글을 부모로 가리키는 행은 서비스가 만들지 않는다.
 *
 * <p>⚠ {@code parent} 의 {@code @OnDelete(CASCADE)} 는 <b>DB 단 하드 삭제</b>(게시글 행 CASCADE 등)에서
 * 자식이 부모 삭제를 막지 않게 하는 것이지 앱 삭제의 연쇄가 아니다. 앱의 삭제·블라인드는 이 행의
 * {@code deletedAt}·{@code blinded} 만 바꾸고 답글에는 아무것도 전파하지 않는다 — "부모가 사라진 답글"은
 * 조회 시점에 부모를 자리 표식으로 표현해 해결한다(저장 상태가 아니다).
 *
 * <p>작성자 FK 에 {@code @OnDelete} 가 없는 이유는 {@link CommunityPost} 와 같다.
 */
@Entity
@Table(name = "community_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityComment parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount userAccount;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "like_count", nullable = false)
    @ColumnDefault("0")
    private long likeCount = 0L;

    @Column(name = "dislike_count", nullable = false)
    @ColumnDefault("0")
    private long dislikeCount = 0L;

    @Column(name = "blinded", columnDefinition = "TINYINT", nullable = false)
    private boolean blinded;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 호출자의 Clock 값 — @CreationTimestamp 를 안 쓰는 이유는 CommunityPost.createdAt 과 같다(컨테이너 UTC 와
    // Clock KST 의 혼재).
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 작성자가 마지막으로 수정한 시각, 미수정이면 null. @UpdateTimestamp 를 일부러 안 붙인 이유와 null 로 두는
    // 근거는 CommunityPost.updatedAt 과 같다(반응 카운터 변경이 "수정됨"으로 보이면 안 된다).
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    private CommunityComment(CommunityPost post, CommunityComment parent, UserAccount userAccount,
            String content, LocalDateTime createdAt) {
        // 카운터·blinded·deletedAt·updatedAt 은 @Builder 파라미터로 받지 않는다 — 항상 정해진 초기값에서 시작
        this.post = post;
        this.parent = parent;
        this.userAccount = userAccount;
        this.content = content;
        this.createdAt = createdAt;
        this.blinded = false;
    }

    public boolean isReply() {
        return parent != null;
    }

    /** {@code updatedAt} 을 쓰는 유일한 경로 — 이유는 {@link CommunityPost#edit} 와 같다. */
    public void edit(String content, LocalDateTime editedAt) {
        this.content = content;
        this.updatedAt = editedAt;
    }

    public void blind() {
        this.blinded = true;
    }

    // 이미 삭제된 댓글이면 no-op 으로 최초 삭제 시각을 보존한다(Chat.delete 와 동일)
    public void delete(LocalDateTime deletedAt) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 삭제되지도 블라인드되지도 않은 상태 — commentCount 가 세는 대상이자 목록에 본문이 실리는 조건. */
    public boolean isVisible() {
        return !isDeleted() && !blinded;
    }

    /** 락 요구는 {@link CommunityPost#adjustReaction(ReactionType, int)} 과 같다. */
    public void adjustReaction(ReactionType type, int delta) {
        if (type == ReactionType.LIKE) {
            this.likeCount = Math.max(0, this.likeCount + delta);
        } else {
            this.dislikeCount = Math.max(0, this.dislikeCount + delta);
        }
    }
}
