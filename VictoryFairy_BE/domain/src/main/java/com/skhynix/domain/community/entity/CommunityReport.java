package com.skhynix.domain.community.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 신고 1건(누가·무엇을·언제). 채팅과 달리 보존한다 — 신고 1건으로 즉시 숨기는 정책에서 악의적 신고를
 * 가려낼 유일한 근거다. 읽는 API 는 없고 운영자가 DB 에서 직접 본다.
 *
 * <p>대상은 FK 가 아니라 ({@code targetType}, {@code targetId}) 쌍이다 — 게시글·댓글 두 테이블을 한
 * 컬럼으로 가리킬 수 없고, 대상이 소프트 삭제라 행이 사라지지도 않는다.
 */
// uk_community_reports_reporter_target: 같은 신고자의 같은 대상 재신고는 행을 더하지 않는다(멱등 200).
@Entity
@Table(name = "community_reports", uniqueConstraints = {
        @UniqueConstraint(name = "uk_community_reports_reporter_target",
                columnNames = {"reporter_account_id", "target_type", "target_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // SET NULL — 신고자가 하드 삭제돼도 블라인드의 근거(행)는 남긴다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_account_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount reporter;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "target_type", columnDefinition = "TINYINT", nullable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private long targetId;

    // 호출자의 Clock 값 — @CreationTimestamp 를 안 쓰는 이유는 CommunityPost.createdAt 참고
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private CommunityReport(UserAccount reporter, ReportTargetType targetType, long targetId,
            LocalDateTime createdAt) {
        this.reporter = reporter;
        this.targetType = targetType;
        this.targetId = targetId;
        this.createdAt = createdAt;
    }
}
