import type { ApiResponse } from './api';

/**
 * 데일리 퀴즈 API(quiz 모듈, base `/rt`) 타입.
 *
 * 이 도메인의 타입을 가르는 축은 하나다 — **정답(`answer`)이 언제 실리는가.**
 * 조회 단계(`/today`·미답 상세)에는 `answer` 키 **자체가 없고**, 답한 뒤
 * (제출 응답·답한 뒤 상세·풀이 이력)에만 실린다. `null`이 오는 게 아니라 키가 빠지므로
 * 상세 응답은 옵셔널 필드가 아니라 `submitted` 를 판별자로 삼는 유니온으로 모델링한다.
 *
 * ── 2026-08-12 제출 시한 도입으로 상태가 셋이 됐다 ─────────────────────
 * `/today` 가 문제를 실어 줄 때 서버가 미답 행을 만들고, 그 행의 **받은 시각 + 8분**이
 * 제출 시한이다. 그래서 `submitted` 는 "받았는가"가 아니라 **"답했는가"**가 됐고,
 * 받아 놓고 시한을 넘긴 상태를 `expired` 가 따로 알린다.
 *
 * | `submitted` | `expired` | 상태 |
 * | --- | --- | --- |
 * | `false` | `false` | 아직 답할 수 있음(또는 받은 적 없음) |
 * | `true`  | `false` | 답함 — 복기 필드가 실린다 |
 * | `false` | `true`  | 시한 초과 — 제출하면 영구히 403, 복구 경로 없음 |
 *
 * ── 2026-08-19 `/today` 보기에 투표 수가 붙었다 ────────────────────────
 * `GET /quizzes/today` 의 `options[]` 에만 `voteCount` 가 실린다. 상세·제출·이력의
 * `options` 에는 없으므로 보기 타입을 `QuizOption` / `DailyQuizOption` 둘로 나눠 두었다.
 *
 * ── 2026-08-13 풀이 이력이 "경기 한 건의 이닝별 결산"이 됐다 ──────────
 * `GET /quizzes/submissions` 의 조회 축이 계정에서 **경기**로 좁혀지고(`gameId` 필수),
 * 응답이 페이지 구조에서 `summary` + `innings[]` 로 바뀌었다. 항목에는 `options` 가
 * 실리고 텍스트 두 필드(`myOptionText`·`answerText`)와 `quizDate` 가 빠졌다.
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

/**
 * `GET /quizzes/today` 의 보기 하나 — 투표 수가 함께 실린다(2026-08-19 신설).
 *
 * **이 필드는 `/today` 에만 있다.** 상세·제출·이력의 `options` 는 `QuizOption` 그대로라,
 * 타입을 나눠 두어 없는 쪽에서 읽으려 하면 컴파일에서 걸리게 한다.
 */
export interface DailyQuizOption extends QuizOption {
  /**
   * 그 보기를 고른 사람 수(0 이상). 서버가 항상 싣는다 — 생략도 null 도 없다.
   *
   * **서빙 시점의 근사 스냅샷이다.** 갱신 경로(SSE·폴링)가 없어 화면이 들고 있는 값은
   * 문제를 받은 순간에 멈춰 있고, 보기별 합이 참여자 수와 같다는 보장도 없다.
   *
   * ⚠️ 집계(Redis)를 못 읽었을 때도 `0` 으로 채워 200 이 온다 — **`0` 은 "아무도 안
   * 골랐다"와 "집계를 못 읽었다"를 구분하지 않는다**(docs/quiz.md). 그래서 전부 `0` 인
   * 경우를 "아직 아무 표도 없다"로만 읽어야 하고, 실패로 안내하면 안 된다.
   */
  voteCount: number;
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
  /** 투표 수가 함께 실린 보기 목록. 이 응답에만 `voteCount` 가 있다. */
  options: DailyQuizOption[];
}

/**
 * 상세 조회 — 아직 답하지 않은 경우.
 * `myOption`·`correct`·`answer`·`liked`·`likeCount` 다섯 키가 통째로 없다.
 */
export interface UnsolvedQuizDetail extends QuizBase {
  /** 출제일(`yyyy-MM-dd`). 생성일이 아니다. */
  quizDate: string;
  submitted: false;
  /**
   * 받아 놓고 시한(받은 시각 + 8분)을 넘겼는지. **저장된 값이 아니라 조회 시각 기준 계산**이다.
   *
   * `true` 면 그 문제는 이후 어떤 `/today` 응답에도 다시 실리지 않고 제출도 영구히 403 이다 —
   * 재발급도 유예도 없으므로 화면은 "다시 풀 수 있다"고 안내하면 안 된다.
   * 받은 적이 없는 문제도 `false` 라, 이 값만으로 "받았는지"를 알 수는 없다.
   */
  expired: boolean;
}

/** 상세 조회 — 답한 경우. 복기용 정보와 좋아요 상태가 함께 실린다. */
export interface SolvedQuizDetail extends QuizBase {
  quizDate: string;
  submitted: true;
  /** 답한 문제는 시한을 따지지 않는다 — 서버가 항상 `false` 로 준다. */
  expired: false;
  /** 내가 낸 보기 번호. */
  myOption: number;
  correct: boolean;
  /** 정답 보기 번호. 답했으므로 오답이어도 공개된다. */
  answer: number;
  /** 내 현재 좋아요 상태. 좋아요는 답한 문제에만 허용되므로 이쪽에만 있다. */
  liked: boolean;
  /** 그 문제의 좋아요 수(취소된 좋아요는 세지 않는다). */
  likeCount: number;
}

/**
 * `GET /quizzes/{quizId}` 응답.
 * `submitted` 로 좁히면 복기 필드 접근이 타입 수준에서 보장된다.
 *
 * ```ts
 * if (detail.submitted) render(detail.answer); // OK
 * else if (detail.expired) render('시간이 지나 풀 수 없어요');
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

/**
 * 이닝 하나의 결산. 산식은 경기 전체 요약과 같고 `earnedPoint` 만 없다.
 *
 * ⚠️ 분모(`total`)에 **답하지 않은 문제도 들어가고 오답으로 친다**(2026-08-12, 제품 결정) —
 * 그 이닝에 **받은** 문항 수이지 답한 수가 아니다(고정 20도 아니다).
 */
export interface QuizInningSummary {
  /** 그 이닝에 받은 문제 수. 답하지 않은 것도 센다. */
  total: number;
  correctCount: number;
  /** 정답률 `0.0`~`1.0`. `total = 0` 이면 `0.0`(null 아님). */
  accuracy: number;
}

/**
 * 경기 한 건의 전체 요약 — 열거된 이닝 전체의 합.
 *
 * ⚠️ **계정 누적이 아니라 이 경기 기준이다**(2026-08-13 계약 교체). 종전에 마이페이지가
 * 쓰던 "계정 전체 누적 정답률"을 주는 경로는 사라졌고 대체 API 가 아직 없다.
 */
export interface QuizGameSummary extends QuizInningSummary {
  /**
   * 정답 문항의 배점 합.
   *
   * **적립 원장이 아니라 표시용 근사치**다 — 서버가 `users_account.point` 를 읽지 않고
   * 이 경기의 정답 행만 더해 만든 값이라, 보유 포인트(`GET /users/me` 의 `point`)와는
   * 다른 수다. 화면에서 "이 경기로 얻은 포인트"로만 써야 한다.
   */
  earnedPoint: number;
}

/**
 * 이닝 하나에서 받은 문제 1건.
 *
 * **"제출한 문제" 목록이 아니라 "받은 문제" 목록이다**(2026-08-12) — 진행 중이거나
 * 시한을 넘긴 문제도 함께 실리고, 그때 `myOption` 이 `null` 이다.
 *
 * ⚠️ 2026-08-13 부터 **답 텍스트 두 필드(`myOptionText`·`answerText`)가 사라지고**
 * 대신 `options` 배열이 실린다 — 화면이 번호로 보기를 찾아 써야 한다.
 */
export interface QuizSubmission {
  quizId: number;
  question: string;
  type: QuizType;
  difficulty: QuizDifficulty | null;
  /** `/today` 의 `options` 와 같은 모양(0-기반). 답 텍스트는 여기서 번호로 찾는다. */
  options: QuizOption[];
  /** **null 가능** — 아직 답하지 않았거나 시한을 넘긴 항목. */
  myOption: number | null;
  /** 답하지 않은 항목은 `false` 다("내지 않으면 틀린 것"). */
  correct: boolean;
  /** 받아 놓고 시한을 넘겼는지. 답한 항목은 `false`. */
  expired: boolean;
  /** 정답 보기 번호. **미답 항목에도 실린다**(이미 끝난 문제라 감출 이유가 없다). */
  answer: number;
  earnedPoint: number;
  /**
   * LocalDateTime 문자열. 타임존 오프셋이 없다(예: "2026-08-08T21:15:03").
   *
   * **답을 낸 시각**이다. 다만 답하지 않은 항목에는 받은 시각이 실리므로,
   * 이 값 하나로 "답한 시각"이라고 단정해 쓰면 안 된다 — `myOption` 유무로 갈라야 한다.
   */
  submittedAt: string;
  /** 이력 항목에는 답 여부와 무관하게 항상 실린다(좋아요는 받은 문제 전체에 열려 있다). */
  liked: boolean;
  likeCount: number;
}

/**
 * 이닝 하나의 결산 묶음.
 *
 * 기록이 하나도 없는 이닝도 원소로 남는다 — `quizzes: []` + `0/0` 이다.
 * (원소 수가 곧 서버가 열거한 이닝 수이며, 진행 중 경기의 현재 이닝은 빠진다.)
 */
export interface QuizInningResult {
  /** 이닝 번호(1부터, 오름차순). */
  inning: number;
  summary: QuizInningSummary;
  /** 받은 순서(행 `id` 오름차순). */
  quizzes: QuizSubmission[];
}

/**
 * `GET /quizzes/submissions?gameId=…` 응답 — 경기 한 건의 이닝별 결산.
 *
 * ⚠️ **`innings: []` 는 서로 다른 셋을 한 모양으로 덮는다** — ① 그 경기에 내 기록이 0건
 * ② 이닝 값이 아직 없음(수집 지연 방어) ③ 진행 중이고 1회라 결산할 완료 이닝이 없음.
 * 서버가 사유를 주지 않으므로 화면도 하나의 문구로 안내한다.
 */
export interface QuizSubmissionHistory {
  summary: QuizGameSummary;
  innings: QuizInningResult[];
}

/**
 * `POST /quizzes/{quizId}/like` 응답 — 토글이 반영된 뒤의 확정 상태.
 *
 * **멱등이 아니다.** 화면이 낙관적으로 뒤집어 두더라도 이 응답이 정본이므로 그대로 덮어써야
 * 한다 — 재시도로 두 번 토글되면 원래대로 돌아온다.
 */
export interface QuizLikeResult {
  liked: boolean;
  likeCount: number;
}

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — 5개 전부 성공도 ApiResponse로 감싼다
 * ------------------------------------------------------------------ */

export type DailyQuizListApiResponse = ApiResponse<DailyQuiz[]>;
export type QuizDetailApiResponse = ApiResponse<QuizDetail>;
export type QuizSubmitResultApiResponse = ApiResponse<QuizSubmitResult>;
export type QuizSubmissionHistoryApiResponse = ApiResponse<QuizSubmissionHistory>;
export type QuizLikeResultApiResponse = ApiResponse<QuizLikeResult>;
