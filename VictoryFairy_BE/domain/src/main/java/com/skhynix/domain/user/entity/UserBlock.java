package com.skhynix.domain.user.entity;

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

/**
 * 회원 간 차단 관계 한 건 — {@code blocker}가 {@code blocked}를 차단했다는 사실만 기록하는
 * 기록성 엔티티다({@code docs/requirements/user/user-block.md} USER-BLK-19). 수정 시각·사유 등
 * 추가 컬럼은 없고, 차단 해제(unblock) API가 없는 이번 스코프에서는 한번 생긴 행이 지워지지도
 * 않는다(계정 하드 삭제로 인한 CASCADE 소멸 제외).
 *
 * <p>조회는 방향 그대로(blocker → blocked, 단방향)다. "A와 B가 서로 차단 관계인가"라는 양방향
 * 판정은 이 엔티티가 아니라 {@link com.skhynix.domain.user.repository.UserBlockRepository}가
 * {@code blocker_id = :id OR blocked_id = :id}로 양쪽을 합쳐 수행한다(USER-BLK-13) — 저장은
 * 단방향, 판정만 양방향인 것이 의도된 설계다.
 */
@Entity
@Table(name = "user_blocks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_blocks_blocker_blocked",
                columnNames = {"blocker_id", "blocked_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 만료 데이터 정리로 차단 주체 계정이 하드 삭제되면 그 계정이 관여한 차단 행도
    // 함께 사라져야 한다(USER-BLK-21). blocked 쪽과 마찬가지로 UserAccount를 가리키는 FK다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount blocker;

    // CASCADE — 차단 대상 계정이 하드 삭제되는 경우도 동일(USER-BLK-21).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount blocked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private UserBlock(UserAccount blocker, UserAccount blocked) {
        // createdAt은 @Builder 파라미터로 받지 않는다 — Hibernate가 자동으로 채운다.
        this.blocker = blocker;
        this.blocked = blocked;
    }
}
