package com.skhynix.user.profileimage.dto;

/**
 * 업로드 응답 — 값은 <b>EP</b>(BaseURL 없는 오브젝트 키)다.
 *
 * <p>필드 이름이 {@code profileImgUrl} 인데 값이 URL 이 아닌 것은 가입 요청·{@code /me} 응답과 키
 * 이름을 맞춘 결과다. 세 곳이 같은 이름으로 같은 형태의 값을 주고받아야 클라이언트가 한 가지 조립
 * 규칙({@code https://victoryfairy.com/} + 값)만 알면 된다.
 */
public record ProfileImageResponse(String profileImgUrl) {
}
