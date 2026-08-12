import type { AxiosResponse } from 'axios';
import { gameClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type {
  DailyQuiz,
  QuizDetail,
  QuizLikeResult,
  QuizSubmissionHistory,
  QuizSubmitRequest,
  QuizSubmitResult,
} from '../types/quiz';

/**
 * 데일리 퀴즈 API (quiz 모듈).
 *
 * base 가 채팅과 같은 `/rt` 라 `gameClient` 를 그대로 쓴다(경로만 `/quizzes/*`).
 * 5개 전부 인증이 필수라 모두 `requiresAuth: true` 로 보내며, 성공도 전부 `ApiResponse`
 * 래핑이라 `unwrap` 으로 `data` 만 벗겨 반환한다.
 *
 * 이 도메인만의 계약 셋:
 * - **정답은 답한 뒤에만 공개된다.** 조회 응답에는 `answer` 키 자체가 없다.
 *   그래서 상세 응답은 `submitted` 를 판별자로 하는 유니온(`QuizDetail`)이다.
 * - **세트는 전원 동일하고 날짜 파라미터가 없다.** "오늘"은 항상 서버가 KST 로 판정한다.
 * - **한 문제는 한 번만 제출할 수 있다.** 두 번째 제출은 오류가 아니라 "이미 푼 문제"(409)다.
 * - **미편성 문제는 존재하지 않는 것과 구분되지 않는다**(둘 다 404). id 순회로 내일 출제분을
 *   미리 보는 것을 막기 위한 의도이며, 클라이언트가 구분할 방법은 없다.
 * - **좋아요는 답한 문제에만 허용된다.** 미존재·미편성·미답이 전부 같은 403 이다.
 *
 * ── ⏱️ 제출 시한 (2026-08-12 신설) ──────────────────────────────────
 * `getTodayQuizzes()` 는 **조회가 아니라 쓰기**다. 응답에 실린 문제마다 서버가 미답 행을
 * 만들고, 그 행의 **받은 시각 + 8분**이 제출 시한이 된다. 화면 설계에 직결되는 성질 셋:
 *
 *   ① **연장 수단이 없다.** `/today` 를 다시 불러도 이미 있는 행의 시한은 갱신되지 않는다.
 *   ② **넘기면 복구 경로가 없다.** 그 문제는 이후 어떤 `/today` 응답에도 다시 실리지 않고
 *      제출도 영구히 403 이다. 재발급 API 도 유예도 없다.
 *   ③ **받은 시각을 서버가 알려주지 않는다.** 남은 시간을 세려면 `/today` 응답을 받은
 *      시각을 화면이 스스로 찍어 두는 수밖에 없다.
 *
 * 그래서 목록을 미리 받아 두고 천천히 푸는 화면은 만들면 안 된다 — 받은 순간 시계가 돈다.
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
 *
 * 판별에 실제로 쓰는 것은 `OPTION_NOT_FOUND` 하나뿐이다 — 400 만 두 갈래(보기 번호 오류 /
 * 입력값 검증)라 문자열을 봐야 하고, 나머지는 상태 코드만으로 유일하다. 403 두 개도
 * 엔드포인트가 서로 달라(제출 / 좋아요) 코드만으로 갈린다.
 *
 * 그래도 전부 적어 두는 이유는 이것이 **서버와 맞춘 문자열의 목록**이기 때문이다 —
 * 화면에 그대로 띄우거나 판별을 옮길 때 여기부터 본다.
 * (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const QUIZ_ERROR_MESSAGE = {
  NOT_FOUND: '존재하지 않는 퀴즈입니다.',
  OPTION_NOT_FOUND: '존재하지 않는 보기 번호입니다.',
  ALREADY_SUBMITTED: '이미 제출한 퀴즈입니다.',
  INVALID_INPUT: '입력값이 올바르지 않습니다.',
  /** 403 `QUIZ_SUBMIT_NOT_ALLOWED` — `/today` 로 받은 적이 없거나 시한(8분)을 넘김. */
  SUBMIT_NOT_ALLOWED: '오늘의 퀴즈로 받은 문제만 제한 시간 안에 제출할 수 있습니다.',
  /** 403 `QUIZ_LIKE_NOT_ALLOWED` — 그 문제를 푼 이력이 없음(미존재·미편성 포함). */
  LIKE_NOT_ALLOWED: '좋아요는 직접 푼 문제에만 할 수 있습니다.',
} as const;

/**
 * 404 — 없는 퀴즈이거나 **아직 편성되지 않은 풀 문제**. 서버가 둘을 구분하지 않는다.
 * 목록을 다시 받아야 한다는 신호로 다루면 된다.
 */
export function isQuizNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * 409 — 이미 답한 문제.
 *
 * 같은 문제를 동시에 두 번 눌러도 미답 행을 채우는 조건부 UPDATE 의 원자성이 최종 중재해
 * "정상 1건 + 409 1건"이 된다. **오류 토스트가 아니라 "이미 푼 문제" 상태로** 처리하고,
 * 필요하면 상세를 다시 조회해 복기 정보를 채운다.
 *
 * ⚠️ 2026-08-12 부터 **판정 순서상 맨 마지막**이다(404 → 403 → 400 → 적립 → 409).
 * 중복 여부가 선검사가 아니라 UPDATE 의 영향 행 수로만 나오기 때문인데, 그 여파로
 * **이미 답한 문제에 없는 보기 번호를 보내면 409 가 아니라 400 이 온다** — 아래
 * `isQuizOptionNotFound` 주석 참고.
 */
export function isQuizAlreadySubmitted(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 403 — 제출 자격 없음(2026-08-12 신설).
 *
 * `/today` 로 받은 적이 없거나(행 없음), 받았지만 8분 시한을 넘긴 경우다.
 * **둘을 응답으로 구분할 수 없다**(상태코드·본문 문자열이 완전히 같다). 하지만 결과는
 * 전혀 달라서, 화면이 갈라야 한다면 상세 조회의 `expired` 를 봐야 한다:
 *   - 받은 적 없음 → 다음 `/today` 에서 다시 받을 수 있다(상한 20 에 밀린 경우 등)
 *   - 시한 초과 → 그 문제는 영원히 못 푼다
 *
 * 어느 쪽이든 지금 이 문제는 제출할 수 없으므로, 재시도를 권하지 말고 목록을 다시 받아야 한다.
 */
export function isQuizSubmitNotAllowed(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/**
 * 400 — 그 문제에 없는 보기 번호. 보기 개수가 문제마다 달라 정적 범위 검증이 없고
 * 서버가 보기 조회 실패로 판정한다(화면이 `options` 밖의 값을 보낸 경우).
 *
 * ⚠️ 2026-08-12 부터 **이미 답한 문제에 없는 보기 번호를 보낸 경우도 여기로 온다**
 * (종전 409). 즉 이 400 은 "보기 번호가 틀렸다"만 뜻하고 중복 제출 여부는 알려주지 않는다.
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

/**
 * 403 — 좋아요 불가. 그 문제를 푼 이력이 없다는 뜻이다.
 *
 * **미존재·미편성 풀 문제·미답 셋을 구분하지 않는 단일 응답**이다(404 가 아니다) —
 * 제출이 선행조건이 되는 순간 "없는 문제"와 "안 푼 문제"가 요청자에게 같은 상태가 되므로,
 * 하나로 합쳐 내일 출제분이 새어 나가지 않게 한 설계다.
 * 정상 흐름(푼 문제에만 버튼 노출)에서는 만날 일이 없다.
 */
export function isQuizLikeNotAllowed(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/* ------------------------------------------------------------------ *
 * 엔드포인트 — 5개 전부 인증 필수 · 성공도 ApiResponse 래핑(200)
 * ------------------------------------------------------------------ */

/**
 * GET /quizzes/today — 오늘(KST) 세트 중 **내가 아직 안 푼 문제만**.
 *
 * ⚠️ **부르는 것만으로 서버 상태가 바뀐다.** 응답에 실린 문제마다 미답 행이 생기고
 * 8분 시계가 돈다(파일 머리말 참고). 화면을 미리 데워 두려고 호출하면 안 되고,
 * 사용자가 실제로 풀기 시작하는 시점에 한 번만 불러야 한다.
 *
 * 답한 문제는 목록에서 빠진다. **시한을 넘긴 문제도 빠지며 다시는 실리지 않는다.**
 * 정렬은 선호 먼저이고 그룹 안에서는 **사용자별로 고정된 랜덤**이다 — 같은 계정은 몇 번을
 * 불러도 같은 순서를 받으므로 새로고침으로 순서가 뒤집히지 않는다.
 * 응답은 최대 20건으로 잘린다(서버 설정, 편성 수와 별개). 날짜 파라미터도 페이징도 없다.
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
 * **내 제출 상태에 따라 응답 키 집합이 달라진다.** 답하지 않았으면 `myOption`·`correct`·
 * `answer`·`liked`·`likeCount` 다섯 키가 `null` 이 아니라 아예 빠지므로, 반드시
 * `submitted` 로 분기해야 한다(`QuizDetail` 유니온이 이를 타입으로 강제한다).
 *
 * `submitted` 는 **"답했는가"**이지 "받았는가"가 아니다(2026-08-12 재정의) — 받는 순간
 * 행이 생기기 때문이다. 받아 놓고 시한을 넘긴 상태는 `expired` 가 알려준다.
 * 제출이 403 으로 막혔을 때 "시간이 지났다"와 "받은 적 없다"를 가르는 유일한 수단이다.
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
 * **새 기록을 남기는 것이 아니라 `/today` 가 만들어 둔 미답 행을 채우는 일이다**
 * (2026-08-12). 그래서 그 행이 없거나 시한이 지났으면 403 이다.
 *
 * 에러(판정 순서 그대로): 404 NOT_FOUND(미편성 포함) → **403 SUBMIT_NOT_ALLOWED** →
 * 400 OPTION_NOT_FOUND → 409 ALREADY_SUBMITTED, 그리고 401 UNAUTHENTICATED.
 * `option` 누락 400 은 컨트롤러 진입 전이라 이 순서보다 앞이다.
 * 409 는 오류가 아니라 "이미 푼 문제" 상태로 처리한다.
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
 * GET /quizzes/submissions?page=N — 내가 **받은** 퀴즈 이력(최신순, 크기 20 고정) + 전체 요약.
 *
 * ⚠️ 2026-08-12 부터 **"푼 문제"가 아니라 "받은 문제" 목록이다** — 아직 답하지 않았거나
 * 시한을 넘긴 문제도 함께 실리고, 그 항목은 `myOption`·`myOptionText` 가 `null` 이다.
 * `summary.total` 역시 그것들을 분모에 넣고 오답으로 친다("내지 않으면 틀린 것").
 * 그래서 `/today` 를 부른 직후 정답률이 떨어졌다가 풀수록 올라간다 — 화면에 그대로 띄우면
 * 오해를 사므로, 답한 것만 세고 싶다면 `content` 에서 `myOption !== null` 로 걸러야 한다
 * (단 그 값은 현재 페이지 기준이라 전체 통계가 되지는 못한다).
 *
 * `summary` 는 현재 페이지가 아니라 **전체** 기준이라 첫 페이지만 받아도 통계를 그릴 수 있다.
 * 받은 문제가 0건이어도 에러가 아니라 빈 목록 + `accuracy: 0.0` 이다.
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

/**
 * POST /quizzes/{quizId}/like — 좋아요 토글(2026-08-11 신설).
 *
 * **멱등이 아니다.** 없으면 켜고 있으면 뒤집는다 — 재시도로 두 번 나가면 원상 복귀하므로
 * 자동 재시도를 걸면 안 된다. 화면 상태의 정본은 응답의 `liked` 이니, 낙관적으로 뒤집어
 * 두었더라도 응답이 오면 그대로 덮어쓴다.
 *
 * 취소해도 행은 남고 `liked: false` 가 될 뿐이며, `likeCount` 는 취소된 것을 세지 않는다.
 *
 * 에러: 403 LIKE_NOT_ALLOWED(푼 이력 없음 — 미존재·미편성도 여기로 접힌다),
 * 401 UNAUTHENTICATED. 요청 본문은 없다.
 */
export function likeQuiz(quizId: number): Promise<QuizLikeResult> {
  return gameClient
    .post<ApiResponse<QuizLikeResult>>(`/quizzes/${quizId}/like`, undefined, { requiresAuth: true })
    .then(unwrap);
}
