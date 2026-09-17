package com.skhynix.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 사용자가 꾸미는 아바타 캐릭터.
 *
 * <p>⚠ 클래스 이름이 {@code java.lang.Character} 와 겹친다. 같은 패키지이거나 이 타입을 import 한
 * 파일에서는 {@code Character} 가 이 엔티티로 해석되므로, 그런 파일에서 문자 래퍼가 필요하면 완전
 * 수식해야 한다. 테이블명 단수형 규약({@code characters})을 따른 결과다.
 */
@Entity
@Table(
        name = "characters",
        uniqueConstraints = @UniqueConstraint(name = "uk_characters_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // UNIQUE 인 이유: 시드가 이 값 기준 anti-join 으로 재실행 안전을 확보하고, 지급 경로도 id 가 아니라
    // 이름으로 대상을 찾는다. 제약이 없으면 기동할 때마다 같은 캐릭터가 한 행씩 늘어난다.
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /**
     * 이미지의 <b>EP</b> — BaseURL 을 뺀 S3 오브젝트 키다({@code characters/victory-fairy.svg}).
     * 절대 URL 을 넣지 말 것 — 도메인·CDN 이 바뀌면 전 행을 UPDATE 해야 한다.
     */
    @Column(name = "img", length = 255, nullable = false)
    private String img;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Character(String name, String img) {
        this.name = name;
        this.img = img;
    }
}
