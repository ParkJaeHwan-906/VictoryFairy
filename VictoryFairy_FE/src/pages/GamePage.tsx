import { useState } from 'react';
import GameDetailSheet from '../components/GameDetailSheet';
import MatchCard from '../components/MatchCard';
import { useLiveGames } from '../hooks/useLiveGames';
import { formatDateLabel, getTodayInSeoul, shiftDate } from '../utils/date';
import '../styles/GamePage.css';

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
 * 옮기면 인사말과 목록이 바뀐다. 카드를 누르면 상세 시트가 올라온다.
 *
 * 오늘 날짜는 SSE(`/games/subscribe`)로 이닝·점수·상태를 실시간으로 갈아 끼운다.
 * 다른 날짜는 바뀔 일이 없어 GET 만 한다(`useLiveGames`).
 */
export default function GamePage() {
  // 서버가 판정하는 "오늘"과 같은 기준(Asia/Seoul)을 쓴다. 화면이 살아 있는 동안 고정이다.
  const [today] = useState(getTodayInSeoul);
  const [date, setDate] = useState(today);

  const { games, isLoading, loadFailed } = useLiveGames('all', date, date === today);

  /*
   * 고른 경기는 객체가 아니라 id 로 든다 — 시트가 열린 동안에도 실시간 갱신이 반영되도록
   * 매 렌더 목록에서 다시 찾는다.
   */
  const [selectedGameId, setSelectedGameId] = useState<string | null>(null);
  const selectedGame = games.find((game) => game.gameId === selectedGameId) ?? null;

  const [headingTop, headingBottom] = getHeading(date, today);

  return (
    <div className="game-page">
      <header className="game-page__topbar">
        <button
          className="game-page__nav game-page__nav--prev"
          type="button"
          onClick={() => setDate((current) => shiftDate(current, -1))}
          aria-label="이전 날짜"
        >
          <span className="game-page__nav-icon" aria-hidden="true" />
        </button>

        <p className="game-page__date">{formatDateLabel(date)}</p>

        <button
          className="game-page__nav game-page__nav--next"
          type="button"
          onClick={() => setDate((current) => shiftDate(current, 1))}
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
                onSelect={(selected) => setSelectedGameId(selected.gameId)}
              />
            ))}
          </ol>
        )}
      </div>

      {selectedGame && (
        <GameDetailSheet game={selectedGame} onClose={() => setSelectedGameId(null)} />
      )}
    </div>
  );
}
