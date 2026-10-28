package com.skhynix.domain.community.entity;

import com.skhynix.domain.team.entity.Team;
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

/**
 * 게시판 카테고리 코드 테이블. 행은 시드({@code infra/sql/community-categories-init.sql})가 만들고
 * 앱은 읽기만 한다.
 *
 * <p>구단 카테고리는 {@code team} 을 가리키고 자유게시판은 {@code null} 이다. {@code teams} 에
 * nullable FK 를 붙이는 대신 코드 테이블을 둔 이유는 자유게시판에 안정적인 id 가 필요하고,
 * 카테고리가 늘 때 코드가 아니라 시드 한 줄로 끝나야 해서다.
 */
// uk_community_categories_name: 시드가 name 으로 anti-join 하므로 동시 기동의 중복 INSERT 를 DB 가 막는다
// (quiz_type.uk_quiz_type_name 과 같은 역할).
@Entity
@Table(name = "community_categories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_community_categories_name", columnNames = {"name"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    // @OnDelete 없음 — 구단은 마스터 데이터라 삭제돼도 카테고리가 딸려 나가면 안 된다(Game → Team 과 같은 판단).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // ⚠ 커뮤니티 8엔티티는 @CreationTimestamp/@UpdateTimestamp 를 쓰지 않고 호출자의 Clock(KST) 값을 받는다 —
    //   Hibernate 생성값은 JVM 기본 시간대(컨테이너 UTC)라 Clock 으로 찍는 수정·삭제 시각과 9시간 어긋난다.
    //   행은 시드 SQL(NOW(6)) 이 만들고 앱은 읽기만 하므로 이 빌더 인자는 테스트·장래 코드용이다.
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private CommunityCategory(String name, Team team, int sortOrder, LocalDateTime createdAt) {
        this.name = name;
        this.team = team;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }
}
