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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * (계정, 퀴즈방)당 한 행인 추천. 해제는 플래그가 아니라 <b>행 삭제</b>다(커뮤니티 반응과 같은 선택).
 * 추천 수는 {@code quiz_rooms.like_count} 카운터가 정본이고 이 행 수와 같은 트랜잭션에서 맞춘다.
 *
 * <p>계정 FK SET NULL — 하드 삭제 뒤에도 행이 남아 카운터와 행 수가 어긋나지 않는다.
 */
@Entity
@Table(name = "quiz_room_likes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_room_likes_account_room",
                columnNames = {"user_account_id", "room_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id",
            foreignKey = @ForeignKey(name = "fk_quiz_room_likes_account"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_likes_room"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoom room;

    // 호출자의 Clock(KST) 값. 주간 점수가 이 값을 KST 월요일 0시 경계와 비교한다 — @CreationTimestamp 는 JVM 기본 존
    // (파드 UTC)으로 찍혀 경계가 9시간 어긋난다
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private QuizRoomLike(UserAccount userAccount, QuizRoom room, LocalDateTime createdAt) {
        this.userAccount = userAccount;
        this.room = room;
        this.createdAt = createdAt;
    }
}
