import { OX_OPTION_NO } from '../api';
import type { DailyQuiz, DailyQuizOption } from '../api';
import '../styles/QuizVoteGauge.css';

/**
 * QuizVoteGauge — 지금 문제의 보기별 선택율.
 * Figma: SWM / [Game] 퀴즈 메인(OorX) (node 744:22745·1365:11847)
 *        SWM / [Game] 퀴즈 메인(AorB) (node 1130:7999 의 1365:12159·1365:12138)
 *
 * 카드 아래 화면 맨 아래에 붙는다. 위쪽에 양쪽 표지와 백분율, 아래쪽에 20칸짜리 막대다.
 * 막대는 **앞선 쪽만** 그 쪽 색으로 채우고 나머지는 비워 둔다 — 디자인 그대로다
 * (왼쪽이 앞서면 왼쪽 끝부터 초록, 오른쪽이 앞서면 오른쪽 끝부터 빨강).
 *
 * ── ⚠️ "실시간"이라고 부르지만 갱신되지 않는다 ─────────────────────────
 * 값은 `GET /quizzes/today` 가 문제를 내줄 때 함께 실어 준 `voteCount` 뿐이고,
 * 갱신 경로(SSE·폴링)가 없다(docs/quiz.md — 2026-08-19). 그래서 이 게이지는 **문제를
 * 받은 순간의 스냅샷**이며, 내가 답해도 여기 숫자는 움직이지 않는다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 막대 칸 수. 디자인이 20칸으로 끊어 두었다 — 한 칸이 곧 5%다. */
const SEGMENT_COUNT = 20;

/** 좌우 어느 쪽에 놓인 보기인지. 표지·색이 여기서 갈린다. */
type Side = 'left' | 'right';

/**
 * 표지를 무엇으로 그릴지.
 * - `ox` — O/X 문제. 초록 O · 빨강 X.
 * - `ab` — O/X 가 아닌 2지선다. 순서만 가리키는 A · B(카드의 글리프와 같은 이름).
 * - `text` — 보기가 셋 이상. 어느 보기인지 글리프로는 가리킬 수 없어 보기 이름을 쓴다.
 */
type MarkKind = 'ox' | 'ab' | 'text';

/**
 * 집계가 하나도 없을 때 왼쪽에 줄 비율.
 *
 * 표가 0 이면 비율을 만들 수 없다. 그렇다고 게이지를 감추면 "이 문제만 뭔가 잘못됐다"로
 * 보이므로, 어느 쪽으로도 기울지 않은 반반으로 그린다.
 */
const EVEN_PERCENT = 50;

/**
 * O/X 로 그릴 수 있는 문제인지. `QuizCard.isOxPair` 와 같은 판정이다 —
 * 보기가 서버 약속(0=O, 1=X)과 다르게 오면 O/X 로 그리지 않는다.
 */
function isOxPair(quiz: DailyQuiz): boolean {
  return (
    quiz.type === 'O/X' &&
    quiz.options.length === 2 &&
    quiz.options[0].no === OX_OPTION_NO.O &&
    quiz.options[1].no === OX_OPTION_NO.X
  );
}

/**
 * 표 수. 서버는 항상 0 이상의 수를 싣지만, 낡은 서버나 깨진 값이 오면 0 으로 본다 —
 * 숫자가 아닌 값이 섞이면 합계가 `NaN` 이 되어 게이지 전체가 사라진다.
 */
function voteOf(option: DailyQuizOption): number {
  return Number.isFinite(option.voteCount) && option.voteCount > 0 ? option.voteCount : 0;
}

/**
 * 게이지에 올릴 보기 둘을 고른다.
 *
 * 보기가 둘이면 그대로다. 셋 이상(4지선다)이면 **가장 많이 선택된 두 개**만 남긴다 —
 * 막대가 좌우 두 칸짜리라 셋을 올릴 자리가 없다. 고른 뒤에는 다시 화면 순서(`no`)대로
 * 좌우에 놓는다. 표가 많은 쪽을 늘 왼쪽에 두면 막대가 항상 왼쪽부터 차서, 어느 쪽이
 * 앞섰는지를 색으로 알 수 없게 된다.
 *
 * 표가 같으면 `no` 가 앞선 보기를 택한다 — 순서가 매번 달라지지 않게.
 */
function pickPair(options: DailyQuizOption[]): [DailyQuizOption, DailyQuizOption] | null {
  if (options.length < 2) return null;
  if (options.length === 2) return [options[0], options[1]];

  const [first, second] = [...options]
    .sort((a, b) => voteOf(b) - voteOf(a) || a.no - b.no)
    .slice(0, 2);

  return first.no <= second.no ? [first, second] : [second, first];
}

/**
 * 두 보기의 표를 백분율로 나눈다. 둘을 더해 100 이 되도록 오른쪽은 빼서 구한다 —
 * 각각 반올림하면 99 나 101 이 나와 막대와 숫자가 어긋난다.
 */
function toPercents(pair: [DailyQuizOption, DailyQuizOption]): [number, number] {
  const left = voteOf(pair[0]);
  const total = left + voteOf(pair[1]);

  if (total === 0) return [EVEN_PERCENT, 100 - EVEN_PERCENT];

  const leftPercent = Math.round((left / total) * 100);
  return [leftPercent, 100 - leftPercent];
}

/** 표지 그림. 카드의 글리프를 24px 로 줄인 것이라 모양은 같다. */
function Mark({ kind, side }: { kind: Exclude<MarkKind, 'text'>; side: Side }) {
  if (kind === 'ab') {
    return (
      <span className={`quiz-vote__mark quiz-vote__mark--${side}`} aria-hidden="true">
        {side === 'left' ? 'A' : 'B'}
      </span>
    );
  }

  if (side === 'left') {
    return <span className="quiz-vote__mark quiz-vote__mark--o" aria-hidden="true" />;
  }

  return (
    <span className="quiz-vote__mark quiz-vote__mark--x" aria-hidden="true">
      <span className="quiz-vote__mark-bar" />
      <span className="quiz-vote__mark-bar" />
    </span>
  );
}

type QuizVoteGaugeProps = {
  /** 지금 화면에 있는 문제. 보기의 `voteCount` 만 쓴다. */
  quiz: DailyQuiz;
};

export default function QuizVoteGauge({ quiz }: QuizVoteGaugeProps) {
  const pair = pickPair(quiz.options);

  // 보기가 하나뿐인 문제는 나눌 것이 없다. 서버가 줄 리 없지만 그리지 않고 비운다.
  if (pair === null) return null;

  const [leftPercent, rightPercent] = toPercents(pair);
  /** 앞선 쪽. 같으면 왼쪽으로 둔다 — 반반일 때 막대가 어느 쪽으로도 튀지 않게. */
  const leading: Side = leftPercent >= rightPercent ? 'left' : 'right';
  /** 채울 칸 수. 한 칸이 5% 라 45% 는 9칸, 55% 는 11칸이다. */
  const filled = Math.round((Math.max(leftPercent, rightPercent) / 100) * SEGMENT_COUNT);

  const markKind: MarkKind = isOxPair(quiz) ? 'ox' : quiz.options.length === 2 ? 'ab' : 'text';

  const renderSide = (side: Side, option: DailyQuizOption, percent: number) => (
    <p className="quiz-vote__side" data-side={side}>
      {markKind === 'text' ? (
        <span className="quiz-vote__name">{option.text}</span>
      ) : (
        <>
          <Mark kind={markKind} side={side} />
          {/* 표지는 그림이라 읽히지 않는다 — 어느 보기의 비율인지 소리로도 알린다. */}
          <span className="quiz-vote__sr-only">{option.text}</span>
        </>
      )}
      <span className="quiz-vote__percent">{percent}%</span>
    </p>
  );

  return (
    <div className="quiz-vote">
      <div className="quiz-vote__sides">
        {renderSide('left', pair[0], leftPercent)}
        {renderSide('right', pair[1], rightPercent)}
      </div>

      {/* 위 백분율을 그림으로 한 번 더 말하는 것뿐이라 읽어 줄 내용이 없다. */}
      <div className="quiz-vote__bar" data-leading={leading} aria-hidden="true">
        {Array.from({ length: SEGMENT_COUNT }, (_, index) => (
          <span
            key={index}
            className="quiz-vote__segment"
            data-filled={
              (leading === 'left' ? index < filled : index >= SEGMENT_COUNT - filled) || undefined
            }
          />
        ))}
      </div>
    </div>
  );
}
