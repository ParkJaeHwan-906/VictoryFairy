import type { ApiResponse } from './api';

/**
 * 구단별 채팅 API(game 모듈) 타입.
 *
 * 식별자 규칙(명세 기준):
 * - 채팅방은 UUID 문자열 `roomUid`로만 가리킨다(순차 PK 미노출).
 * - 메시지는 Long `id`로 가리키며, 전송 응답·히스토리·SSE 이벤트가 모두 같은 값을 싣는다.
 *   신고 API의 `messageId`가 이 값이다.
 * - 발신자는 `senderNickname`으로 가리킨다(계정 PK는 여전히 노출되지 않는다).
 *   2026-08-20부터 `profileImgUrl`(프로필 사진 EP)이 함께 실려 온다 — 계정을 식별하는
 *   값이 아니라 그 메시지를 그릴 때 쓰는 이미지 주소다.
 */

/**
 * 채팅방. 목록 조회 항목과 상세 조회 응답이 완전히 같은 모양이다.
 * 참여 인원(`participants`)은 노출하지 않기로 결정되어 응답에 없다(2026-08-01 명세).
 *
 * 2026-08-04 구단 접근 제어 이후 목록은 **요청자의 응원 구단 방만** 실려 온다 —
 * 다른 구단 방은 목록에도, 상세·구독·전송·히스토리·신고에도 접근할 수 없다(403).
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
  /**
   * 발신자 프로필 사진의 **EP**(BaseURL 을 뺀 오브젝트 키). 2026-08-20 신설.
   *
   * `MyProfile.profileImgUrl` 과 같은 성질의 값이다 — 완성된 URL 이 아니므로 화면에 쓰기 전
   * `toAssetUrl()` 로 도메인을 붙이고, `null` 이면 자리표시 이미지로 대신한다.
   *
   * **`null` 이 두 경우 모두를 뜻한다** — 사진을 올리지 않은 계정과, 탈퇴해서
   * `(알수없음)` 더미 계정으로 남은 메시지다. 화면에서 둘을 가를 방법도, 가를 이유도 없다
   * (어느 쪽이든 자리표시를 그린다).
   *
   * 이 값은 **메시지가 만들어진 시점이 아니라 응답을 만드는 시점의 계정 상태**다 —
   * 발신자가 사진을 바꾸면 히스토리를 다시 받을 때 옛 메시지의 사진도 함께 바뀐다.
   */
  profileImgUrl: string | null;
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
 * 래핑된 응답 별칭 — SSE를 뺀 6개 엔드포인트는 성공도 ApiResponse로 감싼다
 * (명시적 퇴장 `DELETE .../subscribe` 포함. 이쪽은 `data`가 항상 null이다)
 * ------------------------------------------------------------------ */

export type ChatRoomListApiResponse = ApiResponse<ChatRoom[]>;
export type ChatRoomApiResponse = ApiResponse<ChatRoom>;
export type ChatMessageApiResponse = ApiResponse<ChatMessage>;
export type ChatMessagePageApiResponse = ApiResponse<ChatMessagePage>;
