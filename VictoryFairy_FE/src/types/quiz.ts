import type { ApiResponse } from './api';

/**
 * 데일리 퀴즈 API(quiz 모듈, base `/rt`) 타입.
 *
 * 이 도메인의 타입을 가르는 축은 하나다 — **정답(`answer`)이 언제 실리는가.**
 * 조회 단계(`/today`·미제출 상세)에는 `answer` 키 **자체가 없고**, 제출한 뒤
 * (제출 응답·제출 후 상세·풀이 이력)에만 실린다. `null`이 오는 게 아니라 키가 빠지므로
 * 상세 응답은 옵셔널 필드가 아니라 `submitted` 를 판별자로 삼는 유니온으로 모델링한다.
 */

/** 렌더링 분기용 유형. `"O/X"` 는 보기 2개(0=O, 1=X)인 객관식과 같은 모양으로 내려온다. */
export type QuizType = '객관식' | 'O/X';

/** 난이도 배지. 사람이 직접 쓴 문제는 값이 없어 `null` 이 온다. */
export type QuizDifficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'EXPERT';

/** 보기 하나. `no` 는 표기 순서이자 제출 시 보낼 번호다(0-기반). */
export interface QuizOption {
  no: number;
  text: string;
}

/** 목록·상세가 공유하는 문제 본문. 정답 관련 필드는 여기에 없다. */
interface QuizBase {
  /** 퀴즈 식별자. 상세 조회·제출이 이 값으로 문제를 지목한다. */
  id: number;
  type: QuizType;
  question: string;
  /** **null 가능** — 사람이 직접 쓴 퀴즈. */
  difficulty: QuizDifficulty | null;
  /** 배점(정답 시 적립될 포인트). **null 가능** — 배지·안내 문구에서 null 처리 필수. */
  point: number | null;
  /** `no` 오름차순. */
  options: QuizOption[];
}

/**
 * `GET /quizzes/today` 항목.
 * 이미 제출한 문제는 목록에서 빠지므로 여기 있는 것은 전부 미제출이다 —
 * 그래서 `submitted`·`quizDate` 가 없다.
 */
export interface DailyQuiz extends QuizBase {
  /** 내 응원 구단·선수와 매칭되는지 여부. 정렬 근거이자 뱃지 표시용. */
  preferred: boolean;
}

/** 상세 조회 — 아직 제출하지 않은 경우. `myOption`·`correct`·`answer` 키가 없다. */
export interface UnsolvedQuizDetail extends QuizBase {
  /** 출제일(`yyyy-MM-dd`). 생성일이 아니다. */
  quizDate: string;
  submitted: false;
}

/** 상세 조회 — 제출한 경우. 복기용 정보가 함께 실린다. */
export interface SolvedQuizDetail extends QuizBase {
  quizDate: string;
  submitted: true;
  /** 내가 낸 보기 번호. */
  myOption: number;
  correct: boolean;
  /** 정답 보기 번호. 제출했으므로 오답이어도 공개된다. */
  answer: number;
}

/**
 * `GET /quizzes/{quizId}` 응답.
 * `submitted` 로 좁히면 복기 필드 접근이 타입 수준에서 보장된다.
 *
 * ```ts
 * if (detail.submitted) render(detail.answer); // OK
 * ```
 */
export type QuizDetail = UnsolvedQuizDetail | SolvedQuizDetail;

/** `POST /quizzes/{quizId}/submit` 요청 본문. */
export interface QuizSubmitRequest {
  /** 고른 보기의 `options[].no`(0-기반). 누락하면 400. */
  option: number;
}

/** 채점 결과. 채점·포인트 적립·제출 기록이 한 트랜잭션에서 끝난 뒤의 상태다. */
export interface QuizSubmitResult {
  correct: boolean;
  /** 정답 보기 번호(오답이어도 실린다). */
  answer: number;
  /** 내가 낸 번호(에코). */
  myOption: number;
  /** 이번에 적립된 포인트. 오답이거나 배점이 null이면 `0`. */
  earnedPoint: number;
  /** 적립 후 보유 포인트 잔액. `GET /users/me` 의 `point` 와 같은 값이다. */
  totalPoint: number;
}

/** 페이지와 무관한 **전체** 제출 요약. */
export interface QuizSubmissionSummary {
  /** 전체 제출 수(현재 페이지가 아니다). */
  total: number;
  correctCount: number;
  /** 정답률 `0.0`~`1.0`. 제출이 0건이면 `0.0`(null 아님). */
  accuracy: number;
}

/** 풀이 이력 1건. 이미 제출한 문제라 정답 번호·텍스트가 함께 온다(복기 화면 전제). */
export interface QuizSubmission {
  quizId: number;
  question: string;
  type: QuizType;
  difficulty: QuizDifficulty | null;
  /** 출제일(`yyyy-MM-dd`). */
  quizDate: string;
  myOption: number;
  myOptionText: string;
  correct: boolean;
  answer: number;
  answerText: string;
  earnedPoint: number;
  /** LocalDateTime 문자열. 타임존 오프셋이 없다(예: "2026-08-08T21:15:03"). */
  submittedAt: string;
}

/**
 * 풀이 이력 페이지.
 * 채팅 히스토리(`ChatMessagePage`)와 같은 `PageResponse` 구조이며 크기만 30이 아니라 20이다.
 */
export interface QuizSubmissionPage {
  /** 최신순. */
  content: QuizSubmission[];
  page: number;
  /** 서버 고정값 20. */
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/** `GET /quizzes/submissions` 응답 — 전체 요약 + 현재 페이지. */
export interface QuizSubmissionHistory {
  summary: QuizSubmissionSummary;
  submissions: QuizSubmissionPage;
}

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — 4개 전부 성공도 ApiResponse로 감싼다
 * ------------------------------------------------------------------ */

export type DailyQuizListApiResponse = ApiResponse<DailyQuiz[]>;
export type QuizDetailApiResponse = ApiResponse<QuizDetail>;
export type QuizSubmitResultApiResponse = ApiResponse<QuizSubmitResult>;
export type QuizSubmissionHistoryApiResponse = ApiResponse<QuizSubmissionHistory>;
