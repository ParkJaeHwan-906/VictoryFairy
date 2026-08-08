import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Player } from '../api';
import PlayerSearchSheet from '../components/PlayerSearchSheet';
import '../styles/PlayerSelectPage.css';

/**
 * PlayerSelectPage — 온보딩 "선호 선수 선택" 화면.
 * Figma: SWM / [Onboarding] 선호 선수 선택-기본 (node 296:1566)
 *              [Onboarding] 선호 선수 선택-검색x (node 435:3694 — 바텀시트)
 *              [Onboarding] 선호 선수 선택-최대 (node 929:8021 — 4명 선택 상태)
 *
 * 세 노드는 별개 화면이 아니라 같은 화면의 상태다 — 검색 필드를 누르면 시트가 뜨고,
 * 시트에서 고른 선수가 필드 아래에 쌓이며, 한 명이라도 있으면 하단 CTA 가 활성화된다.
 */

/** 디자인 문구("최대 4명까지 선택할 수 있어요")와 실제 제한을 한 값으로 묶는다. */
const MAX_SELECTED = 4;

/*
 * TODO: 회원가입 플로우 변경 시 앞 단계(구단 선택)에서 응원 구단이 넘어온다.
 *       그때 아래 두 값을 그 출처(라우터 state · 온보딩 스토어 등)로 갈아끼우면
 *       나머지 코드는 그대로 동작한다. 지금은 값이 없는 쪽으로만 열어 둔다.
 *
 * - teamId 가 null 이면 `GET /players` 를 구단 조건 없이 호출해 전 구단 선수가 내려온다.
 * - teamName 이 null 이면 안내 문구에서 구단 이름만 빠진다.
 */
const SELECTED_TEAM_ID: number | null = null;
const SELECTED_TEAM_NAME: string | null = null;

export default function PlayerSelectPage() {
  const navigate = useNavigate();

  const [selected, setSelected] = useState<Player[]>([]);
  const [isSheetOpen, setIsSheetOpen] = useState(false);

  /** 시트에서 행을 누를 때. 이미 고른 선수면 해제, 아니면 정원 안에서 추가한다. */
  const handleToggle = (player: Player) => {
    setSelected((prev) => {
      if (prev.some((item) => item.playerId === player.playerId)) {
        return prev.filter((item) => item.playerId !== player.playerId);
      }
      if (prev.length >= MAX_SELECTED) return prev;
      return [...prev, player];
    });
  };

  const handleRemove = (playerId: number) => {
    setSelected((prev) => prev.filter((item) => item.playerId !== playerId));
  };

  const handleSubmit = () => {
    if (selected.length === 0) return;
    // TODO: api-agent - 응원 선수 저장(playerIds) 엔드포인트가 아직 api 계층에 없다.
    //       계약 확인 후 붙이고, 성공하면 다음 온보딩 단계로 넘긴다.
  };

  const guide = SELECTED_TEAM_NAME
    ? `${SELECTED_TEAM_NAME} 소속 선수 최대 ${MAX_SELECTED}명까지 선택할 수 있어요`
    : `최대 ${MAX_SELECTED}명까지 선택할 수 있어요`;

  return (
    <div className="player-select-page">
      <header className="player-select-page__topbar">
        <button
          className="player-select-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="player-select-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="player-select-page__topbar-title">선수 선택</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="player-select-page__topbar-spacer" aria-hidden="true" />
      </header>

      <p className="player-select-page__heading">
        가장 응원하는 선수를
        <br />
        선택해주세요
      </p>

      <p className="player-select-page__guide">{guide}</p>

      <div className="player-select-page__field">
        <p className="player-select-page__field-label" id="player-select-field-label">
          선수 검색
        </p>

        {/*
          디자인의 입력 필드는 여기서 직접 입력받지 않는다 — 필드 전체가 시트를 여는
          버튼이고, 실제 입력·검색은 시트 안에서 한다. 그래서 input 이 아니라 button 이다.
        */}
        <button
          className="player-select-page__trigger"
          type="button"
          onClick={() => setIsSheetOpen(true)}
          aria-describedby="player-select-field-label"
        >
          <span className="player-select-page__trigger-text">선수 이름을 검색해보세요</span>
          <span className="player-select-page__trigger-icon" aria-hidden="true" />
        </button>
      </div>

      <div className="player-select-page__body">
        {selected.length > 0 && (
          <ul className="player-select-page__selected">
            {selected.map((player) => (
              <li className="player-select-page__selected-item" key={player.playerId}>
                <span className="player-select-page__selected-name">{player.playerName}</span>
                <button
                  className="player-select-page__remove"
                  type="button"
                  onClick={() => handleRemove(player.playerId)}
                  aria-label={`${player.playerName} 선택 해제`}
                >
                  <span className="player-select-page__remove-icon" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <button
        className="player-select-page__submit"
        type="button"
        onClick={handleSubmit}
        disabled={selected.length === 0}
      >
        다음으로
      </button>

      {isSheetOpen && (
        <PlayerSearchSheet
          teamId={SELECTED_TEAM_ID}
          selected={selected}
          maxCount={MAX_SELECTED}
          onToggle={handleToggle}
          onClose={() => setIsSheetOpen(false)}
        />
      )}
    </div>
  );
}
