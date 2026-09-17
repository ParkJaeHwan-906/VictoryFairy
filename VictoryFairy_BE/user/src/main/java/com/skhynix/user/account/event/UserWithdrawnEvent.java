package com.skhynix.user.account.event;

/**
 * 탈퇴가 <b>커밋된 뒤에</b> 처리해야 할 뒷정리를 알리는 이벤트.
 *
 * <p>탈퇴 트랜잭션 안에서 S3 를 부르지 않으려고 둔 경계다. 트랜잭션 안에 넣으면 ①계정 행을 만진
 * 트랜잭션이 외부 호출이 끝날 때까지 DB 커넥션을 쥐고 ②삭제 실패가 탈퇴를 되돌린다 — 둘 다
 * "탈퇴는 S3 응답에 좌우되지 않는다"는 계약과 정면으로 어긋난다.
 *
 * <p>계정 식별자(uid·id)를 싣지 않는 것은 의도다. 받는 쪽이 할 일은 객체 하나를 지우는 것뿐이고,
 * 식별자를 실으면 그 값이 로그로 흘러갈 여지가 생긴다.
 *
 * @param profileImgUrl 탈퇴 시점의 프로필 이미지 EP. 이미지가 없던 계정이면 {@code null} 이다
 *                      (컬럼은 비우지 않는다 — 값은 계정 행이 하드 삭제될 때 함께 사라진다)
 */
public record UserWithdrawnEvent(String profileImgUrl) {
}
