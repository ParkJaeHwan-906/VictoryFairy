import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  ApiError,
  getTodayQuizzes,
  isQuizAlreadyServedInInning,
  isQuizAlreadySubmitted,
  isQuizNotFound,
  isQuizNotServable,
  isQuizSubmitNotAllowed,
  submitQuiz,
} from '../api';
import type { DailyQuiz } from '../api';
import ConfirmSheet from '../components/ConfirmSheet';
import QuizCard, { type QuizCardExit } from '../components/QuizCard';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES, readQuizPageState } from '../routes';
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
 * ── ⏱️ 세트 전체에 8분이 걸려 있다 ──────────────────────────────────
 * `GET /quizzes/today` 는 응답에 실은 문제마다 미답 행을 만들고, 그 시각 + 8분이
 * 제출 시한이다(docs/quiz.md). 이 화면은 세트를 **한 번에** 받으므로 **10문제의 시계가
 * 동시에 돌기 시작한다** — 8분을 넘긴 뒤 고른 답은 403 으로 거절되고, 그 문제는 영영
 * 다시 받을 수 없다. 지금은 넘어갈 때 안내만 하고 남은 시간을 보여주지는 않는다.
 * 서버가 받은 시각을 응답에 주지 않아, 세려면 이 화면이 수신 시각을 스스로 찍어야 한다.
 *
 * ── 경기와 문제의 관계 ────────────────────────────────────────────────
 * **`gameId` 가 이제 필수 조회 조건이다**(2026-08-12). 다만 문제를 고르는 값은 아니다 —
 * 세트는 여전히 전원 동일하고, `gameId` 의 역할은 **"지금 보고 있는 경기가 오늘·내 응원
 * 구단·진행 중인가"를 검증하고 받는 행에 찍을 이닝을 확보하는 것** 둘뿐이다(docs/quiz.md).
 * 그래서 문제를 응원 구단 쪽으로 좁히는 일은 여전히 `preferredOnly=true` 가 한다.
 *
 * 문맥(`QuizPageState`)이 없으면 조회 자체를 걸 수 없으므로, 이 화면은 주소를 직접 치고
 * 들어온 경우 아무것도 부르지 않고 돌아가라고 안내한다.
 *
 * ── 한 이닝에 한 세트 ─────────────────────────────────────────────────
 * 같은 `(경기, 이닝)`으로 다시 부르면 409 다. **되받기가 아니라 거절**이므로, 이 화면이
 * 받은 세트를 잃으면(새로고침·이탈) 그 문제들은 8분 뒤 오답으로 확정되고 돌아오지 않는다.
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
 * 나가기 전에 한 번 더 묻는 문구.
 *
 * 이탈은 되돌릴 수 없다 — 같은 `(경기, 이닝)` 으로 다시 부르면 409 라 받은 세트를
 * 되받을 수 없고, 남은 문제는 8분이 지나면 오답으로 확정된다(위 화면 주석 참고).
 * 줄은 `ConfirmSheet` 규약대로 디자인이 끊어 둔 자리에서 끊는다.
 */
const LEAVE_CONFIRM = {
  title: '퀴즈를 그만 풀까요?',
  description: [
    '지금 나가면 이 문제로 다시 돌아올 수 없어요.',
    '답하지 않은 문제는 오답으로 처리돼요.',
  ],
  confirmLabel: '나가기',
} as const;

/**
 * 나갈 때 되돌리는 히스토리 칸 수.
 *
 * 이 화면은 시스템 뒤로가기를 받으려고 같은 주소로 자리를 하나 더 쌓아 둔다(아래 효과
 * 주석 참고). 그 자리는 보여줄 화면이 아니라 뒤로가기를 받아 내는 덫이라, 나갈 때는
 * 덫과 원래 자리 두 칸을 함께 되돌려야 들어오기 전 화면에 닿는다.
 */
const LEAVE_HISTORY_DELTA = -2;

/** 문맥 없이 들어와 조회를 걸 수 없을 때의 안내. */
const NO_CONTEXT_MESSAGE = '경기 정보가 없어 퀴즈를 불러올 수 없어요. 경기 목록에서 다시 들어와 주세요.';

/**
 * 세트를 받지 못한 이유를 화면 문구로 옮긴다.
 *
 * 서버 문구를 그대로 쓰지 않는 이유 — 403 은 다섯 사유("경기 없음"·"오늘 아님"·"내 구단
 * 아님"·"진행 중 아님"·"이닝 없음")가 한 응답으로 합쳐져 있어 원인을 단정할 수 없고,
 * 409 는 실패가 아니라 "다음 이닝을 기다리라"는 안내다. 둘 다 재시도로 풀리지 않으므로
 * 다시 시도를 권하지 않는다.
 */
function toLoadMessage(error: unknown): string {
  if (isQuizAlreadyServedInInning(error)) {
    return '이번 이닝 문제는 이미 받았어요. 다음 이닝에 새 문제가 나와요.';
  }

  if (isQuizNotServable(error)) {
    return '지금은 퀴즈를 받을 수 없어요. 경기가 진행 중일 때만 문제가 나와요.';
  }

  return error instanceof ApiError
    ? error.message
    : '퀴즈를 불러오지 못했어요. 잠시 후 다시 시도해주세요.';
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
  const context = readQuizPageState(useLocation().state);
  const gameId = context?.gameId ?? null;

  const [quizzes, setQuizzes] = useState<DailyQuiz[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [index, setIndex] = useState(0);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  /**
   * 답이 반영되지 못한 채 다음 문제로 넘어갔다는 안내.
   *
   * `submitError` 와 나눠 둔 이유 — 저쪽은 "이 카드에서 다시 해보라"는 뜻이지만
   * 이쪽은 이미 넘어간 뒤라 되돌릴 것이 없다. 같은 상태로 묶으면 다시 시도할 수 있다고 읽힌다.
   */
  const [skipNotice, setSkipNotice] = useState<string | null>(null);
  /** 카드가 빠져나가는 방향. 제출이 실패하면 되돌려야 해서 화면이 들고 있다. */
  const [exit, setExit] = useState<QuizCardExit | null>(null);
  /** 이번에 실제로 제출한 문제 수. 완료 화면에만 쓴다(정오는 세지 않는다). */
  const [submittedCount, setSubmittedCount] = useState(0);
  /** 나가기를 다시 묻는 시트가 떠 있는지. */
  const [isLeaveConfirmOpen, setIsLeaveConfirmOpen] = useState(false);

  /** 뒤로가기를 받아 낼 자리를 이미 쌓았는지. StrictMode 가 효과를 두 번 태워도 하나만 쌓는다. */
  const backGuardRef = useRef(false);
  /** 지금 풀 문제가 화면에 있는지. `popstate` 는 렌더 밖에서 와 state 를 읽을 수 없다. */
  const hasQuizRef = useRef(false);

  useEffect(() => {
    /*
     * 경기를 모르면 호출 자체가 400 이라 아예 걸지 않는다 —
     * 서버 상태를 바꾸는 요청이라 "일단 던져 보는" 편이 더 나쁘다.
     */
    if (gameId === null) {
      setLoadError(NO_CONTEXT_MESSAGE);
      return;
    }

    // 화면을 떠난 뒤 도착한 응답으로 state 를 건드리지 않도록 막는다.
    let alive = true;

    // 여기까지 왔다면 응원 구단은 이미 확인된 상태라 이 필터는 항상 실제로 걸린다.
    getTodayQuizzes(gameId, true)
      .then((list) => {
        if (alive) setQuizzes(list);
      })
      .catch((error: unknown) => {
        if (alive) setLoadError(toLoadMessage(error));
      });

    return () => {
      alive = false;
    };
  }, [gameId]);

  /*
   * 시스템 뒤로가기(브라우저 · 안드로이드 · 스와이프)도 상단 바 뒤로가기와 같게 다룬다.
   *
   * `BrowserRouter` 는 데이터 라우터가 아니라 `useBlocker` 를 쓸 수 없다. 대신 같은
   * 주소로 자리를 하나 더 쌓아 두고, 뒤로가기가 그 자리를 먹으면 곧바로 다시 쌓는다 —
   * 주소가 같으니 화면은 그대로 남고, 우리는 뒤로가기가 눌렸다는 것만 넘겨받는다.
   *
   * 쌓는 자리에는 지금 자리의 값을 그대로 실어 둔다. 비워 두면 그 자리에 되돌아왔을 때
   * `location.state`(경기 문맥)가 없어, 주소를 직접 치고 들어온 것처럼 보인다.
   */
  useEffect(() => {
    if (!backGuardRef.current) {
      window.history.pushState(window.history.state, '', window.location.href);
      backGuardRef.current = true;
    }

    const handlePopState = () => {
      /*
       * 풀 문제가 없으면 잃을 것도 없다 — 묻지 않고 내보낸다.
       * 방금 뒤로가기가 쌓아 둔 자리를 먹었으므로 여기서는 한 칸만 더 물러나면 된다.
       */
      if (!hasQuizRef.current) {
        navigate(-1);
        return;
      }

      // 먹힌 자리를 다시 쌓는다 — 다음 뒤로가기도 같은 자리에서 걸려야 한다.
      window.history.pushState(window.history.state, '', window.location.href);
      setIsLeaveConfirmOpen(true);
    };

    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, [navigate]);

  const handleSelect = (optionNo: number, direction: QuizCardExit) => {
    const quiz = quizzes?.[index];
    if (!quiz || isSubmitting || exit !== null) return;

    // 카드는 응답을 기다리지 않고 바로 빠진다 — 고른 즉시 반응해야 하기 때문이다.
    setExit(direction);
    setIsSubmitting(true);
    setSubmitError(null);
    // 앞 문제에서 남은 안내는 여기서 지운다 — 이번 문제 이야기로 오해되면 안 된다.
    setSkipNotice(null);

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

        /*
         * 403 — 제출 시한(문제를 받은 시각 + 8분)이 지났다.
         *
         * **다시 시도하면 되는 실패가 아니다.** 그 문제는 이후 어떤 `/today` 응답에도
         * 다시 실리지 않고 제출도 영구히 403 이라 복구 경로가 없다(docs/quiz.md).
         * 그래서 카드를 되돌리지 않고 넘기되, 답이 반영되지 않았다는 것은 알린다 —
         * 조용히 넘기면 사용자는 자기 답이 저장된 줄 안다.
         *
         * 문구가 시간을 원인으로 단정하는 이유: 서버는 "받은 적 없음"과 "시한 초과"를
         * 같은 403 으로 주지만, 이 화면의 문제는 전부 방금 `/today` 로 받은 것들이라
         * 남는 원인이 시한뿐이다.
         */
        if (isQuizSubmitNotAllowed(error)) {
          setSkipNotice('시간이 지나 이 문제는 넘어갔어요. 답은 반영되지 않았어요.');
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

  /* `popstate` 가 렌더 밖에서 읽을 수 있도록 최신 값을 옮겨 둔다. */
  useEffect(() => {
    hasQuizRef.current = quiz !== null;
  }, [quiz]);

  /** 쌓아 둔 자리까지 함께 되돌려 실제로 이 화면을 뜬다. */
  const leave = () => {
    navigate(LEAVE_HISTORY_DELTA);
  };

  /**
   * 상단 바의 뒤로 가기.
   *
   * 화면에 풀 문제가 남아 있을 때만 묻는다 — 다 풀었거나 받지 못했으면 나가서 잃을 것이
   * 없어, 한 번 더 묻는 것이 걸림돌이기만 하다.
   */
  const handleBack = () => {
    if (quiz === null) {
      leave();
      return;
    }

    setIsLeaveConfirmOpen(true);
  };

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
          onClick={handleBack}
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
          빈 배열은 "줄 수 있는데 줄 게 없다"만 뜻한다(2026-08-12로 의미가 좁아졌다) —
          오늘 세트가 없거나 이 이닝 몫을 이미 다 받은 경우다. 둘은 구분되지 않는다.
          "지금은 줄 수 없다"에 해당하는 경우는 전부 403·409라 위 loadError 로 빠진다.
        */}
        {isEmpty && (
          <p className="quiz-page__status">
            지금 풀 수 있는 퀴즈가 없어요.
            <br />
            이번 이닝 몫을 이미 다 받았을 수도 있어요.
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

        {/* 넘어간 문제 안내. 되돌릴 수 없는 일이라 경고(alert)가 아니라 알림(status)이다. */}
        {skipNotice !== null && (
          <p className="quiz-page__status quiz-page__status--notice" role="status">
            {skipNotice}
          </p>
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

      {/* 나가기는 곧 화면이 바뀌는 일이라 시트를 따로 닫지 않는다. */}
      {isLeaveConfirmOpen && (
        <ConfirmSheet
          {...LEAVE_CONFIRM}
          onConfirm={leave}
          onClose={() => setIsLeaveConfirmOpen(false)}
        />
      )}
    </div>
  );
}
