import type { AxiosResponse } from 'axios';
import { gameClient, rotateTokens } from './httpClient';
import { GAME_BASE_URL } from './config';
import { getTokenStorage } from './tokenStorage';
import { consumeEventStream } from './eventStream';
import { ApiError, AUTH_ERROR_MESSAGE, apiErrorFromResponse, networkError } from './errors';
import type { ApiErrorResponse, ApiResponse } from '../types/api';
import type {
  ChatMessage,
  ChatMessageEvent,
  ChatMessagePage,
  ChatRoom,
  SendChatMessageRequest,
} from '../types/chat';

/**
 * 구단별 채팅 API (game 모듈).
 *
 * 6개 엔드포인트 전부 인증이 필수이므로 모두 `requiresAuth: true`로 보낸다 —
 * 토큰 주입·refresh 회전·에러 정규화는 gameClient 인터셉터가 처리한다.
 * 경로는 GAME_BASE_URL 기준 상대 경로다(`/chat/rooms` → `.../rt/chat/rooms`).
 *
 * SSE 구독만 axios가 아닌 fetch로 연다(스트림 처리 필요). 이때만 절대 URL이 필요하다.
 *
 * 성공 응답은 SSE를 제외하고 모두 ApiResponse로 감싸여 오므로 여기서 `data`를 벗겨 반환한다.
 * 실패는 인터셉터가 ApiError로 정규화해 reject한다.
 */

/** ApiResponse로 감싸인 성공 응답의 `data`를 벗겨낸다(성공 시 항상 존재). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/** 방 하위 경로. roomUid는 UUID지만 방어적으로 인코딩한다. */
function roomPath(roomUid: string, suffix = ''): string {
  return `/chat/rooms/${encodeURIComponent(roomUid)}${suffix}`;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/** 히스토리 페이지 크기. 서버 고정값이며 쿼리로 바꿀 수 없다. */
export const CHAT_MESSAGE_PAGE_SIZE = 30;

/** 메시지 길이 상한(서버 검증과 동일 기준). */
export const CHAT_MESSAGE_MAX_LENGTH = 500;

/**
 * 채팅 도메인 실패 메시지.
 * 방 없음/메시지 없음은 둘 다 404라 상태 코드만으로는 구분되지 않으므로 문자열로 판별한다.
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const CHAT_ERROR_MESSAGE = {
  ROOM_NOT_FOUND: '채팅방을 찾을 수 없습니다.',
  MESSAGE_NOT_FOUND: '메시지를 찾을 수 없습니다.',
  SELF_REPORT: '자신의 메시지는 신고할 수 없습니다.',
  INVALID_INPUT: '입력값이 올바르지 않습니다.',
} as const;

/** 404 — 존재하지 않거나 삭제된 방. 목록으로 돌려보내는 신호. */
export function isChatRoomNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === CHAT_ERROR_MESSAGE.ROOM_NOT_FOUND
  );
}

/** 404 — 그 방에 없는 messageId이거나 삭제된 메시지. */
export function isChatMessageNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === CHAT_ERROR_MESSAGE.MESSAGE_NOT_FOUND
  );
}

/** 403 — 자기 메시지 신고 시도. */
export function isSelfReport(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/**
 * 전송 전 클라이언트 사전 검사. 위반 시 사유, 통과 시 null.
 * 서버 검증의 대체가 아니라 왕복을 아끼기 위한 것이다.
 *
 * 서버는 Java `String.length()` 기준으로 세어 이모지를 2자로 계산하는데,
 * JS `String.length`도 UTF-16 코드 유닛 수라 같은 값이 나온다.
 */
export function validateChatMessageContent(content: string): string | null {
  if (content.trim().length === 0) return '공백일 수 없습니다';
  if (content.length > CHAT_MESSAGE_MAX_LENGTH) {
    return `메시지는 ${CHAT_MESSAGE_MAX_LENGTH}자를 넘을 수 없습니다`;
  }
  return null;
}

/* ------------------------------------------------------------------ *
 * 채팅방 — 성공 시 ApiResponse 래핑
 * ------------------------------------------------------------------ */

/**
 * GET /chat/rooms — 삭제되지 않은 전체 방 목록.
 * 방이 없으면 빈 배열(200)이며 에러가 아니다. 에러: 401 UNAUTHENTICATED.
 */
export function getChatRooms(): Promise<ChatRoom[]> {
  return gameClient
    .get<ApiResponse<ChatRoom[]>>('/chat/rooms', { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /chat/rooms/{roomUid} — 방 하나. 응답 모양은 목록의 항목 하나와 동일하다.
 * 삭제된 방도 404로 응답한다. 에러: 404 ROOM_NOT_FOUND, 401 UNAUTHENTICATED.
 */
export function getChatRoom(roomUid: string): Promise<ChatRoom> {
  return gameClient
    .get<ApiResponse<ChatRoom>>(roomPath(roomUid), { requiresAuth: true })
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 메시지 — 성공 시 ApiResponse 래핑
 * ------------------------------------------------------------------ */

/**
 * POST /chat/rooms/{roomUid}/messages — 저장 후 201로 저장된 메시지를 돌려준다.
 *
 * 발신자는 자기 메시지를 SSE로 다시 받지 않으므로(에코 없음) **이 응답으로 직접 렌더**해야 한다.
 * 반환된 `id`는 신고 API의 messageId이자 SSE 중복 제거 키다.
 *
 * 입력 검증이 방 존재 확인보다 먼저라, 없는 방에 빈 내용을 보내면 404가 아니라 400이 난다.
 * 에러: 400(fieldErrors.content), 404 ROOM_NOT_FOUND, 401 UNAUTHENTICATED.
 */
export function sendChatMessage(
  roomUid: string,
  body: SendChatMessageRequest,
): Promise<ChatMessage> {
  return gameClient
    .post<ApiResponse<ChatMessage>>(roomPath(roomUid, '/messages'), body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /chat/rooms/{roomUid}/messages?page=N — 최신순 페이징(크기 30 고정).
 * blind 처리·삭제된 메시지는 제외된다. 범위 밖 페이지도 에러가 아니라 빈 목록(200)이다.
 *
 * SSE 재연결 후 누락 구간 복구에도 이 API를 쓴다 — 서버가 `id:` 프레임을 보내지 않아
 * Last-Event-ID 복구가 불가능하기 때문이다. 받아온 뒤 `id`로 이미 그린 메시지를 걸러낸다.
 *
 * 에러: 404 ROOM_NOT_FOUND, 401 UNAUTHENTICATED.
 */
export function getChatMessages(roomUid: string, page = 0): Promise<ChatMessagePage> {
  return gameClient
    .get<ApiResponse<ChatMessagePage>>(roomPath(roomUid, '/messages'), {
      params: { page },
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * POST /chat/rooms/{roomUid}/messages/{messageId}/report — 본문 없음, 성공 시 200.
 *
 * 관리자 개입 없이 즉시 blind 처리되어 이후 히스토리에서 제외된다.
 * 이미 blind된 메시지를 다시 신고해도 성공하는 멱등 동작이므로 재시도해도 안전하다.
 * 다만 이미 화면에 그려진 메시지는 서버가 지워주지 않으니 클라이언트가 직접 제거해야 한다.
 *
 * 검증 순서: 방 존재 → 메시지 존재 → 삭제 여부 → 자기 신고 여부 → blind 적용.
 * 에러: 403 SELF_REPORT, 404 ROOM_NOT_FOUND/MESSAGE_NOT_FOUND, 401 UNAUTHENTICATED.
 */
export async function reportChatMessage(roomUid: string, messageId: number): Promise<void> {
  await gameClient.post<ApiResponse<null>>(
    roomPath(roomUid, `/messages/${messageId}/report`),
    undefined,
    { requiresAuth: true },
  );
}

/* ------------------------------------------------------------------ *
 * 실시간 구독 (SSE) — 유일하게 ApiResponse 래핑이 없는 엔드포인트
 * ------------------------------------------------------------------ */

/** 재연결 백오프: 1s → 2s → 4s … 최대 30s. */
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 30_000;

export interface ChatSubscriptionHandlers {
  /** 새 메시지 도착. 발신자 본인의 메시지는 오지 않는다(에코 없음). */
  onMessage: (event: ChatMessageEvent) => void;
  /**
   * 스트림이 열릴 때마다 호출.
   * `reconnected: true`면 끊겼다 다시 붙은 것이므로, 히스토리를 다시 조회해
   * 끊긴 동안의 공백을 메우고 `id`로 중복을 걸러야 한다.
   */
  onOpen?: (info: { reconnected: boolean }) => void;
  /** 복구 불가 에러(방 없음·인증 실패 등). 호출 시점에 구독은 이미 종료돼 있다. */
  onError?: (error: ApiError) => void;
  /** 일시적 끊김으로 재연결을 예약했을 때. */
  onReconnecting?: (info: { attempt: number; delayMs: number }) => void;
  /** 기본 true. false면 스트림이 끊겨도 재연결하지 않고 종료한다. */
  autoReconnect?: boolean;
}

export interface ChatSubscription {
  /** 구독 종료. 화면 언마운트 시 반드시 호출한다(연결 누수 방지). */
  close(): void;
}

/** 중단 가능한 지연. abort되면 남은 대기를 건너뛰고 즉시 resolve한다. */
function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const onAbort = () => {
      clearTimeout(timer);
      resolve();
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/** 실패 응답 본문을 ApiResponse로 읽는다. 본문이 없거나 JSON이 아니면 null. */
async function readErrorBody(response: Response): Promise<ApiErrorResponse | null> {
  try {
    return (await response.json()) as ApiErrorResponse;
  } catch {
    return null;
  }
}

/** SSE data(JSON 문자열)를 메시지 이벤트로 파싱한다. 깨진 프레임은 버린다. */
function parseMessageEvent(data: string): ChatMessageEvent | null {
  try {
    const parsed = JSON.parse(data) as Partial<ChatMessageEvent>;
    if (typeof parsed?.id !== 'number' || typeof parsed?.content !== 'string') return null;
    return parsed as ChatMessageEvent;
  } catch {
    return null;
  }
}

/**
 * GET /chat/rooms/{roomUid}/subscribe — 방의 새 메시지를 받는 SSE 스트림.
 *
 * 표준 `EventSource`는 헤더를 실을 수 없고 이 API는 쿼리·쿠키 토큰을 지원하지 않으므로
 * fetch로 열어 `Authorization` 헤더를 유지한다(→ `consumeEventStream`이 프레임을 파싱).
 * axios를 타지 않는 유일한 엔드포인트라, 여기서만 GAME_BASE_URL을 직접 붙인다.
 *
 * 연결은 30분 뒤 서버가 닫고 15초마다 `:ping` 하트비트가 온다. 스트림이 끊기면
 * 지수 백오프로 자동 재연결하며, `onOpen({ reconnected: true })`에서 히스토리를 다시
 * 조회해 공백을 메우는 것은 호출자 몫이다(서버가 `id:` 프레임을 주지 않아 Last-Event-ID
 * 복구가 불가능하다).
 *
 * 4xx(방 없음·재발급 실패한 인증 오류)는 재시도해도 소용없으므로 `onError` 후 종료하고,
 * 네트워크 오류·5xx·정상 종료는 재연결 대상으로 본다.
 */
export function subscribeChatRoom(
  roomUid: string,
  handlers: ChatSubscriptionHandlers,
): ChatSubscription {
  const url = `${GAME_BASE_URL}${roomPath(roomUid, '/subscribe')}`;
  const controller = new AbortController();
  const autoReconnect = handlers.autoReconnect ?? true;

  let closed = false;
  let everOpened = false;
  let attempt = 0;

  function stop(error?: ApiError): void {
    if (closed) return;
    closed = true;
    controller.abort();
    if (error) handlers.onError?.(error);
  }

  function openRequest(): Promise<Response> {
    const token = getTokenStorage().getAccessToken();
    return fetch(url, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      signal: controller.signal,
      cache: 'no-store',
    });
  }

  /** 스트림을 열고 끊길 때까지 읽는다. 정상 종료면 resolve, 그 외에는 ApiError로 reject. */
  async function runStream(): Promise<void> {
    let response = await openRequest();

    // access 토큰 만료로 보이면 axios 인터셉터와 같은 단일 회전을 공유해 1회 재발급 후 재시도.
    if (response.status === 401) {
      const body = await readErrorBody(response);
      if (body?.message !== AUTH_ERROR_MESSAGE.UNAUTHENTICATED) {
        throw apiErrorFromResponse(401, body);
      }
      try {
        await rotateTokens();
      } catch {
        getTokenStorage().clear();
        throw apiErrorFromResponse(401, body);
      }
      response = await openRequest();
    }

    if (!response.ok) {
      throw apiErrorFromResponse(response.status, await readErrorBody(response));
    }
    if (!response.body) {
      throw networkError('실시간 스트림을 열 수 없습니다.');
    }

    attempt = 0;
    handlers.onOpen?.({ reconnected: everOpened });
    everOpened = true;

    await consumeEventStream(response.body, (frame) => {
      if (frame.event !== 'message') return;
      const message = parseMessageEvent(frame.data);
      if (message) handlers.onMessage(message);
    });
  }

  async function loop(): Promise<void> {
    while (!closed) {
      try {
        // 정상 종료(30분 타임아웃 등)도 재연결 대상이다.
        await runStream();
      } catch (error) {
        if (closed) return; // close()로 인한 abort — 조용히 끝낸다.

        const apiError =
          error instanceof ApiError ? error : networkError('실시간 연결이 끊어졌습니다.');

        // 4xx는 재시도해도 같은 결과다(방 없음·재발급 실패 등).
        if (apiError.status !== null && apiError.status >= 400 && apiError.status < 500) {
          stop(apiError);
          return;
        }
      }

      if (closed) return;
      if (!autoReconnect) {
        stop();
        return;
      }

      attempt += 1;
      const delayMs = Math.min(
        RECONNECT_BASE_DELAY_MS * 2 ** (attempt - 1),
        RECONNECT_MAX_DELAY_MS,
      );
      handlers.onReconnecting?.({ attempt, delayMs });
      await delay(delayMs, controller.signal);
    }
  }

  // 핸들러가 던진 예외까지 삼켜 unhandled rejection이 되지 않게 한다.
  void loop().catch((error: unknown) => {
    stop(error instanceof ApiError ? error : networkError('실시간 구독이 중단되었습니다.'));
  });

  return {
    close: () => stop(),
  };
}
