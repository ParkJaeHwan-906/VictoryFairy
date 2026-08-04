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
     * KBO 구단 코드(LG, OB, SS …) — py-collector가 upsert 판별에 쓰는 소스 자연키(UNIQUE).
     *
     * <p>InnoDB 보조 인덱스는 리프에 PK를 항상 함께 저장하므로 이 unique 인덱스는 이미 물리적으로
     * (code, id)다 — {@code @Table(indexes = ...)}로 (id, code) 복합 인덱스를 따로 만들지 말 것
     * (완전 중복이라 쓰기 비용만 늘고 조회 이득은 없다).
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
