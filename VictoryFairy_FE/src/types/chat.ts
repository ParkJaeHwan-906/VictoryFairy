import type { ApiResponse } from './api';

/**
 * 구단별 채팅 API(game 모듈) 타입.
 *
 * 식별자 규칙(명세 기준):
 * - 채팅방은 UUID 문자열 `roomUid`로만 가리킨다(순차 PK 미노출).
 * - 메시지는 Long `id`로 가리키며, 전송 응답·히스토리·SSE 이벤트가 모두 같은 값을 싣는다.
 *   신고 API의 `messageId`가 이 값이다.
 * - 발신자는 `senderNickname`으로만 노출된다(계정 PK 미노출).
 */

/**
 * 채팅방. 목록 조회 항목과 상세 조회 응답이 완전히 같은 모양이다.
 * 참여 인원(`participants`)은 노출하지 않기로 결정되어 응답에 없다(2026-08-01 명세).
 */
export interface ChatRoom {
  /** 방 외부 식별자(UUID) */
  roomUid: string;
  /** 구단(팀) 이름 */
  team: string;
  /** 방 이름 */
  name: string;
}

/** 메시지 1건. 전송(201) 응답과 히스토리 항목이 같은 모양이다. */
export interface ChatMessage {
  /** 메시지 식별자. 중복 렌더 제거·신고 호출에 그대로 쓴다. */
  id: number;
  content: string;
  senderNickname: string;
  /** LocalDateTime 문자열. 타임존 오프셋이 없다(예: "2026-07-27T21:15:03"). */
  createdAt: string;
}

/**
 * SSE `event: message` 페이로드.
 * 메시지 필드에 방 식별자가 추가된 형태이며, JSON 래핑(ApiResponse) 없이 내려온다.
 */
export interface ChatMessageEvent extends ChatMessage {
  roomUid: string;
}

/** POST /chat/rooms/{roomUid}/messages 요청 본문 */
export interface SendChatMessageRequest {
  /** 공백 불가, 최대 500자(이모지는 2자로 계산) */
  content: string;
}

/**
 * 히스토리 조회 결과 페이지.
 * 최신순 정렬이며, blind 처리·삭제된 메시지는 제외되어 내려온다.
 */
export interface ChatMessagePage {
  /** 현재 페이지의 메시지 목록(최신순) */
  content: ChatMessage[];
  /** 현재 페이지 번호(0부터) */
  page: number;
  /** 페이지 크기. 서버 고정값 30. */
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — SSE를 뺀 5개 엔드포인트는 성공도 ApiResponse로 감싼다
 * ------------------------------------------------------------------ */

export type ChatRoomListApiResponse = ApiResponse<ChatRoom[]>;
export type ChatRoomApiResponse = ApiResponse<ChatRoom>;
export type ChatMessageApiResponse = ApiResponse<ChatMessage>;
export type ChatMessagePageApiResponse = ApiResponse<ChatMessagePage>;
