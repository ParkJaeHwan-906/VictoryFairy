package com.skhynix.domain.character.entity;

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
 * 사용자가 보유한 캐릭터와 그중 무엇을 쓰고 있는지({@code active}).
 *
 * <p>테이블명이 아이템 쪽({@code user_character_items_inventory})과 복수형 위치가 다른 것은 사용자가
 * 확정한 스키마 그대로다({@code user_support_team}·{@code quiz_type} 과 같은 예외) — 맞춰서 고치지 말 것.
 *
 * <p><b>"한 계정에 켜진 캐릭터는 최대 하나"는 스키마 제약이 아니라 서비스 정책이다.</b> MySQL 에 부분
 * UNIQUE({@code WHERE active = 1})가 없어 표현할 수 없다. 아래 UNIQUE 는 <b>다른 것</b>을 막는다 —
 * 같은 계정이 같은 캐릭터를 두 번 보유하는 상태다.
 */
@Entity
@Table(
        name = "user_characters_inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_characters_inventory_account_character",
                columnNames = {"user_account_id", "character_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCharacterInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Character character;

    // TINYINT(1) 로 못 박는다 — 지정하지 않으면 Hibernate 가 MySQL 에 bit 로 만든다.
    @Column(name = "active", nullable = false, columnDefinition = "TINYINT(1)")
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserCharacterInventory(UserAccount userAccount, Character character, boolean active) {
        this.userAccount = userAccount;
        this.character = character;
        this.active = active;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
