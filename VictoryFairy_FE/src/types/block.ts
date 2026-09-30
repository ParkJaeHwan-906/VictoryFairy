/**
 * 차단(block) 도메인 타입.
 *
 * 회원 간 차단 생성·조회 두 엔드포인트가 쓰는 타입이다(docs/block.md).
 * 차단 주체·조회 주체는 언제나 access 토큰 principal 본인이라 계정 식별자를 담는 필드가 없고,
 * 차단 대상은 어디서도 `id`·`uid`가 아니라 **닉네임**으로만 나타난다(채팅의 `senderNickname`,
 * 순위 응답과 같은 기조).
 */

/** POST /users/me/blocks 요청 본문 */
export interface BlockUserRequest {
  /** 차단할 대상의 현재 닉네임. 공백/빈 문자열이면 400 */
  targetNickname: string;
}

/**
 * 차단 대상 1건.
 *
 * 최초 차단·재요청(멱등) 모두 `POST` 응답이 이 모양이고, `GET` 목록도 이 항목의 배열이다.
 * 안정적 식별자(`id`·`uid`)가 없으므로 목록 렌더 key는 닉네임이나 배열 인덱스를 쓴다 —
 * 닉네임은 이 저장소에서 UNIQUE 제약이 아니라 중복될 수 있음에 유의(docs/block.md).
 */
export interface BlockedUser {
  /**
   * `POST` 응답에서는 서버가 차단 처리 시점에 다시 조회해 확인한 닉네임.
   * `GET` 목록에서는 차단 대상의 **현재** 닉네임(차단 이후 대상이 닉네임을 바꿨으면 바뀐 값).
   */
  nickname: string;
}
