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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 확정된 주간 베스트의 한 순위이자 그 방의 보상 지급 기록. 확정 뒤에는 바뀌지 않는다 — 추천 취소·탈퇴·작성자 이관이 있어도
 * 순위와 지급을 다시 계산하거나 회수하지 않는다.
 *
 * <p>{@code rewardedAccount} 는 지급받은 계정이다. 지급하지 않은 순위(소유자가 탈퇴·{@code (알수없음)})는 NULL 이고
 * {@code rewardPoint} 가 0이다. 계정 FK 가 SET NULL 인 이유는 하드 삭제 뒤에도 순위가 남아야 해서다
 * (user 앱 {@code AccountFkDeleteRuleInspector} 가 회차마다 확인한다).
 */
// uk_..._week_room: (주, 방) 하나에 지급이 한 번뿐이라는 계약을 DB 가 직접 지킨다. 주 행 UNIQUE(QuizRoomWeek)가 먼저 막지만,
// 그 행 없이 순위만 넣는 경로가 생겨도 여기서 거절된다. uk_..._week_rank 는 같은 주에 서로 다른 확정 결과가 섞이지 않게 한다.
// 선행 컬럼이 week_id 라 week FK 자동 인덱스 자리를 대신한다. 이름은 infra/sql/migrate-quiz-room.sql 과 문자 그대로 같아야 한다.
@Entity
@Table(name = "quiz_room_weekly_bests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_room_weekly_bests_week_room", columnNames = {"week_id", "room_id"}),
        @UniqueConstraint(name = "uk_quiz_room_weekly_bests_week_rank", columnNames = {"week_id", "rank_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomWeeklyBest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "week_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_weekly_bests_week"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoomWeek week;

    /** 1부터 시작하는 순위. */
    @Column(name = "rank_no", columnDefinition = "TINYINT", nullable = false)
    private int rankNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_weekly_bests_room"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoom room;

    /** 확정 당시 주간 점수. 응답에는 싣지 않는다(기록용). */
    @Column(name = "score", nullable = false)
    private long score;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rewarded_account_id",
            foreignKey = @ForeignKey(name = "fk_quiz_room_weekly_bests_rewarded_account"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount rewardedAccount;

    @Column(name = "reward_point", nullable = false)
    private int rewardPoint;

    @Builder
    private QuizRoomWeeklyBest(QuizRoomWeek week, int rankNo, QuizRoom room, long score,
            UserAccount rewardedAccount, int rewardPoint) {
        this.week = week;
        this.rankNo = rankNo;
        this.room = room;
        this.score = score;
        this.rewardedAccount = rewardedAccount;
        this.rewardPoint = rewardPoint;
    }
}
