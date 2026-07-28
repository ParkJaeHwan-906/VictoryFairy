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
 * <p><b>테이블명이 단수({@code user_support_team})인 이유</b>: domain 의 다른 엔티티는 모두 복수형
 * 테이블명({@code users_account}, {@code chatrooms} …)을 쓰지만 이 테이블만 사용자가 단수형으로 확정한
 * 예외다. 관례에 맞추려고 임의로 복수형으로 바꾸지 말 것.
 *
 * <p><b>응원 취소/재응원은 이력을 쌓지 않고 같은 행을 토글한다.</b> 취소 시 {@link #oppose(LocalDateTime)}
 * 로 {@code oppose} 시각을 채우고, 재응원 시 {@link #support()} 로 다시 {@code null} 로 되돌린다.
 * 그래서 {@code (user_account_id, team_id)} 조합에 UNIQUE 를 건다.
 *
 * <p><b>UNIQUE 에 {@code oppose} 를 넣지 않은 이유</b>: MySQL 에는 partial unique index 가 없어
 * "oppose 가 null 인 행만 유일" 같은 제약을 표현할 수 없다. 하지만 재응원이 <i>새 행 추가</i>가 아니라
 * <i>같은 행의 재활성</i>이므로 (계정, 구단) 조합 자체가 애초에 하나뿐이고, oppose 를 제약에 포함할
 * 필요가 없다. 포함하면 오히려 같은 조합의 이력 행이 여러 개 생기는 것을 허용해 버린다.
 *
 * <p><b>"한 사용자는 구단을 1개만 응원한다"는 스키마 제약이 아니다.</b> 그건 서비스 정책이라 DB 제약으로
 * 넣지 않았고(정책이 바뀌면 스키마 마이그레이션이 필요해진다), 대신 리포지토리 시그니처가
 * {@code Optional} 을 반환해 그 전제를 드러낸다.
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

    /**
     * 응원 주체 계정. 응원 기록은 계정에 완전히 종속된 데이터라 계정이 사라지면 함께 사라져도 되므로
     * CASCADE 다({@code Chat → UserAccount} 와 같은 기준). 공용 자원인 {@code Chatroom.owner} 와 달리
     * 이 행만 남아도 아무 의미가 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    /**
     * 응원 대상 구단. 구단은 마스터 데이터지만 "구단이 사라졌는데 그 구단을 응원한다는 기록"은 남을 이유가
     * 없는 종속 데이터라 CASCADE 다({@code Chatroom → Team} 과 같은 판단).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    /**
     * 이 구단을 더 이상 응원하지 않게 된 시각. {@code null} 이면 현재 응원 중이다.
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
    private UserSupportTeam(UserAccount userAccount, Team team) {
        // oppose 와 타임스탬프는 @Builder 파라미터로 받지 않는다. oppose 는 null(응원 중)로 시작해
        // oppose()/support() 로만 전이한다 — 생성 시점부터 "응원 안 함"인 행을 만들 수 없게 한다.
        this.userAccount = userAccount;
        this.team = team;
    }

    /**
     * 이 구단에 대한 응원을 취소한다. 이미 취소된 상태면 아무것도 하지 않아 최초 취소 시각을 보존한다
     * ({@code UserAccount.withdraw}/{@code Chatroom.delete} 와 같은 패턴).
     *
     * @param oppose 취소 시각. 같은 트랜잭션의 다른 작업과 시각을 맞출 수 있도록 엔티티가 {@code now()}를
     *               직접 읽지 않고 호출자에게서 받는다.
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
