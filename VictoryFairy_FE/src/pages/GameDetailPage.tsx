import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { getLineUp, isGameNotFound } from '../api';
import type { Game, LineUpBatter, TeamLineUp } from '../api';
import FloatingHearts, { type FloatingHeart } from '../components/FloatingHearts';
import GameChat, { GameChatLoginPrompt } from '../components/GameChat';
import { formatInning, getGameStateDisplay, getScoreDisplay } from '../data/gameState';
import { getTeamDisplay, getTeamDisplayByCode } from '../data/kboTeams';
import { getPositionDisplay } from '../data/positions';
import { useLiveGames } from '../hooks/useLiveGames';
import { ROUTES, readGameDetailPageState, type QuizPageState } from '../routes';
import { useMyProfile } from '../stores/useAccountStore';
import { useIsLoggedIn } from '../stores/useAuthStore';
import { formatGameTime, getTodayInSeoul } from '../utils/date';
import '../styles/GameDetailPage.css';

/**
 * 경기의 날짜(`yyyy-MM-dd`).
 *
 * 들고 들어온 경기가 있으면 그 `gameDate` 를 쓰고, 주소만 있으면 `gameId` 앞 8자리
 * (naver_game_id 는 `20261009HTLG0` 처럼 날짜로 시작한다)로 읽는다. 둘 다 안 되면 `null`.
 */
function resolveGameDate(gameId: string, passed: Game | null): string | null {
  if (passed?.gameId === gameId) return passed.gameDate.slice(0, 10);

  const match = /^(\d{4})(\d{2})(\d{2})/.exec(gameId);
  return match ? `${match[1]}-${match[2]}-${match[3]}` : null;
}

/**
 * 동시에 떠 있을 수 있는 응원 하트 수. 연타·여러 구단이 겹쳐도 DOM 이 한없이 늘지 않게
 * 넘치면 가장 오래된 것부터 지운다(서버도 구독자당 초당 10회 남짓으로 묶어 보낸다).
 */
const MAX_FLOATING_HEARTS = 24;

/**
 * 하트가 떠오르는 로고. 누른 사람의 응원 구단 쪽이다 — 이 경기에 뛰지 않는 구단의 팬이 누른
 * 좋아요는 띄우지 않는다.
 */
type HeartOrigin = 'away' | 'home';

type OriginHeart = FloatingHeart & { origin: HeartOrigin };

/** min ~ max 사이 난수. */
const randomBetween = (min: number, max: number) => min + Math.random() * (max - min);

/** 새 하트 하나. 출발점(로고 안 어딘가)·흔들림·경로는 하나씩 다르게 뽑는다. */
function createHeart(id: number, origin: HeartOrigin): OriginHeart {
  return {
    id,
    origin,
    // 로고 칸(96 x 56)의 가운데 언저리 — 로고 그림 위에서 나오도록 가장자리는 비운다.
    x: randomBetween(-22, 22),
    y: randomBetween(10, 42),
    sway: randomBetween(10, 18) * (Math.random() < 0.5 ? -1 : 1),
    drift: randomBetween(-24, 24),
    duration: Math.round(randomBetween(1200, 1600)),
  };
}

type TeamColumnProps = {
  name: string;
  /** 이 로고에서 떠오르는 하트들. */
  hearts: OriginHeart[];
  /** 받은 좋아요 신호 수. 늘 때마다 로고가 한 번 두근거린다(0 이면 가만히). */
  beat: number;
  onHeartDone: (id: number) => void;
  /** 실시간 승리확률(%). 숫자가 아니면(값 없음·구버전 서버) 줄 자체를 그리지 않는다. */
  winRate?: number | null;
};

/** 로고 + 구단명(+ 진행 중이면 승률) 한 칸. */
function TeamColumn({ name, hearts, beat, onHeartDone, winRate }: TeamColumnProps) {
  const display = getTeamDisplay(name);

  return (
    <div className="game-detail__team">
      <span className="game-detail__logo-box">
        {display && (
          <img
            className="game-detail__logo"
            src={display.logo}
            alt=""
            // 홀짝으로 키프레임 이름을 바꿔 걸어야 연타 중에도 처음부터 다시 뛴다(CSS 참고).
            data-beat={beat === 0 ? undefined : beat % 2 === 1 ? 'odd' : 'even'}
          />
        )}
        <FloatingHearts hearts={hearts} onDone={onHeartDone} />
      </span>
      <span className="game-detail__team-name">{display?.label ?? name}</span>
      {typeof winRate === 'number' && (
        <span className="game-detail__win-rate">승률 {winRate.toFixed(1)}%</span>
      )}
    </div>
  );
}

/** 한 팀의 선발 타순. 라인업이 없으면 빈 칸으로 남겨 두 열의 균형을 유지한다. */
function BatterColumn({ batters }: { batters: LineUpBatter[] }) {
  return (
    <ol className="game-detail__batters">
      {batters.map((batter) => {
        const position = getPositionDisplay(batter.positionName);

        return (
          <li className="game-detail__batter" key={`${batter.batOrder}-${batter.name}`}>
            <span className="game-detail__batter-name">{batter.name}</span>
            {position && (
              <span className="game-detail__batter-position" title={position.detail}>
                {position.label}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}

/**
 * GameDetailPage — 경기 상세(예전 `GameDetailSheet` 를 페이지로 뺀 것).
 *
 * 위에서부터 경기 요약(구장 · 시각 · 상태 · 점수), 접었다 펴는 선발 라인업, 퀴즈 CTA,
 * 그리고 남은 높이를 모두 쓰는 **경기 채팅**(`GameChat`) 순이다.
 *
 * 경기는 주소의 `gameId` 로 그 날짜 목록에서 다시 찾는다 — 오늘 경기면 SSE 로 이닝·점수·상태가
 * 실시간으로 바뀌고(`useLiveGames`), 카드에서 넘겨받은 값은 첫 응답 전까지만 쓴다.
 *
 * **로그인 없이 열리는 공개 화면이다**(App Store 심사 5.1.1(v)). 경기 요약 · 라인업은 공개 API 라
 * 그대로 그리고, 계정 기능인 채팅은 로그인 안내로 바꾼다. 퀴즈 CTA 는 응원 구단 경기에만 서므로
 * 프로필이 없는 비로그인에서는 애초에 그려지지 않는다.
 */
export default function GameDetailPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { gameId = '' } = useParams();
  const passedGame = readGameDetailPageState(location.state);

  const [today] = useState(getTodayInSeoul);
  const date = resolveGameDate(gameId, passedGame);

  // 날짜를 못 읽는 주소면 아래에서 돌려보내지만, 훅은 조건 없이 불러야 해 오늘로 채운다.
  const { games, isLoading, loadFailed } = useLiveGames('all', date ?? today, date === today);
  const game =
    games.find((item) => item.gameId === gameId) ??
    (passedGame?.gameId === gameId ? passedGame : null);

  const profile = useMyProfile();
  const isLoggedIn = useIsLoggedIn();

  const [lineUps, setLineUps] = useState<TeamLineUp[] | null>(null);
  const [lineUpError, setLineUpError] = useState<string | null>(null);
  const [isLineUpOpen, setIsLineUpOpen] = useState(false);
  const lineUpPanelId = useId();

  useEffect(() => {
    if (!gameId) return;

    // 다른 경기로 갈아탄 뒤 늦게 도착한 응답으로 상태를 건드리지 않도록 막는다.
    let alive = true;
    setLineUps(null);
    setLineUpError(null);

    getLineUp(gameId)
      .then((list) => {
        if (alive) setLineUps(list);
      })
      .catch((error: unknown) => {
        if (!alive) return;
        setLineUpError(
          isGameNotFound(error) ? '경기 정보를 찾을 수 없어요' : '선수 정보를 불러오지 못했어요',
        );
      });

    return () => {
      alive = false;
    };
  }, [gameId]);

  /*
   * 좋아요 — 채팅(`GameChat`)이 받은 SSE `likes` 신호마다 누른 사람의 응원 구단 로고에서
   * 하트가 하나씩 떠오르고, 그 로고가 두근거린다.
   */
  const [hearts, setHearts] = useState<OriginHeart[]>([]);
  const [beats, setBeats] = useState({ away: 0, home: 0 });
  const heartIdRef = useRef(0);

  const awayTeam = game?.awayTeam;
  const homeTeam = game?.homeTeam;

  const handleLikes = useCallback(
    (teamCodes: string[]) => {
      // 이름(경기)과 코드(채팅)는 표기가 달라 같은 표시 정보로 모이는지로 맞춘다.
      const away = awayTeam === undefined ? undefined : getTeamDisplay(awayTeam);
      const home = homeTeam === undefined ? undefined : getTeamDisplay(homeTeam);

      const spawned = teamCodes.flatMap((code): OriginHeart[] => {
        const display = getTeamDisplayByCode(code);
        const origin = display && display === away ? 'away' : display && display === home ? 'home' : null;
        if (origin === null) return [];
        heartIdRef.current += 1;
        return [createHeart(heartIdRef.current, origin)];
      });
      if (spawned.length === 0) return;

      setHearts((prev) => [...prev, ...spawned].slice(-MAX_FLOATING_HEARTS));

      const awayCount = spawned.some((heart) => heart.origin === 'away') ? 1 : 0;
      const homeCount = spawned.some((heart) => heart.origin === 'home') ? 1 : 0;
      setBeats((prev) => ({ away: prev.away + awayCount, home: prev.home + homeCount }));
    },
    [awayTeam, homeTeam],
  );

  const removeHeart = useCallback((id: number) => {
    setHearts((prev) => prev.filter((heart) => heart.id !== id));
  }, []);

  /* 경기가 바뀌면 이전 경기의 하트가 남지 않게 비운다. */
  useEffect(() => {
    setHearts([]);
    setBeats({ away: 0, home: 0 });
  }, [gameId]);

  if (!gameId || date === null) return <Navigate to={ROUTES.game} replace />;

  const goBack = () => navigate(-1);

  const topbar = (
    <header className="game-detail__topbar">
      <button className="game-detail__back" type="button" onClick={goBack} aria-label="뒤로 가기">
        <span className="game-detail__back-icon" aria-hidden="true" />
      </button>
      <h1 className="game-detail__topbar-title">경기 정보</h1>
      {/* 타이틀을 가운데 두기 위한 자리(다른 화면의 상단 바와 같다) */}
      <span className="game-detail__topbar-spacer" aria-hidden="true" />
    </header>
  );

  if (!game) {
    return (
      <div className="game-detail">
        {topbar}
        <p className="game-detail__status">
          {isLoading
            ? '경기 정보를 불러오는 중이에요'
            : loadFailed
              ? '경기 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요'
              : '경기 정보를 찾을 수 없어요'}
        </p>
      </div>
    );
  }

  // 취소 경기면 칩 문구가 취소 사유로 바뀐다.
  const state = getGameStateDisplay(game);
  const inning = formatInning(game);
  const score = getScoreDisplay(game);

  /*
   * 서버는 홈/원정을 판정하지 않고 `teamId` 오름차순으로만 준다(docs/game.md).
   * 배열 순서를 믿으면 안 되므로 경기의 구단 PK 와 직접 맞춘다.
   */
  const awayLineUp = lineUps?.find((lineUp) => lineUp.teamId === game.awayTeamId);
  const homeLineUp = lineUps?.find((lineUp) => lineUp.teamId === game.homeTeamId);
  const hasLineUp = Boolean(awayLineUp || homeLineUp);

  /** 접힌 상태에서도 펼쳐 볼 만한 게 있는지 한눈에 알리는 오른쪽 보조 문구. */
  const lineUpHint = lineUpError
    ? '불러오기 실패'
    : lineUps === null
      ? '불러오는 중'
      : hasLineUp
        ? null
        : '발표 전';

  /*
   * 퀴즈는 **응원 구단이 뛰는 경기에서만** 제공한다 — 남의 경기에는 CTA 를 그리지 않는다.
   * 판정은 이름이 아니라 PK 로 한다(`GET /teams` 의 `id` 와 같은 값 체계다).
   */
  const supportTeamId = profile?.supportTeam?.id ?? null;
  const isSupportedGame =
    supportTeamId !== null &&
    (supportTeamId === game.homeTeamId || supportTeamId === game.awayTeamId);

  const quizState: QuizPageState = {
    gameId: game.gameId,
    awayTeam: game.awayTeam,
    homeTeam: game.homeTeam,
  };

  return (
    <div className="game-detail">
      {topbar}

      <section className="game-detail__summary" aria-label="경기 요약">
        <div className="game-detail__header">
          <p className="game-detail__place">
            <span className="game-detail__stadium">{game.stadium ?? '구장 미정'}</span>
            <span className="game-detail__time">{formatGameTime(game.gameDate)}</span>
          </p>
          {/* 긴 취소 사유는 칩에서 잘리므로 전체 문구를 title 로 남긴다 */}
          <span className={`game-detail__chip game-detail__chip--${state.tone}`} title={state.label}>
            {state.label}
          </span>
        </div>

        <div className="game-detail__teams">
          <TeamColumn
            name={game.awayTeam}
            hearts={hearts.filter((heart) => heart.origin === 'away')}
            beat={beats.away}
            onHeartDone={removeHeart}
            winRate={game.awayTeamWinRate}
          />
          {/* 이닝은 VS 위에 겹쳐 띄운다(MatchCard 와 같은 이유 — 높이를 흔들지 않는다) */}
          <span className="game-detail__center">
            {inning && <span className="game-detail__inning">{inning}</span>}
            {score && (
              <span className="game-detail__score" aria-label={`원정 ${score.away}점`}>
                {score.away}
              </span>
            )}
            <span className="game-detail__vs">VS</span>
            {score && (
              <span className="game-detail__score" aria-label={`홈 ${score.home}점`}>
                {score.home}
              </span>
            )}
          </span>
          <TeamColumn
            name={game.homeTeam}
            hearts={hearts.filter((heart) => heart.origin === 'home')}
            beat={beats.home}
            onHeartDone={removeHeart}
            winRate={game.homeTeamWinRate}
          />
        </div>

        <div className="game-detail__lineup">
          <button
            className="game-detail__lineup-toggle"
            type="button"
            aria-expanded={isLineUpOpen}
            aria-controls={lineUpPanelId}
            onClick={() => setIsLineUpOpen((open) => !open)}
          >
            <span className="game-detail__lineup-title">선발 라인업</span>
            {lineUpHint && <span className="game-detail__lineup-hint">{lineUpHint}</span>}
            <span className="game-detail__lineup-arrow" aria-hidden="true" />
          </button>

          {isLineUpOpen && (
            <div className="game-detail__lineup-panel" id={lineUpPanelId}>
              {lineUpError && <p className="game-detail__notice">{lineUpError}</p>}

              {!lineUpError && lineUps === null && (
                <p className="game-detail__notice">선수 정보를 불러오는 중이에요</p>
              )}

              {/* 라인업 미공시는 빈 배열(200)로 온다 — 오류가 아니라 정상 응답이다. */}
              {!lineUpError && lineUps !== null && !hasLineUp && (
                <p className="game-detail__notice">선수 정보가 아직 업데이트 되지 않았어요</p>
              )}

              {hasLineUp && (
                <div className="game-detail__lineup-columns">
                  <BatterColumn batters={awayLineUp?.batters ?? []} />
                  <BatterColumn batters={homeLineUp?.batters ?? []} />
                </div>
              )}
            </div>
          )}
        </div>

        {state.cta && isSupportedGame && (
          <button
            className={`game-detail__cta game-detail__cta--${state.cta.tone}`}
            type="button"
            // 진행중이면 문제를 풀러, 종료됐으면 결과를 보러 간다.
            onClick={() =>
              navigate(state.cta?.tone === 'live' ? ROUTES.quiz : ROUTES.quizResult, {
                state: quizState,
              })
            }
          >
            {state.cta.label}
          </button>
        )}
      </section>

      {/*
        경기 채팅방은 경기 당일에만 열린다(서버의 방 = 오늘 경기).
        채팅은 계정 기능이라 비로그인이면 API 를 부르지 않고 로그인 안내를 세운다.
      */}
      {isLoggedIn ? (
        <GameChat gameId={game.gameId} isOpenDay={date === today} onLikes={handleLikes} />
      ) : (
        <GameChatLoginPrompt />
      )}
    </div>
  );
}
