import { useEffect, useState } from 'react';
import type { QuizSubmission } from '../api';
import '../styles/QuizResultSheet.css';

/**
 * QuizResultSheet — 퀴즈 결과의 "문제 및 정답" 바텀시트.
 * Figma: SWM / [Game] 퀴즈 결과 상세보기-펼침예시 (node 724:18166)
 *
 * 회차 행의 화살표를 누르면 올라온다. 디자인은 `1회 초`·`1회 말` 로 나눠 담지만
 * **서버가 회차를 주지 않아 지금은 한 묶음으로 전부 보여준다**(QuizResultPage 머리말 참고).
 *
 * 카드는 접힌 상태로 시작하고, 누르면 내가 고른 답과 정답이 함께 펼쳐진다.
 */

type QuizResultSheetProps = {
  /** 시트 제목. 회차가 생기기 전까지는 화면이 "1회 …" 로 넘겨 준다. */
  title: string;
  submissions: QuizSubmission[];
  correctCount: number;
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
              <AnswerMark
                text={submission.myOptionText}
                tone={submission.correct ? 'correct' : 'wrong'}
              />
            </span>
            <span className="quiz-result-sheet__answer-divider" aria-hidden="true" />
            <span className="quiz-result-sheet__answer">
              <span className="quiz-result-sheet__answer-label">정답</span>
              <AnswerMark text={submission.answerText} tone="correct" />
            </span>
          </span>
        )}
      </button>
    </li>
  );
}

export default function QuizResultSheet({
  title,
  submissions,
  correctCount,
  onClose,
}: QuizResultSheetProps) {
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
            <span className="quiz-result-sheet__group-name">전체 문제</span>
            <span className="quiz-result-sheet__group-count">
              {correctCount}/{submissions.length}
            </span>
          </p>

          <ul className="quiz-result-sheet__cards">
            {submissions.map((submission, index) => (
              <QuizAnswerCard key={submission.quizId} order={index + 1} submission={submission} />
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
}
