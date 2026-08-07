import type { GameState } from '../api';

/**
 * `gameState` → 상태 칩 · 하단 CTA 표시.
 *
 * `gameState` 는 닫힌 enum 이 아니므로(docs/game.md) 표에 없는 값도 온다.
 * 모르는 값은 회색 칩에 원문을 그려, 목록에서 카드가 통째로 사라지지 않게 한다.
 */

/** 칩 배경색 계열. CSS 는 `--tone` 접미사 클래스로 받는다. */
export type GameStateTone = 'scheduled' | 'live' | 'done' | 'canceled';

export interface GameStateDisplay {
  label: string;
  tone: GameStateTone;
  /** 상세 시트 하단 CTA. 진행 전·취소 경기에는 버튼이 없다. */
  cta: { label: string; tone: 'live' | 'done' } | null;
}

const DISPLAY: Record<string, GameStateDisplay> = {
  SCHEDULED: { label: '경기 예정', tone: 'scheduled', cta: null },
  IN_PROGRESS: { label: '경기 진행중', tone: 'live', cta: { label: '퀴즈 풀러 가기', tone: 'live' } },
  FINISHED: { label: '경기 종료', tone: 'done', cta: { label: '퀴즈 결과 확인하기', tone: 'done' } },
  // 무승부도 끝난 경기라 종료와 같은 취급이다(디자인에 별도 칩이 없다).
  DRAW: { label: '무승부', tone: 'done', cta: { label: '퀴즈 결과 확인하기', tone: 'done' } },
  CANCELED: { label: '경기 취소', tone: 'canceled', cta: null },
};

export function getGameStateDisplay(gameState: GameState): GameStateDisplay {
  return DISPLAY[gameState] ?? { label: gameState, tone: 'canceled', cta: null };
}
