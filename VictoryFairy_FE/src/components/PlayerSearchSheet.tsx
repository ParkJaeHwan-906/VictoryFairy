import { useEffect, useState } from 'react';
import { getPlayerList } from '../api';
import type { Player } from '../api';
import { getPlayerPositionLabel } from '../data/playerPositions';
import '../styles/PlayerSearchSheet.css';

/** 타이핑이 멎고 이만큼 지나면 검색한다. 페이징이 없어 호출 1회가 곧 전체 목록이다. */
const SEARCH_DEBOUNCE_MS = 300;

type PlayerSearchSheetProps = {
  /**
   * 적용할 구단 PK. `null` 이면 구단으로 거르지 않고 전 구단 선수를 받는다.
   * TODO: 회원가입 플로우가 바뀌면 앞 단계(구단 선택)에서 실제 값이 내려온다.
   */
  teamId: number | null;
  /** 지금까지 고른 선수. 목록에서 선택 표시를 그리는 데 쓴다. */
  selected: Player[];
  /** 고를 수 있는 최대 인원. 다 차면 고르지 않은 행이 잠긴다. */
  maxCount: number;
  /** 행을 누를 때. 이미 고른 선수면 해제, 아니면 추가한다(판단은 페이지가 한다). */
  onToggle: (player: Player) => void;
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/**
 * PlayerSearchSheet — 선호 선수 검색·선택 바텀시트.
 * Figma: SWM / [Onboarding] 선호 선수 선택-검색x (node 435:3694)
 *
 * 디자인에는 완료 버튼이 없다 — 여러 명을 이어서 고르는 화면이라 선택해도 닫지 않고,
 * 핸들·딤·Esc 로만 닫는다. 고른 선수는 시트 뒤 페이지에 쌓인다.
 */
export default function PlayerSearchSheet({
  teamId,
  selected,
  maxCount,
  onToggle,
  onClose,
}: PlayerSearchSheetProps) {
  const [query, setQuery] = useState('');
  /** 실제로 서버에 보내는 검색어. `query` 를 디바운스한 값이다. */
  const [term, setTerm] = useState('');

  const [players, setPlayers] = useState<Player[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);

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

  /* 타이핑이 멎으면 검색어를 확정한다. 검색 버튼은 이 대기를 건너뛴다. */
  useEffect(() => {
    const timer = setTimeout(() => setTerm(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    // 검색어를 바꾼 뒤 늦게 도착한 이전 응답으로 목록을 덮어쓰지 않도록 막는다.
    let alive = true;

    setPlayers(null);
    setLoadFailed(false);

    getPlayerList({ teamId: teamId ?? undefined, name: term })
      .then((list) => {
        if (alive) setPlayers(list);
      })
      .catch(() => {
        if (alive) setLoadFailed(true);
      });

    return () => {
      alive = false;
    };
  }, [teamId, term]);

  const selectedIds = new Set(selected.map((player) => player.playerId));
  const isFull = selected.length >= maxCount;
  const isSearching = term.length > 0;

  return (
    <div className="player-sheet">
      {/* 딤. 클릭하면 닫히지만 읽어 줄 내용은 없다. */}
      <button
        className="player-sheet__dim"
        type="button"
        onClick={onClose}
        aria-label="선수 검색 닫기"
      />

      <section
        className="player-sheet__sheet"
        role="dialog"
        aria-modal="true"
        aria-label="선수 검색"
      >
        <button className="player-sheet__handle" type="button" onClick={onClose}>
          <span className="player-sheet__handle-bar" aria-hidden="true" />
          <span className="player-sheet__sr-only">선수 검색 닫기</span>
        </button>

        {/*
          엔터·검색 버튼은 디바운스를 건너뛰고 바로 조회한다.
          입력 자체로도 검색되므로 버튼이 없어도 동작은 같다.
        */}
        <form
          className="player-sheet__search"
          onSubmit={(event) => {
            event.preventDefault();
            setTerm(query.trim());
          }}
        >
          <label className="player-sheet__sr-only" htmlFor="player-search-input">
            선수 이름 검색
          </label>
          <input
            className="player-sheet__input"
            id="player-search-input"
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="선수 이름을 검색해보세요"
            autoComplete="off"
            // 시트는 검색하려고 여는 화면이라 열자마자 입력에 커서를 둔다.
            autoFocus
          />
          <button className="player-sheet__search-button" type="submit" aria-label="검색">
            <span className="player-sheet__search-icon" aria-hidden="true" />
          </button>
        </form>

        <p className="player-sheet__list-title">{isSearching ? '검색 결과' : '전체 선수'}</p>

        <div className="player-sheet__body">
          {players === null && !loadFailed && (
            <p className="player-sheet__notice">선수 목록을 불러오는 중이에요</p>
          )}

          {loadFailed && (
            <p className="player-sheet__notice player-sheet__notice--error">
              선수 목록을 불러오지 못했어요
            </p>
          )}

          {/* 검색 결과 없음·해당 구단 선수 없음은 오류가 아니라 빈 배열(200)이다. */}
          {players !== null && players.length === 0 && (
            <p className="player-sheet__notice">
              {isSearching ? '검색 결과가 없어요' : '선수 정보가 아직 없어요'}
            </p>
          )}

          {players !== null && players.length > 0 && (
            <ul className="player-sheet__list">
              {players.map((player) => {
                const isSelected = selectedIds.has(player.playerId);
                // 다 찼으면 새로 고를 수는 없지만, 이미 고른 행은 해제할 수 있어야 한다.
                const isBlocked = isFull && !isSelected;
                const position = getPlayerPositionLabel(player.playerPosition);

                return (
                  <li key={player.playerId}>
                    <button
                      className={[
                        'player-sheet__row',
                        isSelected ? 'player-sheet__row--selected' : '',
                      ]
                        .filter(Boolean)
                        .join(' ')}
                      type="button"
                      onClick={() => onToggle(player)}
                      disabled={isBlocked}
                      aria-pressed={isSelected}
                    >
                      <span className="player-sheet__name">{player.playerName}</span>

                      {/*
                        포지션·등번호는 실측 약 47% 가 둘 다 null 이다(docs/player.md).
                        없으면 자리를 비워 이름만 보이게 둔다.
                      */}
                      <span className="player-sheet__meta">
                        {position && <span>{position}</span>}
                        {position && player.playerNumber && (
                          <span className="player-sheet__dot" aria-hidden="true" />
                        )}
                        {player.playerNumber && <span>#{player.playerNumber}</span>}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}
