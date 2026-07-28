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
 * 사용자가 특정 선수를 응원한다는 사실을 나타내는 엔티티. 구조는 {@link UserSupportTeam} 과 같고 대상만
 * 구단 대신 선수다.
 *
 * <p><b>테이블명이 단수({@code user_support_player})인 이유</b>: domain 의 다른 엔티티는 복수형 테이블명을
 * 쓰지만 이 테이블만 사용자가 단수형으로 확정한 예외다. 관례에 맞추려고 임의로 복수형으로 바꾸지 말 것.
 *
 * <p><b>응원 취소/재응원은 이력을 쌓지 않고 같은 행을 토글한다.</b> 그래서
 * {@code (user_account_id, player_id)} 조합에 UNIQUE 를 건다.
 *
 * <p><b>UNIQUE 에 {@code oppose} 를 넣지 않은 이유</b>: MySQL 에는 partial unique index 가 없어
 * "oppose 가 null 인 행만 유일" 제약을 표현할 수 없다. 재응원이 새 행이 아니라 같은 행의 재활성이므로
 * (계정, 선수) 조합만으로 충분하며, oppose 를 넣으면 오히려 같은 조합의 중복 행을 허용하게 된다.
 *
 * <p>구단과 달리 <b>선수는 복수 응원을 허용하고 상한도 없다</b> — 리포지토리가 {@code List} 를 반환하는 이유다.
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

    /**
     * 응원 주체 계정. 응원 기록은 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 되므로 CASCADE 다
     * ({@code Chat → UserAccount} 와 같은 기준).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    /**
     * 응원 대상 선수. 선수가 사라지면 그 선수를 응원한다는 기록도 남을 이유가 없는 종속 데이터라 CASCADE 다
     * ({@code Player → Team} 과 같은 기준).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    /**
     * 이 선수를 더 이상 응원하지 않게 된 시각. {@code null} 이면 현재 응원 중이다.
     */
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
        // oppose 와 타임스탬프는 @Builder 파라미터로 받지 않는다. oppose 는 null(응원 중)로 시작해
        // oppose()/support() 로만 전이한다 — 생성 시점부터 "응원 안 함"인 행을 만들 수 없게 한다.
        this.userAccount = userAccount;
        this.player = player;
    }

    /**
     * 이 선수에 대한 응원을 취소한다. 이미 취소된 상태면 아무것도 하지 않아 최초 취소 시각을 보존한다
     * ({@code UserAccount.withdraw}/{@code Chatroom.delete} 와 같은 패턴).
     *
     * @param oppose 취소 시각. 엔티티가 {@code now()}를 직접 읽지 않고 호출자에게서 받는다.
     */
    public void oppose(LocalDateTime oppose) {
        if (isOpposed()) {
            return;
        }
        this.oppose = oppose;
    }

    /**
     * 다시 응원한다. 이력 행을 새로 쌓지 않고 기존 행의 {@code oppose} 를 {@code null} 로 되돌려 재활성한다.
     */
    public void support() {
        this.oppose = null;
    }

    /**
     * 응원이 취소된 상태인지 여부. {@code oppose} 가 채워져 있으면 현재 응원하지 않는다.
     */
    public boolean isOpposed() {
        return oppose != null;
    }
}
