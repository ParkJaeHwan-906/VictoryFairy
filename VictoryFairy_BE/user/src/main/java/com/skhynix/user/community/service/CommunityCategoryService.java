package com.skhynix.user.community.service;

import com.skhynix.domain.community.repository.CommunityCategoryRepository;
import com.skhynix.user.community.dto.CategoryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 쓰기 경로 없음 — 카테고리는 시드 SQL 소유다(TeamService 와 같은 처지)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityCategoryService {

    private final CommunityCategoryRepository categoryRepository;

    public List<CategoryResponse> findAll() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
