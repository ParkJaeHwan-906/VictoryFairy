import type { Game } from '../api';

/**
 * 경기 상태 → 상태 칩 · 하단 CTA 표시.
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

/**
 * 취소 사유를 칩 문구로 다듬는다 — 서버 원문 `폭염취소` → 표시 `폭염 취소`.
 *
 * 다른 칩 문구가 모두 "경기 취소"·"경기 종료"처럼 띄어 쓰기 때문에, 사유만 붙여 쓰면
 * 같은 자리에 다른 규칙의 문구가 섞인다. **표시용 손질이라 서버 값은 그대로 둔다.**
 *
 * 손대는 건 끝에 붙은 `취소` 하나뿐이다 — 값의 종류가 닫힌 집합이 아니라서
 * 사유 문자열을 더 파싱하면 새 사유가 들어올 때마다 깨진다.
 * 이미 띄어 쓴 값(`폭염 취소`)은 공백을 다시 넣지 않고, 사유가 `취소` 뿐이면 그대로 둔다.
 */
function formatCancelReason(reason: string): string {
  return reason.replace(/^(.+?)\s*취소$/, '$1 취소');
}

/**
 * 취소 경기는 칩에 `label` 대신 **취소 사유**를 싣는다("경기 취소" → "폭염 취소").
 *
 * `gameState` 와 `cancelReason` 을 따로 받지 않고 경기를 통째로 받는 이유는,
 * 사유 fallback 을 `CANCELED` 일 때만 걸어야 해서다 — 인자를 쪼개면 호출부가 사유를 빠뜨리거나
 * 다른 상태에 잘못 얹을 수 있다(그러면 정상 경기 칩에 취소 문구가 뜬다).
 *
 * 빈 문자열·공백만 들어온 사유는 없는 것으로 본다 — 칩이 빈 회색 알약으로 보이기 때문이다.
 */
export function getGameStateDisplay(
  game: Pick<Game, 'gameState' | 'cancelReason'>,
): GameStateDisplay {
  const display = DISPLAY[game.gameState] ?? {
    label: game.gameState,
    tone: 'canceled' as const,
    cta: null,
  };

  if (game.gameState !== 'CANCELED') return display;

  const reason = game.cancelReason?.trim();
  return reason ? { ...display, label: formatCancelReason(reason) } : display;
}
