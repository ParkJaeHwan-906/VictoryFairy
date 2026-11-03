package com.skhynix.user.community.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.community.dto.CategoryResponse;
import com.skhynix.user.community.service.CommunityCategoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 전 경로는 회원 전용이다 — {@code anyRequest().authenticated()} 에 자연히 걸린다.
 * ⚠ SecurityConfig 에 {@code /community} permitAll 줄을 추가하면 그것이 버그다({@code /games/support} 선례).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/community/categories
@RequestMapping("/community/categories")
public class CommunityCategoryController {

    private final CommunityCategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.ok(categoryService.findAll()));
    }
}
