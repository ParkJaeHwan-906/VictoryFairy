import type { AxiosResponse } from 'axios';
import { chatClient } from './httpClient';
import { CHAT_BASE_URL } from './config';
import { openSseSubscription, type SseLifecycleHandlers, type SseSubscription } from './sseSubscription';
import { ApiError } from './errors';
import { validateChatMessageContent } from './chat';
import type { ApiResponse } from '../types/api';
import type {
  GameChatDeletedEvent,
  GameChatMessage,
  GameChatMessagePage,
  GameChatRoom,
  SendGameChatMessageRequest,
  SendGameChatMessageResult,
} from '../types/gameChat';

/**
 * 경기별 채팅 API (chat 모듈, `docs/game-chat.md`).
 *
 * 구단별 채팅(`api/chat.ts`, `/rt/chat/**`)과 병행 운영 중인 **다른 서버**다. 방이 구단이 아니라
 * 오늘 경기(`gameId`)이고, 응원 구단 가드가 없다. base 가 `.../chat` 이므로 경로는 `/rooms` 부터 쓴다.
 *
 * 8개 엔드포인트 전부 인증 필수라 모두 `requiresAuth: true` — 토큰 주입·refresh 회전·에러
 * 정규화는 chatClient 인터셉터가 처리한다. 성공 응답은 SSE 구독과 좋아요(202 무본문)를 빼고
 * 모두 ApiResponse 로 감싸여 오므로 여기서 `data` 를 벗겨 반환한다. 실패는 전부 래퍼다.
 *
 * 방은 서버의 Redis 메타 기준으로 존재한다. 상세·구독·전송은 오늘 경기면 그 자리에서 방을
 * 만들지만, 히스토리·신고는 만들지 않는다 — 처음 들어갈 때는 상세나 구독을 먼저 부르는 게 안전하다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(성공 시 항상 존재). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/** 방 하위 경로. gameId 는 영숫자지만 방어적으로 인코딩한다. */
function roomPath(gameId: string, suffix = ''): string {
  return `/rooms/${encodeURIComponent(gameId)}${suffix}`;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/** 히스토리 페이지 크기. 서버 고정값이며 쿼리로 바꿀 수 없다. */
export const GAME_CHAT_MESSAGE_PAGE_SIZE = 30;

/** 메시지 길이 상한(서버 검증과 동일 기준). */
export const GAME_CHAT_MESSAGE_MAX_LENGTH = 500;

/**
 * 경기 채팅 도메인 실패 메시지.
 *
 * 404 가 방 없음/메시지 없음 두 가지라 문자열로 가른다. 409·429·503 은 이 API 에서 각각
 * 한 종류뿐이라 판별 함수는 상태 코드만 본다(인프라가 본문 없이 돌려준 503 도 같은 대응이다).
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const GAME_CHAT_ERROR_MESSAGE = {
  ROOM_NOT_FOUND: '존재하지 않는 채팅방입니다.',
  MESSAGE_NOT_FOUND: '존재하지 않는 메시지입니다.',
  SELF_REPORT: '자신의 메시지는 신고할 수 없습니다.',
  MESSAGE_IN_FLIGHT: '같은 메시지를 처리하고 있습니다. 잠시 후 다시 시도해 주세요.',
  RATE_LIMIT_EXCEEDED: '메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.',
  BROKER_UNAVAILABLE: '채팅 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.',
} as const;

/** 404 — 방 없음. 오늘 경기가 아니거나(지난 경기 포함) 없는 gameId. 목록으로 돌려보내는 신호. */
export function isGameChatRoomNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === GAME_CHAT_ERROR_MESSAGE.ROOM_NOT_FOUND
  );
}

/**
 * 404 — 신고하려는 메시지가 Stream 에 없음.
 * 전송 202 직후엔 적재가 늦어 이 에러가 날 수 있으니 짧게 재시도할 만하다.
 */
export function isGameChatMessageNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === GAME_CHAT_ERROR_MESSAGE.MESSAGE_NOT_FOUND
  );
}

/** 403 — 자기 메시지 신고. 이 API 의 403 은 이것뿐이다. */
export function isGameChatSelfReport(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/** 409 — 같은 clientMsgId 의 첫 요청이 아직 처리 중. 실패가 아니라 잠시 뒤 **같은 값으로** 재시도. */
export function isGameChatMessageInFlight(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/** 429 — 사용자당 초당 3건 초과(방 불문). 같은 clientMsgId 재요청도 계수된다. */
export function isGameChatRateLimited(error: unknown): boolean {
  return error instanceof ApiError && error.status === 429;
}

/** 503 — Kafka/Redis 일시 장애. 전송이면 **같은 clientMsgId 로** 재시도한다. */
export function isGameChatBrokerUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503;
}

/**
 * 전송 전 클라이언트 사전 검사. 위반 시 사유, 통과 시 null.
 * 규칙(공백 불가·500자)이 구단 채팅과 같아 그 검사를 그대로 쓴다.
 */
export function validateGameChatMessageContent(content: string): string | null {
  return validateChatMessageContent(content);
}

/**
 * 전송용 `clientMsgId`(UUID v4, 36자)를 만든다. 메시지마다 새로 만들고 재시도에는 재사용한다.
 *
 * 서버가 UUID 형식을 검사하므로 형식을 지켜야 한다. `crypto.randomUUID` 는 보안 컨텍스트
 * (https · localhost)에서만 있어, 없으면 `getRandomValues` 로 같은 형식을 만든다.
 */
export function createClientMsgId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx

  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/* ------------------------------------------------------------------ *
 * 채팅방 — 성공 시 ApiResponse 래핑
 * ------------------------------------------------------------------ */

/**
 * GET /rooms — 오늘(KST) 경기 = 오늘 채팅방. 경기 시각 오름차순.
 * 경기 없는 날은 빈 배열(200)이며 에러가 아니다. Redis 를 보지 않아 Redis 장애에도 200.
 * 에러: 401 UNAUTHENTICATED.
 */
export function getGameChatRooms(): Promise<GameChatRoom[]> {
  return chatClient
    .get<ApiResponse<GameChatRoom[]>>('/rooms', { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /rooms/{gameId} — 방 하나. 값은 경기 데이터의 현재 값이다.
 * 오늘 경기면 방 메타가 없어도 이때 만든다.
 * 에러: 404 ROOM_NOT_FOUND(오늘 경기가 아님), 503 BROKER_UNAVAILABLE, 401.
 */
export function getGameChatRoom(gameId: string): Promise<GameChatRoom> {
  return chatClient
    .get<ApiResponse<GameChatRoom>>(roomPath(gameId), { requiresAuth: true })
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 메시지 — 성공 시 ApiResponse 래핑
 * ------------------------------------------------------------------ */

/**
 * POST /rooms/{gameId}/messages — Kafka 에 넣고 **202** 로 `{gameId, msgId, content}` 를 준다.
 *
 * 발신자 본인에게는 SSE 에코가 없으므로 **이 응답으로 직접 렌더**한다. `content` 는 마스킹
 * 결과라 보낸 문자열과 다를 수 있으니 응답 값을 그린다.
 *
 * `clientMsgId` 는 `createClientMsgId()` 로 메시지마다 만들고, 409·503(·네트워크 오류)
 * 재시도에는 **같은 값을** 보낸다. 120초 안의 같은 값 재요청은 첫 202 본문을 그대로 돌려준다.
 *
 * 판정 순서: 404(방) → 400(본문, `fieldErrors.content`/`clientMsgId`) → 429 → 409/재반환 → 503 → 202.
 * 에러: 404 ROOM_NOT_FOUND, 400, 409 MESSAGE_IN_FLIGHT, 429 RATE_LIMIT_EXCEEDED,
 * 503 BROKER_UNAVAILABLE, 500(마스킹 필터 예외 — 원문 미전송), 401.
 */
export function sendGameChatMessage(
  gameId: string,
  body: SendGameChatMessageRequest,
): Promise<SendGameChatMessageResult> {
  return chatClient
    .post<ApiResponse<SendGameChatMessageResult>>(roomPath(gameId, '/messages'), body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /rooms/{gameId}/messages?cursor= — 최신순 30건 고정(최근 50,000건 창).
 *
 * @param cursor 이전 페이지의 `nextCursor`. 그 msgId 는 빼고 더 오래된 것부터 준다. 생략하면 최신부터.
 *
 * blind·차단 관계 발신자 메시지는 빠져 와서 30건보다 적을 수 있다 — 더 있는지는 `hasNext` 만 본다.
 * 202 직후의 내 메시지는 아직 없을 수 있다. 방 메타를 만들지 않으므로 처음 들어갈 땐 상세/구독을 먼저.
 * 에러: 404 ROOM_NOT_FOUND, 503 BROKER_UNAVAILABLE, 401.
 */
export function getGameChatMessages(
  gameId: string,
  cursor?: number | null,
): Promise<GameChatMessagePage> {
  return chatClient
    .get<ApiResponse<GameChatMessagePage>>(roomPath(gameId, '/messages'), {
      params: cursor != null ? { cursor } : undefined,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * POST /rooms/{gameId}/messages/{msgId}/report — 즉시 blind, 성공 시 200(data null).
 *
 * 멱등이라 재시도해도 안전하다. blind 되면 구독자에게 `deleted` 이벤트가 나가지만 신고자 화면에서도
 * 그 이벤트로 지워지는지에 기대지 말고 성공하면 직접 제거한다.
 *
 * 판정 순서: 404(방 메타) → 404(메시지) → 403(본인) → blind.
 * 에러: 404 ROOM_NOT_FOUND/MESSAGE_NOT_FOUND(202 직후면 적재 지연 — 짧게 재시도),
 * 403 SELF_REPORT, 503 BROKER_UNAVAILABLE, 401.
 */
export async function reportGameChatMessage(gameId: string, msgId: number): Promise<void> {
  await chatClient.post<ApiResponse<null>>(
    roomPath(gameId, `/messages/${msgId}/report`),
    undefined,
    { requiresAuth: true },
  );
}

/**
 * DELETE /rooms/{gameId}/subscribe — 이 방의 내 SSE 구독을 서버에서 끊는다(다른 파드 포함).
 *
 * 방 존재를 보지 않아 없는 방·구독 없음이어도 200 인 **전면 멱등**이다. 정의된 실패는 401뿐.
 * 부르지 않아도 30분 타임아웃이 회수하지만, 화면을 닫을 때 불러 준다.
 */
export async function leaveGameChatRoom(gameId: string): Promise<void> {
  await chatClient.delete<ApiResponse<null>>(roomPath(gameId, '/subscribe'), {
    requiresAuth: true,
  });
}

/**
 * POST /rooms/{gameId}/likes — 응원 애니메이션 신호. 성공은 **202 무본문**(ApiResponse 아님).
 *
 * **202 는 "받았다"일 뿐 "전달됐다"가 아니다** — 초당 10회 초과·응원 구단 없음·Redis 장애·
 * 없는 gameId 를 전부 조용히 버리고 202 를 준다. 그래서 응답으로 성공 여부를 판단하지 말고
 * 기다리지도 말 것(fire-and-forget). 애니메이션은 SSE `likes` 로 돌아오는 본인 좋아요로 재생한다
 * (클릭 시 로컬 재생까지 하면 두 번 재생된다).
 *
 * 저장·집계가 없어 개수를 알 수 없다. 에러: 400(gameId 형식, 영숫자 1~20자), 401.
 */
export async function likeGameChatRoom(gameId: string): Promise<void> {
  await chatClient.post(roomPath(gameId, '/likes'), undefined, { requiresAuth: true });
}

/* ------------------------------------------------------------------ *
 * 실시간 구독 (SSE) — ApiResponse 래핑 없음
 * ------------------------------------------------------------------ */

export interface GameChatSubscriptionHandlers extends SseLifecycleHandlers {
  /**
   * 새 메시지(msgId 오름차순). 본인이 보낸 메시지는 오지 않는다(에코 없음).
   * 이미 받은 msgId 이하는 이 계층에서 걸러 내므로 그대로 덧붙이면 된다.
   */
  onMessages: (messages: GameChatMessage[]) => void;
  /** 메시지가 blind 됐다 — 화면에서 제거. 끊긴 사이의 삭제는 재생되지 않는다(히스토리 재조회로 수렴). */
  onDeleted?: (msgId: number) => void;
  /** 화면을 비우고 히스토리를 다시 조회하라는 신호(복구 범위 초과·복구 중 오류). 이후 실시간 합류. */
  onReset?: () => void;
  /**
   * 응원 신호 — 구단 코드 배열(중복 없음, 순서 무의미, 개수 없음). 받을 때마다 든 구단의
   * 애니메이션을 재생한다. 본인 좋아요도 돌아온다. 재접속 복구는 없다.
   */
  onLikes?: (teamCodes: string[]) => void;
}

export interface GameChatSubscribeOptions {
  /**
   * 이미 화면에 그린 가장 큰 msgId(보통 첫 히스토리 페이지의 맨 앞). 주면 첫 연결부터
   * `Last-Event-ID` 로 실어, 히스토리 조회와 구독 사이에 온 메시지를 서버가 메워 준다.
   */
  lastMsgId?: number | null;
}

export type GameChatSubscription = SseSubscription;

function isGameChatMessage(value: unknown): value is GameChatMessage {
  const v = value as Partial<GameChatMessage> | null;
  return typeof v?.msgId === 'number' && typeof v?.content === 'string';
}

/** JSON 파싱. 깨진 프레임은 undefined. */
function parseJson(data: string): unknown {
  try {
    return JSON.parse(data) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * GET /rooms/{gameId}/subscribe — 경기 채팅방 SSE 스트림.
 *
 * fetch 로 열어 `Authorization` 헤더를 싣는다(연결·재발급·재연결은 `openSseSubscription`).
 * axios 를 타지 않는 경로라 여기서만 CHAT_BASE_URL 을 직접 붙인다.
 *
 * ── Last-Event-ID 복구 ───────────────────────────────────────────────
 * 구단 채팅과 달리 `messages` 프레임에 `id:`(배열 마지막 msgId)가 붙는다. 마지막으로 처리한
 * msgId 를 여기서 들고 있다가 재연결 때 `Last-Event-ID` 로 실어, 끊긴 사이 메시지를 서버가
 * 최대 2,500건까지 다시 보내게 한다. 그 이상이면 서버가 `reset` 을 보낸다(`onReset`).
 * 복구분과 실시간이 겹쳐 같은 메시지가 두 번 올 수 있어, **처리한 msgId 이하는 버린다**.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 404(방 없음)는 재시도해도 같아 `onError` 후 종료하고, 503·네트워크 오류·정상 종료(30분
 * 타임아웃)는 재연결한다. 같은 계정은 방 불문 last-one-wins 라 멀티탭이 서로를 끊는다.
 */
export function subscribeGameChatRoom(
  gameId: string,
  handlers: GameChatSubscriptionHandlers,
  options: GameChatSubscribeOptions = {},
): GameChatSubscription {
  const { onMessages, onDeleted, onReset, onLikes, ...lifecycle } = handlers;
  let lastMsgId: number | null = options.lastMsgId ?? null;

  return openSseSubscription({
    ...lifecycle,
    url: `${CHAT_BASE_URL}${roomPath(gameId, '/subscribe')}`,
    requiresAuth: true,
    getLastEventId: () => (lastMsgId !== null ? String(lastMsgId) : null),
    onFrame: (frame) => {
      switch (frame.event) {
        case 'messages': {
          const parsed = parseJson(frame.data);
          if (!Array.isArray(parsed)) return;

          const fresh = parsed
            .filter(isGameChatMessage)
            .filter((m) => lastMsgId === null || m.msgId > lastMsgId)
            .sort((a, b) => a.msgId - b.msgId);

          const frameId = frame.id !== undefined ? Number(frame.id) : NaN;
          const newest = Math.max(
            Number.isSafeInteger(frameId) ? frameId : -1,
            fresh.length > 0 ? fresh[fresh.length - 1].msgId : -1,
          );
          if (newest >= 0 && (lastMsgId === null || newest > lastMsgId)) lastMsgId = newest;

          if (fresh.length > 0) onMessages(fresh);
          return;
        }
        case 'deleted': {
          const parsed = parseJson(frame.data) as Partial<GameChatDeletedEvent> | undefined;
          if (typeof parsed?.msgId === 'number') onDeleted?.(parsed.msgId);
          return;
        }
        case 'reset':
          onReset?.();
          return;
        case 'likes': {
          const parsed = parseJson(frame.data);
          if (!Array.isArray(parsed)) return;
          const teamCodes = parsed.filter((code): code is string => typeof code === 'string');
          if (teamCodes.length > 0) onLikes?.(teamCodes);
          return;
        }
        default:
          // 알 수 없는 이벤트는 무시한다(서버가 이벤트를 늘려도 옛 프론트가 깨지지 않게).
          return;
      }
    },
  });
}
