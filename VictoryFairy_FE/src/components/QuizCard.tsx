import { useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { OX_OPTION_NO } from '../api';
import type { DailyQuiz, QuizOption } from '../api';
import swipeHint from '../assets/quiz_swipe_hint.svg';
import '../styles/QuizCard.css';

/**
 * QuizCard — 퀴즈 한 장.
 * Figma: SWM / [Game] 퀴즈 메인(OorX) (node 744:22436) 의 `quiz card` (1130:7925)
 *
 * 흰 카드 안에 경기 머리말 → 문항 → 선택 영역 → 진행 번호가 세로로 놓인다.
 * 고르는 방법은 **보기 개수**로 갈린다(유형 이름이 아니다 — 아래 참고).
 * - **보기 2개** — 좌우로 밀어서 고르고, 카드도 민 방향으로 빠진다.
 * - **보기 3개 이상** — 카드 안 버튼으로 고르고, 카드가 위로 올라가며 사라진다.
 *
 * **정오는 이 화면에서 보여주지 않는다.** 제출하면 서버가 정답을 함께 주지만
 * (docs/quiz.md — 제출 후 공개) 채점 화면이 따로 있어, 여기서는 고르고 넘어가기만 한다.
 */

/**
 * 이만큼 끌어야 선택으로 친다. 카드 폭(354)의 약 1/4.
 * 넘지 못하면 제자리로 돌아가므로, 스크롤하려다 살짝 흔든 것은 선택되지 않는다.
 */
const SWIPE_COMMIT_PX = 80;

/**
 * 이만큼 움직여야 "미는 중"으로 보고 포인터를 카드에 붙잡아 둔다.
 *
 * 누르자마자 붙잡으면 안 되는 이유 — 포인터를 붙잡은 동안에는 `click` 도 카드로
 * 옮겨가서, 카드 안 보기 버튼을 눌러도 버튼의 `onClick` 이 오지 않는다.
 * 즉 눌러서 고르는 길이 통째로 막힌다. 그래서 손가락이 실제로 움직인 뒤에 붙잡는다.
 */
const DRAG_CAPTURE_PX = 6;

/** 끄는 동안 카드가 같이 기우는 정도(도/픽셀). 클수록 과장된다. */
const SWIPE_TILT_DEG_PER_PX = 0.02;

/**
 * 카드가 빠져나가는 방향. 고른 방식과 맞춘다 —
 * 민 방향 그대로(`left`·`right`)거나, 버튼으로 골랐으면 위로(`up`).
 */
export type QuizCardExit = 'left' | 'right' | 'up';

/** 미는 방향과 보기의 대응. 왼쪽이 첫 보기, 오른쪽이 둘째 보기다. */
type SwipeSide = 'left' | 'right';

type QuizCardProps = {
  quiz: DailyQuiz;
  /** 카드에 붙는 문제 번호(1-기반) — 디자인의 "Q1." 접두사이자 아래쪽 진행 표시. */
  order: number;
  /** 이번 세트의 문제 수 — 디자인의 "1 / 10" 에서 분모. */
  total: number;
  /** 머리말 왼쪽 첫 줄. 오늘의 세트라 곧 오늘 날짜다. */
  dateLabel: string;
  /**
   * 머리말 왼쪽 둘째 줄(`NC 다이노스 VS LG 트윈스`).
   * 주소를 직접 치고 들어오면 경기 문맥이 없어 `null` 이고, 그때는 줄을 그리지 않는다.
   */
  matchLabel: string | null;
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

/**
 * O/X 로 그릴 수 있는 문제인지.
 *
 * 유형이 `O/X` 라도 보기가 서버 약속(0=O, 1=X)과 다르게 오면 O/X 로 그리지 않는다 —
 * 그러면 왼쪽 O 가 실제로는 다른 보기를 가리키는, 눈으로는 알 수 없는 오답이 된다.
 */
function isOxPair(quiz: DailyQuiz): boolean {
  return (
    quiz.type === 'O/X' &&
    quiz.options.length === 2 &&
    quiz.options[0].no === OX_OPTION_NO.O &&
    quiz.options[1].no === OX_OPTION_NO.X
  );
}

/** Figma `Ellipse 2` — 80px 박스에 10px 초록 테두리 원(r=35·stroke=10 을 그대로 옮긴 값). */
function GlyphO() {
  return <span className="quiz-card__glyph quiz-card__glyph--o" aria-hidden="true" />;
}

/** Figma 1130:8176 — 폭 10px·반경 11px 막대 두 개를 ±45° 로 교차시킨 빨간 X. */
function GlyphX() {
  return (
    <span className="quiz-card__glyph quiz-card__glyph--x" aria-hidden="true">
      <span className="quiz-card__glyph-bar" />
      <span className="quiz-card__glyph-bar" />
    </span>
  );
}

/**
 * O/X 가 아닌 2지선다의 글리프 — 왼쪽이 A, 오른쪽이 B.
 *
 * 같은 자리에 O/X 를 놓으면 초록 O 가 "맞다", 빨간 X 가 "틀리다"로 읽힌다 —
 * 보기가 `안타`·`아웃` 처럼 옳고 그름과 무관할 때는 없는 뜻을 덧씌우게 된다.
 * A·B 는 순서만 가리키는 이름이라 그런 뜻이 붙지 않는다.
 *
 * **글자는 보기 이름 위에 얹는 표지일 뿐 문제 본문에는 넣지 않는다** —
 * 서버가 주는 `question`·`options[].text` 는 그대로 둔다.
 */
function GlyphSide({ side }: { side: SwipeSide }) {
  return (
    <span className={`quiz-card__glyph quiz-card__glyph--${side}`} aria-hidden="true">
      <span className="quiz-card__glyph-letter">{side === 'left' ? 'A' : 'B'}</span>
    </span>
  );
}

export default function QuizCard({
  quiz,
  order,
  total,
  dateLabel,
  matchLabel,
  isSubmitting,
  exit,
  onSelect,
}: QuizCardProps) {
  /** 끌린 거리(px). 놓으면 0 으로 돌아간다. */
  const [dragX, setDragX] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const startXRef = useRef(0);

  /*
   * 미는 방식은 **보기가 둘일 때**다. 유형(`O/X`·`객관식`)이 아니라 개수로 정하는 이유 —
   * 서버 유형은 둘뿐이라(types/quiz.ts) 2지선다와 4지선다가 똑같이 `객관식` 으로 온다.
   * 개수로 보면 O/X 와 2지선다가 자연히 같은 조작이 되고, 3개 이상만 버튼으로 갈린다.
   */
  const swipePair: [QuizOption, QuizOption] | null =
    quiz.options.length === 2 ? [quiz.options[0], quiz.options[1]] : null;
  const isOx = swipePair !== null && isOxPair(quiz);

  const isLocked = isSubmitting || exit !== null;

  /** 지금 놓으면 어느 쪽이 선택되는지. 끄는 동안 그쪽 보기를 강조한다. */
  const lean: SwipeSide | null =
    isDragging && Math.abs(dragX) >= SWIPE_COMMIT_PX ? (dragX < 0 ? 'left' : 'right') : null;

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (swipePair === null || isLocked) return;

    startXRef.current = event.clientX;
    setIsDragging(true);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging) return;

    const distance = event.clientX - startXRef.current;

    /*
     * 미는 것이 분명해진 시점에 포인터를 붙잡는다(위 DRAG_CAPTURE_PX 참고).
     * 붙잡아야 손가락이 카드 밖으로 나가도 끝까지 따라오고, 동시에 이 드래그가
     * 보기 버튼의 click 으로 새어나가지 않는다.
     */
    if (
      Math.abs(distance) >= DRAG_CAPTURE_PX &&
      !event.currentTarget.hasPointerCapture(event.pointerId)
    ) {
      event.currentTarget.setPointerCapture(event.pointerId);
    }

    setDragX(distance);
  };

  /**
   * 손을 뗐을 때. `pointerup` 뿐 아니라 `pointercancel`(전화 수신 등 OS 가 가로챈 경우)
   * 과 `pointerleave`(붙잡기 전에 카드 밖으로 나간 경우)에서도 불러야
   * 카드가 끌린 채로 굳지 않는다.
   */
  const handlePointerEnd = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!isDragging) return;

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }

    const distance = event.clientX - startXRef.current;
    setIsDragging(false);
    setDragX(0);

    // 왼쪽으로 밀면 첫 보기, 오른쪽으로 밀면 둘째 보기 — 카드 위에 놓인 순서 그대로다.
    if (swipePair !== null && event.type === 'pointerup' && Math.abs(distance) >= SWIPE_COMMIT_PX) {
      const side: SwipeSide = distance < 0 ? 'left' : 'right';
      onSelect(swipePair[side === 'left' ? 0 : 1].no, side);
    }
  };

  /** 미는 조작을 못 쓰는 사용자를 위해 보기 자체도 누를 수 있게 한다. */
  const renderChoice = (option: QuizOption, side: SwipeSide) => (
    <button
      className="quiz-card__choice"
      type="button"
      data-side={side}
      data-active={lean === side || undefined}
      disabled={isLocked}
      onClick={() => onSelect(option.no, side)}
    >
      {isOx ? side === 'left' ? <GlyphO /> : <GlyphX /> : <GlyphSide side={side} />}
      <span className="quiz-card__choice-label">{option.text}</span>
    </button>
  );

  return (
    <div
      className="quiz-card"
      data-dragging={isDragging || undefined}
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
      onPointerLeave={handlePointerEnd}
    >
      <div className="quiz-card__meta">
        <p className="quiz-card__meta-date">{dateLabel}</p>
        {/* 디자인은 오른쪽에 "1회 초"도 쓰지만 `GET /games` 응답에 이닝이 없어 뺐다. */}
        {matchLabel !== null && <p className="quiz-card__meta-match">{matchLabel}</p>}
      </div>

      <hr className="quiz-card__divider" />

      <p className="quiz-card__order">Q{order}.</p>
      <p className="quiz-card__question">{quiz.question}</p>

      {swipePair !== null ? (
        <>
          <div className="quiz-card__choices" data-ox={isOx || undefined}>
            {renderChoice(swipePair[0], 'left')}
            {renderChoice(swipePair[1], 'right')}
          </div>

          {/* 좌우로 밀라는 안내 그림 — 조작은 카드와 위 버튼이 받는다 */}
          <img className="quiz-card__hint" src={swipeHint} alt="" aria-hidden="true" />
        </>
      ) : (
        <ul className="quiz-card__options">
          {quiz.options.map((option) => (
            <li key={option.no}>
              <button
                className="quiz-card__option"
                type="button"
                disabled={isLocked}
                onClick={() => onSelect(option.no, 'up')}
              >
                {option.text}
              </button>
            </li>
          ))}
        </ul>
      )}

      <p className="quiz-card__counter">
        {order} / {total}
      </p>
    </div>
  );
}
