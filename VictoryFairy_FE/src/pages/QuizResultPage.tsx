import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError, getQuizSubmissions, isQuizGameNotFound, isQuizGameNotStarted } from '../api';
import type { QuizInningResult, QuizSubmissionHistory } from '../api';
import LedNumber from '../components/LedNumber';
import QuizResultSheet from '../components/QuizResultSheet';
import { readQuizPageState } from '../routes';
import '../styles/QuizResultPage.css';

/**
 * QuizResultPage — 퀴즈 결과(이닝별).
 * Figma: SWM / [Game] 퀴즈 결과-스크롤 (node 708:16321 — 1~9회 행)
 *              [Game] 퀴즈 결과 상세보기-펼침예시 (node 724:18166 — 문제 및 정답 시트)
 *
 * 종료된 경기의 상세 시트에서 "퀴즈 결과 확인하기"로 들어온다.
 *
 * ── 이닝이 생겼다(2026-08-13) ──────────────────────────────────────
 * `GET /quizzes/submissions` 가 `gameId` 로 **경기 한 건**의 이닝별 결산을 준다 —
 * 경기 전체 요약(`summary`) + 이닝 배열(`innings[]`, 각각 자기 요약과 문제 목록).
 * 그래서 디자인대로 회차를 늘어놓을 수 있게 됐고, 행을 누르면 **그 이닝의** 문제만
 * 시트로 열린다(종전에는 이닝 개념이 없어 `1회` 한 줄에 전부 담았다).
 *
 * 열거되는 것은 **결산이 끝난 이닝뿐**이다 — 진행 중 경기면 현재 이닝이 빠지고,
 * 문제를 못 받은 이닝도 `0/0` 행으로 남는다(빈 행은 열 것이 없어 누를 수 없다).
 *
 * ── 머리말이 성적에 따라 갈린다 ──────────────────────────────────────
 * 맨 위 축하 문구는 고정이 아니라 **경기 전체 정답률**이 고른다(90/70/40 경계,
 * `HEADING_TIERS` 참고). 요약을 받아야 정해지므로 불러오는 동안에는 띄우지 않는다.
 *
 * ── 숫자의 출처 ────────────────────────────────────────────────────
 *   획득 BQ   → `summary.earnedBq` (2026-09-03 신설)
 *   정답률·맞힌 수 → `summary.accuracy` · `summary.correctCount` / `summary.total`
 *   회별 게이지 → `innings[].summary` 의 같은 세 값
 *
 * ── ⚠️ 전광판의 큰 수는 **재화가 아니다** ────────────────────────────
 * 배점이 2026-09-03 에 두 축으로 갈렸다 — 상점에서 쓰는 **포인트(`point`)** 와 랭킹을
 * 가르는 **BQ(`bqScore`)** 다. 이 화면이 세우는 것은 뒤쪽이다: 경기를 잘 풀면 랭킹이
 * 오른다는 이야기라, 여기서 포인트를 보여 주면 "이만큼 벌었다"로 읽혀 상점 잔액과 어긋난다
 * (`earnedPoint` 도 응답에 함께 오지만 이 화면은 쓰지 않는다).
 *
 * 그리고 `earnedBq` 는 **적립 원장이 아니라 이 경기 정답 배점의 합(표시용)** 이다 —
 * 누적 BQ(`GET /users/me` 의 `bqScore`, 라운지 랭킹의 수)와는 다른 수다.
 */

/** 게이지 칸 수. 디자인 고정값이라 정답률을 이 눈금으로 반올림해 채운다. */
const GAUGE_SEGMENTS = 10;

/**
 * 정답률이 가장 낮은 구간의 머리말. 아래 표에서 걸리는 구간이 없을 때의 값이기도 하다.
 * 줄바꿈 위치는 디자인이 정한 것이라 문구에 그대로 넣고 `white-space: pre-line` 으로 살린다.
 */
const LOWEST_HEADING = '다음 경기에서는\n더 좋은 결과를 기대할게요!';

/**
 * 정답률 구간별 머리말.
 *
 * 위에서부터 훑어 **처음 걸리는 구간**을 쓰므로 `minPercent` 내림차순이어야 한다
 * (구간의 위쪽 끝을 따로 적지 않아도 되는 대신, 순서가 곧 계약이다).
 * 40% 미만은 `LOWEST_HEADING` 이 받는다.
 */
const HEADING_TIERS = [
  { minPercent: 90, message: '오늘 경기,\n완벽하게 읽어냈어요!' },
  { minPercent: 70, message: '오늘 경기 감각이\n정말 좋았어요!' },
  { minPercent: 40, message: '좋은 출발이에요!\n다음 경기에서 더 높이 올라가 볼까요?' },
] as const;

/**
 * 정답률(%)에 맞는 머리말을 고른다.
 *
 * 기준은 게이지·전광판과 같은 **반올림한 정수 퍼센트**다 — 89.6% 를 두고 화면이 `90%`
 * 라고 써 놓고 머리말만 한 단계 낮게 나가면 서로 어긋나 보인다.
 */
function toHeading(accuracyPercent: number): string {
  return (
    HEADING_TIERS.find(({ minPercent }) => accuracyPercent >= minPercent)?.message ?? LOWEST_HEADING
  );
}

/** 문맥 없이 들어와 조회를 걸 수 없을 때의 안내. */
const NO_CONTEXT_MESSAGE = '경기 정보가 없어 결과를 불러올 수 없어요. 경기 목록에서 다시 들어와 주세요.';

/** 조회 실패를 화면 문구로 옮긴다. 실패는 둘뿐이라(404·403) 나머지는 일반 문구다. */
function toLoadMessage(error: unknown): string {
  if (isQuizGameNotFound(error)) return '경기를 찾을 수 없어요. 경기 목록에서 다시 들어와 주세요.';
  if (isQuizGameNotStarted(error)) return '아직 시작하지 않은 경기예요.';

  return error instanceof ApiError
    ? error.message
    : '퀴즈 결과를 불러오지 못했어요. 잠시 후 다시 시도해주세요.';
}

/**
 * 회차 한 줄 — 이닝 번호 · 정답률 게이지 · 점수 · 화살표.
 *
 * 문제를 하나도 받지 않은 이닝(`0/0`)은 열어도 보여줄 것이 없어 누를 수 없게 둔다.
 * 행 자체는 남긴다 — 디자인이 열거된 이닝을 빠짐없이 늘어놓고, 빈 회차도 "그 이닝엔
 * 문제를 못 받았다"는 정보이기 때문이다.
 */
function InningRow({ result, onOpen }: { result: QuizInningResult; onOpen: () => void }) {
  const { inning, summary, quizzes } = result;
  const filledSegments = Math.round(summary.accuracy * GAUGE_SEGMENTS);
  const isEmpty = quizzes.length === 0;

  return (
    <li>
      <button
        className="quiz-result-page__inning"
        type="button"
        disabled={isEmpty}
        onClick={onOpen}
        aria-label={
          isEmpty
            ? `${inning}회 받은 문제 없음`
            : `${inning}회 문제 및 정답 보기. ${summary.total}문제 중 ${summary.correctCount}문제 정답`
        }
      >
        <span className="quiz-result-page__inning-label">{inning}회</span>

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
  );
}

export default function QuizResultPage() {
  const navigate = useNavigate();
  const context = readQuizPageState(useLocation().state);
  const gameId = context?.gameId ?? null;

  const [result, setResult] = useState<QuizSubmissionHistory | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /**
   * 시트에 띄울 이닝의 **번호**. 닫혀 있으면 `null` 이다.
   *
   * 이닝 객체를 그대로 붙들지 않는 이유 — 좋아요가 `result` 를 갈아 끼우면 붙들어 둔
   * 객체는 갱신 전 사본으로 남아, 시트가 방금 누른 좋아요를 반영하지 못한다.
   */
  const [openInningNumber, setOpenInningNumber] = useState<number | null>(null);

  useEffect(() => {
    // 경기를 모르면 `gameId` 없는 요청은 400 이라 아예 걸지 않는다.
    if (gameId === null) {
      setLoadError(NO_CONTEXT_MESSAGE);
      return;
    }

    // 화면을 떠난 뒤 도착한 응답으로 state 를 건드리지 않도록 막는다.
    let alive = true;

    getQuizSubmissions(gameId)
      .then((loaded) => {
        if (alive) setResult(loaded);
      })
      .catch((error: unknown) => {
        if (alive) setLoadError(toLoadMessage(error));
      });

    return () => {
      alive = false;
    };
  }, [gameId]);

  /**
   * 좋아요를 이 화면의 자료에 반영한다.
   *
   * 시트·카드는 접히거나 닫히면 사라지는 자리라 상태를 들고 있을 수 없다
   * (`QuizLikeChangeHandler` 주석 참고). 낙관적 반영·서버 확정·실패 되돌리기가 모두
   * 이 함수로 들어오므로, 여기서는 받은 값을 그대로 덮어쓰기만 한다.
   */
  const handleLikeChange = (quizId: number, liked: boolean, likeCount: number) => {
    setResult((current) =>
      current === null
        ? current
        : {
            ...current,
            innings: current.innings.map((inningResult) => ({
              ...inningResult,
              quizzes: inningResult.quizzes.map((quiz) =>
                quiz.quizId === quizId ? { ...quiz, liked, likeCount } : quiz,
              ),
            })),
          },
    );
  };

  const summary = result?.summary ?? null;
  const accuracyPercent = Math.round((summary?.accuracy ?? 0) * 100);
  const openInning =
    result?.innings.find((inningResult) => inningResult.inning === openInningNumber) ?? null;

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

      {/*
        머리말이 정답률에 따라 갈리므로 요약을 받은 뒤에야 쓸 수 있다 —
        불러오는 중에 미리 띄우면 0% 문구("다음 경기에서는…")가 잠깐 스쳐 지나간다.
      */}
      {summary !== null && (
        <p className="quiz-result-page__heading">{toHeading(accuracyPercent)}</p>
      )}

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
                {/* 상점 재화(포인트)가 아니라 랭킹 축이다 — 머리말 참고 */}
                <p className="quiz-result-page__board-chip">획득 BQ</p>
                <LedNumber
                  value={summary.earnedBq}
                  tone="primary"
                  label={`획득 BQ ${summary.earnedBq}점`}
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

          <h2 className="quiz-result-page__section-title">회별 정답률</h2>

          {/*
            빈 배열은 서로 다른 셋을 한 모양으로 덮는다(docs/quiz.md) — 이 경기에 내 기록이
            0건 · 이닝 값을 아직 못 읽음 · 진행 중인데 1회라 결산할 이닝이 없음. 서버가 사유를
            주지 않으므로 화면도 하나의 문구로 안내한다.
          */}
          {result.innings.length === 0 ? (
            <p className="quiz-result-page__status">이 경기에서 푼 문제가 아직 없어요</p>
          ) : (
            <ul className="quiz-result-page__innings">
              {result.innings.map((inningResult) => (
                <InningRow
                  key={inningResult.inning}
                  result={inningResult}
                  onOpen={() => setOpenInningNumber(inningResult.inning)}
                />
              ))}
            </ul>
          )}
        </>
      )}

      {openInning !== null && (
        <QuizResultSheet
          inning={openInning}
          onLikeChange={handleLikeChange}
          onClose={() => setOpenInningNumber(null)}
        />
      )}
    </div>
  );
}
