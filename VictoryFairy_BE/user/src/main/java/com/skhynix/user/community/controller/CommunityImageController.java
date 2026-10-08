package com.skhynix.user.community.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.community.dto.CommunityImageResponse;
import com.skhynix.user.community.service.CommunityImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 선업로드 — 파일 1개를 {@code temp/} 에 올리고 EP 를 돌려준다. 5장이면 5회 호출이다.
 * 파트는 {@code required=false} 로 받아 "없음"과 "이름이 다름"을 같은 400 으로 만든다(프로필과 동일).
 * {@code appId} 는 받지 않는다 — 한도는 비인증 경로만의 장치다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/community/images
@RequestMapping("/community/images")
public class CommunityImageController {

    private final CommunityImageService imageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CommunityImageResponse>> upload(
            @RequestPart(name = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(ApiResponse.ok(imageService.upload(image)));
    }
}
