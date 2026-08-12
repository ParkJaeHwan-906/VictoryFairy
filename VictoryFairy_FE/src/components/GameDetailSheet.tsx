import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
// [DUMMY] UI 확인용. 백엔드가 뜨면 getLineUp 을 되살리고 dummyGames import 를 지운다.
import { isGameNotFound } from '../api';
import { getLineUp } from '../api';
import type { Game, LineUpBatter, TeamLineUp } from '../api';
// import { getDummyLineUp } from '../data/dummyGames';
import { getGameStateDisplay } from '../data/gameState';
import { getTeamDisplay } from '../data/kboTeams';
import { getPositionDisplay } from '../data/positions';
import { ROUTES, type QuizPageState } from '../routes';
import { useMyProfile } from '../stores/useAccountStore';
import { formatGameTime } from '../utils/date';
import '../styles/GameDetailSheet.css';

type GameDetailSheetProps = {
  game: Game;
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/** 로고 + 구단명 한 칸(시트 쪽은 목록보다 로고·글자가 크다). */
function TeamColumn({ name }: { name: string }) {
  const display = getTeamDisplay(name);

  return (
    <div className="game-sheet__team">
      <span className="game-sheet__logo-box">
        {display && <img className="game-sheet__logo" src={display.logo} alt="" />}
      </span>
      <span className="game-sheet__team-name">{display?.label ?? name}</span>
    </div>
  );
}

/** 한 팀의 선발 타순. 라인업이 없으면 빈 칸으로 남겨 두 열의 균형을 유지한다. */
function BatterColumn({ batters }: { batters: LineUpBatter[] }) {
  return (
    <ol className="game-sheet__batters">
      {batters.map((batter) => {
        const position = getPositionDisplay(batter.positionName);

        return (
          <li className="game-sheet__batter" key={`${batter.batOrder}-${batter.name}`}>
            <span className="game-sheet__batter-name">{batter.name}</span>
            {position && (
              <span className="game-sheet__batter-position" title={position.detail}>
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
 * GameDetailSheet — 경기 상세 정보 바텀시트.
 * Figma: SWM / [Game] 경기 상세 정보 (node 597:7982 · 724:20319 · 597:8919 · 597:9230)
 *
 * 네 노드는 별개 화면이 아니라 같은 시트의 상태 차이다 — 라인업 유무와 `gameState`
 * 에 따라 선수 목록/안내문과 하단 CTA 만 갈린다.
 */
export default function GameDetailSheet({ game, onClose }: GameDetailSheetProps) {
  const navigate = useNavigate();
  const [lineUps, setLineUps] = useState<TeamLineUp[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  /* 로그인해야 닿는 화면이라 프로필은 이미 스토어에 있다(다른 화면들과 같은 전제). */
  const profile = useMyProfile();

  // 취소 경기면 칩 문구가 취소 사유로 바뀐다.
  const state = getGameStateDisplay(game);

  /* Esc 로 닫기 — 시트가 떠 있는 동안에만 듣는다. */
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

  useEffect(() => {
    // 다른 경기로 갈아탄 뒤 늦게 도착한 응답으로 상태를 건드리지 않도록 막는다.
    let alive = true;

    setLineUps(null);
    setLoadError(null);

    // [DUMMY] 되돌릴 때: getDummyLineUp → getLineUp
    getLineUp(game.gameId)
      .then((list) => {
        if (alive) setLineUps(list);
      })
      .catch((error: unknown) => {
        if (!alive) return;
        setLoadError(
          isGameNotFound(error)
            ? '경기 정보를 찾을 수 없어요'
            : '선수 정보를 불러오지 못했어요',
        );
      });

    return () => {
      alive = false;
    };
  }, [game.gameId]);

  /*
   * 서버는 홈/원정을 판정하지 않고 `teamId` 오름차순으로만 준다(docs/game.md).
   * 배열 순서를 믿으면 안 되므로 경기의 구단 PK 와 직접 맞춘다.
   */
  const awayLineUp = lineUps?.find((lineUp) => lineUp.teamId === game.awayTeamId);
  const homeLineUp = lineUps?.find((lineUp) => lineUp.teamId === game.homeTeamId);
  // 라인업 미공시는 빈 배열(200)로 온다 — 오류가 아니라 정상 응답이다.
  const hasLineUp = Boolean(awayLineUp || homeLineUp);

  /*
   * 퀴즈는 **응원 구단이 뛰는 경기에서만** 제공한다.
   * 그래서 남의 경기에는 CTA 자체를 그리지 않는다 — 눌러 봐야 풀 문제가 없기 때문이다.
   * 판정은 이름이 아니라 PK 로 한다(`GET /teams` 의 `id` 와 같은 값 체계다).
   */
  const supportTeamId = profile?.supportTeam?.id ?? null;
  const isSupportedGame =
    supportTeamId !== null &&
    (supportTeamId === game.homeTeamId || supportTeamId === game.awayTeamId);

  /**
   * 진행중 경기의 "퀴즈 풀러 가기".
   * 넘기는 값은 조회 조건이 아니라 퀴즈 화면 상단 바에 쓸 표시용 문맥이다
   * (퀴즈 API 에 경기별 필터가 없다 — `QuizPageState` 주석 참고).
   */
  const handleQuizStart = () => {
    const quizState: QuizPageState = {
      gameId: game.gameId,
      awayTeam: game.awayTeam,
      homeTeam: game.homeTeam,
    };

    navigate(ROUTES.quiz, { state: quizState });
  };

  return (
    <div className="game-sheet">
      {/* 딤. 클릭하면 닫히지만 읽어 줄 내용은 없다. */}
      <button
        className="game-sheet__dim"
        type="button"
        onClick={onClose}
        aria-label="경기 정보 닫기"
      />

      <section className="game-sheet__sheet" role="dialog" aria-modal="true" aria-label="경기 정보">
        <button className="game-sheet__handle" type="button" onClick={onClose}>
          <span className="game-sheet__handle-bar" aria-hidden="true" />
          <span className="game-sheet__handle-label">경기 정보 닫기</span>
        </button>

        <div className="game-sheet__body">
          <h2 className="game-sheet__title">경기 정보</h2>

          <div className="game-sheet__header">
            <p className="game-sheet__place">
              <span className="game-sheet__stadium">{game.stadium ?? '구장 미정'}</span>
              <span className="game-sheet__time">{formatGameTime(game.gameDate)}</span>
            </p>
            {/* 긴 취소 사유는 칩에서 잘리므로 전체 문구를 title 로 남긴다 */}
            <span className={`game-sheet__chip game-sheet__chip--${state.tone}`} title={state.label}>
              {state.label}
            </span>
          </div>

          <div className="game-sheet__teams">
            <TeamColumn name={game.awayTeam} />
            <span className="game-sheet__vs">VS</span>
            <TeamColumn name={game.homeTeam} />
          </div>

          {loadError && <p className="game-sheet__notice">{loadError}</p>}

          {!loadError && lineUps === null && (
            <p className="game-sheet__notice">선수 정보를 불러오는 중이에요</p>
          )}

          {!loadError && lineUps !== null && !hasLineUp && (
            <p className="game-sheet__notice">선수 정보가 아직 업데이트 되지 않았어요</p>
          )}

          {hasLineUp && (
            <div className="game-sheet__lineup">
              <BatterColumn batters={awayLineUp?.batters ?? []} />
              <BatterColumn batters={homeLineUp?.batters ?? []} />
            </div>
          )}
        </div>

        {state.cta && isSupportedGame && (
          <button
            className={`game-sheet__cta game-sheet__cta--${state.cta.tone}`}
            type="button"
            // TODO: react-agent - 종료 경기의 "퀴즈 결과 확인하기" 화면이 생기면 연결한다.
            onClick={state.cta.tone === 'live' ? handleQuizStart : undefined}
          >
            {state.cta.label}
          </button>
        )}
      </section>
    </div>
  );
}
