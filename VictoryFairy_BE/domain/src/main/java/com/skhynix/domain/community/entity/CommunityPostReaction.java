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

/**
 * (계정, 게시글)당 한 행인 반응. 취소는 플래그가 아니라 <b>행 삭제</b>다 — 3상태(LIKE/DISLIKE/없음)에서
 * "없음"을 행 부재로 두는 쪽이 UNIQUE·SET NULL 과 맞고, 반응 이력을 읽는 곳이 없다({@code QuizLike} 가
 * 취소 행을 남기는 것과 다른 선택이며 그쪽은 그대로 맞다).
 *
 * <p>계정 FK 가 {@code SET NULL} 인 이유는 {@code QuizLike} 와 같다 — 계정이 하드 삭제돼도 집계
 * (게시글 카운터)는 보존돼야 하고, 더미 계정으로 이관하면 UNIQUE 때문에 탈퇴자 여럿의 같은 글 반응이
 * 충돌한다.
 */
// uk_community_post_reactions_account_post: 같은 계정의 동시 요청 2건이 둘 다 "행 없음"을 보고 INSERT 하는
// race 를 DB 가 심판한다. 선행 컬럼이 계정인 것은 기존 UNIQUE 들과의 일관성이다.
@Entity
@Table(name = "community_post_reactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_community_post_reactions_account_post",
                columnNames = {"user_account_id", "post_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityPost post;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "type", columnDefinition = "TINYINT", nullable = false)
    private ReactionType type;

    // 둘 다 호출자의 Clock 값 — Hibernate 생성값을 안 쓰는 이유는 CommunityPost.createdAt 참고. 생성 시 updatedAt 은
    // createdAt 과 같은 값(같은 변수라 µs 어긋남 없음)이고 change() 가 갱신한다.
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private CommunityPostReaction(UserAccount userAccount, CommunityPost post, ReactionType type,
            LocalDateTime createdAt) {
        this.userAccount = userAccount;
        this.post = post;
        this.type = type;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** LIKE↔DISLIKE 전환. 카운터 조정은 호출자가 같은 트랜잭션에서 한다. {@code changedAt} 은 호출자의 Clock 값. */
    public void change(ReactionType type, LocalDateTime changedAt) {
        this.type = type;
        this.updatedAt = changedAt;
    }
}
