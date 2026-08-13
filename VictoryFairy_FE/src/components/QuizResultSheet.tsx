import { useEffect, useRef, useState } from 'react';
import { findOption, likeQuiz } from '../api';
import type { QuizInningResult, QuizOption, QuizSubmission, QuizType } from '../api';
import '../styles/QuizResultSheet.css';

/**
 * QuizResultSheet — 퀴즈 결과의 "문제 및 정답" 바텀시트.
 * Figma: SWM / [Game] 퀴즈 결과 상세보기-펼침예시 (node 724:18166)
 *
 * 회차 행의 화살표를 누르면 **그 이닝의 문제만** 담아 올라온다(2026-08-13 — 서버가
 * 이닝별로 묶어 주기 전에는 전부 한 묶음이었다).
 *
 * 디자인은 묶음을 `1회 초`·`1회 말` 로 나누지만 **서버는 초·말을 구분하지 않는다** —
 * 이닝 번호 하나뿐이라 `N회` 한 묶음으로 둔다.
 *
 * 카드는 접힌 상태로 시작하고, 누르면 내가 고른 답과 정답이 함께 펼쳐진다.
 *
 * ── 답을 보기 번호로 접는다 ──────────────────────────────────────────
 * "내가 고른 답 | 정답" 두 칸이 한 줄을 나눠 쓰는 좁은 자리라, 보기 글을 그대로 넣으면
 * 다지선다에서 잘려 무슨 답인지 알 수 없었다. 그래서 **O/X 는 지금처럼 그림으로,
 * 그 밖의 문제는 보기 번호로** 보여주고, 번호를 꾹 누르면 말풍선으로 글을 편다.
 *
 * 번호는 **서버의 0-기반 `no` 에 1을 더한 표기용 값**이다 — 사람이 세는 순서와 맞추기
 * 위해서이며, 제출에 쓰는 값이 아니라 여기서만 쓰는 표시다(제출은 항상 `no` 그대로다).
 */

/**
 * 좋아요가 바뀌었음을 위로 알린다 — 낙관적 반영 · 서버 확정 · 실패 되돌리기 모두 이 하나로 전한다.
 *
 * **상태를 시트가 들고 있지 않은 이유**: 카드를 접으면 좋아요 버튼이 사라지고, 시트를 닫으면
 * 카드가 사라진다. 그 안에 상태를 두면 다시 열 때마다 처음 받아 온 값으로 되돌아가 방금
 * 누른 좋아요가 풀린 것처럼 보인다. 그래서 자료를 들고 있는 화면까지 올려 보낸다.
 */
export type QuizLikeChangeHandler = (quizId: number, liked: boolean, likeCount: number) => void;

type QuizResultSheetProps = {
  /** 띄울 이닝의 결산(번호 · 요약 · 문제 목록). */
  inning: QuizInningResult;
  /** 좋아요가 바뀔 때마다 호출된다(위 주석 참고). */
  onLikeChange: QuizLikeChangeHandler;
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/** 정오에 따른 색조. 두 칸(내 답·정답)이 같은 규칙을 쓴다. */
type AnswerTone = 'correct' | 'wrong';

/** "꾹 누름"으로 볼 시간(ms). 짧으면 탭에도 열리고, 길면 눌러도 안 열린 것처럼 느껴진다. */
const LONG_PRESS_MS = 350;

/**
 * 보기 번호 하나 — 꾹 누르면 보기 글이 말풍선으로 뜬다.
 *
 * 버튼인 이유는 두 가지다. 키보드로도 닿아야 하고(포커스 후 Enter·Space 를 누르고 있으면
 * 같은 방식으로 열린다), 눌러서 무언가 나온다는 것이 손가락에게도 읽혀야 한다.
 * **카드 펼침 버튼 안에 들어가면 안 되므로**(버튼 중첩) 답 영역은 카드 버튼 밖에 있다.
 *
 * 화면에 보이는 번호는 `no + 1` 이지만 읽어 주는 이름표에는 보기 글을 함께 넣는다 —
 * 스크린 리더 사용자는 꾹 누를 수 없으니 말풍선에 기대면 안 된다.
 */
function OptionNumber({ option, tone }: { option: QuizOption; tone: AnswerTone }) {
  const [isRevealed, setIsRevealed] = useState(false);
  const timerRef = useRef<number | null>(null);

  const cancelTimer = () => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const startPress = () => {
    cancelTimer();
    timerRef.current = window.setTimeout(() => setIsRevealed(true), LONG_PRESS_MS);
  };

  /* 손(또는 키)을 떼면 닫는다. 누르고 있는 동안만 보이는 것이 "꾹 누름"의 약속이다. */
  const endPress = () => {
    cancelTimer();
    setIsRevealed(false);
  };

  /* 말풍선이 열린 채로 카드가 접히거나 시트가 닫혀도 타이머가 남지 않게 한다. */
  useEffect(() => cancelTimer, []);

  return (
    <span className="quiz-result-sheet__answer-slot">
      <button
        className="quiz-result-sheet__answer-number"
        type="button"
        data-tone={tone}
        aria-label={`${option.no + 1}번 보기 ${option.text}`}
        onPointerDown={startPress}
        onPointerUp={endPress}
        onPointerLeave={endPress}
        onPointerCancel={endPress}
        onBlur={endPress}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          // Space 는 기본 동작이 스크롤이라 막는다. 눌린 채로 반복 이벤트가 와도 타이머는 하나다.
          event.preventDefault();
          if (!event.repeat) startPress();
        }}
        onKeyUp={endPress}
        // 모바일에서 길게 누르면 뜨는 복사·선택 메뉴가 말풍선을 가린다.
        onContextMenu={(event) => event.preventDefault()}
      >
        {option.no + 1}
      </button>

      {isRevealed && (
        <span className="quiz-result-sheet__bubble" role="tooltip">
          {option.text}
        </span>
      )}
    </span>
  );
}

/**
 * 답 한 칸을 그린다 — 미응답 · O/X 그림 · 보기 번호 셋 중 하나다.
 *
 * O/X 는 보기 글자가 그대로 `"O"`·`"X"` 라 디자인처럼 동그라미·가위표로 보여 준다.
 * 그 밖(객관식)은 글이 길어 자리에서 잘리므로 번호로 접고, 글은 꾹 눌러 펴게 한다.
 * 유형(`type`)을 먼저 보되 글자까지 확인하는 이유는, `O/X` 인데 보기 글이 O·X 가
 * 아닌 자료가 오면 그릴 그림이 없어 번호로 흘려보내야 하기 때문이다.
 */
function AnswerMark({
  option,
  type,
  tone,
}: {
  option: QuizOption | null;
  type: QuizType;
  tone: AnswerTone;
}) {
  if (option === null) {
    return <span className="quiz-result-sheet__answer-none">미응답</span>;
  }

  if (type === 'O/X' && (option.text === 'O' || option.text === 'X')) {
    return (
      <span
        className="quiz-result-sheet__answer-glyph"
        data-mark={option.text === 'O' ? 'o' : 'x'}
        data-tone={tone}
        role="img"
        aria-label={option.text}
      />
    );
  }

  return <OptionNumber option={option} tone={tone} />;
}

/**
 * 좋아요 토글 — 펼친 문제의 번호 칩 아래에 놓인다.
 *
 * ⚠️ **멱등이 아니다**(docs/quiz.md) — 없으면 켜고 있으면 뒤집는 토글이라, 두 번 나가면
 * 원상 복귀한다. 그래서 자동 재시도를 걸지 않고, 응답이 오기 전 두 번째 요청도 막는다.
 * 화면 상태의 정본은 응답의 `liked` 이므로 낙관적으로 뒤집어 두었더라도 그대로 덮어쓴다.
 *
 * 실패하면 누르기 전으로 되돌린다. 이 시트에는 오류를 띄울 자리가 없고, **되돌아가는
 * 것 자체가 "반영되지 않았다"는 신호**라 조용히 되돌리는 편이 덜 혼란스럽다.
 */
function LikeButton({
  submission,
  onChange,
}: {
  submission: QuizSubmission;
  onChange: QuizLikeChangeHandler;
}) {
  const { liked, likeCount } = submission;
  /** 응답을 기다리는 중. 토글이 멱등이 아니라 연타를 여기서 막는다. */
  const [isPending, setIsPending] = useState(false);
  /** 켤 때만 터지는 축하 효과. 끌 때는 조용히 꺼진다(유튜브 좋아요와 같은 규칙). */
  const [isBursting, setIsBursting] = useState(false);

  const handleClick = () => {
    if (isPending) return;

    // 되돌릴 값을 먼저 붙잡는다 — 아래에서 위쪽 상태를 갈아엎기 때문이다.
    const previousLiked = liked;
    const previousCount = likeCount;
    const nextLiked = !previousLiked;

    onChange(submission.quizId, nextLiked, previousCount + (nextLiked ? 1 : -1));
    if (nextLiked) setIsBursting(true);
    setIsPending(true);

    likeQuiz(submission.quizId)
      .then((result) => onChange(submission.quizId, result.liked, result.likeCount))
      .catch(() => onChange(submission.quizId, previousLiked, previousCount))
      .finally(() => setIsPending(false));
  };

  return (
    <button
      className="quiz-result-sheet__like"
      type="button"
      data-liked={liked || undefined}
      aria-pressed={liked}
      aria-label={`좋아요 ${likeCount}개${liked ? ' (누름)' : ''}`}
      onClick={handleClick}
    >
      <span className="quiz-result-sheet__like-icon" data-bursting={isBursting || undefined} />
      {/* 퍼져 나가는 고리. 효과가 끝나면 스스로 걷힌다 — 다음에 켤 때 다시 돌아야 한다. */}
      {isBursting && (
        <span
          className="quiz-result-sheet__like-burst"
          aria-hidden="true"
          onAnimationEnd={() => setIsBursting(false)}
        />
      )}
    </button>
  );
}

/**
 * 문제 한 장. 접힘/펼침을 스스로 들고 있다 — 밖에서 알 필요가 없는 상태다.
 *
 * 카드 전체가 아니라 **머리 부분만 버튼**이다. 펼친 답 안에 꾹 누를 수 있는 보기 번호가
 * 들어가는데, 그것을 카드 버튼 안에 두면 버튼이 중첩돼(잘못된 마크업) 번호를 누르는 것이
 * 카드 접기로도 새어 나간다.
 */
function QuizAnswerCard({
  order,
  submission,
  onLikeChange,
}: {
  order: number;
  submission: QuizSubmission;
  onLikeChange: QuizLikeChangeHandler;
}) {
  const [isOpen, setIsOpen] = useState(false);

  /*
   * 답 텍스트 두 필드가 사라져(2026-08-13) 번호로 보기를 찾는다.
   * 미답 항목은 `myOption` 이 null 이라 그대로 "미응답"으로 접힌다.
   */
  const myOption = findOption(submission.options, submission.myOption);
  const answerOption = findOption(submission.options, submission.answer);

  return (
    <li className="quiz-result-sheet__card" data-open={isOpen || undefined}>
      <button
        className="quiz-result-sheet__card-head"
        type="button"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className="quiz-result-sheet__card-title">
          {/* 번호 칩 색이 곧 정오다 — 펼치지 않아도 결과를 알 수 있다 */}
          <span className="quiz-result-sheet__chip" data-correct={submission.correct || undefined}>
            {order}
          </span>
          <span className="quiz-result-sheet__question">{submission.question}</span>
        </span>
        <span className="quiz-result-sheet__card-arrow" aria-hidden="true" />
      </button>

      {/*
        펼친 아래쪽 줄. 왼쪽 칸은 머리말의 번호 칩과 같은 폭이라, 좋아요가 정확히
        문제 번호 아래에 놓인다(종전에는 그만큼 답 영역을 들여쓰기만 했다).
      */}
      {isOpen && (
        <div className="quiz-result-sheet__foot">
          <LikeButton submission={submission} onChange={onLikeChange} />

          <div className="quiz-result-sheet__answers">
            <div className="quiz-result-sheet__answer">
              <span className="quiz-result-sheet__answer-label">내가 선택한 답</span>
              <AnswerMark
                option={myOption}
                type={submission.type}
                tone={submission.correct ? 'correct' : 'wrong'}
              />
            </div>
            <span className="quiz-result-sheet__answer-divider" aria-hidden="true" />
            <div className="quiz-result-sheet__answer">
              <span className="quiz-result-sheet__answer-label">정답</span>
              <AnswerMark option={answerOption} type={submission.type} tone="correct" />
            </div>
          </div>
        </div>
      )}
    </li>
  );
}

export default function QuizResultSheet({
  inning,
  onLikeChange,
  onClose,
}: QuizResultSheetProps) {
  const title = `${inning.inning}회 문제 및 정답`;

  /* Esc 로 닫기 — 시트가 떠 있는 동안에만 듣는다(다른 시트들과 같은 규칙). */
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  /* 시트가 떠 있는 동안 뒤 페이지가 같이 스크롤되지 않게 막는다. */
  useEffect(() => {
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);

  return (
    <div className="quiz-result-sheet">
      <button
        className="quiz-result-sheet__dim"
        type="button"
        onClick={onClose}
        aria-label="문제 및 정답 닫기"
      />

      <section
        className="quiz-result-sheet__panel"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <button className="quiz-result-sheet__handle" type="button" onClick={onClose}>
          <span className="quiz-result-sheet__handle-bar" aria-hidden="true" />
          <span className="quiz-result-sheet__sr-only">문제 및 정답 닫기</span>
        </button>

        <h2 className="quiz-result-sheet__title">{title}</h2>

        <div className="quiz-result-sheet__body">
          <p className="quiz-result-sheet__group">
            <span className="quiz-result-sheet__group-name">{inning.inning}회</span>
            {/* 분모는 그 이닝에 **받은** 문항 수다(고정 20이 아니다) */}
            <span className="quiz-result-sheet__group-count">
              {inning.summary.correctCount}/{inning.summary.total}
            </span>
          </p>

          <ul className="quiz-result-sheet__cards">
            {inning.quizzes.map((submission, index) => (
              <QuizAnswerCard
                key={submission.quizId}
                order={index + 1}
                submission={submission}
                onLikeChange={onLikeChange}
              />
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
}
