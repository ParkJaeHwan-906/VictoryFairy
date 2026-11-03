package com.skhynix.domain.community.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** (계정, 댓글·답글)당 한 행인 반응. 규칙은 {@link CommunityPostReaction} 과 같다. */
@Entity
@Table(name = "community_comment_reactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_community_comment_reactions_account_comment",
                columnNames = {"user_account_id", "comment_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCommentReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityComment comment;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "type", columnDefinition = "TINYINT", nullable = false)
    private ReactionType type;

    // 둘 다 호출자의 Clock 값 — 규칙은 CommunityPostReaction 과 같다
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private CommunityCommentReaction(UserAccount userAccount, CommunityComment comment,
            ReactionType type, LocalDateTime createdAt) {
        this.userAccount = userAccount;
        this.comment = comment;
        this.type = type;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void change(ReactionType type, LocalDateTime changedAt) {
        this.type = type;
        this.updatedAt = changedAt;
    }
}
