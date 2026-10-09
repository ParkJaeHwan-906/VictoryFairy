import { rotateTokens } from './httpClient';
import { getTokenStorage } from './tokenStorage';
import { consumeEventStream, type SseFrame } from './eventStream';
import { ApiError, AUTH_ERROR_MESSAGE, apiErrorFromResponse, networkError } from './errors';
import type { ApiErrorResponse } from '../types/api';

/**
 * fetch 기반 SSE 구독의 공통 골격 — 연결·인증 재발급·재연결 루프.
 *
 * 채팅(`subscribeChatRoom`)과 경기(`subscribeGameList`·`subscribeSupportGameList`)가 함께 쓴다.
 * 이 파일은 "프레임이 무슨 뜻인지"는 모른다 — 프레임 해석은 `onFrame` 으로 호출자에게 넘긴다.
 *
 * 표준 `EventSource` 를 쓰지 않는 이유: `Authorization` 헤더를 실을 수 없고, 백엔드는
 * 쿼리·쿠키 토큰을 받지 않는다. 공개 스트림(`/games/subscribe`)도 같은 경로로 열어
 * 재연결·에러 처리를 한 곳에 모은다.
 *
 * 서버는 `id:` 프레임을 보내지 않아 Last-Event-ID 복구가 불가능하다. 끊긴 사이의 공백을
 * 메우는 방법은 스트림마다 다르다(채팅은 히스토리 재조회, 경기는 재접속 시 오는 `snapshot`).
 */

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
export const STABLE_CONNECTION_MS = 30_000;

/** 화면이 넘기는 공통 콜백. 스트림별 핸들러 타입이 이것을 확장한다. */
export interface SseLifecycleHandlers {
  /**
   * 스트림이 열릴 때마다 호출.
   * `reconnected: true`면 끊겼다 다시 붙은 것이다 — 공백 복구 방법은 스트림별 주석 참고.
   */
  onOpen?: (info: { reconnected: boolean }) => void;
  /** 복구 불가 에러(4xx·인증 실패 등). 호출 시점에 구독은 이미 종료돼 있다. */
  onError?: (error: ApiError) => void;
  /** 일시적 끊김으로 재연결을 예약했을 때. */
  onReconnecting?: (info: { attempt: number; delayMs: number }) => void;
  /** 기본 true. false면 스트림이 끊겨도 재연결하지 않고 종료한다. */
  autoReconnect?: boolean;
}

export interface SseSubscription {
  /** 구독 종료. 화면 언마운트 시 반드시 호출한다(연결 누수 방지). */
  close(): void;
}

export interface SseSubscriptionOptions extends SseLifecycleHandlers {
  /** 절대 URL(axios 를 타지 않으므로 base 를 호출자가 붙인다). */
  url: string;
  /**
   * true면 `Authorization` 헤더를 싣고, 401(UNAUTHENTICATED)이면 axios 인터셉터와 같은
   * 단일 회전으로 1회 재발급 후 재시도한다. 공개 스트림에는 붙이지 않는다 —
   * 재발급 실패 시 저장된 토큰을 비워버리기 때문이다.
   */
  requiresAuth: boolean;
  /** `:ping` 등 주석을 걸러낸 프레임마다 호출. 이벤트 이름 분기는 호출자 몫이다. */
  onFrame: (frame: SseFrame) => void;
  /**
   * 서버가 연결을 정상 종료했을 때 재연결 대신 멈출지 판단한다. true를 돌려주면 조용히 종료한다.
   * 생략하면 정상 종료(30분 타임아웃 등)는 항상 재연결 대상이다.
   */
  shouldStopOnEnd?: (info: { lifetimeMs: number }) => boolean;
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

/**
 * SSE 스트림을 열고, 끊기면 지수 백오프로 재연결한다.
 *
 * 4xx 는 재시도해도 같은 결과이므로 `onError` 후 종료하고, 네트워크 오류·5xx·정상 종료는
 * 재연결 대상으로 본다(`shouldStopOnEnd` 가 true면 정상 종료에서 멈춘다).
 */
export function openSseSubscription(options: SseSubscriptionOptions): SseSubscription {
  const { url, requiresAuth } = options;
  const controller = new AbortController();
  const autoReconnect = options.autoReconnect ?? true;

  let closed = false;
  let everOpened = false;
  let attempt = 0;
  /** 현재 연결이 열린 시각. 끊긴 뒤 "얼마나 버텼는지"로 백오프 초기화를 판단한다. */
  let openedAt: number | null = null;

  function stop(error?: ApiError): void {
    if (closed) return;
    closed = true;
    controller.abort();
    if (error) options.onError?.(error);
  }

  function openRequest(): Promise<Response> {
    const token = requiresAuth ? getTokenStorage().getAccessToken() : null;
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
    if (requiresAuth && response.status === 401) {
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
    options.onOpen?.({ reconnected: everOpened });
    everOpened = true;

    await consumeEventStream(response.body, options.onFrame);
  }

  async function loop(): Promise<void> {
    while (!closed) {
      openedAt = null;

      try {
        await runStream();

        // 정상 종료. 호출자가 "더 받을 게 없다"고 판단하면 재연결하지 않는다.
        if (
          !closed &&
          openedAt !== null &&
          options.shouldStopOnEnd?.({ lifetimeMs: Date.now() - openedAt })
        ) {
          stop();
          return;
        }
      } catch (error) {
        if (closed) return; // close()로 인한 abort — 조용히 끝낸다.

        const apiError =
          error instanceof ApiError ? error : networkError('실시간 연결이 끊어졌습니다.');

        // 4xx는 재시도해도 같은 결과다(방 없음·구단 불일치·날짜 형식·재발급 실패 등).
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
      options.onReconnecting?.({ attempt, delayMs });
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
