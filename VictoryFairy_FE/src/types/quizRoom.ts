import type { ApiResponse } from './api';
import type { QuizOption, QuizType } from './quiz';

/**
 * 퀴즈방 API(quiz 모듈, base `/rt`, 경로 `/quiz-rooms`, `docs/quiz-room.md`) 타입.
 *
 * 오늘의 퀴즈(`types/quiz.ts`, `/rt/quizzes`)와는 **별개 도메인**이다(문항·풀이 기록·보상 분리).
 * 이름이 겹치지 않게 전부 `QuizRoom` 접두사를 붙인다. 다만 문항 유형(`QuizType`)과
 * 보기(`QuizOption` = `{no, text}`)는 모양이 완전히 같아 그대로 가져다 쓴다.
 *
 * 이 도메인 타입의 성질 셋:
 * - **정답·분포는 REST 에 실리지 않는다.** 퀴즈 리스트에는 `answer` 키가 없고(다 푼 계정도 같음),
 *   정답은 마지막 답 제출의 `result` 로만, 보기별 투표 수는 SSE `votes` 로만 온다.
 * - **풀이 이력 조회 API 가 없다.** 마지막 제출 응답의 `result` 가 결과를 받는 유일한 기회다.
 * - 보기 번호(`no`)·`answer`·`option` 은 모두 **0부터**다.
 */

/** 작성자. id·uid 는 노출하지 않는다. */
export interface QuizRoomAuthor {
  nickname: string;
  /** 프로필 이미지 EP. 화면에서 `toAssetUrl()` 을 거친다. 없으면 `null`. */
  profileImgUrl: string | null;
}

/**
 * `RoomSummaryResponse` — 목록(`GET /quiz-rooms`)·인기(`/popular`) 항목.
 *
 * `likeCount`·`solvingCount` 는 **응답 시점 값**이다. 인기 목록은 순서가 주간 확정 시점에
 * 고정돼 있어 숫자와 순서가 어긋나 보일 수 있다.
 */
export interface QuizRoomSummary {
  /** 방 id. 이후 모든 경로의 `{roomId}` */
  roomId: number;
  title: string;
  description: string;
  author: QuizRoomAuthor;
  /** 문항 수(1~20) */
  quizCount: number;
  /** 추천 수 */
  likeCount: number;
  /**
   * 풀이 중 인원 — SSE `presence.count` 와 같은 Redis 집계다.
   * Redis 장애 시 `0` 으로 채워 200 이 오므로 `0` 을 실패로 안내하지 않는다.
   */
  solvingCount: number;
  /** 생성 시각. 오프셋 없는 KST LocalDateTime(예: "2026-10-14T14:03:21") */
  createdAt: string;
}

/** `RoomDetailResponse` — 상세. 요약 8필드에 요청자 기준 두 값이 붙는다. */
export interface QuizRoomDetail extends QuizRoomSummary {
  /** 내가 추천 중인지 */
  liked: boolean;
  /** 내가 작성자인지(JSON 키 정확히 `isAuthor`). 작성자도 풀고 추천할 수 있다 — 구분용일 뿐이다. */
  isAuthor: boolean;
}

/** 페이지 응답(`GET /quiz-rooms`). `page` 는 0부터. */
export interface QuizRoomPage {
  content: QuizRoomSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/** 목록 페이징 파라미터. `page` 기본 0(0 이상), `size` 기본 20(**1~50**, 벗어나면 400). */
export interface QuizRoomPageParams {
  page?: number;
  size?: number;
}

/* ------------------------------------------------------------------ *
 * 생성
 * ------------------------------------------------------------------ */

/**
 * 생성 요청의 문항 하나(`QuizItem`). `type` 으로 갈리는 유니온이다.
 *
 * 공통 제약: `question` 공백 불가·최대 200자, `answer` 는 보기 번호 범위 안(0부터).
 */
export type QuizRoomCreateQuizItem = QuizRoomChoiceQuizItem | QuizRoomOxQuizItem;

/** 객관식 문항 — 보기 2~4개, 각 1~100자(공백만은 불가). `answer` 는 `0 <= answer < options.length`. */
export interface QuizRoomChoiceQuizItem {
  type: Extract<QuizType, '객관식'>;
  question: string;
  options: string[];
  answer: number;
}

/**
 * O/X 문항 — 서버가 보기를 `O`(0)·`X`(1)로 고정 저장하므로 `options` 는 보내지 않는다
 * (보내도 무시된다). `answer` 는 0(O) 또는 1(X).
 */
export interface QuizRoomOxQuizItem {
  type: Extract<QuizType, 'O/X'>;
  question: string;
  options?: string[];
  answer: number;
}

/**
 * `POST /quiz-rooms` 요청 본문. 값은 다듬지 않고 그대로 저장되며 길이는 `String.length()` 기준.
 * 문항 표시 순서는 배열 순서다.
 */
export interface QuizRoomCreateRequest {
  /** 공백 불가, 최대 50자 */
  title: string;
  /** 공백 불가, 최대 500자 */
  description: string;
  /** 1~20개 */
  quizzes: QuizRoomCreateQuizItem[];
}

/** 생성 201 응답 `data`. */
export interface QuizRoomCreateResult {
  roomId: number;
}

/* ------------------------------------------------------------------ *
 * 풀이
 * ------------------------------------------------------------------ */

/**
 * `GET /quiz-rooms/{roomId}/quizzes` 항목 — 풀이용 문항.
 * **정답·투표 분포 키가 없다.** 보기는 `no` 오름차순, 문항은 생성 순서.
 */
export interface QuizRoomQuiz {
  quizId: number;
  type: QuizType;
  question: string;
  /** O/X 는 `[{no:0,text:"O"},{no:1,text:"X"}]` 로 내려온다. */
  options: QuizOption[];
}

/** `POST /quiz-rooms/{roomId}/quizzes/{quizId}/answer` 요청 본문. */
export interface QuizRoomAnswerRequest {
  /** 고른 보기 번호(0부터). 없는 번호·누락은 400 QUIZ_OPTION_NOT_FOUND. */
  option: number;
}

/** 결과의 문항 하나 — 풀이용 문항에 내 답·정답·정오가 붙는다. */
export interface QuizRoomResultQuiz extends QuizRoomQuiz {
  myOption: number;
  answer: number;
  correct: boolean;
}

/** `QuizRoomResultResponse` — 마지막 답 제출에만 실리는 방 전체 결과. */
export interface QuizRoomResult {
  correctCount: number;
  total: number;
  /** 문항 생성 순서 */
  quizzes: QuizRoomResultQuiz[];
}

/** 답 제출 응답 `data`. */
export interface QuizRoomAnswerResult {
  /** 이 계정이 이 방에서 답한 문항 수(이번 포함) */
  answeredCount: number;
  /** 방의 문항 수 */
  total: number;
  /**
   * 마지막 제출(답이 문항 수만큼 쌓인 순간)에만 채워지고 그 전엔 `null`(키는 항상 실린다).
   * 이력 조회 API 가 없어 **여기서 받지 못하면 다시 받을 방법이 없다.**
   */
  result: QuizRoomResult | null;
}

/* ------------------------------------------------------------------ *
 * 추천
 * ------------------------------------------------------------------ */

/** `PUT /quiz-rooms/{roomId}/like` 요청 본문 — 토글이 아니라 **지정**이다. */
export interface QuizRoomLikeRequest {
  /** true 추천 / false 해제 */
  liked: boolean;
}

/** 추천 응답 `data` — 처리 후 상태. */
export interface QuizRoomLikeResult {
  /** 처리 후 상태(= 요청값) */
  liked: boolean;
  /** 처리 후 방의 추천 수 */
  likeCount: number;
}

/* ------------------------------------------------------------------ *
 * SSE 페이로드 — `GET /quiz-rooms/{roomId}/subscribe` (ApiResponse 래핑 없음, `id:` 없음)
 * ------------------------------------------------------------------ */

/** `event: presence` — 그 방 구독 중인 고유 계정 수(본인 포함, 전 파드 합산). */
export interface QuizRoomPresenceEvent {
  count: number;
}

/** 보기 하나의 투표 수. */
export interface QuizRoomVoteOption {
  no: number;
  count: number;
}

/** `event: votes` — 한 문항의 보기별 투표 수. 0표 보기까지 전부, `no` 오름차순. */
export interface QuizRoomVotesEvent {
  quizId: number;
  options: QuizRoomVoteOption[];
}

/* ------------------------------------------------------------------ *
 * 도메인 ErrorCode
 * ------------------------------------------------------------------ */

/**
 * 퀴즈방 도메인 ErrorCode 이름. **응답 본문에는 이 이름이 오지 않는다** — 프론트가 받는 건
 * `message` 문자열뿐이라 `api/quizRoom.ts` 의 `resolveQuizRoomErrorCode()` 가 상태 코드와
 * 메시지로 역판별한다.
 */
export type QuizRoomErrorCode =
  | 'QUIZ_ROOM_NOT_FOUND'
  | 'QUIZ_NOT_FOUND'
  | 'QUIZ_OPTION_NOT_FOUND'
  | 'QUIZ_ALREADY_SUBMITTED';

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — SSE 구독을 뺀 8개는 성공도 ApiResponse 로 감싼다
 * ------------------------------------------------------------------ */

export type QuizRoomCreateApiResponse = ApiResponse<QuizRoomCreateResult>;
export type QuizRoomPageApiResponse = ApiResponse<QuizRoomPage>;
export type QuizRoomPopularApiResponse = ApiResponse<QuizRoomSummary[]>;
export type QuizRoomDetailApiResponse = ApiResponse<QuizRoomDetail>;
export type QuizRoomQuizListApiResponse = ApiResponse<QuizRoomQuiz[]>;
export type QuizRoomAnswerApiResponse = ApiResponse<QuizRoomAnswerResult>;
export type QuizRoomLikeApiResponse = ApiResponse<QuizRoomLikeResult>;
