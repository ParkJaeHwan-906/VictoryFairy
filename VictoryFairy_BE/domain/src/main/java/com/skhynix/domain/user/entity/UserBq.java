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
}
