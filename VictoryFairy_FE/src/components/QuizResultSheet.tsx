import { useEffect, useState } from 'react';
import { findOptionText } from '../api';
import type { QuizInningResult, QuizSubmission } from '../api';
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
 */

type QuizResultSheetProps = {
  /** 띄울 이닝의 결산(번호 · 요약 · 문제 목록). */
  inning: QuizInningResult;
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/**
 * 답 하나를 O·X 그림이나 글자로 그린다.
 *
 * O/X 문제는 보기 글자가 그대로 `"O"`·`"X"` 라 디자인처럼 동그라미·가위표로 보여 주고,
 * 4지선다처럼 보기가 글인 문제는 그 글을 그대로 쓴다 — 디자인에는 O/X 예시만 있지만
 * 같은 자리에 4지선다 답도 들어와야 하기 때문이다.
 */
function AnswerMark({ text, tone }: { text: string | null; tone: 'correct' | 'wrong' }) {
  if (text === null) {
    return <span className="quiz-result-sheet__answer-none">미응답</span>;
  }

  if (text === 'O' || text === 'X') {
    return (
      <span
        className="quiz-result-sheet__answer-glyph"
        data-mark={text === 'O' ? 'o' : 'x'}
        data-tone={tone}
        role="img"
        aria-label={text}
      />
    );
  }

  return (
    <span className="quiz-result-sheet__answer-text" data-tone={tone}>
      {text}
    </span>
  );
}

/** 문제 한 장. 접힘/펼침을 스스로 들고 있다 — 밖에서 알 필요가 없는 상태다. */
function QuizAnswerCard({ order, submission }: { order: number; submission: QuizSubmission }) {
  const [isOpen, setIsOpen] = useState(false);

  /*
   * 답 텍스트 두 필드가 사라져(2026-08-13) 번호로 보기를 찾는다.
   * 미답 항목은 `myOption` 이 null 이라 그대로 "미응답"으로 접힌다.
   */
  const myOptionText = findOptionText(submission.options, submission.myOption);
  const answerText = findOptionText(submission.options, submission.answer);

  return (
    <li>
      <button
        className="quiz-result-sheet__card"
        type="button"
        data-open={isOpen || undefined}
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className="quiz-result-sheet__card-head">
          <span className="quiz-result-sheet__card-title">
            {/* 번호 칩 색이 곧 정오다 — 펼치지 않아도 결과를 알 수 있다 */}
            <span
              className="quiz-result-sheet__chip"
              data-correct={submission.correct || undefined}
            >
              {order}
            </span>
            <span className="quiz-result-sheet__question">{submission.question}</span>
          </span>
          <span className="quiz-result-sheet__card-arrow" aria-hidden="true" />
        </span>

        {isOpen && (
          <span className="quiz-result-sheet__answers">
            <span className="quiz-result-sheet__answer">
              <span className="quiz-result-sheet__answer-label">내가 선택한 답</span>
              <AnswerMark text={myOptionText} tone={submission.correct ? 'correct' : 'wrong'} />
            </span>
            <span className="quiz-result-sheet__answer-divider" aria-hidden="true" />
            <span className="quiz-result-sheet__answer">
              <span className="quiz-result-sheet__answer-label">정답</span>
              <AnswerMark text={answerText} tone="correct" />
            </span>
          </span>
        )}
      </button>
    </li>
  );
}

export default function QuizResultSheet({ inning, onClose }: QuizResultSheetProps) {
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
              <QuizAnswerCard key={submission.quizId} order={index + 1} submission={submission} />
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
}
