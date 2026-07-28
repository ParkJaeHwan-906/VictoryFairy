package com.skhynix.domain.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "teams")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /**
     * KBO 구단 코드(LG, OB, SS …). py-collector 가 재실행해도 중복 없이 upsert 하기 위한
     * 소스 자연키(UNIQUE)로, 서비스 로직에서는 몰라도 된다.
     *
     * <p>이 {@code unique = true} 하나로 (code, id) 커버링 인덱스까지 끝난다 —
     * {@code @Table(indexes = ...)} 로 복합 인덱스를 따로 만들지 말 것. InnoDB 보조 인덱스는
     * 리프에 PK 값을 행 포인터로 항상 함께 저장하므로, 여기서 생기는 유니크 인덱스는 물리적으로
     * 이미 (code, id) 다. 즉 {@code SELECT id FROM teams WHERE code = ?} 는 클러스터드 인덱스를
     * 타지 않는 index-only scan 이다. 반대 방향(id → code)은 PK 클러스터드 인덱스에 전 컬럼이
     * 들어 있어 애초에 보조 인덱스가 필요 없다. (id, code) 복합 인덱스를 추가하면 완전히 중복된
     * 인덱스가 하나 더 생겨 쓰기 비용·저장 공간만 늘고 조회 이득은 0 이다.
     */
    @Column(name = "code", length = 4, unique = true)
    private String code;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Team(String name, String code) {
        this.name = name;
        this.code = code;
    }
}
