package com.skhynix.user.character.dto;

/**
 * 토글 결과 — <b>요청 후의</b> 상태다. 요청만으로는 켜기인지 끄기인지 알 수 없어(서버가 아는 현재
 * 상태에 달렸다) 응답이 확정된 상태를 돌려준다.
 */
public record CharacterItemActiveResponse(Long characterItemId, boolean active) {
}
