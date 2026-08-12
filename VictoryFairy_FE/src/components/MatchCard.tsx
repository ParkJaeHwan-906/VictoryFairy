import type { Game } from '../api';
import { getGameStateDisplay } from '../data/gameState';
import { getTeamDisplay } from '../data/kboTeams';
import { formatGameTime } from '../utils/date';
import '../styles/MatchCard.css';

type MatchCardProps = {
  game: Game;
  /** 카드를 누르면 경기 상세 시트를 연다. */
  onSelect: (game: Game) => void;
};

/**
 * 로고 + 구단명 한 칸. 모르는 구단이면 로고 없이 이름만 남긴다.
 * 카드 전체가 button 이라 바깥을 span 으로 둔다(button 안에는 div 를 넣을 수 없다).
 */
function TeamColumn({ name }: { name: string }) {
  const display = getTeamDisplay(name);

  return (
    <span className="match-card__team">
      <span className="match-card__logo-box">
        {display && <img className="match-card__logo" src={display.logo} alt="" />}
      </span>
      <span className="match-card__team-name">{display?.label ?? name}</span>
    </span>
  );
}

/**
 * MatchCard — 경기 목록의 카드 한 장.
 * Figma: SWM / `Card/Match` ([Game] 경기 메인 node 657:3919 안)
 *
 * 왼쪽이 원정, 오른쪽이 홈이다(디자인의 `NC VS LG` @잠실 기준).
 */
export default function MatchCard({ game, onSelect }: MatchCardProps) {
  // 취소 경기면 칩 문구가 취소 사유로 바뀐다.
  const state = getGameStateDisplay(game);

  return (
    <li className="match-card">
      <button className="match-card__button" type="button" onClick={() => onSelect(game)}>
        <span className="match-card__header">
          <span className="match-card__place">
            {/* 구장 미정이면 stadium 이 null 로 온다 */}
            <span className="match-card__stadium">{game.stadium ?? '구장 미정'}</span>
            <span className="match-card__time">{formatGameTime(game.gameDate)}</span>
          </span>
          {/* 긴 취소 사유는 칩에서 잘리므로 전체 문구를 title 로 남긴다 */}
          <span className={`match-card__chip match-card__chip--${state.tone}`} title={state.label}>
            {state.label}
          </span>
        </span>

        <span className="match-card__teams">
          <TeamColumn name={game.awayTeam} />
          <span className="match-card__vs">VS</span>
          <TeamColumn name={game.homeTeam} />
        </span>
      </button>
    </li>
  );
}
