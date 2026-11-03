package com.skhynix.domain.quizroom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 베스트를 확정한 주 하나. 베스트가 0건인 주도 행이 생긴다 — 그래야 "확정했는데 비었다"와 "아직 확정 안 했다"가
 * 갈리고, 인기 목록이 빈 주를 건너뛰어 그 전 주의 베스트를 다시 보여 주지 않는다.
 *
 * <p>{@code uk_quiz_room_weeks_week_start} 가 다중 파드 동시 확정의 심판이다. 확정 트랜잭션은 이 행을 <b>계정 락보다 먼저</b>
 * 넣고 flush 하므로, 늦은 쪽은 아무 계정도 잠그지 않은 채 이 UNIQUE 에서 기다리다 중복으로 롤백된다 — 지급까지 통째로.
 * 분산 락(Redis SET NX)은 TTL·분단 시 겹칠 수 있어 point 중복 지급의 심판이 못 된다.
 */
@Entity
@Table(name = "quiz_room_weeks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_room_weeks_week_start", columnNames = "week_start")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomWeek {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 그 주의 월요일(KST). 주는 이 날 00:00 부터 다음 월요일 00:00 직전까지다. */
    @Column(name = "week_start", nullable = false, updatable = false)
    private LocalDate weekStart;

    // 호출자의 Clock(KST) 값 — QuizRoom.createdAt 과 같은 이유
    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private LocalDateTime confirmedAt;

    @Builder
    private QuizRoomWeek(LocalDate weekStart, LocalDateTime confirmedAt) {
        this.weekStart = weekStart;
        this.confirmedAt = confirmedAt;
    }
}
