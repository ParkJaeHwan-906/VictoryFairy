import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  ApiError,
  getTodayQuizzes,
  isQuizAlreadySubmitted,
  isQuizNotFound,
  submitQuiz,
} from '../api';
import type { DailyQuiz } from '../api';
import QuizCard, { type QuizCardExit } from '../components/QuizCard';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES, type QuizPageState } from '../routes';
import { formatKoreanDate, getTodayInSeoul } from '../utils/date';
import '../styles/QuizPage.css';

/**
 * QuizPage — 오늘의 퀴즈 풀이.
 * Figma: SWM / [Game] 퀴즈 메인(OorX) (node 744:22436)
 *
 * 경기 상세 시트의 "퀴즈 풀러 가기"로 들어온다. NavBar 는 디자인에 없어
 * `AppLayout` 밖 라우트다(온보딩 화면들과 같은 구성).
 *
 * 화면이 가진 것은 상단 바와 카드 더미뿐이다 — 날짜·맞대결·문항 번호가 모두
 * 카드 안으로 들어가 있어, 화면은 어느 문제를 보여줄지만 정한다.
 *
 * **정오는 여기서 보여주지 않는다.** 제출 응답에 정답이 실려 오지만 채점 화면이
 * 따로 있으므로, 이 화면은 고르고 다음 문제로 넘기는 일만 한다.
 *
 * ── 경기와 문제의 관계 ────────────────────────────────────────────────
 * **퀴즈 API 는 경기별로 문제를 주지 않는다.** `GET /quizzes/today` 는 그날의
 * 세트(전원 동일)를 줄 뿐이고 `gameId` 로 좁히는 파라미터가 없다(docs/quiz.md).
 * 지금 할 수 있는 가장 가까운 것은 `preferredOnly=true` — 내 응원 구단·선수와
 * 매칭되는 문제만 남기는 필터다. 그래서 이 화면은
 *   ① 진입 자체를 응원 구단 경기로 제한하고(GameDetailSheet 가 CTA 를 가린다)
 *   ② 받은 세트도 선호 문제로 좁힌다
 * 는 두 겹으로 "선호 구단 경기 문제"에 근사한다. 경기 단위로 정확히 묶으려면
 * 백엔드에 `gameId` 필터가 생겨야 한다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 디자인 아래쪽의 실시간 선택율(O 65% / X 35% 와 게이지, 744:22706~22748)은 넣지 않았다 —
 * **보기별 선택 비율을 주는 API 가 없다**(docs/quiz.md). 제출 응답이 주는 것은 내 정오와
 * 정답 번호뿐이고 `accuracy` 도 내 누적 정답률이라 다른 값이다. 숫자를 지어내지 않는다.
 */

/**
 * 카드가 빠져나가는 데 걸리는 시간(ms).
 * CSS 로 넘겨 애니메이션과 대기 시간이 한 값에서 나오게 한다 — 둘이 어긋나면
 * 카드가 사라지기 전에 다음 문제가 뜨거나, 빈 자리가 잠깐 남는다.
 */
const CARD_EXIT_MS = 280;

/** 카드로 보여줄 뒷장 수. 디자인은 2장이고, 남은 문제가 적으면 그만큼 줄인다. */
const MAX_DECK_LAYERS = 2;

/**
 * 앞 화면(경기 상세)이 넘긴 경기 문맥을 읽는다.
 *
 * 주소를 직접 치면 비어 있고, history 에 남은 옛 형태가 되살아날 수도 있다.
 * 없으면 상단 바 제목만 일반 문구로 바뀔 뿐 퀴즈는 정상 동작한다 —
 * 조회 조건이 아니라 표시용 값이기 때문이다.
 */
function readQuizState(state: unknown): QuizPageState | null {
  if (typeof state !== 'object' || state === null) return null;

  const { gameId, awayTeam, homeTeam } = state as Partial<QuizPageState>;
  return typeof gameId === 'string' && typeof awayTeam === 'string' && typeof homeTeam === 'string'
    ? { gameId, awayTeam, homeTeam }
    : null;
}

/** 서버의 짧은 구단명을 디자인의 정식 명칭으로 바꾼다. 모르는 구단이면 원문 그대로. */
function teamLabel(name: string): string {
  return getTeamDisplay(name)?.label ?? name;
}

function toSubmitMessage(error: unknown): string {
  return error instanceof ApiError
    ? error.message
    : '답을 제출하지 못했어요. 잠시 후 다시 시도해주세요.';
}

/** 카드가 다 빠져나갈 때까지 기다리는 지연. */
function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export default function QuizPage() {
  const navigate = useNavigate();
  const context = readQuizState(useLocation().state);

  const [quizzes, setQuizzes] = useState<DailyQuiz[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [index, setIndex] = useState(0);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** 카드가 빠져나가는 방향. 제출이 실패하면 되돌려야 해서 화면이 들고 있다. */
  const [exit, setExit] = useState<QuizCardExit | null>(null);
  /** 이번에 실제로 제출한 문제 수. 완료 화면에만 쓴다(정오는 세지 않는다). */
  const [submittedCount, setSubmittedCount] = useState(0);

  useEffect(() => {
    // 화면을 떠난 뒤 도착한 응답으로 state 를 건드리지 않도록 막는다.
    let alive = true;

    // 응원 정보가 하나도 없으면 서버가 이 필터를 무시하고 전체를 준다 — 오류가 아니다.
    getTodayQuizzes(true)
      .then((list) => {
        if (alive) setQuizzes(list);
      })
      .catch((error: unknown) => {
        if (alive) {
          setLoadError(
            error instanceof ApiError
              ? error.message
              : '퀴즈를 불러오지 못했어요. 잠시 후 다시 시도해주세요.',
          );
        }
      });

    return () => {
      alive = false;
    };
  }, []);

  const handleSelect = (optionNo: number, direction: QuizCardExit) => {
    const quiz = quizzes?.[index];
    if (!quiz || isSubmitting || exit !== null) return;

    // 카드는 응답을 기다리지 않고 바로 빠진다 — 고른 즉시 반응해야 하기 때문이다.
    setExit(direction);
    setIsSubmitting(true);
    setSubmitError(null);

    /*
     * 응답과 애니메이션 둘 다 끝나야 다음 문제로 넘어간다.
     * 응답이 더 빠르면 카드가 사라지기 전에 내용이 바뀌고, 애니메이션이 더 빠르면
     * 빈 자리가 남는다 — 늦은 쪽에 맞춰야 둘 다 안 생긴다.
     */
    Promise.all([submitQuiz(quiz.id, optionNo), wait(CARD_EXIT_MS)])
      .then(() => {
        setSubmittedCount((current) => current + 1);
        goNext();
      })
      .catch((error: unknown) => {
        /*
         * 409 는 실패가 아니라 "이미 푼 문제"다 — 같은 문제를 두 번 눌러 경합이 나면
         * 정상 1건 + 409 1건이 되는 것이 정상이므로 오류로 띄우지 않는다(docs/quiz.md).
         * 404 는 없거나 미편성된 문제라 여기서 더 보여줄 것이 없다. 둘 다 그냥 넘긴다.
         */
        if (isQuizAlreadySubmitted(error) || isQuizNotFound(error)) {
          goNext();
          return;
        }

        // 그 밖의 실패는 답이 저장되지 않았다는 뜻이라 카드를 되돌려 다시 고르게 한다.
        setExit(null);
        setSubmitError(toSubmitMessage(error));
      })
      .finally(() => {
        setIsSubmitting(false);
      });
  };

  const goNext = () => {
    setExit(null);
    setSubmitError(null);
    setIndex((current) => current + 1);
  };

  /*
   * 맞대결은 상단 바가 아니라 카드 머리말에 쓴다 — 상단 바 제목은 디자인이 "경기 퀴즈"로
   * 고정이다. 문맥 없이 들어오면(주소 직접 입력) 카드에서 그 줄만 빠진다.
   */
  const matchLabel = context
    ? `${teamLabel(context.awayTeam)} VS ${teamLabel(context.homeTeam)}`
    : null;

  /* 오늘의 세트라 카드 날짜는 곧 오늘이다 — 서버가 판정하는 "오늘"과 같은 서울 기준. */
  const dateLabel = formatKoreanDate(getTodayInSeoul());

  const quiz = quizzes?.[index] ?? null;
  const isDone = quizzes !== null && index >= quizzes.length;
  const isEmpty = quizzes !== null && quizzes.length === 0;

  /* 남은 문제 수만큼 뒷장을 깐다. 현재 카드를 뺀 나머지가 더미다. */
  const remaining = quizzes ? quizzes.length - index : 0;
  const layerCount = Math.max(0, Math.min(MAX_DECK_LAYERS, remaining - 1));
  /* 뒤에 깔릴수록 먼저 그려야 앞장에 가려진다 — DOM 순서가 곧 쌓임 순서다. */
  const layers = Array.from({ length: layerCount }, (_, order) => layerCount - order);

  return (
    <div
      className="quiz-page"
      // 카드 퇴장 시간을 CSS 와 나눠 쓴다(위 CARD_EXIT_MS 주석 참고).
      style={{ '--quiz-card-exit-duration': `${CARD_EXIT_MS}ms` } as React.CSSProperties}
    >
      <header className="quiz-page__topbar">
        <button
          className="quiz-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="quiz-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="quiz-page__topbar-title">경기 퀴즈</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="quiz-page__topbar-spacer" aria-hidden="true" />
      </header>

      <div className="quiz-page__body">
        {quizzes === null && loadError === null && (
          <p className="quiz-page__status">퀴즈를 불러오는 중이에요</p>
        )}

        {loadError !== null && (
          <p className="quiz-page__status quiz-page__status--error">{loadError}</p>
        )}

        {/*
          빈 배열은 "오늘 세트가 없음"과 "오늘 세트를 다 풀었음"이 구분되지 않는다
          (docs/quiz.md) — 둘 다 아우르는 문구를 쓴다.
        */}
        {isEmpty && (
          <p className="quiz-page__status">
            지금 풀 수 있는 퀴즈가 없어요.
            <br />
            오늘 몫을 이미 다 풀었을 수도 있어요.
          </p>
        )}

        {quiz !== null && (
          <div className="quiz-page__deck">
            {layers.map((depth) => (
              <span
                key={depth}
                className={`quiz-page__layer quiz-page__layer--${depth}`}
                aria-hidden="true"
              />
            ))}

            <QuizCard
              // 문제가 바뀌면 카드를 새로 만든다 — 이전 문제의 끌린 위치·퇴장 상태가 남지 않게 한다.
              key={quiz.id}
              quiz={quiz}
              order={index + 1}
              total={quizzes?.length ?? 0}
              dateLabel={dateLabel}
              matchLabel={matchLabel}
              isSubmitting={isSubmitting}
              exit={exit}
              onSelect={handleSelect}
            />
          </div>
        )}

        {submitError !== null && (
          <p className="quiz-page__status quiz-page__status--error" role="alert">
            {submitError}
          </p>
        )}

        {isDone && !isEmpty && (
          <div className="quiz-page__done">
            <p className="quiz-page__done-title">오늘의 퀴즈를 모두 풀었어요!</p>
            {/* 정오·적립 포인트는 채점 화면 몫이라 개수만 알린다. */}
            <p className="quiz-page__done-score">{submittedCount}문제를 풀었어요</p>
            <button
              className="quiz-page__done-action"
              type="button"
              onClick={() => navigate(ROUTES.game)}
            >
              경기로 돌아가기
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
