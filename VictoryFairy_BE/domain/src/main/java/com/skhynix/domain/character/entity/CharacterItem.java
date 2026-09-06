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
 * — 상점 상품 테이블을 따로 두면 같은 아이템이 두 곳에서 따로 관리된다.
 */
@Entity
@Table(
        name = "character_items",
        // 시드가 (캐릭터, 이름) 기준 anti-join 으로 재실행 안전을 확보한다. 없으면 기동할 때마다
        // 카탈로그가 통째로 한 벌씩 늘어난다.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_character_items_character_name", columnNames = {"character_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Character character;

    /**
     * 부위. <b>CASCADE 를 걸지 않는다</b> — 부위는 아이템보다 오래 사는 코드 테이블이라, 부위 행이
     * 실수로 지워질 때 카탈로그가 통째로 딸려 나가면 안 된다(FK 가 그 삭제를 막는 것이 맞는 동작이다).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_type_id", nullable = false)
    private ItemType itemType;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /**
     * 상점 진열용 이미지 EP. {@link #usingImg} 와 <b>같은 그림이지만 좌표계가 다르다</b> — 진열용은
     * 아이템 단독(80x80), 착용용은 캐릭터에 겹쳤을 때 맞도록(160x200) 그려져 있다. 그래서 한 컬럼으로
     * 합칠 수 없다.
     */
    @Column(name = "display_img", length = 255, nullable = false)
    private String displayImg;

    /** 캐릭터에 겹쳐 그릴 때 쓰는 이미지 EP. {@link #displayImg} 주석 참고. */
    @Column(name = "using_img", length = 255, nullable = false)
    private String usingImg;

    // long 인 이유: users_account.point 와 타입을 맞춰야 차감(point - price)에 조용한 승격·축소가
    // 끼어들지 않는다.
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
