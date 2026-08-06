import { useEffect, useState } from 'react';
// [DUMMY] UI 확인용. 백엔드가 뜨면 아래 한 줄을 되살리고 dummyGames import 를 지운다.
import { getGameList } from '../api';
import type { Game } from '../api';
import GameDetailSheet from '../components/GameDetailSheet';
import MatchCard from '../components/MatchCard';
// import { getDummyGameList } from '../data/dummyGames';
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
 */
export default function GamePage() {
  // 서버가 판정하는 "오늘"과 같은 기준(Asia/Seoul)을 쓴다. 화면이 살아 있는 동안 고정이다.
  const [today] = useState(getTodayInSeoul);
  const [date, setDate] = useState(today);

  const [games, setGames] = useState<Game[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [selectedGame, setSelectedGame] = useState<Game | null>(null);

  useEffect(() => {
    // 날짜를 연달아 넘길 때 늦게 도착한 이전 날짜 응답이 화면을 덮지 않도록 막는다.
    let alive = true;

    setIsLoading(true);
    setLoadFailed(false);

    // 날짜는 항상 명시해 보낸다 — 생략하면 자정 경계에서 서버의 "오늘"과 어긋난다.
    // [DUMMY] 되돌릴 때: getDummyGameList → getGameList
    getGameList(date)
      .then((list) => {
        if (alive) setGames(list);
      })
      .catch(() => {
        if (alive) setLoadFailed(true);
      })
      .finally(() => {
        if (alive) setIsLoading(false);
      });

    return () => {
      alive = false;
    };
  }, [date]);

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
              <MatchCard key={game.gameId} game={game} onSelect={setSelectedGame} />
            ))}
          </ol>
        )}
      </div>

      {selectedGame && (
        <GameDetailSheet game={selectedGame} onClose={() => setSelectedGame(null)} />
      )}
    </div>
  );
}
