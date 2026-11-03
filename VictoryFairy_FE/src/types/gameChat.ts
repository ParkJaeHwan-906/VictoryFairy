import type { ApiResponse } from './api';
import type { GameState } from './game';

/**
 * 경기별 채팅 API(chat 모듈, `docs/game-chat.md`) 타입.
 *
 * 구단별 채팅(`types/chat.ts`, `/rt/chat/**`)과 병행 운영 중인 **별개의 API** 라
 * 이름이 겹치지 않게 전부 `GameChat` 접두사를 붙인다.
 *
 * 식별자 규칙:
 * - 채팅방은 경기 `gameId`(= `GET /games` 의 `gameId`, naver_game_id)로 가리킨다. 방 = 오늘 경기.
 * - 메시지는 `msgId`(Kafka 오프셋)로 가리킨다. **연속이 아니다** — 크기 비교에만 쓴다.
 */

/** 채팅방 = 오늘(KST) 경기 하나. 목록 항목과 상세 응답이 같은 모양(`RoomResponse`)이다. */
export interface GameChatRoom {
  /** naver_game_id. 이후 모든 경로의 `{gameId}` */
  gameId: string;
  homeTeam: string;
  homeTeamId: number;
  awayTeam: string;
  awayTeamId: number;
  /** 경기 시작 시각. 오프셋 없는 KST LocalDateTime(예: "2026-10-09T18:30:00") */
  gameDate: string;
  gameState: GameState;
}

/**
 * 메시지 1건(`ChatMessageView`). 히스토리 항목과 SSE `messages` 배열 원소가 같은 모양이다.
 * 발신자 정보(닉네임·구단·사진)는 전부 **전송 시점 스냅샷**이다.
 */
export interface GameChatMessage {
  /** Kafka 오프셋. 띄엄띄엄이며 크기 비교·중복 제거·신고 경로에 쓴다. */
  msgId: number;
  /** 마스킹 결과(금지어는 `*` 치환) */
  content: string;
  senderNickname: string;
  /** 발신자 응원 구단 코드(teams.code, 예 `HT`). 응원 구단이 없었으면 null. */
  teamCode: string | null;
  /** 프로필 이미지 EP. 화면에서 `toAssetUrl()` 을 거친다. null 이면 자리표시. */
  profileImgUrl: string | null;
  /** `+09:00` 오프셋 포함 ISO-8601(예: "2026-10-09T19:03:21.482+09:00") */
  sentAt: string;
}

/** POST /rooms/{gameId}/messages 요청 본문 */
export interface SendGameChatMessageRequest {
  /** 공백 불가, 최대 500자 */
  content: string;
  /** 메시지마다 새 UUID(36자). **재시도는 같은 값으로** 보내야 서버가 중복을 걸러 준다. */
  clientMsgId: string;
}

/**
 * 전송 202 응답. 발신자에게는 SSE 에코가 없으므로 이 `msgId`·`content` 로 직접 렌더한다.
 * `content` 는 마스킹된 결과라 입력값과 다를 수 있다.
 */
export interface SendGameChatMessageResult {
  gameId: string;
  msgId: number;
  content: string;
}

/** 히스토리 페이지(최신순 30건 고정). */
export interface GameChatMessagePage {
  /** 최신순. blind·차단 관계 발신자 메시지는 빠져 있어 30건보다 적을 수 있다. */
  messages: GameChatMessage[];
  /** 다음 요청의 `cursor`. 필터 이전 원본 기준이며 빈 페이지면 null. */
  nextCursor: number | null;
  /** 더 있는지는 `messages.length` 가 아니라 이 값만 본다. */
  hasNext: boolean;
}

/** SSE `event: deleted` 페이로드 — blind 된 메시지. 화면에서 제거한다. */
export interface GameChatDeletedEvent {
  msgId: number;
}

/** SSE `event: presence` 페이로드 — 방 접속 인원(고유 계정 수, 본인 포함). */
export interface GameChatPresenceEvent {
  count: number;
}

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — SSE 구독과 좋아요(202 무본문)를 뺀 나머지는 성공도 ApiResponse 로 감싼다
 * ------------------------------------------------------------------ */

export type GameChatRoomListApiResponse = ApiResponse<GameChatRoom[]>;
export type GameChatRoomApiResponse = ApiResponse<GameChatRoom>;
export type SendGameChatMessageApiResponse = ApiResponse<SendGameChatMessageResult>;
export type GameChatMessagePageApiResponse = ApiResponse<GameChatMessagePage>;
