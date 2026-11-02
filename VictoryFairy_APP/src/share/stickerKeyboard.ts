import StickerKeyboard from '../../modules/sticker-keyboard';
import type { StickerKeyboardSource, StickerKeyboardStatus } from '../../modules/sticker-keyboard';

/**
 * 승요 스티커 키보드를 앱 쪽 사정까지 포함해 감싼 층.
 *
 * 네이티브 모듈은 안드로이드에만 있고(`modules/sticker-keyboard`), 없는 기기에서는
 * 모든 함수가 "키보드 없음"으로 조용히 답한다. 부르는 쪽(`useStickerKeyboard`)이
 * 플랫폼을 따로 가르지 않아도 되게 하려는 경계다.
 */

/**
 * 'SNS에 올리기'의 결말. `opened` 는 "올렸다"가 아니라 "인스타로 넘어갔다"다 —
 * 스티커를 실제로 넣는 것은 사용자가 인스타 안에서 키보드로 한다.
 */
export type KeyboardShareOutcome =
  | 'opened'
  | 'instagram-missing'
  /** 그림을 받지 못했다. 키보드에는 이전 목록이 남아 있다. */
  | 'failed';

/** 웹에 돌려주는 값. 상태에 '키보드가 있는 앱인가'와 (올리기였다면) 그 결말을 더한다. */
export interface KeyboardReport extends StickerKeyboardStatus {
  available: boolean;
  shareOutcome?: KeyboardShareOutcome;
}

const UNAVAILABLE_STATUS: StickerKeyboardStatus = { enabled: false, selected: false, stickerCount: 0 };

export async function getKeyboardReportAsync(): Promise<KeyboardReport> {
  if (!StickerKeyboard) {
    return { ...UNAVAILABLE_STATUS, available: false };
  }

  try {
    return { ...(await StickerKeyboard.getStatusAsync()), available: true };
  } catch {
    return { ...UNAVAILABLE_STATUS, available: true };
  }
}

/**
 * 담은 스티커를 키보드에 채우고 인스타그램을 연다.
 *
 * 순서가 중요하다 — 그림을 다 받은 **뒤에** 인스타를 연다. 먼저 열면 사용자가 키보드를
 * 펼쳤을 때 아직 이전 목록이 떠 있을 수 있다. 받지 못했으면 인스타를 열지 않는다.
 */
export async function shareWithKeyboardAsync(
  stickers: StickerKeyboardSource[],
): Promise<KeyboardShareOutcome> {
  if (!StickerKeyboard) {
    return 'failed';
  }

  try {
    await StickerKeyboard.syncStickersAsync(stickers);
  } catch (error) {
    console.warn('[sticker-keyboard] 스티커를 받아 두지 못했어요.', error);
    return 'failed';
  }

  try {
    return await StickerKeyboard.openInstagramAsync();
  } catch (error) {
    console.warn('[sticker-keyboard] 인스타그램을 열지 못했어요.', error);
    return 'failed';
  }
}

export async function openStickerKeyboardSettingsAsync(): Promise<void> {
  if (!StickerKeyboard) {
    return;
  }

  try {
    await StickerKeyboard.openSettingsAsync();
  } catch (error) {
    console.warn('[sticker-keyboard] 키보드 설정을 열지 못했어요.', error);
  }
}
