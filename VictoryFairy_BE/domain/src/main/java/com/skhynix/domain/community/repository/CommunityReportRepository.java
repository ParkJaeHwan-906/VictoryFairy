package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityReport;
import com.skhynix.domain.community.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityReportRepository extends JpaRepository<CommunityReport, Long> {

    // 재신고의 행 추가를 막는 1차 판정. 동시 재신고는 uk_community_reports_reporter_target 이 심판한다.
    boolean existsByReporter_IdAndTargetTypeAndTargetId(Long reporterId, ReportTargetType targetType,
            long targetId);
}
