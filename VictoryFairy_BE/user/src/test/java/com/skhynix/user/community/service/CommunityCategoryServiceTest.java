package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.skhynix.domain.community.entity.CommunityCategory;
import com.skhynix.domain.community.repository.CommunityCategoryRepository;
import com.skhynix.user.community.dto.CategoryResponse;
import com.skhynix.user.community.support.CommunityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link CommunityCategoryService} - sort_order 순 조회 위임과 teamId 매핑. USER-CM-11. */
@ExtendWith(MockitoExtension.class)
class CommunityCategoryServiceTest {

    @Mock
    private CommunityCategoryRepository categoryRepository;

    @InjectMocks
    private CommunityCategoryService service;

    @Test
    @DisplayName("[AC-CM-11-1, AC-CM-11-3] 구단 카테고리는 teamId를 싣고 자유게시판은 teamId=null이며 리포지토리 순서(sort_order 오름차순)를 그대로 지킨다")
    void findAll_mapsTeamIdAndKeepsRepositoryOrder() {
        CommunityCategory lg = CommunityFixtures.teamCategory(1L, "LG 트윈스", 7L);
        CommunityCategory free = CommunityFixtures.category(11L, "자유게시판");
        given(categoryRepository.findAllByOrderBySortOrderAsc()).willReturn(List.of(lg, free));

        List<CategoryResponse> result = service.findAll();

        assertThat(result).containsExactly(
                new CategoryResponse(1L, "LG 트윈스", 7L),
                new CategoryResponse(11L, "자유게시판", null));
    }

    @Test
    @DisplayName("카테고리가 하나도 없으면 null이 아닌 빈 리스트다")
    void findAll_empty() {
        given(categoryRepository.findAllByOrderBySortOrderAsc()).willReturn(List.of());

        assertThat(service.findAll()).isNotNull().isEmpty();
    }
}
