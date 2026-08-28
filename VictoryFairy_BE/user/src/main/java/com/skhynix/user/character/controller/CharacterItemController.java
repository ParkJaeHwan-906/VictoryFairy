package com.skhynix.user.character.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.character.dto.CharacterItemActiveResponse;
import com.skhynix.user.character.dto.CharacterItemPurchaseResponse;
import com.skhynix.user.character.dto.CharacterItemRequest;
import com.skhynix.user.character.dto.CharacterItemResponse;
import com.skhynix.user.character.service.CharacterItemService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캐릭터 아이템 상점·인벤토리.
 *
 * <p>세 경로 모두 {@code anyRequest().authenticated()} 에 자연히 걸린다 —
 * ⚠ SecurityConfig 에 permitAll 줄을 추가하면 그것이 버그다({@code /games/support} 선례).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/characters/items/**
@RequestMapping("/characters/items")
public class CharacterItemController {

    private final CharacterItemService characterItemService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CharacterItemResponse>>> findAll(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(characterItemService.findAll(userAccountId)));
    }

    // 한 번에 한 개만 산다 — 목록을 받지 않는 것이 의도다(부분 실패의 의미를 정의하지 않아도 된다).
    @PostMapping("/purchase")
    public ResponseEntity<ApiResponse<CharacterItemPurchaseResponse>> purchase(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody CharacterItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                characterItemService.purchase(userAccountId, request.characterItemId())));
    }

    // PUT 이지만 멱등이 아니다 — 같은 요청을 두 번 보내면 켜졌다 꺼진다(사용자가 확정한 계약).
    // 클라이언트는 응답의 active 로 결과 상태를 확인한다.
    @PutMapping("/active")
    public ResponseEntity<ApiResponse<CharacterItemActiveResponse>> toggleActive(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody CharacterItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                characterItemService.toggleActive(userAccountId, request.characterItemId())));
    }
}
