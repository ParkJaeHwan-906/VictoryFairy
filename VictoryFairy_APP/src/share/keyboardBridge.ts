import type { StickerKeyboardSource } from '../../modules/sticker-keyboard';
import { WEB_URL } from '../config';
import type { KeyboardReport } from './stickerKeyboard';

/**
 * 웹과 승요 스티커 키보드(안드로이드)를 잇는 다리.
 *
 * ── 무엇이 오가나 ─────────────────────────────────────────────────────
 * 사용자는 웹의 스티커 화면에서 스티커를 담고 'SNS에 올리기'를 누른다. 그때 웹이 **담은
 * 스티커 목록(그림 주소)** 을 보내면, 앱은 그림을 기기에 받아 키보드 목록을 바꾼 뒤
 * 인스타그램을 연다. 사용자는 스토리 글쓰기 칸에서 키보드를 바꿔 하나씩 넣는다.
 *
 * 키보드는 WebView 없이 뜨므로 그때그때 웹에 물어볼 수 없다 — 그래서 앱이 미리 받아 둔다.
 * 그림의 원본은 계속 웹이다(인스타 공유와 같은 분업, `bridge.ts`).
 *
 * 이미지는 base64 가 아니라 주소로 온다. 이미 받은 그림은 다시 받지 않아도 되고,
 * 여러 장을 `postMessage` 문자열로 나르기에는 크다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * ```
 * 웹 → 앱   { source, type: 'share', stickers: [{ id, categoryId, categoryLabel, name, url }] }
 *           { source, type: 'status' }
 *           { source, type: 'open-settings' }
 * 앱 → 웹   window.__victoryFairyStickerKeyboard({ available, enabled, selected, stickerCount, shareOutcome? })
 * ```
 *
 * ⚠️ 표식·전역 이름은 웹의 `VictoryFairy_FE/src/utils/stickerKeyboard.ts` 가 그대로 들고 있다.
 */

const MESSAGE_SOURCE = 'victoryfairy-app/sticker-keyboard';

/** 상태를 돌려줄 때 부르는, 웹이 심어 둔 전역 함수의 이름. */
const STATUS_CALLBACK = '__victoryFairyStickerKeyboard';

/**
 * 한 번에 받는 스티커 수의 상한. 웹의 전체 스티커가 51장이다.
 * 웹이 엉뚱한 목록을 보냈을 때 수백 장을 내려받지 않게 막는 값이다.
 */
const MAX_STICKERS = 100;

/** 이름·아이디에 받아들일 길이. 화면에 짧게 쓰는 값들이다. */
const MAX_TEXT_LENGTH = 100;

export type KeyboardRequest =
  | { type: 'share'; stickers: StickerKeyboardSource[] }
  | { type: 'status' }
  | { type: 'open-settings' };

/**
 * `onMessage`로 올라온 문자열이 키보드 요청인지 보고, 맞으면 그 내용을 돌려준다.
 *
 * 형태가 어긋난 스티커가 하나라도 있으면 목록 전체를 버린다 — 일부만 받아 두면
 * 사용자가 담은 스티커가 키보드에서 말없이 빠진다.
 */
export function parseKeyboardRequest(raw: string): KeyboardRequest | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }

  const message = parsed as Record<string, unknown>;
  if (message.source !== MESSAGE_SOURCE) {
    return null;
  }

  if (message.type === 'status' || message.type === 'open-settings') {
    return { type: message.type };
  }

  if (message.type !== 'share' || !Array.isArray(message.stickers)) {
    return null;
  }

  if (message.stickers.length === 0 || message.stickers.length > MAX_STICKERS) {
    return null;
  }

  const stickers: StickerKeyboardSource[] = [];
  for (const item of message.stickers) {
    const sticker = asSource(item);
    if (!sticker) {
      return null;
    }
    stickers.push(sticker);
  }

  return { type: 'share', stickers };
}

function asSource(value: unknown): StickerKeyboardSource | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }

  const item = value as Record<string, unknown>;
  const id = asText(item.id);
  const categoryId = asText(item.categoryId);
  const categoryLabel = asText(item.categoryLabel);
  const name = asText(item.name);

  if (!id || !categoryId || !categoryLabel || !name || !isOwnAssetUrl(item.url)) {
    return null;
  }

  return { id, categoryId, categoryLabel, name, url: item.url };
}

function asText(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 && value.length <= MAX_TEXT_LENGTH ? value : null;
}

/**
 * 앱이 내려받아도 되는 주소인지 — **WebView 가 띄운 웹과 같은 곳**의 파일만 받는다.
 *
 * 웹이 그린 그림이 웹 서버에 있는 것이 정상이고, 다른 주소가 오면 무언가 잘못된
 * 것이다. 앱이 아무 주소나 받아 기기에 저장하는 통로가 되지 않게 막는다.
 * RN 의 `URL` 은 `origin` 을 다 구현하지 않아 접두사로 비교한다(WEB_URL 은 끝 슬래시가 없다).
 */
function isOwnAssetUrl(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith(`${WEB_URL}/`);
}

/**
 * 상태를 웹에 돌려주는 스크립트. 전역이 없으면(스티커 화면이 아니면) 아무 일도 하지 않는다.
 *
 * 답에 `available` 이 들어 있는 것은, 웹이 이 답 하나로 "키보드가 있는 앱인가"를 알게
 * 하려는 것이다. 키보드가 없는 옛 앱은 아예 답하지 않으므로 웹은 예전 공유 방식을 쓴다.
 */
export function keyboardReportScript(report: KeyboardReport): string {
  return `
(function () {
  if (typeof window.${STATUS_CALLBACK} === 'function') {
    window.${STATUS_CALLBACK}(${JSON.stringify(report)});
  }
})();
true;
`;
}
