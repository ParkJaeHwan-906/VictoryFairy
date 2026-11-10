import { memo } from 'react';
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
 * 보기가 셋 이상(4지선다)이면 좌우 두 칸으로는 나눌 수 없어 따로 그린다(`MultiGauge`) —
 * 막대를 보기 수만큼 구역으로 나누어 선택율만큼 폭을 주고, 초록 · 흰 색을 번갈아 칠한다.
 * 위쪽 표지는 보기 글이 길어 보기 번호(1·2·3·4)로 쓴다.
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
 * 2지선다 표지를 무엇으로 그릴지.
 * - `ox` — O/X 문제. 초록 O · 빨강 X.
 * - `ab` — O/X 가 아닌 2지선다. 순서만 가리키는 A · B(카드의 글리프와 같은 이름).
 */
type MarkKind = 'ox' | 'ab';

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

/**
 * 보기 여럿의 표를 백분율로 나눈다. 합이 꼭 100 이 되도록 최대 나머지 방식으로 반올림한다 —
 * 각각 반올림하면 99 나 101 이 나와 막대 폭과 숫자가 어긋난다.
 * 표가 하나도 없으면 고르게 나눈다(2지선다의 반반과 같은 이유).
 */
function toMultiPercents(options: DailyQuizOption[]): number[] {
  const votes = options.map(voteOf);
  const total = votes.reduce((sum, vote) => sum + vote, 0);
  const raw = votes.map((vote) => (total === 0 ? 100 / options.length : (vote / total) * 100));
  const percents = raw.map((value) => Math.floor(value));
  let rest = 100 - percents.reduce((sum, percent) => sum + percent, 0);

  // 나머지가 큰 보기부터 1 씩 얹는다. 같으면 앞 보기부터 — 매번 같은 결과가 나오게.
  const byRemainder = raw
    .map((value, index) => ({ index, remainder: value - Math.floor(value) }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index);
  for (const { index } of byRemainder) {
    if (rest <= 0) break;
    percents[index] += 1;
    rest -= 1;
  }

  return percents;
}

/** 보기 순서대로 번갈아 칠할 색. 짝수 번째(1·3번)는 초록, 홀수 번째(2·4번)는 흰색. */
type Tone = 'fill' | 'blank';

const toneOf = (index: number): Tone => (index % 2 === 0 ? 'fill' : 'blank');

/**
 * 보기가 셋 이상인 문제의 게이지.
 *
 * 막대를 보기 수만큼 구역으로 나누고, 각 구역 폭은 그 보기의 선택율이다. 색은 보기 순서대로
 * 초록 · 흰 · 초록 · 흰을 번갈아 써서 이웃 구역이 늘 갈린다. 0% 인 보기는 구역을 그리지
 * 않는다(폭이 없으니 틈만 남는다). 위쪽 표지는 같은 색의 보기 번호 칩과 백분율이다.
 */
function MultiGauge({ options }: { options: DailyQuizOption[] }) {
  const percents = toMultiPercents(options);

  return (
    <div className="quiz-vote quiz-vote--multi">
      <ol
        className="quiz-vote__labels"
        style={{ '--quiz-vote-columns': options.length } as React.CSSProperties}
      >
        {options.map((option, index) => (
          <li key={option.no} className="quiz-vote__label" data-tone={toneOf(index)}>
            <span className="quiz-vote__no" aria-hidden="true">
              {index + 1}
            </span>
            {/* 번호는 그림처럼 쓰인다 — 어느 보기의 비율인지 소리로는 보기 글로 알린다. */}
            <span className="quiz-vote__sr-only">{option.text}</span>
            <span className="quiz-vote__percent">{percents[index]}%</span>
          </li>
        ))}
      </ol>

      {/* 위 백분율을 그림으로 한 번 더 말하는 것뿐이라 읽어 줄 내용이 없다. */}
      <div className="quiz-vote__bar" aria-hidden="true">
        {options.map((option, index) =>
          percents[index] > 0 ? (
            <span
              key={option.no}
              className="quiz-vote__zone"
              data-tone={toneOf(index)}
              style={{ flexGrow: percents[index] }}
            />
          ) : null,
        )}
      </div>
    </div>
  );
}

/** 표지 그림. 카드의 글리프를 24px 로 줄인 것이라 모양은 같다. */
function Mark({ kind, side }: { kind: MarkKind; side: Side }) {
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

function QuizVoteGauge({ quiz }: QuizVoteGaugeProps) {
  // 보기가 하나뿐인 문제는 나눌 것이 없다. 서버가 줄 리 없지만 그리지 않고 비운다.
  if (quiz.options.length < 2) return null;
  if (quiz.options.length > 2) return <MultiGauge options={quiz.options} />;

  const pair: [DailyQuizOption, DailyQuizOption] = [quiz.options[0], quiz.options[1]];

  const [leftPercent, rightPercent] = toPercents(pair);
  /** 앞선 쪽. 같으면 왼쪽으로 둔다 — 반반일 때 막대가 어느 쪽으로도 튀지 않게. */
  const leading: Side = leftPercent >= rightPercent ? 'left' : 'right';
  /** 채울 칸 수. 한 칸이 5% 라 45% 는 9칸, 55% 는 11칸이다. */
  const filled = Math.round((Math.max(leftPercent, rightPercent) / 100) * SEGMENT_COUNT);

  const markKind: MarkKind = isOxPair(quiz) ? 'ox' : 'ab';

  const renderSide = (side: Side, option: DailyQuizOption, percent: number) => (
    <p className="quiz-vote__side" data-side={side}>
      <Mark kind={markKind} side={side} />
      {/* 표지는 그림이라 읽히지 않는다 — 어느 보기의 비율인지 소리로도 알린다. */}
      <span className="quiz-vote__sr-only">{option.text}</span>
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

/**
 * 문제가 바뀔 때만 다시 그린다.
 *
 * 이 게이지가 보는 값은 `quiz` 하나뿐인데, 화면(QuizPage)은 남은 시간 때문에 1초마다
 * 다시 렌더된다. 그대로 두면 막대 20칸과 표지가 매초 함께 재조정되고, 그 시간이 하필
 * 카드를 끄는 도중에도 끼어든다. 스냅샷이라 문제가 바뀌기 전에는 값이 움직일 일이
 * 없으므로(위 머리말 참고) 여기서 끊는 것이 안전하다.
 */
export default memo(QuizVoteGauge);
