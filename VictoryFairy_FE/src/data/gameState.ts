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

/** 이닝 초/말 → 표시 접미사. `DISPLAY` 와 같은 이유로 Record<string, …> 라 미지의 값도 안전하다. */
const INNING_HALF_LABEL: Record<string, string> = {
  TOP: '초',
  BOTTOM: '말',
};

/**
 * 진행 중 경기의 이닝 문구를 만든다 — `inning: 5` · `inningHalf: 'TOP'` → `5회초`.
 *
 * **표시 형태는 서버가 정하지 않는다**(docs/game.md) — 두 값을 합치는 건 이 계층의 몫이다.
 *
 * `getGameStateDisplay` 와 같이 경기를 통째로 받아 `IN_PROGRESS` 일 때만 문구를 낸다.
 * 다른 상태에서는 서버도 두 값을 채우지 않지만, 상태를 여기서 한 번 더 막아 두면
 * 호출부가 끝난 경기에 이닝을 얹는 실수를 할 수 없다(`cancelReason` fallback 과 같은 논리다).
 *
 * ⚠️ **지금은 진행 중 경기라도 거의 항상 `null` 이 돌아온다** — 컬럼만 생겼고 값을 채우는
 * py-collector 구현이 아직 없다(docs/game.md). 값이 없는 쪽이 당분간 정상 경로이므로,
 * 호출부는 `null` 을 오류가 아니라 "표시할 것이 없다"로 조용히 다뤄야 한다.
 *
 * 한쪽만 온 수집 중간 상태도 다룬다 — 번호만 있으면 `5회` 로 줄이고, 초/말만 있고 번호가
 * 없으면 그것만으로는 뜻이 서지 않으므로 `null` 이다.
 */
export function formatInning(
  game: Pick<Game, 'gameState' | 'inning' | 'inningHalf'>,
): string | null {
  if (game.gameState !== 'IN_PROGRESS') return null;

  const { inning, inningHalf } = game;
  // 정상값은 1~11 이다(DB CHECK). 그래도 0·소수 같은 값이 오면 그리지 않는다 —
  // 칩과 달리 이 문구는 숫자가 그대로 노출돼 이상한 값이 곧바로 보이기 때문이다.
  if (inning === null || !Number.isInteger(inning) || inning < 1) return null;

  const half = inningHalf ? (INNING_HALF_LABEL[inningHalf] ?? '') : '';
  return `${inning}회${half}`;
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
