package com.skhynix.domain.support.entity;

import com.skhynix.domain.team.entity.Team;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 사용자가 특정 구단을 응원한다는 사실을 나타내는 엔티티.
 *
 * <p>테이블명이 단수({@code user_support_team})인 것은 사용자가 확정한 예외다 — 복수형으로 바꾸지 말 것.
 *
 * <p>응원 취소/재응원은 이력을 쌓지 않고 같은 행의 {@code oppose}를 토글한다({@link #oppose(LocalDateTime)}
 * / {@link #support()}). UNIQUE({@code user_account_id, team_id})에 {@code oppose}를 넣지 않은 것은
 * 재응원이 새 행이 아닌 같은 행 재활성이기 때문이다 — 넣으면 오히려 같은 조합의 중복 행을 허용하게 된다.
 * "한 사용자는 구단을 1개만 응원한다"는 스키마 제약이 아니라 서비스 정책이며, 리포지토리가
 * {@code Optional}을 반환하는 것으로만 그 전제가 드러난다.
 */
@Entity
@Table(name = "user_support_team", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_support_team_account_team",
                columnNames = {"user_account_id", "team_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSupportTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 응원 기록은 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 됨
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    // CASCADE — 구단이 사라지면 그 구단을 응원한다는 기록도 함께 사라져야 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    // null이면 현재 응원 중
    @Column(name = "oppose")
    private LocalDateTime oppose;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserSupportTeam(UserAccount userAccount, Team team) {
        // oppose·타임스탬프는 @Builder 파라미터로 받지 않는다 — 생성 시점부터 정해진 초기값에서 시작
        this.userAccount = userAccount;
        this.team = team;
    }

    // 이미 취소된 상태면 no-op으로 최초 취소 시각을 보존한다
    public void oppose(LocalDateTime oppose) {
        if (isOpposed()) {
            return;
        }
        this.oppose = oppose;
    }

    public void support() {
        this.oppose = null;
    }

    public boolean isOpposed() {
        return oppose != null;
    }
}
