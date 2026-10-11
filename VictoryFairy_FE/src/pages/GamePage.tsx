import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { Game } from '../api';
import MatchCard from '../components/MatchCard';
import { useLiveGames } from '../hooks/useLiveGames';
import { gameDetailPath, type GameDetailPageState } from '../routes';
import { formatDateLabel, getTodayInSeoul, shiftDate } from '../utils/date';
import '../styles/GamePage.css';

/** 주소의 `?date=` 가 `yyyy-MM-dd` 꼴일 때만 받는다. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 고른 날짜가 오늘의 앞/뒤 어디인지에 따라 인사말이 달라진다.
 * Figma: 07.22(과거) · 07.23(오늘) · 07.24(미래) 세 노드가 이 차이만 다르다.
 */
function getHeading(date: string, today: string): [string, string] {
  if (date < today) return ['지난 경기를 확인하고', '퀴즈 결과를 확인해보세요!'];
  if (date > today) return ['다가올 경기 일정을', '확인해보세요'];
  return ['오늘의 경기를 확인하고', '퀴즈를 풀어볼까요?'];
}

/**
 * GamePage — 날짜별 경기 목록.
 * Figma: SWM / [Game] 경기 메인 (node 657:3919, 589:6000, 597:7009)
 *
 * 세 노드는 별개 화면이 아니라 같은 화면의 날짜 차이다 — 상단 바에서 날짜를
 * 옮기면 인사말과 목록이 바뀐다. 카드를 누르면 경기 상세 페이지로 간다.
 *
 * 고른 날짜는 주소(`?date=`)에 둔다 — 상세에 갔다가 뒤로 돌아왔을 때 보던 날짜가
 * 오늘로 되돌아가지 않게 하려는 것이다. 오늘이면 주소를 비워 둔다.
 *
 * 오늘 날짜는 SSE(`/games/subscribe`)로 이닝·점수·상태를 실시간으로 갈아 끼운다.
 * 다른 날짜는 바뀔 일이 없어 GET 만 한다(`useLiveGames`).
 */
export default function GamePage() {
  const navigate = useNavigate();
  // 서버가 판정하는 "오늘"과 같은 기준(Asia/Seoul)을 쓴다. 화면이 살아 있는 동안 고정이다.
  const [today] = useState(getTodayInSeoul);
  const [searchParams, setSearchParams] = useSearchParams();
  const dateParam = searchParams.get('date');
  const date = dateParam !== null && DATE_PATTERN.test(dateParam) ? dateParam : today;

  /** 날짜를 옮긴다. 기록을 쌓지 않아(replace) 뒤로가기가 날짜를 하나씩 되감지 않는다. */
  const moveDate = (days: number) => {
    const next = shiftDate(date, days);
    setSearchParams(next === today ? {} : { date: next }, { replace: true });
  };

  const { games, isLoading, loadFailed } = useLiveGames('all', date, date === today);

  /** 카드를 누르면 상세로 간다. 들고 있는 경기를 넘겨 첫 응답 전에도 바로 그린다. */
  const openGame = (game: Game) => {
    const state: GameDetailPageState = { game };
    navigate(gameDetailPath(game.gameId), { state });
  };

  const [headingTop, headingBottom] = getHeading(date, today);

  return (
    <div className="game-page">
      <header className="game-page__topbar">
        <button
          className="game-page__nav game-page__nav--prev"
          type="button"
          onClick={() => moveDate(-1)}
          aria-label="이전 날짜"
        >
          <span className="game-page__nav-icon" aria-hidden="true" />
        </button>

        <p className="game-page__date">{formatDateLabel(date)}</p>

        <button
          className="game-page__nav game-page__nav--next"
          type="button"
          onClick={() => moveDate(1)}
          aria-label="다음 날짜"
        >
          <span className="game-page__nav-icon" aria-hidden="true" />
        </button>
      </header>

      <h1 className="game-page__heading">
        {headingTop}
        <br />
        {headingBottom}
      </h1>

      <div className="game-page__body">
        {isLoading && <p className="game-page__status">경기 목록을 불러오는 중입니다.</p>}

        {loadFailed && (
          <p className="game-page__status game-page__status--error">
            경기 목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {/* 그 날짜에 경기가 없으면 빈 배열(200)이 온다 — 오류가 아니다. */}
        {!isLoading && !loadFailed && games.length === 0 && (
          <p className="game-page__status">이 날은 예정된 경기가 없어요.</p>
        )}

        {!isLoading && !loadFailed && games.length > 0 && (
          <ol className="game-page__list">
            {games.map((game) => (
              <MatchCard
                key={game.gameId}
                game={game}
                showScore
                onSelect={openGame}
              />
            ))}
          </ol>
        )}
      </div>
    </div>
  );
}
