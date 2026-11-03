package com.skhynix.domain.community.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * 커뮤니티 게시글.
 *
 * <p>카운터 네 개({@code view/like/dislike/comment_count})는 반응·댓글 행 수를 비정규화한 값이다 —
 * 좋아요 순·조회수 순 정렬과 인기 점수가 전체 게시글 대상 정렬이라 요청마다 GROUP BY 하면 비용이
 * 게시글 수×반응 수로 자란다. 대가로 <b>카운터와 행 수의 일치는 같은 트랜잭션 안에서 호출자가
 * 지켜야 한다</b>({@code quizzes} 가 카운터를 두지 않은 것과 반대 선택이며 그쪽은 그대로 맞다).
 *
 * <p>작성자 FK 에 {@code @OnDelete} 가 없는 것은 의도다 — 만료 데이터 정리가 계정을 지우기 전에
 * {@code (알수없음)} 계정으로 이관해야 하며, 이관을 빠뜨린 삭제는 조용히 글을 지우는 대신 FK 위반으로
 * 시끄럽게 실패한다({@code chatrooms.owner_account_id} 와 같은 fail-closed).
 */
// idx_community_posts_created_at: 인기 목록이 "최근 7일" 범위를 매 요청 스캔한다 — 캐시가 없어 이 범위
// 축소가 유일한 비용 통제다. 정렬 축(like_count·view_count)은 인덱스를 두지 않았다(튜닝 담당 판단).
@Entity
@Table(name = "community_posts", indexes = {
        @Index(name = "idx_community_posts_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // @OnDelete 없음 — 카테고리는 코드 테이블(GameStatus·QuizType 과 같은 정책)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CommunityCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount userAccount;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "view_count", nullable = false)
    @ColumnDefault("0")
    private long viewCount = 0L;

    @Column(name = "like_count", nullable = false)
    @ColumnDefault("0")
    private long likeCount = 0L;

    @Column(name = "dislike_count", nullable = false)
    @ColumnDefault("0")
    private long dislikeCount = 0L;

    @Column(name = "comment_count", nullable = false)
    @ColumnDefault("0")
    private long commentCount = 0L;

    @Column(name = "blinded", columnDefinition = "TINYINT", nullable = false)
    private boolean blinded;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * ⚠ domain 컨벤션("타임스탬프는 생성자 파라미터로 받지 않는다")에서 <b>일부러</b> 벗어난 자리다 — 커뮤니티
     * 8엔티티 전부 {@code @CreationTimestamp} 를 쓰지 않고 호출자가 {@code Clock}(KST) 값을 넘긴다. Hibernate 생성값은
     * JVM 기본 시간대라 컨테이너(UTC)에서는 {@code created_at} 이 UTC 로 찍히는데 수정·삭제·인기 7일 기준은
     * {@code Clock}(KST) 이라 같은 글의 createdAt 이 updatedAt 보다 9시간 앞서는 혼재가 생겼다(실기동 실측).
     * 파드 TZ 전역 변경은 보류 사안이라 커뮤니티의 시각 출처를 {@code Clock} 하나로 통일한 것이다.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * <b>작성자가 마지막으로 수정한 시각</b>. 한 번도 수정하지 않았으면 {@code null} 이다 — 응답의 {@code updatedAt}
     * 도 그대로 {@code null} 로 나간다(프론트는 "수정됨" 표시의 근거로 쓴다).
     *
     * <p>⚠ {@code @UpdateTimestamp} 를 <b>일부러 안 붙였다.</b> 반응(LIKE/DISLIKE)이 카운터를 바꾸면 이 행이 dirty 가
     * 되어 Hibernate 가 updated_at 을 갱신하는데, 그러면 남의 반응이 "작성자가 수정함"으로 보인다. 그래서 이 컬럼은
     * {@link #edit} 만 쓴다. 생성 시 {@code createdAt} 과 같은 값을 넣지 않는 것은 "미수정 = null"이 응답 계약
     * (프론트의 수정됨 판정)에 가장 정직해서다. 블라인드·소프트 삭제·카운터 조정은 이 값을 건드리지 않는다.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    private CommunityPost(CommunityCategory category, UserAccount userAccount, String title,
            String content, LocalDateTime createdAt) {
        // 카운터·blinded·deletedAt·updatedAt 은 @Builder 파라미터로 받지 않는다 — 항상 정해진 초기값에서 시작
        this.category = category;
        this.userAccount = userAccount;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
        this.blinded = false;
    }

    /**
     * 수정은 전체 교체다 — 카운터·작성자·createdAt 은 건드리지 않는다.
     *
     * <p>{@code editedAt}(호출자의 {@code Clock}) 이 {@code updatedAt} 을 쓰는 <b>유일한 경로</b>다. 제목·본문·카테고리가
     * 그대로이고 이미지(별도 테이블)만 바뀐 수정도 여기서 updatedAt 이 바뀌어 UPDATE 가 나간다.
     */
    public void edit(CommunityCategory category, String title, String content, LocalDateTime editedAt) {
        this.category = category;
        this.title = title;
        this.content = content;
        this.updatedAt = editedAt;
    }

    public void blind() {
        this.blinded = true;
    }

    // 이미 삭제된 글이면 no-op 으로 최초 삭제 시각을 보존한다(Chat.delete 와 동일)
    public void delete(LocalDateTime deletedAt) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 반응 카운터 조정. ⚠ 호출자는 이 행을 {@code PESSIMISTIC_WRITE} 로 잠근 뒤 불러야 한다 —
     * 잠금 없이 두 트랜잭션이 같은 값에서 각자 더하면 한쪽이 유실되고, 그 순간 카운터와 반응 행 수가
     * 어긋난다(되돌릴 길이 없다).
     */
    public void adjustReaction(ReactionType type, int delta) {
        if (type == ReactionType.LIKE) {
            this.likeCount = Math.max(0, this.likeCount + delta);
        } else {
            this.dislikeCount = Math.max(0, this.dislikeCount + delta);
        }
    }
}
