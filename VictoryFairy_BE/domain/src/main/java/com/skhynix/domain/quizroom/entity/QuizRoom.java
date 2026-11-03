package com.skhynix.domain.quizroom.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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

/**
 * 사용자가 만든 퀴즈 묶음(퀴즈방).
 *
 * <p>⚠ 오늘의 퀴즈({@code quizzes}) 테이블을 쓰지 않는 것이 이 엔티티 묶음의 존재 이유다. 같은 테이블에 넣으면
 * {@code quiz_date IS NULL} 미편성 풀로 보여 매일 편성이 {@code /today} 세트로 끌어가고 point·bq 까지 적립된다.
 *
 * <p>작성자 FK 에 {@code @OnDelete} 가 없는 것은 의도다 — 만료 데이터 정리가 계정을 지우기 전에 {@code (알수없음)}
 * 계정으로 이관해야 하며, 이관을 빠뜨린 삭제는 FK 위반으로 실패한다({@code community_posts} 와 같은 fail-closed).
 *
 * <p>{@code quizCount} 는 수정·삭제가 없어 생성 시점 값이 영구히 맞다. {@code likeCount} 는 추천 행 수의 비정규화이며
 * 목록마다 행을 세지 않으려고 둔다 — 일치는 방 행 락을 쥔 같은 트랜잭션에서 호출자가 지킨다. 주간 베스트는 이 카운터가 아니라
 * 그 주의 추천 행으로 센다(카운터에는 언제 받은 추천인지가 없다).
 */
@Entity
@Table(name = "quiz_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_rooms_author"))
    private UserAccount author;

    @Column(name = "title", length = 50, nullable = false)
    private String title;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "quiz_count", nullable = false)
    private int quizCount;

    @Column(name = "like_count", nullable = false)
    @ColumnDefault("0")
    private long likeCount = 0L;

    // 호출자의 Clock(KST) 값 — 파드 JVM 이 UTC 라 Hibernate 생성값은 응답 시각이 9시간 어긋난다(community 와 같은 이유)
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private QuizRoom(UserAccount author, String title, String description, int quizCount,
            LocalDateTime createdAt) {
        this.author = author;
        this.title = title;
        this.description = description;
        this.quizCount = quizCount;
        this.createdAt = createdAt;
    }

    public void increaseLikeCount() {
        this.likeCount++;
    }

    public void decreaseLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }
}
