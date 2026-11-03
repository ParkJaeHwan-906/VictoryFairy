import { memo } from 'react';
import type { QuizOption, QuizRoomVoteOption } from '../api';
import '../styles/QuizRoomVoteBars.css';

/**
 * QuizRoomVoteBars — 퀴즈방 한 문항의 보기별 선택율.
 *
 * 오늘의 퀴즈 게이지(`QuizVoteGauge`)와 같은 자리 · 같은 역할이지만 모양은 다르다. 저쪽은
 * 좌우 두 칸짜리 막대라 4지선다면 위 두 개만 올리는데, 퀴즈방은 사람이 만든 문제라 보기가
 * 2~4개로 고르게 섞인다 — **보기마다 한 줄씩** 막대를 그려 하나도 빠뜨리지 않는다.
 *
 * ── 값은 SSE `votes` 로만 온다 ─────────────────────────────────────────
 * REST 어디에도 분포가 없다(docs/quiz-room.md). 구독 직후 문항마다 스냅샷 1건, 이후 누가
 * 답할 때마다 그 문항 1건이 와서 **통째로 덮어쓴다**(증분 아님). 그래서 아직 한 건도 못
 * 받았으면(`votes` 없음) 막대 대신 기다리는 문구를 둔다.
 *
 * 아직 안 푼 문항에도 값은 오지만 **답하기 전에는 이 컴포넌트를 그리지 않는다** — 그건
 * 화면(QuizRoomPage)이 정한다.
 * ──────────────────────────────────────────────────────────────────────
 */

type QuizRoomVoteBarsProps = {
  /** 문항의 보기(글). 줄 순서는 이 배열 순서(`no` 오름차순)다. */
  options: QuizOption[];
  /** 그 문항의 최신 `votes.options`. 아직 못 받았으면 `undefined`. */
  votes: QuizRoomVoteOption[] | undefined;
  /** 내가 고른 보기. 모르면(409 로 넘긴 문항) `null`. */
  myOption: number | null;
  /** 정답 보기. 결과 화면에서만 안다 — 풀이 중에는 `null`. */
  answer?: number | null;
};

/** 표 수. 깨진 값이 섞여 합계가 `NaN` 이 되지 않게 0 으로 본다. */
function countOf(votes: QuizRoomVoteOption[], no: number): number {
  const count = votes.find((vote) => vote.no === no)?.count ?? 0;
  return Number.isFinite(count) && count > 0 ? count : 0;
}

function QuizRoomVoteBars({ options, votes, myOption, answer = null }: QuizRoomVoteBarsProps) {
  if (votes === undefined) {
    return (
      <p className="quiz-room-vote quiz-room-vote--pending" role="status">
        선택율을 불러오는 중이에요
      </p>
    );
  }

  const counts = options.map((option) => countOf(votes, option.no));
  const total = counts.reduce((sum, count) => sum + count, 0);

  return (
    <ul className="quiz-room-vote" aria-label={`선택율 (총 ${total}명)`}>
      {options.map((option, index) => {
        const count = counts[index];
        // 표가 0 이면 비율을 만들 수 없다 — 모두 0% 로 둔다(빈 막대).
        const percent = total === 0 ? 0 : Math.round((count / total) * 100);
        const isMine = myOption === option.no;
        const isAnswer = answer === option.no;

        const modifiers = [
          isMine ? ' quiz-room-vote__row--mine' : '',
          isAnswer ? ' quiz-room-vote__row--answer' : '',
        ].join('');

        return (
          <li
            key={option.no}
            className={`quiz-room-vote__row${modifiers}`}
            // 막대 길이는 CSS 가 이 값으로 그린다(width: calc(var(--quiz-room-vote-ratio) * 1%)).
            style={{ '--quiz-room-vote-ratio': percent } as React.CSSProperties}
          >
            <span className="quiz-room-vote__label">
              <span className="quiz-room-vote__text">{option.text}</span>
              {isMine && <span className="quiz-room-vote__badge quiz-room-vote__badge--mine">내 선택</span>}
              {isAnswer && <span className="quiz-room-vote__badge quiz-room-vote__badge--answer">정답</span>}
            </span>
            <span className="quiz-room-vote__value">
              <span className="quiz-room-vote__percent">{percent}%</span>
              <span className="quiz-room-vote__count">{count}명</span>
            </span>
            {/* 위 백분율을 그림으로 한 번 더 말하는 것뿐이라 읽어 줄 내용이 없다. */}
            <span className="quiz-room-vote__bar" aria-hidden="true">
              <span className="quiz-room-vote__fill" />
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** 같은 문항의 값이 바뀔 때만 다시 그린다 — 다른 문항의 `votes` 가 올 때마다 그릴 이유가 없다. */
export default memo(QuizRoomVoteBars);
