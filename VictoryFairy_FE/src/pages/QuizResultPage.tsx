import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, getQuizSubmissions } from '../api';
import type { QuizSubmission, QuizSubmissionSummary } from '../api';
import LedNumber from '../components/LedNumber';
import QuizResultSheet from '../components/QuizResultSheet';
import '../styles/QuizResultPage.css';

/**
 * QuizResultPage — 퀴즈 결과.
 * Figma: SWM / [Game] 퀴즈 결과 (node 597:9739)
 *              [Game] 퀴즈 결과 상세보기-펼침예시 (node 724:18166 — 문제 및 정답 시트)
 *
 * 종료된 경기의 상세 시트에서 "퀴즈 결과 확인하기"로 들어온다.
 *
 * ── 회차가 없다 ────────────────────────────────────────────────────
 * 디자인은 1~9회 정답률을 회차별로 늘어놓지만 **서버에 회차 개념이 없다** —
 * `GET /quizzes/submissions` 는 내가 받은 문제를 이닝 구분 없이 최신순으로 줄 뿐이고
 * 경기로 좁히는 파라미터도 없다(docs/quiz.md). 그래서 지금은 **`1회` 한 줄만** 두고,
 * 그 줄을 누르면 전체 문제 목록이 시트로 열린다. 회차가 생기면 이 화면은 목록을
 * 회차별로 쪼개고 시트에 회차를 넘기기만 하면 된다 — 행·게이지·시트는 그대로 쓴다.
 *
 * ── 숫자의 출처 ────────────────────────────────────────────────────
 *   획득 포인트 → `submissions.content[].earnedPoint` 합
 *   정답률·맞힌 수 → `summary.accuracy` · `summary.correctCount` / `summary.total`
 * 요약은 전체 기준이지만 포인트 합은 **받아 온 페이지만큼**이라, 아래에서 모든 페이지를
 * 이어 받는다. 목록 시트도 같은 자료를 쓰므로 어차피 전부 필요하다.
 */

/**
 * 이어 받을 최대 페이지 수(한 페이지 20건).
 *
 * 이력은 계정이 오래될수록 무한정 길어진다 — 상한이 없으면 화면 하나를 여는 데
 * 요청 수십 건이 나간다. 여기서 끊기면 포인트 합이 실제보다 작아지므로,
 * 현실적으로 닿기 어려운 값으로 두되 닿았다는 사실은 화면에 알린다.
 */
const MAX_SUBMISSION_PAGES = 25;

type LoadedResult = {
  summary: QuizSubmissionSummary;
  submissions: QuizSubmission[];
  /** 상한에 걸려 뒤를 못 받았는지. 포인트 합이 실제보다 작다는 뜻이다. */
  isTruncated: boolean;
};

/**
 * 풀이 이력을 끝까지 이어 받는다.
 *
 * 페이지를 병렬로 던지지 않는 이유 — 전체 페이지 수는 첫 응답을 받아야 알 수 있고,
 * 그 사이 새 제출이 끼면 경계가 밀린다. 순서대로 `hasNext` 를 따라가는 편이 안전하다.
 */
async function loadAllSubmissions(): Promise<LoadedResult> {
  const first = await getQuizSubmissions(0);
  const submissions = [...first.submissions.content];

  let hasNext = first.submissions.hasNext;
  let page = 0;

  while (hasNext && page + 1 < MAX_SUBMISSION_PAGES) {
    page += 1;
    const next = await getQuizSubmissions(page);
    submissions.push(...next.submissions.content);
    hasNext = next.submissions.hasNext;
  }

  return { summary: first.summary, submissions, isTruncated: hasNext };
}

/** 게이지 칸 수. 디자인 고정값이라 정답률을 이 눈금으로 반올림해 채운다. */
const GAUGE_SEGMENTS = 10;

export default function QuizResultPage() {
  const navigate = useNavigate();

  const [result, setResult] = useState<LoadedResult | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [isSheetOpen, setIsSheetOpen] = useState(false);

  useEffect(() => {
    // 화면을 떠난 뒤 도착한 응답으로 state 를 건드리지 않도록 막는다.
    let alive = true;

    loadAllSubmissions()
      .then((loaded) => {
        if (alive) setResult(loaded);
      })
      .catch((error: unknown) => {
        if (alive) {
          setLoadError(
            error instanceof ApiError
              ? error.message
              : '퀴즈 결과를 불러오지 못했어요. 잠시 후 다시 시도해주세요.',
          );
        }
      });

    return () => {
      alive = false;
    };
  }, []);

  const summary = result?.summary ?? null;
  const earnedPoint = result?.submissions.reduce((sum, item) => sum + item.earnedPoint, 0) ?? 0;
  const accuracyPercent = Math.round((summary?.accuracy ?? 0) * 100);
  const filledSegments = Math.round((accuracyPercent / 100) * GAUGE_SEGMENTS);

  return (
    <div className="quiz-result-page">
      <header className="quiz-result-page__topbar">
        <button
          className="quiz-result-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="quiz-result-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="quiz-result-page__topbar-title">퀴즈 결과</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="quiz-result-page__topbar-spacer" aria-hidden="true" />
      </header>

      <p className="quiz-result-page__heading">오늘 경기 감각이 정말 좋았어요!</p>

      {loadError !== null && (
        <p className="quiz-result-page__status quiz-result-page__status--error" role="alert">
          {loadError}
        </p>
      )}

      {result === null && loadError === null && (
        <p className="quiz-result-page__status">결과를 불러오는 중이에요</p>
      )}

      {result !== null && summary !== null && (
        <>
          {/* 전광판 — 야구장 스코어보드를 흉내 낸 판이라 나사·통풍구까지 디자인에 있다 */}
          <section className="quiz-result-page__board" aria-label="퀴즈 성적">
            <span className="quiz-result-page__screw" data-corner="tl" aria-hidden="true" />
            <span className="quiz-result-page__screw" data-corner="tr" aria-hidden="true" />
            <span className="quiz-result-page__screw" data-corner="bl" aria-hidden="true" />
            <span className="quiz-result-page__screw" data-corner="br" aria-hidden="true" />

            <div className="quiz-result-page__board-main">
              <div className="quiz-result-page__board-col">
                <p className="quiz-result-page__board-chip">획득 포인트</p>
                <LedNumber
                  value={earnedPoint}
                  tone="primary"
                  label={`획득 포인트 ${earnedPoint}점`}
                />
              </div>

              <span className="quiz-result-page__board-divider" aria-hidden="true" />

              <div className="quiz-result-page__board-col">
                <p className="quiz-result-page__board-chip">정답률</p>
                <LedNumber
                  value={accuracyPercent}
                  tone="white"
                  suffix="percent"
                  label={`정답률 ${accuracyPercent}퍼센트`}
                />
              </div>
            </div>

            <div className="quiz-result-page__board-foot">
              <span className="quiz-result-page__grille" aria-hidden="true">
                {Array.from({ length: 12 }, (_, index) => (
                  <span key={index} />
                ))}
              </span>
              <p className="quiz-result-page__board-note">
                {summary.correctCount}/{summary.total} 문제 정답
              </p>
              <span className="quiz-result-page__grille" aria-hidden="true">
                {Array.from({ length: 12 }, (_, index) => (
                  <span key={index} />
                ))}
              </span>
            </div>
          </section>

          {result.isTruncated && (
            <p className="quiz-result-page__status quiz-result-page__status--note">
              풀이 이력이 많아 최근 {MAX_SUBMISSION_PAGES * 20}문제까지만 계산했어요
            </p>
          )}

          <h2 className="quiz-result-page__section-title">회별 정답률</h2>

          <ul className="quiz-result-page__innings">
            <li>
              <button
                className="quiz-result-page__inning"
                type="button"
                onClick={() => setIsSheetOpen(true)}
                aria-label={`1회 문제 및 정답 보기. ${summary.total}문제 중 ${summary.correctCount}문제 정답`}
              >
                <span className="quiz-result-page__inning-label">1회</span>

                <span className="quiz-result-page__gauge" aria-hidden="true">
                  {Array.from({ length: GAUGE_SEGMENTS }, (_, index) => (
                    <span data-on={index < filledSegments || undefined} key={index} />
                  ))}
                </span>

                <span className="quiz-result-page__inning-score">
                  {summary.correctCount}/{summary.total}
                </span>
                <span className="quiz-result-page__inning-arrow" aria-hidden="true" />
              </button>
            </li>
          </ul>
        </>
      )}

      {isSheetOpen && result !== null && summary !== null && (
        <QuizResultSheet
          title="1회 문제 및 정답"
          submissions={result.submissions}
          correctCount={summary.correctCount}
          onClose={() => setIsSheetOpen(false)}
        />
      )}
    </div>
  );
}
