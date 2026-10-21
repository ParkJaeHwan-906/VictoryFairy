/**
 * 승요 스티커 키보드(안드로이드 앱) — **담는 것은 웹이, 넣는 것은 키보드가** 한다.
 *
 * 사용자는 스티커 화면에서 복사하기로 스티커를 담고 'SNS에 올리기'를 누른다. 그러면 앱이
 * 담은 스티커만 키보드에 채우고 인스타그램을 연다. 인스타 스토리 글쓰기 칸에서 키보드를
 * "승요 스티커"로 바꿔 누르면, 스티커가 하나씩 따로 움직이는 스티커로 들어간다.
 *
 * 키보드는 WebView 없이 뜨므로 그 자리에서 웹에 물어볼 수 없다. 그래서 올리기를 누를 때
 * 그림 주소를 앱에 보내고, 앱이 그림을 기기에 받아 둔다. 그림의 원본은 `shareStickers.ts` 다.
 *
 * ⚠️ 표식·전역 이름은 앱의 `src/share/keyboardBridge.ts` 가 그대로 들고 있다.
 * **한쪽만 바꾸면 앱은 이 메시지를 알아보지 못하고 조용히 흘려보낸다.**
 */
import type { ShareSticker } from './shareStickers';

const MESSAGE_SOURCE = 'victoryfairy-app/sticker-keyboard';

/** 앱이 상태를 돌려줄 때 부르는 전역 함수의 이름. */
const STATUS_CALLBACK = '__victoryFairyStickerKeyboard';

/** 키보드에서 담은 스티커가 모이는 탭. 탭이 하나면 키보드는 탭 줄을 숨긴다. */
const SELECTED_CATEGORY = { id: 'selected', label: '담은 스티커' } as const;

/**
 * 'SNS에 올리기'의 결말. `opened` 는 "올렸다"가 아니라 "인스타로 넘어갔다"다 —
 * 스티커를 실제로 넣는 것은 사용자가 인스타 안에서 키보드로 한다.
 */
export type StickerKeyboardShareOutcome = 'opened' | 'instagram-missing' | 'failed';

export interface StickerKeyboardStatus {
  /**
   * 이 앱에 키보드가 있는지. 안드로이드 앱에서만 `true` 다.
   *
   * 키보드가 없는 옛 앱은 아예 답하지 않으므로, 답이 왔다는 것 자체가 새 앱이라는 뜻이다.
   */
  available: boolean;
  /** 설정 → 키보드에서 켜 두었는지. */
  enabled: boolean;
  /** 지금 기본 키보드로 골라 둔 상태인지. */
  selected: boolean;
  /** 키보드에 들어 있는 스티커 수. */
  stickerCount: number;
  /** 올리기에 대한 답이면 그 결말. 상태만 물었을 때는 없다. */
  shareOutcome?: StickerKeyboardShareOutcome;
}

interface AppWindow extends Window {
  ReactNativeWebView?: { postMessage: (message: string) => void };
  [STATUS_CALLBACK]?: (status: unknown) => void;
}

function appWindow(): AppWindow {
  return window as AppWindow;
}

function post(message: Record<string, unknown>): void {
  appWindow().ReactNativeWebView?.postMessage(JSON.stringify({ source: MESSAGE_SOURCE, ...message }));
}

/**
 * 담은 스티커를 키보드에 채우고 인스타그램을 열어 달라고 한다. 답으로 상태와 결말이 온다.
 *
 * 주소는 절대 주소로 바꿔 보낸다 — 앱은 WebView 가 띄운 웹과 같은 곳의 파일만 받는다.
 *
 * ⚠️ Vite 는 4KB 보다 작은 그림을 `data:` 주소로 인라인한다. 그런 스티커가 섞이면 앱이
 * 목록 전체를 거절하므로, 작은 스티커를 추가할 때는 인라인을 꺼야 한다.
 */
export function shareWithStickerKeyboard(stickers: readonly ShareSticker[]): void {
  post({
    type: 'share',
    stickers: stickers.map((sticker) => ({
      id: sticker.id,
      categoryId: SELECTED_CATEGORY.id,
      categoryLabel: SELECTED_CATEGORY.label,
      name: sticker.name,
      url: new URL(sticker.src, window.location.href).href,
    })),
  });
}

/** 지금 상태를 묻는다. 화면을 열 때와 설정 화면에서 돌아왔을 때 부른다. */
export function requestStickerKeyboardStatus(): void {
  post({ type: 'status' });
}

/** 설정 → 키보드 목록 화면을 열어 달라고 한다. 켜는 것은 사용자가 직접 한다. */
export function openStickerKeyboardSettings(): void {
  post({ type: 'open-settings' });
}

/** 앱이 알려주는 상태를 받는다. 돌려받은 함수를 부르면 구독이 끊긴다. */
export function subscribeStickerKeyboardStatus(
  listener: (status: StickerKeyboardStatus) => void,
): () => void {
  const target = appWindow();

  target[STATUS_CALLBACK] = (status: unknown) => {
    if (typeof status !== 'object' || status === null) {
      return;
    }

    const value = status as Record<string, unknown>;
    const outcome = value.shareOutcome;
    listener({
      available: value.available === true,
      enabled: value.enabled === true,
      selected: value.selected === true,
      stickerCount: typeof value.stickerCount === 'number' ? value.stickerCount : 0,
      shareOutcome:
        outcome === 'opened' || outcome === 'instagram-missing' || outcome === 'failed'
          ? outcome
          : undefined,
    });
  };

  return () => {
    delete target[STATUS_CALLBACK];
  };
}
