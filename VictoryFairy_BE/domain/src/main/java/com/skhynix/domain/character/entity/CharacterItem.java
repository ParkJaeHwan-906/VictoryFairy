package com.skhynix.domain.character.entity;

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
 * 캐릭터에 겹쳐 입히는 아이템의 카탈로그. 상점에 전시되는 것도, 사용자가 사서 착용하는 것도 이 행이다
 * (상점 상품 테이블과 아이템 테이블을 나누지 않는다 — 나누면 같은 아이템이 두 곳에서 따로 관리된다).
 */
@Entity
@Table(
        name = "character_items",
        // 시드(character-asset-init.sql)가 (캐릭터, 이름) 기준 anti-join 으로 재실행 안전을 확보한다.
        // 이 UNIQUE 가 없으면 기동할 때마다 카탈로그 23행이 통째로 한 벌씩 늘어난다.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_character_items_character_name", columnNames = {"character_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 아이템은 특정 캐릭터에 입히려고 만든 것이라 캐릭터가 사라지면 함께 사라져도 된다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Character character;

    /**
     * 부위. 컬럼명이 {@code item_type} 이 아니라 {@code item_type_id} 인 것은 이 저장소의 FK 규약이다
     * ({@code Quiz.quiz_type_id}·{@code Game.game_status_id} 와 같다).
     *
     * <p>CASCADE 를 걸지 않는다 — 부위는 아이템보다 오래 사는 코드 테이블이고, 부위 행이 실수로
     * 지워질 때 카탈로그가 통째로 딸려 나가면 안 된다. FK 가 그 삭제를 막는 것이 맞는 동작이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_type_id", nullable = false)
    private ItemType itemType;

    /** 상점·인벤토리에 그대로 노출되는 표시명(예 {@code 네이비 라인 유니폼}). */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /**
     * 상점 진열용 이미지 EP. {@link #usingImg} 와 <b>같은 그림이지만 좌표계가 다르다</b> — 진열용은
     * 아이템 단독으로 보이도록, 착용용은 캐릭터 위에 겹쳤을 때 맞도록 그려져 있다. 그래서 한 컬럼으로
     * 합칠 수 없다(합치면 상점에서는 아이템이 화면 밖으로 밀려나거나 캐릭터에서 어긋난다).
     */
    @Column(name = "display_img", length = 255, nullable = false)
    private String displayImg;

    /** 캐릭터에 겹쳐 그릴 때 쓰는 이미지 EP. {@link #displayImg} 주석 참고. */
    @Column(name = "using_img", length = 255, nullable = false)
    private String usingImg;

    /**
     * 구매 가격(포인트). 전 아이템 100 포인트로 시작한다(사용자 확정).
     *
     * <p>{@code int} 가 아니라 {@code long} 인 것은 {@code users_account.point} 와 타입을 맞추기 위해서다 —
     * 차감이 {@code point - price} 한 줄이라 타입이 갈리면 조용한 승격·축소가 끼어든다.
     */
    @Column(name = "price", nullable = false)
    private long price;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private CharacterItem(Character character, ItemType itemType, String name,
            String displayImg, String usingImg, long price) {
        this.character = character;
        this.itemType = itemType;
        this.name = name;
        this.displayImg = displayImg;
        this.usingImg = usingImg;
        this.price = price;
    }
}
