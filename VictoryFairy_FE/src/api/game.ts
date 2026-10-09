import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { USER_BASE_URL } from './config';
import { ApiError } from './errors';
import type { SseFrame } from './eventStream';
import {
  openSseSubscription,
  STABLE_CONNECTION_MS,
  type SseLifecycleHandlers,
  type SseSubscription,
} from './sseSubscription';
import type { ApiResponse } from '../types/api';
import type { Game, GameUpdateEvent, TeamLineUp } from '../types/game';

/**
 * 경기 API (user 모듈).
 *
 * ── 인증이 경로마다 갈린다 ─────────────────────────────────────────
 * `/games` · `/games/lineup` 은 구단·선수와 같은 **공개 참조 데이터**라 GET 한정으로
 * 열려 있고 인증이 없다. 이 둘에 `requiresAuth` 를 붙이면 안 된다 — 토큰이 낭비될 뿐
 * 아니라, 401 이 나면 httpClient 의 refresh 회전 분기를 타서 저장된 토큰을 비워버린다.
 *
 * 반면 **`/games/support` 는 인증이 필수다**(2026-08-13 신규). 같은 `/games` 접두사
 * 아래에서 인증 정책이 갈리는 첫 사례라, 경로만 보고 짐작하지 말고 각 함수의 주석을 봐야 한다.
 *
 * 세 엔드포인트 모두 페이징이 없고, 빈 배열(`[]`)은 오류가 아니라 정상 200 이다.
 *
 * `/games/subscribe` · `/games/support/subscribe`(2026-10-09 신규)는 각각 `/games` ·
 * `/games/support` 의 **SSE 판**이다. 인증 정책·`date` 해석·항목 13필드가 짝과 같고,
 * 응답만 래퍼가 아니라 이벤트 스트림이다. 기존 GET 은 폴백·최초 1회 조회용으로 그대로 남는다.
 *
 * `/games/lineup` 은 `/games` 의 하위 경로지만 백엔드에서 공개 규칙이 따로 걸려 있다
 * (`/games` 의 공개 매처가 정확 경로 매칭이라 하위 경로를 커버하지 못한다).
 * 즉 둘은 서로의 공개 여부를 보장하지 않는다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * 경기 도메인 실패 메시지.
 * 백엔드 ErrorCode 이름은 응답에 오지 않고 프론트가 받는 건 `message` 문자열뿐이라
 * auth·chat 과 같이 문자열로 판별한다. (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const GAME_ERROR_MESSAGE = {
  GAME_NOT_FOUND: '존재하지 않는 경기입니다.',
} as const;

/**
 * 404 — 없는 `gameId`. 내부 PK 를 넣었거나 `?gameId=` 처럼 **값이 빈** 경우도 여기로 온다.
 *
 * `gameId` 파라미터 자체를 빠뜨린 것은 400 이라 이 판별에 걸리지 않는다(아래 `getLineUp` 참고).
 */
export function isGameNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === GAME_ERROR_MESSAGE.GAME_NOT_FOUND
  );
}

/**
 * GET /games — 날짜별 경기 목록. 성공 시 ApiResponse 래핑(200), 경기 시각 오름차순 고정.
 *
 * @param date `yyyy-MM-dd`. 생략하면 서버가 정한 "오늘"이 된다.
 *
 * **화면이 날짜를 알고 있다면 항상 넘겨야 한다.** 서버의 "오늘"은 `Asia/Seoul` 기준이라
 * 클라이언트 시간대·기기 시계와 어긋날 수 있고, 자정 경계에서는 같은 화면이 요청 시점에 따라
 * 다른 응답을 받는다. 생략은 편의 기능이지 권장 사용법이 아니다.
 *
 * 그 날짜에 경기가 없으면 빈 배열(200)이며 오류가 아니다.
 * 응답의 `stadium` 은 구장 미정 시 `null` 이므로 표기 처리가 필요하다.
 *
 * `cancelReason` 도 같은 성질이다 — 값이 없을 때 서버가 표시 문구로 채워 주지 않는다.
 * 서버가 채우면 "사유를 아는 경우"와 "모르는 경우"의 구분이 응답에서 사라지기 때문에 일부러 안 채운다.
 * 그래서 기본 문구 fallback 은 이 계층이 아니라 표시 계층(`getGameStateDisplay`)의 몫이다.
 *
 * `inning`·`inningHalf` 는 `IN_PROGRESS` 경기에서만 값이 있다(2026-08-12 부터 수집기가 채운다).
 * 못 채운 구간이 있을 수 있으니 `null` 표시 처리는 여전히 필요하다.
 *
 * 형식이 어긋나면(`20260801`, `2026-13-01` 등) 400 이다. **이 400 도 2026-08-20 부터
 * `{success, data, message}` 래퍼를 탄다**(백엔드 `GlobalExceptionHandler.handleTypeMismatch` 신설).
 * 즉 `normalizeError` 가 다른 실패와 똑같이 본문을 파싱하지만, 실리는 문구는 도메인 오류가 아니라
 * 타입 변환 실패 메시지다 — **여전히 문자열로 판별하지 말고 status 로만 다룬다.**
 *
 * `date` 를 아예 넘기지 않는 것은 오류가 아니다(200 + 서버 기준 오늘).
 */
export function getGameList(date?: string): Promise<Game[]> {
  return userClient
    .get<ApiResponse<Game[]>>('/games', { params: date ? { date } : undefined })
    .then(unwrap);
}

/**
 * GET /games/support — 내 응원 구단이 홈 또는 원정으로 뛰는 경기만 걸러서 반환.
 * 성공 시 ApiResponse 래핑(200). **인증 필수**(2026-08-13 신규).
 *
 * @param date `yyyy-MM-dd`. 생략 규칙·경고는 `getGameList` 와 완전히 같다.
 *
 * 같은 날짜 `GET /games` 응답의 **부분집합**이다 — 겹치는 `gameId` 의 값이 전부 같고,
 * 이 경로 전용 필드·가공값은 없다. **어느 쪽이 내 구단인지도 알려주지 않으므로**
 * `homeTeamId`/`awayTeamId` 를 `GET /users/me` 의 `supportTeam.id` 와 대조해야 한다.
 *
 * `gameState` 로 거르지 않는다 — 취소·종료 경기도 그대로 들어온다.
 *
 * ⚠️ **빈 배열이 두 가지 뜻을 겸한다** — "그 날 응원 구단 경기가 없다"와 "응원 구단을
 * 고른 적이 없다(또는 취소했다)"가 똑같은 `[]` 200 으로 온다. 전용 오류 코드는 없다.
 * 둘을 갈라 안내해야 하는 화면은 이 응답이 아니라 `GET /users/me` 의 `supportTeam` 이
 * `null` 인지로 판별해야 한다.
 *
 * 에러: 401(토큰 없음·위조·만료·refresh 토큰 오용·탈퇴 계정을 구분하지 않는다).
 * 날짜 형식 오류 400 은 `getGameList` 와 사정이 같다(2026-08-20 부터 ApiResponse 래퍼를 탄다).
 * 인증 여부와 무관하게 이 400 이 401 보다 먼저 나간다.
 */
export function getSupportGameList(date?: string): Promise<Game[]> {
  return userClient
    .get<ApiResponse<Game[]>>('/games/support', {
      params: date ? { date } : undefined,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /games/lineup — 경기 1건의 선발 라인업을 홈·원정 두 팀 함께 반환. 성공 시 ApiResponse 래핑(200).
 *
 * @param gameId `GET /games` 응답의 `gameId`(네이버 자연키 문자열).
 *
 * **내부 PK 가 아니다.** 내부 PK 를 넣으면 항상 404 다.
 *
 * 반환 배열은 `teamId` 오름차순이라 **순서로 홈/원정을 판단할 수 없다.**
 * 서버가 홈/원정을 판정하지 않으므로 호출자가 `Game.homeTeamId`/`awayTeamId` 와 대조해야 한다.
 *
 * 경기는 있으나 아직 선발이 공시되지 않았으면 빈 배열(200)이며 오류가 아니다 —
 * "없는 경기"(404)와 구분해서 표시해야 한다.
 *
 * 에러: 404 GAME_NOT_FOUND(`isGameNotFound`). `?gameId=` 처럼 값이 비어도 400 이 아니라 404 다.
 * 반면 `gameId` 파라미터를 아예 빠뜨리면 400 이다 — 이 응답도 2026-08-13 부터 ApiResponse
 * 래퍼를 탄다(문구: `필수 요청 파라미터가 누락되었습니다: gameId`).
 * 이 함수는 인자를 필수로 받으므로 정상 호출에서는 발생하지 않는다.
 */
export function getLineUp(gameId: string): Promise<TeamLineUp[]> {
  return userClient
    .get<ApiResponse<TeamLineUp[]>>('/games/lineup', { params: { gameId } })
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 실시간 구독 (SSE) — ApiResponse 래핑이 없는 이벤트 스트림
 * ------------------------------------------------------------------ */

export interface GameSubscriptionHandlers extends SseLifecycleHandlers {
  /**
   * 연결(재연결 포함) 직후 1회 — 같은 `date` 의 GET 응답 `data` 와 같은 배열.
   * 목록을 **통째로 교체**하면 된다. 끊긴 사이의 갱신도 이것이 흡수한다
   * (Last-Event-ID 재전송은 없지만 재접속마다 snapshot 이 다시 오므로 따로 복구할 필요가 없다).
   */
  onSnapshot: (games: Game[]) => void;
  /** 경기 1건의 이닝·점수·상태 변화. `applyGameUpdate` 로 목록에 반영한다. */
  onUpdate: (event: GameUpdateEvent) => void;
}

export interface SupportGameSubscriptionHandlers extends GameSubscriptionHandlers {
  /**
   * 활성 응원 구단이 없어 서버가 빈 `snapshot` 뒤 곧바로 연결을 닫았을 때.
   * 호출 시점에 구독은 이미 종료돼 있고 재접속하지 않는다(에러가 아니다).
   * 가능하면 열기 전에 `GET /users/me` 의 `supportTeam` 으로 먼저 거르는 편이 낫다.
   */
  onNoSupportTeam?: () => void;
}

export type GameSubscription = SseSubscription;

/** 깨진 프레임은 버린다. 배열이 아니면 snapshot 으로 보지 않는다. */
function parseSnapshot(data: string): Game[] | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return Array.isArray(parsed) ? (parsed as Game[]) : null;
  } catch {
    return null;
  }
}

/**
 * 검사는 교체 키인 `game.gameId` 하나로 최소화한다 — 채팅의 `parseMessageEvent` 와 같은 이유로,
 * 필드가 늘 때마다 검사를 조이면 옛 프론트가 새 프레임을 통째로 버리게 된다.
 */
function parseGameUpdate(data: string): GameUpdateEvent | null {
  try {
    const parsed = JSON.parse(data) as Partial<GameUpdateEvent>;
    if (typeof parsed?.game?.gameId !== 'string') return null;
    return {
      ...parsed,
      changed: Array.isArray(parsed.changed) ? parsed.changed : [],
    } as GameUpdateEvent;
  } catch {
    return null;
  }
}

/** `snapshot`·`game-update` 프레임을 핸들러로 넘기는 공통 분배기. 그 외 이벤트는 무시한다. */
function dispatchGameFrame(
  frame: SseFrame,
  handlers: Pick<GameSubscriptionHandlers, 'onSnapshot' | 'onUpdate'>,
): void {
  if (frame.event === 'snapshot') {
    const games = parseSnapshot(frame.data);
    if (games) handlers.onSnapshot(games);
  } else if (frame.event === 'game-update') {
    const update = parseGameUpdate(frame.data);
    if (update) handlers.onUpdate(update);
  }
}

/** axios 를 타지 않으므로 USER_BASE_URL 과 쿼리를 직접 붙인다. */
function subscribeUrl(path: string, date?: string): string {
  const query = date ? `?date=${encodeURIComponent(date)}` : '';
  return `${USER_BASE_URL}${path}${query}`;
}

/**
 * `game-update` 를 목록에 반영한 **새 배열**을 돌려준다(원본 불변).
 *
 * 같은 `gameId` 항목을 `event.game` 으로 통째 교체한다 — 부분 머지 규칙은 없다.
 * 목록에 없는 `gameId` 면(정상 계약에선 오지 않는다) 버리지 않고 끝에 붙인다.
 */
export function applyGameUpdate(games: Game[], event: GameUpdateEvent): Game[] {
  const index = games.findIndex((g) => g.gameId === event.game.gameId);
  if (index === -1) return [...games, event.game];
  const next = games.slice();
  next[index] = event.game;
  return next;
}

/**
 * GET /games/subscribe — 날짜별 경기 목록의 SSE 판. **인증 없음**(공개 매처).
 *
 * @param date `yyyy-MM-dd`. 해석 규칙·경고는 `getGameList` 와 같다. 구독은 **연결 시점에 해석한
 * 날짜에 고정**되므로, 생략한 채 자정을 넘기면 계속 전날 경기를 받는다 — 화면이 날짜를 알면
 * 넘기고, 날짜가 바뀌면 `close()` 후 다시 구독해야 한다.
 *
 * 프레임: 연결 직후 `snapshot`(경기 배열) 1회 → 이후 `game-update`. 15초마다 오는 `:ping` 은
 * 파서가 거른다. 해상도는 1분(수집기 라이브 폴링, KST 13:00~23:59)이라 그 밖의 시간엔
 * `game-update` 가 오지 않는 게 정상이다. 경기가 없는 날은 빈 `snapshot` 뒤 아무것도 오지 않는다.
 *
 * 서버 타임아웃 30분 등으로 끊기면 지수 백오프로 재접속하고 `snapshot` 을 다시 받는다.
 * 400(날짜 형식)은 스트림을 열지 않고 래퍼로 오므로 `onError` 후 종료한다.
 */
export function subscribeGameList(
  handlers: GameSubscriptionHandlers,
  date?: string,
): GameSubscription {
  const { onSnapshot, onUpdate, ...lifecycle } = handlers;
  return openSseSubscription({
    ...lifecycle,
    url: subscribeUrl('/games/subscribe', date),
    requiresAuth: false,
    onFrame: (frame) => dispatchGameFrame(frame, { onSnapshot, onUpdate }),
  });
}

/**
 * GET /games/support/subscribe — 내 응원 구단 경기의 SSE 판. **인증 필수**.
 *
 * @param date `subscribeGameList` 와 같다(날짜 고정·자정 경계 주의 포함).
 *
 * 프레임 형식은 `subscribeGameList` 와 같고, 범위만 활성 응원 구단이 홈 또는 원정인 경기로
 * 좁혀진다. 어느 쪽이 내 구단인지는 알려주지 않으므로 `GET /users/me` 의 `supportTeam.id` 와
 * 대조한다. 응원 구단은 **연결 시점에 고정**된다 — 구독 중 `/support` 로 바꾸면 `close()` 후
 * 다시 구독해야 새 구단 경기가 온다.
 *
 * 활성 응원 구단이 없으면 서버가 200 으로 연 뒤 빈 `snapshot` 만 보내고 즉시 닫는다.
 * 이 경우 재접속하지 않고 `onNoSupportTeam` 을 부른다. 판정은 "빈 snapshot 만 받고 업데이트 없이
 * `STABLE_CONNECTION_MS` 안에 정상 종료"다 — 응원 구단 경기가 없는 날 30분 타임아웃으로 닫히는
 * 경우는 오래 버텼으므로 여기에 걸리지 않고 평소대로 재접속한다.
 *
 * 에러: 401(미인증·무효 토큰·탈퇴 계정) — UNAUTHENTICATED 면 1회 재발급 후 재시도하고, 그래도
 * 안 되면 `onError`. 400(날짜 형식)도 `onError` 후 종료.
 */
export function subscribeSupportGameList(
  handlers: SupportGameSubscriptionHandlers,
  date?: string,
): GameSubscription {
  const { onSnapshot, onUpdate, onNoSupportTeam, onOpen, ...lifecycle } = handlers;

  /** 이번 연결에서 받은 것 — 재연결마다 onOpen 에서 초기화한다. */
  let snapshotEmpty = false;
  let receivedUpdate = false;

  return openSseSubscription({
    ...lifecycle,
    url: subscribeUrl('/games/support/subscribe', date),
    requiresAuth: true,
    onOpen: (info) => {
      snapshotEmpty = false;
      receivedUpdate = false;
      onOpen?.(info);
    },
    onFrame: (frame) =>
      dispatchGameFrame(frame, {
        onSnapshot: (games) => {
          snapshotEmpty = games.length === 0;
          onSnapshot(games);
        },
        onUpdate: (event) => {
          receivedUpdate = true;
          onUpdate(event);
        },
      }),
    shouldStopOnEnd: ({ lifetimeMs }) => {
      const noSupportTeam =
        snapshotEmpty && !receivedUpdate && lifetimeMs < STABLE_CONNECTION_MS;
      if (noSupportTeam) onNoSupportTeam?.();
      return noSupportTeam;
    },
  });
}
