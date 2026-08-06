package com.skhynix.domain.support.entity;

import com.skhynix.domain.player.entity.Player;
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
 * 사용자가 특정 선수를 응원한다는 사실을 나타내는 엔티티. 구조는 {@link UserSupportTeam}과 같고 대상만
 * 구단 대신 선수다(단수형 테이블명 예외, oppose 토글 설계, UNIQUE에 oppose 미포함 이유 모두 동일).
 *
 * <p>구단과 달리 선수는 복수 응원을 허용하고 상한도 없다 — 리포지토리가 {@code List}를 반환하는 이유다.
 */
@Entity
@Table(name = "user_support_player", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_support_player_account_player",
                columnNames = {"user_account_id", "player_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSupportPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 응원 기록은 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 됨
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    // CASCADE — 선수가 사라지면 그 선수를 응원한다는 기록도 함께 사라져야 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

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
    private UserSupportPlayer(UserAccount userAccount, Player player) {
        // oppose·타임스탬프는 @Builder 파라미터로 받지 않는다 — 생성 시점부터 정해진 초기값에서 시작
        this.userAccount = userAccount;
        this.player = player;
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
