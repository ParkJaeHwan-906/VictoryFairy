import type { AxiosResponse } from 'axios';
import { gameClient } from './httpClient';
import { GAME_BASE_URL } from './config';
import { openSseSubscription, type SseLifecycleHandlers, type SseSubscription } from './sseSubscription';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type {
  QuizRoomAnswerRequest,
  QuizRoomAnswerResult,
  QuizRoomCreateRequest,
  QuizRoomCreateResult,
  QuizRoomDetail,
  QuizRoomErrorCode,
  QuizRoomLikeRequest,
  QuizRoomLikeResult,
  QuizRoomPage,
  QuizRoomPageParams,
  QuizRoomPresenceEvent,
  QuizRoomQuiz,
  QuizRoomSummary,
  QuizRoomVoteOption,
  QuizRoomVotesEvent,
} from '../types/quizRoom';

/**
 * 퀴즈방 API (quiz 모듈, `docs/quiz-room.md`) — 사용자가 만든 퀴즈방.
 *
 * base 가 채팅·오늘의 퀴즈와 같은 `/rt` 라 `gameClient` 를 그대로 쓴다(경로만 `/quiz-rooms/*`).
 * 오늘의 퀴즈(`api/quiz.ts`, `/quizzes/*`)와는 **별개 도메인**이다 — 문항·풀이 기록·보상이 따로다.
 *
 * 9개 전부 인증 필수라 모두 `requiresAuth: true`. SSE 구독만 ApiResponse 래핑이 아니고,
 * 나머지 8개는 성공도 래핑이라 `unwrap` 으로 `data` 만 벗겨 반환한다. 생성만 201 이다.
 *
 * 이 도메인만의 계약:
 * - **풀이는 첫 답 제출로 시작하고, 이력 조회 API 가 없다.** 마지막 답 제출 응답의 `result` 가
 *   결과를 받는 유일한 기회다. 내가 어디까지 풀었는지도 서버가 알려 주지 않는다.
 * - **정답·분포는 REST 에 없다.** 정답은 위 `result` 로만, 보기별 투표 수는 SSE `votes` 로만 온다.
 * - **추천은 토글이 아니라 지정(PUT)이다** — 같은 값을 다시 보내도 변화가 없어 재시도에 안전하다.
 * - 보기 번호(`no`)·`answer`·`option` 은 모두 0부터다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(chat·quiz 와 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/** 방 하위 경로. */
function roomPath(roomId: number, suffix = ''): string {
  return `/quiz-rooms/${roomId}${suffix}`;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/** 목록 페이지 크기 기본값(서버 기본값과 같다). */
export const QUIZ_ROOM_PAGE_SIZE = 20;
/** 목록 페이지 크기 상한. 넘기면 400. */
export const QUIZ_ROOM_PAGE_SIZE_MAX = 50;

/** 생성 요청 제약(서버 Bean Validation 과 같은 기준 — 길이는 `String.length()`). */
export const QUIZ_ROOM_TITLE_MAX_LENGTH = 50;
export const QUIZ_ROOM_DESCRIPTION_MAX_LENGTH = 500;
export const QUIZ_ROOM_QUIZ_MAX = 20;
export const QUIZ_ROOM_QUESTION_MAX_LENGTH = 200;
export const QUIZ_ROOM_OPTION_MIN = 2;
export const QUIZ_ROOM_OPTION_MAX = 4;
export const QUIZ_ROOM_OPTION_MAX_LENGTH = 100;

/**
 * 퀴즈방 도메인 실패 메시지. ErrorCode 이름은 응답에 오지 않아 이 문자열로 판별한다.
 *
 * `QUIZ_NOT_FOUND`·`QUIZ_OPTION_NOT_FOUND`·`QUIZ_ALREADY_SUBMITTED` 는 오늘의 퀴즈와 문구가 같다
 * (백엔드가 같은 ErrorCode 를 공유). 엔드포인트가 달라 섞이지는 않는다.
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const QUIZ_ROOM_ERROR_MESSAGE: Record<QuizRoomErrorCode, string> = {
  QUIZ_ROOM_NOT_FOUND: '존재하지 않는 퀴즈방입니다.',
  QUIZ_NOT_FOUND: '존재하지 않는 퀴즈입니다.',
  QUIZ_OPTION_NOT_FOUND: '존재하지 않는 보기 번호입니다.',
  QUIZ_ALREADY_SUBMITTED: '이미 제출한 퀴즈입니다.',
};

/** ErrorCode 별 HTTP 상태. 메시지와 함께 맞아야 그 코드로 본다. */
const QUIZ_ROOM_ERROR_STATUS: Record<QuizRoomErrorCode, number> = {
  QUIZ_ROOM_NOT_FOUND: 404,
  QUIZ_NOT_FOUND: 404,
  QUIZ_OPTION_NOT_FOUND: 400,
  QUIZ_ALREADY_SUBMITTED: 409,
};

/**
 * 에러를 퀴즈방 도메인 ErrorCode 로 역판별한다. 해당 없음(검증 400·401·네트워크 등)이면 `null`.
 * 상태 코드와 메시지가 둘 다 맞아야 한다.
 */
export function resolveQuizRoomErrorCode(error: unknown): QuizRoomErrorCode | null {
  if (!(error instanceof ApiError)) return null;
  const codes = Object.keys(QUIZ_ROOM_ERROR_MESSAGE) as QuizRoomErrorCode[];
  return (
    codes.find(
      (code) =>
        QUIZ_ROOM_ERROR_STATUS[code] === error.status &&
        QUIZ_ROOM_ERROR_MESSAGE[code] === error.message,
    ) ?? null
  );
}

/** 404 QUIZ_ROOM_NOT_FOUND — 방이 없다. 목록으로 돌려보내는 신호. */
export function isQuizRoomNotFound(error: unknown): boolean {
  return resolveQuizRoomErrorCode(error) === 'QUIZ_ROOM_NOT_FOUND';
}

/** 404 QUIZ_NOT_FOUND — 그 방의 문항이 아니다(다른 방의 quizId 포함). 퀴즈 리스트를 다시 받는다. */
export function isQuizRoomQuizNotFound(error: unknown): boolean {
  return resolveQuizRoomErrorCode(error) === 'QUIZ_NOT_FOUND';
}

/** 400 QUIZ_OPTION_NOT_FOUND — option 누락·null·그 문항에 없는 번호. */
export function isQuizRoomOptionNotFound(error: unknown): boolean {
  return resolveQuizRoomErrorCode(error) === 'QUIZ_OPTION_NOT_FOUND';
}

/**
 * 409 QUIZ_ALREADY_SUBMITTED — 이미 이 문항에 답했다(정정·재제출 불가).
 * 오류 토스트가 아니라 "이미 푼 문항"으로 다루고 다음 문항으로 넘어간다.
 *
 * ⚠️ 이 409 에는 `result` 가 없다. 마지막 문항의 첫 응답을 놓친 뒤 재시도해 409 를 받으면
 * 결과를 다시 받을 방법이 없다(이력 조회 API 없음).
 */
export function isQuizRoomAlreadySubmitted(error: unknown): boolean {
  return resolveQuizRoomErrorCode(error) === 'QUIZ_ALREADY_SUBMITTED';
}

/* ------------------------------------------------------------------ *
 * 퀴즈방 — 성공 시 ApiResponse 래핑
 * ------------------------------------------------------------------ */

/**
 * POST /quiz-rooms — 방·문항·보기를 한 트랜잭션에 저장한다. 성공은 **201** `{roomId}`.
 *
 * O/X 문항은 `options` 를 무시하고 `O`(0)·`X`(1)로 저장한다.
 * 에러: 400(본문 검증 — `fieldErrors` 키는 `title`·`description`·`quizzes`·`quizzes[i]`·
 * `quizzes[i].type`/`.question`/`.answer`/`.optionsValid`/`.answerInRange`, 키당 메시지 하나.
 * 깨진 JSON 이면 `fieldErrors` 는 null), 401.
 */
export function createQuizRoom(body: QuizRoomCreateRequest): Promise<QuizRoomCreateResult> {
  return gameClient
    .post<ApiResponse<QuizRoomCreateResult>>('/quiz-rooms', body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /quiz-rooms?page=&size= — 최신순(id 내림차순) 페이지 목록.
 *
 * 인기 방도 여기서 빠지지 않으므로 인기 목록과 함께 그릴 때 **중복 제거는 화면 몫**이다.
 * 생략한 파라미터는 보내지 않아 서버 기본값(page 0, size 20)을 쓴다.
 * 에러: 400(page<0 · size 1~50 밖 · 숫자 아님 — `fieldErrors` 에 파라미터명), 401.
 */
export function getQuizRooms(params: QuizRoomPageParams = {}): Promise<QuizRoomPage> {
  return gameClient
    .get<ApiResponse<QuizRoomPage>>('/quiz-rooms', { params, requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /quiz-rooms/popular — 가장 최근에 확정된 주간 베스트(0~5건, 배열 순서가 곧 순위).
 *
 * 요청마다 계산하지 않는다 — 구성·순서는 다음 주 월요일 확정 전까지 고정이고 `likeCount`·
 * `solvingCount` 만 응답 시점 값이라 숫자와 순서가 어긋나 보일 수 있다(재정렬하지 말 것).
 * 확정된 주가 없거나 대상 방이 없으면 `[]`(200). 에러: 401.
 */
export function getPopularQuizRooms(): Promise<QuizRoomSummary[]> {
  return gameClient
    .get<ApiResponse<QuizRoomSummary[]>>('/quiz-rooms/popular', { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /quiz-rooms/{roomId} — 상세. 요청자 기준 `liked`·`isAuthor` 가 붙는다.
 * 에러: 404 QUIZ_ROOM_NOT_FOUND, 400(roomId 숫자 아님), 401.
 */
export function getQuizRoom(roomId: number): Promise<QuizRoomDetail> {
  return gameClient
    .get<ApiResponse<QuizRoomDetail>>(roomPath(roomId), { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /quiz-rooms/{roomId}/quizzes — 풀이용 문항 목록(생성 순서, 보기는 `no` 오름차순).
 *
 * **정답·투표 분포 키가 없다**(이미 다 푼 계정도 같다). 내가 어디까지 풀었는지도 알려 주지
 * 않으므로, 진행 상태는 답 제출 응답의 `answeredCount` 와 409 로 화면이 추적한다.
 * 에러: 404 QUIZ_ROOM_NOT_FOUND, 401.
 */
export function getQuizRoomQuizzes(roomId: number): Promise<QuizRoomQuiz[]> {
  return gameClient
    .get<ApiResponse<QuizRoomQuiz[]>>(roomPath(roomId, '/quizzes'), { requiresAuth: true })
    .then(unwrap);
}

/**
 * POST /quiz-rooms/{roomId}/quizzes/{quizId}/answer — 한 문항의 답 제출(문항당 1회).
 *
 * 순서·건너뛰기 제한은 없다. 답이 방의 문항 수만큼 쌓이는 **마지막 제출에만 `result`** 가
 * 채워지고 그 전엔 `null` 이다. 같은 계정의 동시 제출은 서버가 줄 세우므로 마지막 두 문항을
 * 동시에 내도 한 응답에는 반드시 `result` 가 실린다. point·bq 적립은 없다.
 *
 * 판정 순서: 404(방) → 404(문항) → 400(보기) → 409(중복). 본문이 깨졌으면 그보다 앞서 400.
 * 에러: 404 QUIZ_ROOM_NOT_FOUND / QUIZ_NOT_FOUND, 400 QUIZ_OPTION_NOT_FOUND,
 * 409 QUIZ_ALREADY_SUBMITTED, 401.
 *
 * @param option 고른 보기의 `options[].no`(0부터).
 */
export function submitQuizRoomAnswer(
  roomId: number,
  quizId: number,
  option: number,
): Promise<QuizRoomAnswerResult> {
  const body: QuizRoomAnswerRequest = { option };

  return gameClient
    .post<ApiResponse<QuizRoomAnswerResult>>(roomPath(roomId, `/quizzes/${quizId}/answer`), body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * PUT /quiz-rooms/{roomId}/like — 추천 상태를 **지정**한다(토글 아님).
 *
 * 같은 값을 다시 보내도 변화가 없어 재시도해도 안전하다. 풀이 여부·작성자 여부는 보지 않는다.
 * 응답의 `liked`·`likeCount` 가 정본이니 낙관적 갱신을 했더라도 응답으로 덮어쓴다.
 * 에러: 404 QUIZ_ROOM_NOT_FOUND, 400(liked 누락 — `fieldErrors.liked`), 401.
 */
export function setQuizRoomLike(roomId: number, liked: boolean): Promise<QuizRoomLikeResult> {
  const body: QuizRoomLikeRequest = { liked };

  return gameClient
    .put<ApiResponse<QuizRoomLikeResult>>(roomPath(roomId, '/like'), body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * DELETE /quiz-rooms/{roomId}/subscribe — 이 계정의 **그 방** 구독을 닫는다(풀이 중 인원에서 빠짐).
 *
 * 구독이 없어도·방이 없어도 200 인 **전면 멱등**이다(data null). 정의된 실패는 401 과
 * 경로 변수 형식 400 뿐. 부르지 않아도 하트비트 만료(45초)가 회수하지만 화면을 닫을 때 불러 준다.
 * 구독 객체의 `close()` 를 먼저 불러 재연결 루프를 멈춘 뒤 호출한다.
 */
export async function leaveQuizRoom(roomId: number): Promise<void> {
  await gameClient.delete<ApiResponse<null>>(roomPath(roomId, '/subscribe'), {
    requiresAuth: true,
  });
}

/* ------------------------------------------------------------------ *
 * 실시간 구독 (SSE) — ApiResponse 래핑 없음
 * ------------------------------------------------------------------ */

export interface QuizRoomSubscriptionHandlers extends SseLifecycleHandlers {
  /**
   * 풀이 중 인원(고유 계정 수, 본인 포함). 구독 직후 1회, 이후엔 값이 바뀔 때만 온다.
   * Redis 장애 중엔 오지 않으므로 마지막 값을 계속 보여 줄 것.
   */
  onPresence?: (count: number) => void;
  /**
   * 한 문항의 보기별 투표 수(0표 보기 포함, `no` 오름차순). 구독 직후 문항마다 1건씩 스냅샷,
   * 이후 누가 답할 때마다 그 문항 1건. 아직 안 푼 구독자에게도 오므로 숨길지는 화면 몫이다.
   * 같은 `quizId` 는 **최신 값으로 통째로 덮어쓰면** 된다(증분이 아니다).
   */
  onVotes?: (votes: QuizRoomVotesEvent) => void;
}

export type QuizRoomSubscription = SseSubscription;

/** JSON 파싱. 깨진 프레임은 undefined. */
function parseJson(data: string): unknown {
  try {
    return JSON.parse(data) as unknown;
  } catch {
    return undefined;
  }
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isVoteOption(value: unknown): value is QuizRoomVoteOption {
  const v = value as Partial<QuizRoomVoteOption> | null;
  return isNonNegativeInteger(v?.no) && isNonNegativeInteger(v?.count);
}

/**
 * GET /quiz-rooms/{roomId}/subscribe — 퀴즈방 SSE 스트림.
 *
 * fetch 로 열어 `Authorization` 헤더를 싣는다(연결·재발급·재연결은 `openSseSubscription`).
 * axios 를 타지 않는 경로라 여기서만 GAME_BASE_URL 을 직접 붙인다. `:ping` 하트비트는
 * 파서가 주석으로 걸러 낸다.
 *
 * `id:` 가 없어 Last-Event-ID 복구는 없다 — 재접속하면 `presence` 1건 + 문항별 `votes`
 * 스냅샷이 다시 와 그대로 수렴한다(`onOpen({reconnected})` 에서 따로 할 일이 없다).
 *
 * 404(방 없음)는 `onError` 후 종료하고, 네트워크 오류·5xx·정상 종료(30분 타임아웃)는 재연결한다.
 * 같은 계정의 퀴즈방 구독은 동시에 하나(last-one-wins) — 다른 방(같은 방의 이전 구독 포함)을
 * 구독하면 서버가 이전 것을 닫는다. 구단 채팅 구독과는 서로 영향이 없다.
 */
export function subscribeQuizRoom(
  roomId: number,
  handlers: QuizRoomSubscriptionHandlers,
): QuizRoomSubscription {
  const { onPresence, onVotes, ...lifecycle } = handlers;

  return openSseSubscription({
    ...lifecycle,
    url: `${GAME_BASE_URL}${roomPath(roomId, '/subscribe')}`,
    requiresAuth: true,
    onFrame: (frame) => {
      switch (frame.event) {
        case 'presence': {
          const parsed = parseJson(frame.data) as Partial<QuizRoomPresenceEvent> | undefined;
          if (isNonNegativeInteger(parsed?.count)) onPresence?.(parsed.count);
          return;
        }
        case 'votes': {
          const parsed = parseJson(frame.data) as Partial<QuizRoomVotesEvent> | undefined;
          if (!isNonNegativeInteger(parsed?.quizId) || !Array.isArray(parsed.options)) return;
          onVotes?.({
            quizId: parsed.quizId,
            options: parsed.options.filter(isVoteOption).sort((a, b) => a.no - b.no),
          });
          return;
        }
        default:
          // 알 수 없는 이벤트는 무시한다(서버가 이벤트를 늘려도 옛 프론트가 깨지지 않게).
          return;
      }
    },
  });
}
