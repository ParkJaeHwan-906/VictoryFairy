package com.skhynix.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 계정별 누적 획득 점수(BQ score)를 담는 엔티티. 요구사항: {@code docs/requirements/user/me-profile.md}.
 */
@Entity
@Table(name = "users_bq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    /**
     * 누적 획득 점수. {@code @ColumnDefault}(기존 행)와 자바 필드 초기값(신규 행)이 각각 다른 경로의
     * 0 보장을 맡는다 — 하나만 있으면 한쪽이 깨진다.
     */
    @Column(name = "bq_score", nullable = false)
    @ColumnDefault("0")
    private long bqScore = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserBq(UserAccount userAccount) {
        this.userAccount = userAccount;
        this.bqScore = 0L;
    }

    /**
     * 레이팅 점수 적립 — 퀴즈 정답 보상이 이 뮤테이터를 쓰는 유일한 경로다
     * ({@link UserAccount#addPoint(long)} 와 같은 계열의 다른 축).
     *
     * <p>⚠ 호출자는 반드시 {@code UserBqRepository.findWithLockByUserAccountId} 로 이 행을 잠근 뒤
     * 불러야 한다 — 일반 조회로 읽은 두 트랜잭션이 같은 누적치에서 각자 더하면 한쪽 적립이
     * 유실된다(lost update). 락 없는 조회 경로에 습관적으로 붙이지 말 것.
     *
     * <p>⚠ <b>락 순서</b>: 이 행을 잠그는 트랜잭션은 계정 행({@code findWithLockById})을 <b>먼저</b>
     * 잡는다. 두 축을 한 트랜잭션에서 적립하므로 순서가 뒤집히는 경로가 하나라도 생기면 데드락이
     * 난다 — 계정 행 락이 항상 앞이다.
     *
     * <p>0 이하는 아무것도 하지 않는다({@code updated_at} 도 건드리지 않는다) — 배점이 없거나
     * 0 인 문제를 맞힌 것은 "적립이 없다"는 뜻이지 오류가 아니다.
     */
    public void addBqScore(long delta) {
        if (delta <= 0) {
            return;
        }
        this.bqScore += delta;
    }
}


