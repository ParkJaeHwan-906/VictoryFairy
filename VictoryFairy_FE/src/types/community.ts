/**
 * 라운지(커뮤니티) 화면이 그리는 데이터의 모양.
 *
 * 아직 연결된 API 가 없어 `src/data` 의 더미 데이터가 이 타입을 채운다.
 * 서버 연결 시 api-agent 가 응답 타입을 여기에 맞추거나, 이 타입을 응답 타입으로 교체한다.
 */

/** 승리요정 랭킹 1줄(시상대 1~3위와 목록 행이 같은 모양이다). */
export interface RankingEntry {
  /** 목록 key 로 쓰는 고정 식별자 */
  id: number;
  /** 순위. 1부터 시작한다. */
  rank: number;
  nickname: string;
  /** 누적 포인트. 화면에는 `1500p` 처럼 표시한다. */
  point: number;
  /**
   * 프로필 사진 URL. 없으면 자리표시 아바타를 그린다.
   * (디자인의 사진 아바타는 사용자가 올린 이미지이므로 더미에는 담지 않는다.)
   */
  avatarUrl?: string;
}

/**
 * 라운지 채팅 말풍선 1건.
 *
 * `ChatMessage`(src/types/chat.ts, 구단 채팅 API)와 필드 이름을 맞춰 두었다.
 * 실제 연결 시엔 `isMine` 만 로그인 사용자 닉네임과 비교해 채우면 된다.
 */
export interface LoungeChatMessage {
  id: number;
  senderNickname: string;
  content: string;
  /** LocalDateTime 문자열(타임존 오프셋 없음). 예: "2026-08-03T19:32:00" */
  createdAt: string;
  /** 내가 보낸 메시지인지. true 면 오른쪽 정렬 + 주황 말풍선. */
  isMine: boolean;
  /** 프로필 사진 URL. 없으면 자리표시 아바타를 그린다. */
  avatarUrl?: string;
}
