package com.skhynix.domain.user.repository;

/**
 * 하드 삭제 대상으로 선정된 탈퇴 계정의 최소 정보 — 정리 작업이 실제로 쓰는 세 값뿐이다.
 *
 * <p>엔티티가 아니라 프로젝션인 이유는 {@link ActiveAccountView} 와 같다: 이 경로는 계정의 나머지
 * 컬럼(닉네임·이메일·비밀번호 해시)을 <b>단 하나도 읽지 않아야 한다.</b> 정리 로그에 개인정보를 남기지
 * 않는다는 계약이 있어, 애초에 읽지 않는 형태로 두는 편이 "실수로 로그에 찍는" 경로 자체를 없앤다.
 *
 * <ul>
 *   <li>{@code accountId} — 자식 데이터 이관·정리의 기준({@code users_account.id})</li>
 *   <li>{@code uid} — 실패 로그에 남기는 유일한 식별자. 내부 PK 를 로그에 남기지 않는 것은 토큰
 *       subject 규약과 같은 이유다</li>
 *   <li>{@code userId} — 실제로 지우는 부모 행({@code users.id}). 자식은 DB 의 CASCADE/SET NULL 이
 *       처리하므로 애플리케이션이 지우는 행은 이 하나뿐이다</li>
 * </ul>
 */
public record ExpiredAccountView(Long accountId, String uid, Long userId) {
}
