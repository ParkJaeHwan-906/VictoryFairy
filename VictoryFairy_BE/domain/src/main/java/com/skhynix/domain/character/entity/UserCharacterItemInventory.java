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
 * 사용자가 구매해 보유한 아이템과 그중 무엇을 착용 중인지({@code active}).
 *
 * <p><b>행의 존재 자체가 "구매했다"이다.</b> 별도의 구매 이력 테이블이 없으므로 이 행을 지우면 구매
 * 사실이 사라진다 — 착용 해제는 행 삭제가 아니라 {@code active} 를 끄는 것이다.
 *
 * <p>{@code UNIQUE(user_account_id, character_item_id)} 가 "하나의 아이템은 영구적으로 한 개만 구매한다"를
 * 스키마 수준에서 보장한다. 서비스의 중복 구매 검사는 사용자에게 409 를 돌려주기 위한 것이고, 이
 * 제약은 그 검사와 INSERT 사이를 파고드는 동시 요청까지 막는 마지막 방어선이다.
 *
 * <p><b>"한 계정은 같은 부위 아이템을 하나만 켠다"는 스키마 제약이 아니라 서비스 정책이다</b> — 부위는
 * 이 테이블이 아니라 {@code character_items.item_type_id} 에 있어 UNIQUE 로 표현할 수 없다.
 */
@Entity
@Table(
        name = "user_character_items_inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_character_items_inventory_account_item",
                columnNames = {"user_account_id", "character_item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCharacterItemInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 보유 기록은 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 된다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    // CASCADE — 카탈로그에서 내린 아이템의 보유 기록은 함께 사라져야 한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_item_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CharacterItem characterItem;

    /** 착용 중인가. TINYINT(1) 로 못 박는 이유는 {@link UserCharacterInventory#isActive()} 와 같다. */
    @Column(name = "active", nullable = false, columnDefinition = "TINYINT(1)")
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserCharacterItemInventory(UserAccount userAccount, CharacterItem characterItem,
            boolean active) {
        this.userAccount = userAccount;
        this.characterItem = characterItem;
        this.active = active;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
