import { useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { OX_OPTION_NO } from '../api';
import type { DailyQuiz } from '../api';
import '../styles/QuizCard.css';

/**
 * QuizCard — 퀴즈 한 장.
 * Figma: SWM / [Game] 퀴즈 메인 (시안) (node 929:8097) 의 `quiz card` (929:8102)
 *
 * 유형에 따라 카드 안쪽과 카드가 빠지는 방향이 갈린다.
 * - **O/X** — 좌우로 밀어서 고르고, 카드도 민 방향으로 빠진다.
 * - **객관식** — 카드 안 버튼으로 고르고, 카드가 아래로 내려가며 사라진다.
 *
 * **정오는 이 화면에서 보여주지 않는다.** 제출하면 서버가 정답을 함께 주지만
 * (docs/quiz.md — 제출 후 공개) 채점 화면이 따로 있어, 여기서는 고르고 넘어가기만 한다.
 */

/**
 * 이만큼 끌어야 선택으로 친다. 카드 폭(354)의 약 1/4.
 * 넘지 못하면 제자리로 돌아가므로, 스크롤하려다 살짝 흔든 것은 선택되지 않는다.
 */
const SWIPE_COMMIT_PX = 80;

/** 끄는 동안 카드가 같이 기우는 정도(도/픽셀). 클수록 과장된다. */
const SWIPE_TILT_DEG_PER_PX = 0.02;

/**
 * 카드가 빠져나가는 방향. 고른 방식과 맞춘다 —
 * 민 방향 그대로(`left`·`right`)거나, 버튼으로 골랐으면 아래로(`down`).
 */
export type QuizCardExit = 'left' | 'right' | 'down';

type QuizCardProps = {
  quiz: DailyQuiz;
  /** 카드에 붙는 문제 번호(1-기반) — 디자인의 "Q1." 접두사. */
  order: number;
  /** 제출 중. 같은 문제를 두 번 보내지 않도록 입력을 막는다. */
  isSubmitting: boolean;
  /**
   * 빠져나가는 방향. `null` 이면 제자리다.
   * 값을 카드가 아니라 화면이 들고 있는 이유 — 제출이 실패하면 카드를 되돌려야 하는데,
   * 그 판단은 응답을 받는 화면만 할 수 있다.
   */
  exit: QuizCardExit | null;
  onSelect: (optionNo: number, exit: QuizCardExit) => void;
};

/** Figma `Ellipse 2` — 100px 박스에 13px 흰 테두리 원(내보낸 SVG 의 r=43.5/stroke=13 그대로). */
function GlyphO() {
  return <span className="quiz-card__glyph quiz-card__glyph--o" aria-hidden="true" />;
}

/** Figma 929:8106 — 폭 13px·반경 11px 막대 두 개를 ±45° 로 교차시킨 X. */
function GlyphX() {
  return (
    <span className="quiz-card__glyph quiz-card__glyph--x" aria-hidden="true">
      <span className="quiz-card__glyph-bar" />
      <span className="quiz-card__glyph-bar" />
    </span>
  );
}

export default function QuizCard({ quiz, order, isSubmitting, exit, onSelect }: QuizCardProps) {
  /** 끌린 거리(px). 놓으면 0 으로 돌아간다. */
  const [dragX, setDragX] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const startXRef = useRef(0);

  /*
   * O/X 는 보기 번호가 서버에서 0=O, 1=X 로 고정이다(docs/quiz.md).
   * 그래도 번호를 가정하지 않고 실제 `options` 에서 찾는다 — 둘 중 하나라도 없으면
   * 유형만 O/X 인 예외 데이터이므로 객관식 버튼으로 그려 문제를 못 풀게 되는 것을 막는다.
   */
  const optionO = quiz.options.find((option) => option.no === OX_OPTION_NO.O);
  const optionX = quiz.options.find((option) => option.no === OX_OPTION_NO.X);
  const isOx = quiz.type === 'O/X' && optionO !== undefined && optionX !== undefined;

  const isLocked = isSubmitting || exit !== null;

  /** 지금 놓으면 어느 쪽이 선택되는지. 끄는 동안 그쪽 글리프를 강조한다. */
  const lean =
    isDragging && Math.abs(dragX) >= SWIPE_COMMIT_PX ? (dragX < 0 ? 'o' : 'x') : null;

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isOx || isLocked) return;

    event.currentTarget.setPointerCapture(event.pointerId);
    startXRef.current = event.clientX;
    setIsDragging(true);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging) return;
    setDragX(event.clientX - startXRef.current);
  };

  /**
   * 손을 뗐을 때. `pointerup` 뿐 아니라 `pointercancel`(전화 수신 등 OS 가 가로챈 경우)
   * 에서도 불러야 카드가 끌린 채로 굳지 않는다.
   */
  const handlePointerEnd = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging) return;

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }

    const distance = event.clientX - startXRef.current;
    setIsDragging(false);
    setDragX(0);

    // 왼쪽(‹)은 O, 오른쪽(›)은 X — 카드 위 글리프가 놓인 방향 그대로다.
    if (event.type === 'pointerup' && Math.abs(distance) >= SWIPE_COMMIT_PX) {
      const toO = distance < 0;
      onSelect(toO ? OX_OPTION_NO.O : OX_OPTION_NO.X, toO ? 'left' : 'right');
    }
  };

  return (
    <div
      className="quiz-card"
      data-dragging={isDragging || undefined}
      data-lean={lean ?? undefined}
      data-exit={exit ?? undefined}
      // 빠져나가는 중에는 CSS 가 transform 을 맡는다 — 끌린 위치는 이미 0 으로 되돌아갔다.
      style={
        exit === null
          ? { transform: `translateX(${dragX}px) rotate(${dragX * SWIPE_TILT_DEG_PER_PX}deg)` }
          : undefined
      }
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerEnd}
      onPointerCancel={handlePointerEnd}
    >
      <p className="quiz-card__question">
        Q{order}. {quiz.question}
      </p>

      {isOx ? (
        <div className="quiz-card__ox">
          {/*
            디자인의 양끝 화살표를 실제 버튼으로 둔다 — 끌기만으로는 키보드·보조기기
            사용자가 답할 방법이 없다. 화살표는 각각 바로 옆 글리프의 방향을 가리킨다.
          */}
          <button
            className="quiz-card__arrow quiz-card__arrow--prev"
            type="button"
            onClick={() => onSelect(OX_OPTION_NO.O, 'left')}
            disabled={isLocked}
            aria-label={`${optionO.text} 선택`}
          >
            <span className="quiz-card__arrow-icon" aria-hidden="true" />
          </button>

          {/* 두 글리프는 한 덩어리로 가운데 정렬한다 — 화살표와 간격이 서로 다르기 때문이다. */}
          <span className="quiz-card__choices">
            <span className="quiz-card__choice" data-active={lean === 'o' || undefined}>
              <GlyphO />
            </span>
            <span className="quiz-card__choice" data-active={lean === 'x' || undefined}>
              <GlyphX />
            </span>
          </span>

          <button
            className="quiz-card__arrow quiz-card__arrow--next"
            type="button"
            onClick={() => onSelect(OX_OPTION_NO.X, 'right')}
            disabled={isLocked}
            aria-label={`${optionX.text} 선택`}
          >
            <span className="quiz-card__arrow-icon" aria-hidden="true" />
          </button>
        </div>
      ) : (
        <ul className="quiz-card__options">
          {quiz.options.map((option) => (
            <li key={option.no}>
              <button
                className="quiz-card__option"
                type="button"
                onClick={() => onSelect(option.no, 'down')}
                disabled={isLocked}
              >
                {option.text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
