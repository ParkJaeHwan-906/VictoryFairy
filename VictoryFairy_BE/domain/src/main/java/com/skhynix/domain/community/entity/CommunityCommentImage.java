package com.skhynix.domain.community.entity;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 댓글·답글에 붙은 이미지 1장. 규칙은 {@link CommunityPostImage} 와 같다. */
@Entity
@Table(name = "community_comment_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCommentImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityComment comment;

    @Column(name = "endpoint", length = 255, nullable = false)
    private String endpoint;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // 호출자의 Clock 값 — @CreationTimestamp 를 안 쓰는 이유는 CommunityPost.createdAt 참고
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private CommunityCommentImage(CommunityComment comment, String endpoint, int sortOrder,
            LocalDateTime createdAt) {
        this.comment = comment;
        this.endpoint = endpoint;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }
}
