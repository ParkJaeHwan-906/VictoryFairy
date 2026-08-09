import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getGameList } from '../api';
import type { Game, MyProfile } from '../api';
import GameDetailSheet from '../components/GameDetailSheet';
import MatchCard from '../components/MatchCard';
import { getTeamDisplay } from '../data/kboTeams';
import { getPlayerPositionLabel } from '../data/playerPositions';
import { ROUTES } from '../routes';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import { getTodayInSeoul } from '../utils/date';
import '../styles/MainPage.css';

/** 응원 구단이 없을 때 오늘 경기 목록에서 보여줄 최대 개수. */
const GAME_PREVIEW_LIMIT = 3;

/**
 * 화면에 세울 오늘의 경기.
 *
 * 응원 구단이 있으면 그 구단 경기만 남긴다(더블헤더면 두 건이다). 아직 안 골랐거나
 * 모르는 구단이면 오늘 경기 앞쪽 몇 건을 대신 보여준다 — 빈 화면보다는 낫다.
 */
function pickGames(games: Game[], supportTeam: MyProfile['supportTeam']): Game[] {
  if (!supportTeam) return games.slice(0, GAME_PREVIEW_LIMIT);

  const mine = games.filter(
    (game) => game.homeTeam === supportTeam.name || game.awayTeam === supportTeam.name,
  );

  return mine;
}

/** 헤더의 응원 구단 배지. 모르는 구단이면 로고 없이 이름만 남긴다(MatchCard 와 같은 규칙). */
function SupportTeamBadge({ name }: { name: string }) {
  const display = getTeamDisplay(name);

  return (
    <p className="main-page__team">
      {display && <img className="main-page__team-logo" src={display.logo} alt="" />}
      <span className="main-page__team-name">{display?.label ?? name}</span>
    </p>
  );
}

/**
 * MainPage — 홈 메인.
 *
 * ⚠ 임시(MVP) 화면이다. 홈 디자인이 아직 없어, 다른 화면들에서 이미 확정된 것만
 * 조합했다 — 라운지의 아이보리 헤더 카드, 경기 화면의 MatchCard·상세 시트,
 * 402px 앱 폭·24px 거터·토큰 색/타이포. 새 시각 언어는 만들지 않았다.
 * 디자인이 나오면 이 파일과 MainPage.css 를 통째로 교체하는 것을 전제로 한다.
 *
 * 그리는 값은 전부 이미 있는 계약뿐이다(새 API 를 만들지 않았다):
 *   프로필(`GET /users/me`) — 닉네임 · 응원 구단 · 응원 선수 · 포인트 · 승리요정 점수
 *   오늘 경기(`GET /games`) — 경기 화면과 같은 함수를 그대로 쓴다
 */
export default function MainPage() {
  const profile = useMyProfile();
  const profileStatus = useAccountStore((state) => state.status);
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  // 프로필은 새로고침하면 비어 있는 상태로 시작한다(persist 하지 않는다).
  // 홈이 로그인 후 첫 화면이라 여기서 채운다. 진행 중이면 스토어가 같은 요청에 합류시킨다.
  useEffect(() => {
    if (profileStatus === 'idle') {
      void fetchProfile();
    }
  }, [profileStatus, fetchProfile]);

  // 서버가 판정하는 "오늘"과 같은 기준(Asia/Seoul). 화면이 살아 있는 동안 고정이다.
  const [today] = useState(getTodayInSeoul);
  const [games, setGames] = useState<Game[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [selectedGame, setSelectedGame] = useState<Game | null>(null);

  useEffect(() => {
    // 늦게 도착한 응답이 떠난 화면을 건드리지 않게 막는다(GamePage 와 같은 방식).
    let alive = true;

    getGameList(today)
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
  }, [today]);

  const supportTeam = profile?.supportTeam ?? null;
  const supportPlayers = profile?.supportPlayers ?? [];
  const visibleGames = pickGames(games, supportTeam);

  return (
    <main className="main-page">
      <header className="main-page__header">
        <p className="main-page__greeting">
          {/* 프로필이 아직 없으면 이름 자리를 비워 둔다 — 잠깐 뒤 채워진다. */}
          {profile ? `${profile.nickname}님, 안녕하세요` : '안녕하세요'}
        </p>

        <h1 className="main-page__title">
          오늘의 경기를 확인하고
          <br />
          퀴즈를 풀어볼까요?
        </h1>

        {supportTeam ? (
          <SupportTeamBadge name={supportTeam.name} />
        ) : (
          // 온보딩을 건너뛰었거나 아직 안 고른 경우. 고르러 갈 길을 남긴다.
          <Link className="main-page__team main-page__team--empty" to={ROUTES.teamSelect}>
            응원 구단을 골라주세요
          </Link>
        )}

        <dl className="main-page__stats">
          <div className="main-page__stat">
            <dt className="main-page__stat-label">포인트</dt>
            <dd className="main-page__stat-value">{profile ? `${profile.point}p` : '-'}</dd>
          </div>
          <div className="main-page__stat">
            <dt className="main-page__stat-label">승리요정 점수</dt>
            <dd className="main-page__stat-value">{profile ? `${profile.bqScore}점` : '-'}</dd>
          </div>
        </dl>
      </header>

      <section className="main-page__section">
        <div className="main-page__section-head">
          <h2 className="main-page__section-title">
            {supportTeam ? `오늘의 ${supportTeam.name} 경기` : '오늘의 경기'}
          </h2>
          <Link className="main-page__more" to={ROUTES.game}>
            전체 보기
          </Link>
        </div>

        {isLoading && <p className="main-page__status">경기를 불러오는 중입니다.</p>}

        {loadFailed && (
          <p className="main-page__status main-page__status--error">
            경기를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {/* 경기가 없는 날은 빈 배열(200)이 온다 — 오류가 아니다. */}
        {!isLoading && !loadFailed && visibleGames.length === 0 && (
          <p className="main-page__status">
            {supportTeam ? '오늘은 응원 구단의 경기가 없어요.' : '오늘은 예정된 경기가 없어요.'}
          </p>
        )}

        {!isLoading && !loadFailed && visibleGames.length > 0 && (
          <ol className="main-page__game-list">
            {visibleGames.map((game) => (
              <MatchCard key={game.gameId} game={game} onSelect={setSelectedGame} />
            ))}
          </ol>
        )}
      </section>

      <section className="main-page__section">
        <div className="main-page__section-head">
          <h2 className="main-page__section-title">내 응원 선수</h2>
          <Link className="main-page__more" to={ROUTES.my}>
            관리
          </Link>
        </div>

        {supportPlayers.length === 0 ? (
          <p className="main-page__status">아직 등록한 응원 선수가 없어요.</p>
        ) : (
          <ul className="main-page__player-list">
            {supportPlayers.map((player) => {
              const position = getPlayerPositionLabel(player.playerPosition);

              return (
                <li className="main-page__player" key={player.playerId}>
                  <span className="main-page__player-name">{player.playerName}</span>
                  {/* 등번호·포지션은 절반 가까이가 null 이라 있는 것만 붙인다(docs/player.md) */}
                  <span className="main-page__player-meta">
                    {[player.playerNumber && `No.${player.playerNumber}`, position]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {selectedGame && (
        <GameDetailSheet game={selectedGame} onClose={() => setSelectedGame(null)} />
      )}
    </main>
  );
}
