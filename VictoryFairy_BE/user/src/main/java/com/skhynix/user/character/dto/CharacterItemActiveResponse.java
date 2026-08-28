package com.skhynix.user.character.dto;

/**
 * 토글 결과 — 요청 후의 상태다. 토글은 요청만 보고는 결과를 알 수 없으므로(켜기인지 끄기인지는 서버가
 * 아는 현재 상태에 달렸다) 응답이 확정된 상태를 돌려준다.
 */
public record CharacterItemActiveResponse(Long characterItemId, boolean active) {
}
