import { useState } from 'react';
import { findOption } from '../api';
import type { QuizOption, QuizRoomResult, QuizRoomResultQuiz, QuizRoomVoteOption, QuizType } from '../api';
import QuizRoomVoteBars from './QuizRoomVoteBars';
import '../styles/QuizRoomResultView.css';

/**
 * QuizRoomResultView — 퀴즈방 풀이 결과.
 *
 * 생김새는 오늘의 퀴즈 결과 시트(`QuizResultSheet`)의 문제 카드를 따른다 — 번호 칩 색이 곧
 * 정오이고, 아래에 "내가 선택한 답 | 정답" 두 칸이 놓인다. 다만 시트가 아니라 화면 본문이고,
 * 퀴즈방에는 문항 좋아요가 없어 그 자리를 비운다.
 *
 * 시트는 좁은 칸에 보기 글이 잘려 번호로 접었지만, 여기서는 칸이 한 줄을 다 써서 **보기 글을
 * 그대로** 적는다(O/X 만 그림). 꾹 눌러 펴는 장치가 필요 없다.
 *
 * ⚠️ 결과는 마지막 답 제출 응답에만 실리고 다시 받을 길이 없다. 그래서 이 화면은 받은 값을
 * 그리기만 하고, 보관(sessionStorage)은 부르는 쪽(QuizRoomPage)이 한다.
 */

type QuizRoomResultViewProps = {
  result: QuizRoomResult;
  /** 문항별 최신 SSE `votes`. 결과 화면에서는 모든 문항을 답했으므로 펼치면 선택율을 보여 준다. */
  votesByQuiz: Record<number, QuizRoomVoteOption[]>;
};

/** 정오에 따른 색조. 두 칸(내 답·정답)이 같은 규칙을 쓴다(`QuizResultSheet` 와 같다). */
type AnswerTone = 'correct' | 'wrong';

/** 답 한 칸. O/X 는 그림, 그 밖은 보기 글. 보기를 못 찾으면(깨진 자료) "알 수 없음". */
function AnswerMark({ option, type, tone }: { option: QuizOption | null; type: QuizType; tone: AnswerTone }) {
  if (option === null) {
    return <span className="quiz-room-result__answer-none">알 수 없음</span>;
  }

  if (type === 'O/X' && (option.text === 'O' || option.text === 'X')) {
    return (
      <span
        className="quiz-room-result__answer-glyph"
        data-mark={option.text === 'O' ? 'o' : 'x'}
        data-tone={tone}
        role="img"
        aria-label={option.text}
      />
    );
  }

  return (
    <span className="quiz-room-result__answer-text" data-tone={tone}>
      {option.text}
    </span>
  );
}

function ResultCard({
  order,
  quiz,
  votes,
}: {
  order: number;
  quiz: QuizRoomResultQuiz;
  votes: QuizRoomVoteOption[] | undefined;
}) {
  const [isVotesOpen, setIsVotesOpen] = useState(false);

  const myOption = findOption(quiz.options, quiz.myOption);
  const answerOption = findOption(quiz.options, quiz.answer);
  const votesId = `quiz-room-result-votes-${quiz.quizId}`;

  return (
    <li className={`quiz-room-result__card${quiz.correct ? ' quiz-room-result__card--correct' : ' quiz-room-result__card--wrong'}`}>
      <p className="quiz-room-result__card-title">
        {/* 번호 칩 색이 곧 정오다. 색만으로 알리지 않도록 읽어 줄 글도 붙인다. */}
        <span className="quiz-room-result__chip" data-correct={quiz.correct || undefined}>
          {order}
          <span className="quiz-room-result__sr-only">{quiz.correct ? '번 정답' : '번 오답'}</span>
        </span>
        <span className="quiz-room-result__question">{quiz.question}</span>
      </p>

      <div className="quiz-room-result__answers">
        <div className="quiz-room-result__answer">
          <span className="quiz-room-result__answer-label">내가 선택한 답</span>
          <AnswerMark option={myOption} type={quiz.type} tone={quiz.correct ? 'correct' : 'wrong'} />
        </div>
        <span className="quiz-room-result__answer-divider" aria-hidden="true" />
        <div className="quiz-room-result__answer">
          <span className="quiz-room-result__answer-label">정답</span>
          <AnswerMark option={answerOption} type={quiz.type} tone="correct" />
        </div>
      </div>

      <button
        className="quiz-room-result__votes-toggle"
        type="button"
        aria-expanded={isVotesOpen}
        aria-controls={votesId}
        onClick={() => setIsVotesOpen((open) => !open)}
      >
        {isVotesOpen ? '선택율 접기' : '선택율 보기'}
        <span className="quiz-room-result__votes-arrow" aria-hidden="true" />
      </button>

      {isVotesOpen && (
        <div className="quiz-room-result__votes" id={votesId}>
          <QuizRoomVoteBars
            options={quiz.options}
            votes={votes}
            myOption={quiz.myOption}
            answer={quiz.answer}
          />
        </div>
      )}
    </li>
  );
}

export default function QuizRoomResultView({ result, votesByQuiz }: QuizRoomResultViewProps) {
  const isPerfect = result.total > 0 && result.correctCount === result.total;

  return (
    <section className="quiz-room-result" aria-labelledby="quiz-room-result-title">
      <div className="quiz-room-result__summary">
        <h2 className="quiz-room-result__title" id="quiz-room-result-title">
          {isPerfect ? '모두 맞혔어요!' : '퀴즈를 다 풀었어요'}
        </h2>
        <p className="quiz-room-result__score">
          <span className="quiz-room-result__score-correct">{result.correctCount}</span>
          <span className="quiz-room-result__score-divider" aria-hidden="true">
            /
          </span>
          <span className="quiz-room-result__score-total">{result.total}</span>
          <span className="quiz-room-result__sr-only">
            {result.total}문제 중 {result.correctCount}문제 정답
          </span>
        </p>
      </div>

      <ul className="quiz-room-result__cards">
        {result.quizzes.map((quiz, index) => (
          <ResultCard key={quiz.quizId} order={index + 1} quiz={quiz} votes={votesByQuiz[quiz.quizId]} />
        ))}
      </ul>
    </section>
  );
}
