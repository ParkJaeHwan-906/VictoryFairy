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

/**
 * 게시글에 붙은 이미지 1장. {@code endpoint} 는 확정 EP({@code community/{uuid}.{ext}})만 담는다 —
 * {@code temp/} 값이 여기 저장되는 일은 없어야 한다(임시 객체 정리가 "DB 를 안 본다"는 전제가 그것이다).
 *
 * <p>수정은 행을 지우고 다시 만든다(전체 교체). 기록성이라 {@code updated_at} 이 없다.
 */
@Entity
@Table(name = "community_post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityPost post;

    @Column(name = "endpoint", length = 255, nullable = false)
    private String endpoint;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // 호출자의 Clock 값(글 작성·수정 시각과 같은 값) — @CreationTimestamp 를 안 쓰는 이유는 CommunityPost.createdAt 참고
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private CommunityPostImage(CommunityPost post, String endpoint, int sortOrder, LocalDateTime createdAt) {
        this.post = post;
        this.endpoint = endpoint;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }
}
