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
 * 7개 엔드포인트 전부 인증이 필수이므로 모두 `requiresAuth: true`로 보낸다 —
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
 *
 * 상태 코드만으로는 종류가 갈리지 않아 문자열로 판별한다 —
 * 404는 방 없음/메시지 없음 두 가지, **403은 자기 신고/구단 불일치 두 가지**다.
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const CHAT_ERROR_MESSAGE = {
  ROOM_NOT_FOUND: '채팅방을 찾을 수 없습니다.',
  MESSAGE_NOT_FOUND: '메시지를 찾을 수 없습니다.',
  SELF_REPORT: '자신의 메시지는 신고할 수 없습니다.',
  /** 2026-08-04 구단 접근 제어 도입 — 응원 구단 미선택(400) */
  SUPPORT_TEAM_REQUIRED: '응원하는 구단을 먼저 선택해 주세요.',
  /** 2026-08-04 구단 접근 제어 도입 — 내 응원 구단 방이 아님(403) */
  TEAM_MISMATCH: '응원하는 구단의 채팅방만 이용할 수 있습니다.',
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

/**
 * 403 — 자기 메시지 신고 시도.
 *
 * 상태 코드만 보면 안 된다 — 구단 불일치도 403이라, 남의 구단 방에서 신고를 시도한 것을
 * "자기 메시지 신고"로 잘못 안내하게 된다. 문구로 갈라야 한다.
 */
export function isSelfReport(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    error.message === CHAT_ERROR_MESSAGE.SELF_REPORT
  );
}

/**
 * 400 — 응원 구단을 아직 고르지 않음(온보딩 중).
 * 명시적 퇴장(`leaveChatRoom`)을 뺀 6개 경로 전부에서 날 수 있다.
 */
export function isSupportTeamRequired(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 400 &&
    error.message === CHAT_ERROR_MESSAGE.SUPPORT_TEAM_REQUIRED
  );
}

/**
 * 403 — 내 응원 구단의 방이 아님. 구단을 바꾼 뒤 예전 방을 다시 열 때도 이 에러가 난다.
 * 들고 있는 응원 구단 정보가 낡았다는 신호이기도 하다(프로필 재조회 대상).
 */
export function isChatTeamMismatch(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    error.message === CHAT_ERROR_MESSAGE.TEAM_MISMATCH
  );
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
 * GET /chat/rooms — **요청자의 응원 구단** 방 목록(2026-08-04부터 전체 목록이 아니다).
 *
 * `teamId`를 생략하면 서버가 요청자의 현재 응원 구단으로 간주한다. 값을 주면 응원 구단과
 * 같아야만 통과하고, 다르거나 없는 구단이면 403이다 — 즉 이 파라미터는 목록을 넓히는
 * 필터가 아니라 "내가 아는 내 구단이 서버 기준과 같은지" 확인하는 가드다.
 *
 * 방이 없으면 빈 배열(200)이며 에러가 아니다.
 * 에러: 400 SUPPORT_TEAM_REQUIRED, 403 TEAM_MISMATCH, 401 UNAUTHENTICATED.
 */
export function getChatRooms(params?: { teamId?: number }): Promise<ChatRoom[]> {
  return gameClient
    .get<ApiResponse<ChatRoom[]>>('/chat/rooms', {
      params: params?.teamId != null ? { teamId: params.teamId } : undefined,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /chat/rooms/{roomUid} — 방 하나. 응답 모양은 목록의 항목 하나와 동일하다.
 * 삭제된 방도 404로 응답한다.
 * 에러: 404 ROOM_NOT_FOUND, 400 SUPPORT_TEAM_REQUIRED, 403 TEAM_MISMATCH, 401 UNAUTHENTICATED.
 */
export function getChatRoom(roomUid: string): Promise<ChatRoom> {
  return gameClient
    .get<ApiResponse<ChatRoom>>(roomPath(roomUid), { requiresAuth: true })
    .then(unwrap);
}

/**
 * 내 응원 구단의 채팅방을 가져온다. 그 구단 방이 아직 없으면 `null`(에러 아님).
 *
 * 목록 자체가 이미 응원 구단으로 좁혀져 오므로 이름을 맞춰 볼 필요가 없다.
 * 구단당 방이 여럿이면 첫 번째를 쓴다 — 방 선택 화면은 아직 없다.
 *
 * 에러는 그대로 올린다: 400 SUPPORT_TEAM_REQUIRED(구단 미선택),
 * 403 TEAM_MISMATCH(들고 있던 teamId가 서버 기준과 다름 → 프로필이 낡음).
 */
export async function findMyTeamChatRoom(teamId?: number): Promise<ChatRoom | null> {
  const rooms = await getChatRooms({ teamId });
  return rooms[0] ?? null;
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
 * 판정 순서: content 검증(400) → 404 → 400 SUPPORT_TEAM_REQUIRED → 403 TEAM_MISMATCH.
 * 400이 두 종류라 `fieldErrors.content` 유무로 갈린다(입력 오류에만 실린다).
 * 에러: 400(fieldErrors.content), 404 ROOM_NOT_FOUND, 400 SUPPORT_TEAM_REQUIRED,
 * 403 TEAM_MISMATCH, 401 UNAUTHENTICATED.
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
 * 구단을 바꾸면 이전 구단 방의 히스토리는 자기가 쓴 메시지까지 403이라 다시 볼 수 없다.
 * 에러: 404 ROOM_NOT_FOUND, 400 SUPPORT_TEAM_REQUIRED, 403 TEAM_MISMATCH, 401 UNAUTHENTICATED.
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
 * 검증 순서: 방 존재(404) → 응원 구단 없음(400) → 구단 불일치(403) → 메시지 존재 →
 * 삭제 여부 → 자기 신고 여부 → blind 적용.
 *
 * **403이 두 종류다** — 자기 신고와 구단 불일치는 문구로만 갈린다(`isSelfReport` 참고).
 * 에러: 403 SELF_REPORT/TEAM_MISMATCH, 404 ROOM_NOT_FOUND/MESSAGE_NOT_FOUND,
 * 400 SUPPORT_TEAM_REQUIRED, 401 UNAUTHENTICATED.
 */
export async function reportChatMessage(roomUid: string, messageId: number): Promise<void> {
  await gameClient.post<ApiResponse<null>>(
    roomPath(roomUid, `/messages/${messageId}/report`),
    undefined,
    { requiresAuth: true },
  );
}

/**
 * DELETE /chat/rooms/{roomUid}/subscribe — 이 방의 내 SSE 구독을 서버에서 끊는다.
 *
 * **협조적 정리이지 보안 통제가 아니다** — 부르지 않아도 하트비트 실패·30분 타임아웃이
 * 결국 회수한다. 다만 그때까지 최대 30분 연결이 남으므로 화면을 닫을 때 불러 준다.
 *
 * 7개 중 유일하게 구단 가드가 없고 **전면 멱등**이다: 끊을 구독이 없어도, 응원 구단이
 * 없어도, 방이 삭제됐어도, 남의 구단 방이어도 전부 200이다. 정의된 실패 응답은 401뿐이라
 * 호출부에서 실패를 따로 다룰 일이 없다.
 */
export async function leaveChatRoom(roomUid: string): Promise<void> {
  await gameClient.delete<ApiResponse<null>>(roomPath(roomUid, '/subscribe'), {
    requiresAuth: true,
  });
}

/* ------------------------------------------------------------------ *
 * 실시간 구독 (SSE) — 유일하게 ApiResponse 래핑이 없는 엔드포인트
 * ------------------------------------------------------------------ */

/** 재연결 백오프: 1s → 2s → 4s … 최대 30s. */
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 30_000;

/**
 * 이만큼 버틴 연결만 "성공"으로 보고 백오프를 처음으로 되돌린다.
 *
 * 열자마자 끊기는 상황(같은 계정의 다른 탭이 구독을 뺏어가는 last-one-wins)에서
 * 열릴 때마다 백오프를 0으로 되돌리면 두 탭이 1초 간격으로 서로를 끊는 핑퐁이 된다.
 * 지속 시간을 기준으로 삼으면 그 경우 간격이 점점 벌어져 서로를 갉아먹지 않는다.
 */
const STABLE_CONNECTION_MS = 30_000;

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
 * 4xx(방 없음·구단 불일치·재발급 실패한 인증 오류)는 재시도해도 소용없으므로 `onError` 후
 * 종료하고, 네트워크 오류·5xx·정상 종료는 재연결 대상으로 본다. 구단 일치 검사는 **여는
 * 시점에 한 번만** 하므로, 연결 중에 응원 구단을 바꿔도 이미 열린 스트림은 유지된다.
 *
 * 같은 계정의 기존 구독은 방을 가리지 않고 서버가 끊는다(last-one-wins) — 같은 계정으로
 * 두 탭을 열면 먼저 연 탭의 스트림이 닫히고, 그 탭은 여기서 자동 재연결하며 이번엔 반대쪽을
 * 끊는다. 서로 뺏는 상황 자체는 서버 정책이라 막을 수 없어, 재연결 간격이 점점 벌어지도록만
 * 해 뒀다(`STABLE_CONNECTION_MS`). 놓친 메시지는 재연결 후 히스토리로 복구된다.
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
  /** 현재 연결이 열린 시각. 끊긴 뒤 "얼마나 버텼는지"로 백오프 초기화를 판단한다. */
  let openedAt: number | null = null;

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

    openedAt = Date.now();
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
      openedAt = null;

      try {
        // 정상 종료(30분 타임아웃 등)도 재연결 대상이다.
        await runStream();
      } catch (error) {
        if (closed) return; // close()로 인한 abort — 조용히 끝낸다.

        const apiError =
          error instanceof ApiError ? error : networkError('실시간 연결이 끊어졌습니다.');

        // 4xx는 재시도해도 같은 결과다(방 없음·구단 불일치·재발급 실패 등).
        if (apiError.status !== null && apiError.status >= 400 && apiError.status < 500) {
          stop(apiError);
          return;
        }
      }

      if (closed) return;

      // 충분히 버틴 연결이었다면 다음 끊김은 처음부터 짧게 재시도한다.
      if (openedAt !== null && Date.now() - openedAt >= STABLE_CONNECTION_MS) {
        attempt = 0;
      }
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
