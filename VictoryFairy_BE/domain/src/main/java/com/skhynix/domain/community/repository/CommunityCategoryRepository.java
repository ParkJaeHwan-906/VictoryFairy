package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityCategoryRepository extends JpaRepository<CommunityCategory, Long> {

    // 정렬은 DB 단독 수행 — team 은 응답에 id 만 실려 프록시를 깨우지 않으므로 fetch join 이 필요 없다
    List<CommunityCategory> findAllByOrderBySortOrderAsc();
}
