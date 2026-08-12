import type { AxiosResponse } from 'axios';
import { gameClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type {
  DailyQuiz,
  QuizDetail,
  QuizSubmissionHistory,
  QuizSubmitRequest,
  QuizSubmitResult,
} from '../types/quiz';

/**
 * 데일리 퀴즈 API (quiz 모듈).
 *
 * base 가 채팅과 같은 `/rt` 라 `gameClient` 를 그대로 쓴다(경로만 `/quizzes/*`).
 * 4개 전부 인증이 필수라 모두 `requiresAuth: true` 로 보내며, 성공도 전부 `ApiResponse`
 * 래핑이라 `unwrap` 으로 `data` 만 벗겨 반환한다.
 *
 * 이 도메인만의 계약 셋:
 * - **정답은 제출 후에만 공개된다.** 조회 응답에는 `answer` 키 자체가 없다.
 *   그래서 상세 응답은 `submitted` 를 판별자로 하는 유니온(`QuizDetail`)이다.
 * - **세트는 전원 동일하고 날짜 파라미터가 없다.** "오늘"은 항상 서버가 KST 로 판정한다.
 * - **한 문제는 한 번만 제출할 수 있다.** 두 번째 제출은 오류가 아니라 "이미 푼 문제"(409)다.
 * - **미편성 문제는 존재하지 않는 것과 구분되지 않는다**(둘 다 404). id 순회로 내일 출제분을
 *   미리 보는 것을 막기 위한 의도이며, 클라이언트가 구분할 방법은 없다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(chat·support 와 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/** 풀이 이력 페이지 크기. 서버 고정값이며 쿼리로 바꿀 수 없다(채팅은 30, 여기는 20). */
export const QUIZ_SUBMISSION_PAGE_SIZE = 20;

/**
 * O/X 유형의 보기 번호. 서버가 고정한 매핑이라 토글 UI 는 이 값을 그대로 제출한다.
 * (`options` 에도 같은 번호로 내려오므로 목록을 렌더할 때는 이 상수가 필요 없다.)
 */
export const OX_OPTION_NO = { O: 0, X: 1 } as const;

/**
 * 퀴즈 도메인 실패 메시지.
 * `NOT_FOUND`(404)·`ALREADY_SUBMITTED`(409)는 상태 코드만으로도 유일하지만,
 * 400 이 두 갈래(보기 번호 오류 / 입력값 검증)라 문자열 판별이 필요하다.
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const QUIZ_ERROR_MESSAGE = {
  NOT_FOUND: '존재하지 않는 퀴즈입니다.',
  OPTION_NOT_FOUND: '존재하지 않는 보기 번호입니다.',
  ALREADY_SUBMITTED: '이미 제출한 퀴즈입니다.',
  INVALID_INPUT: '입력값이 올바르지 않습니다.',
} as const;

/**
 * 404 — 없는 퀴즈이거나 **아직 편성되지 않은 풀 문제**. 서버가 둘을 구분하지 않는다.
 * 목록을 다시 받아야 한다는 신호로 다루면 된다.
 */
export function isQuizNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * 409 — 이미 제출한 문제.
 *
 * 같은 문제를 동시에 두 번 눌러도 DB UNIQUE 제약이 최종 중재해 "정상 1건 + 409 1건"이 된다.
 * **오류 토스트가 아니라 "이미 푼 문제" 상태로** 처리하고, 필요하면 상세를 다시 조회해
 * 복기 정보를 채운다.
 */
export function isQuizAlreadySubmitted(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 400 — 그 문제에 없는 보기 번호. 보기 개수가 문제마다 달라 정적 범위 검증이 없고
 * 서버가 보기 조회 실패로 판정한다(화면이 `options` 밖의 값을 보낸 경우).
 */
export function isQuizOptionNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 400 &&
    error.message === QUIZ_ERROR_MESSAGE.OPTION_NOT_FOUND
  );
}

/**
 * 400 — `option` 누락(`@NotNull`). 컨트롤러 진입 전 검증이라 **퀴즈 미존재 404보다 먼저** 난다.
 *
 * 메시지 문구는 Hibernate Validator 기본값이라 로케일에 따라 달라진다 —
 * 문구가 아니라 `fieldErrors` 의 **키(`option`) 존재**로만 판별한다.
 */
export function isQuizOptionMissing(error: unknown): boolean {
  return (
    error instanceof ApiError && error.status === 400 && error.fieldErrors?.option !== undefined
  );
}

/* ------------------------------------------------------------------ *
 * 엔드포인트 — 4개 전부 인증 필수 · 성공도 ApiResponse 래핑(200)
 * ------------------------------------------------------------------ */

/**
 * GET /quizzes/today — 오늘(KST) 세트 중 **내가 아직 안 푼 문제만**.
 *
 * 이미 제출한 문제는 목록에서 빠지고, 정렬은 선호 먼저 → `id` 오름차순이다.
 * 날짜 파라미터도 페이징도 없다.
 *
 * ⚠️ **빈 배열의 의미가 둘이다** — "오늘 세트가 없음"과 "오늘 세트를 다 풀었음"이
 * 똑같이 `[]` 라 이 응답만으로는 구분되지 않는다. 구분이나 진행률 표시가 필요하면
 * `getQuizSubmissions()` 의 `summary` 를 함께 쓴다.
 *
 * @param preferredOnly `true` 면 응원 구단·선수와 매칭되는 문제만 남긴다.
 *                      **응원 정보가 하나도 없으면 무시되고 전체가 온다** — 취향 미설정과
 *                      "오늘 퀴즈 없음"을 구분하기 위한 의도된 동작이다.
 *                      기본값이 서버에서도 `false` 라 거짓일 때는 아예 보내지 않는다.
 */
export function getTodayQuizzes(preferredOnly = false): Promise<DailyQuiz[]> {
  return gameClient
    .get<ApiResponse<DailyQuiz[]>>('/quizzes/today', {
      params: preferredOnly ? { preferredOnly: true } : undefined,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /quizzes/{quizId} — 퀴즈 하나의 상세.
 *
 * **내 제출 상태에 따라 응답 키 집합이 달라진다.** 미제출이면 `myOption`·`correct`·`answer`
 * 세 키가 `null` 이 아니라 아예 빠지므로, 반드시 `submitted` 로 분기해야 한다
 * (`QuizDetail` 유니온이 이를 타입으로 강제한다).
 *
 * 에러: 404 NOT_FOUND(미편성 포함), 401 UNAUTHENTICATED.
 */
export function getQuiz(quizId: number): Promise<QuizDetail> {
  return gameClient
    .get<ApiResponse<QuizDetail>>(`/quizzes/${quizId}`, { requiresAuth: true })
    .then(unwrap);
}

/**
 * POST /quizzes/{quizId}/submit — 서버가 채점하고 정답이면 배점만큼 포인트를 적립한다.
 *
 * **오답은 실패가 아니다** — `correct: false`, `earnedPoint: 0` 인 200 이며 `answer` 로
 * 정답을 알려준다. 실패로 다뤄야 하는 것은 아래 에러들뿐이다.
 *
 * 적립되는 것은 포인트(`point`)뿐이고 누적 점수(`bqScore`)는 건드리지 않는다.
 * 반환된 `totalPoint` 는 `GET /users/me` 의 `point` 와 같은 값이라, 유저 스토어를
 * 재조회 없이 이 값으로 갱신하면 된다.
 *
 * 에러: 400 OPTION_NOT_FOUND / `option` 누락, 404 NOT_FOUND, 409 ALREADY_SUBMITTED,
 * 401 UNAUTHENTICATED. 409 는 오류가 아니라 "이미 푼 문제" 상태로 처리한다.
 *
 * @param option 고른 보기의 `options[].no`(0-기반). O/X 는 `OX_OPTION_NO` 참고.
 */
export function submitQuiz(quizId: number, option: number): Promise<QuizSubmitResult> {
  const body: QuizSubmitRequest = { option };

  return gameClient
    .post<ApiResponse<QuizSubmitResult>>(`/quizzes/${quizId}/submit`, body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /quizzes/submissions?page=N — 내 풀이 이력(최신순, 크기 20 고정) + 전체 요약.
 *
 * `summary` 는 현재 페이지가 아니라 **전체** 기준이라 첫 페이지만 받아도 통계를 그릴 수 있다.
 * 제출이 0건이어도 에러가 아니라 빈 목록 + `accuracy: 0.0` 이다.
 *
 * 에러: 401 UNAUTHENTICATED — 이 엔드포인트의 유일한 실패다.
 */
export function getQuizSubmissions(page = 0): Promise<QuizSubmissionHistory> {
  return gameClient
    .get<ApiResponse<QuizSubmissionHistory>>('/quizzes/submissions', {
      params: { page },
      requiresAuth: true,
    })
    .then(unwrap);
}
