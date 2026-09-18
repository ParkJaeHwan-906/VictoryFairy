/**
 * 인스타그램 스토리 공유 — **그림은 웹이 그리고, 넘기는 것은 앱이 한다**.
 *
 * 스토리에 "사용자가 끌어서 옮길 수 있는 스티커"를 얹는 것은 앱과 앱 사이의 약속
 * (안드로이드 인텐트 · iOS URL 스킴)이라 브라우저에서는 흉내 낼 수 없다.
 * `navigator.share` 로는 OS 공유 시트까지가 끝이고 스티커 겹을 지정할 수 없다.
 * 그래서 스티커 이미지는 여기서 만들고(`stickerCanvas.ts`), 인스타로 건네는 일만
 * 앱(`VictoryFairy_APP/src/share/`)에 맡긴다 — 알림과 같은 분업이다.
 *
 * ⚠️ 표식·전역 이름은 앱의 `src/share/bridge.ts` 가 그대로 들고 있다.
 * **한쪽만 바꾸면 앱은 이 메시지를 알아보지 못하고 조용히 흘려보낸다.**
 *
 * 브라우저에서 이 함수들을 부르는 것은 아무 데도 닿지 않는다 — `ReactNativeWebView`
 * 전역이 없기 때문이다. 화면은 `isInApp()` 으로 미리 갈라 안내문을 띄운다.
 */
import { isInApp } from './pushNotification';

export { isInApp };

/** 웹이 앱으로 보내는 메시지에 붙이는 표식. 앱이 이 값으로 다른 메시지와 가른다. */
const MESSAGE_SOURCE = 'victoryfairy-app/instagram-share';

/** 앱이 결과를 돌려줄 때 부르는 전역 함수의 이름. 앱의 `bridge.ts` 와 짝이다. */
const RESULT_CALLBACK = '__victoryFairyInstagramShare';

/** 이 기기에서 공유가 가능한지 앱이 문서 로드마다 놓아 두는 값. */
const AVAILABILITY_FLAG = '__victoryFairyInstagramShareAvailable';

/**
 * 공유 시도의 결말.
 *
 * `opened` 는 **"올렸다"가 아니라 "넘겼다"** 다 — 인스타 편집기가 열린 뒤 사용자가
 * 실제로 게시했는지, 취소했는지는 인스타가 알려주지 않는다. 그래서 화면도 성공을
 * 축하하지 않고 조용히 제자리에 머문다.
 */
export type InstagramShareOutcome =
  /** 인스타 스토리 편집기가 열렸다. */
  | 'opened'
  /** 기기에 인스타그램이 없다. */
  | 'instagram-missing'
  /** 앱 빌드에 Meta 앱 ID 가 들어가지 않았다 — 사용자가 할 수 있는 일이 없다. */
  | 'not-configured'
  /** 이미지가 깨졌거나 인스타를 띄우지 못했다. */
  | 'failed';

/** 앱이 스티커와 함께 받는 것. 색은 스티커 뒤에 깔릴 배경 그라데이션이다. */
export interface InstagramSharePayload {
  /** 스티커 PNG. `data:image/png;base64,` 접두사가 붙어 있어도 앱이 떼어 낸다. */
  stickerBase64: string;
  /** `#RRGGBB`. 형태가 어긋나면 앱이 버리고 인스타 기본 배경으로 보낸다. */
  backgroundTopColor?: string;
  backgroundBottomColor?: string;
}

interface AppWindow extends Window {
  ReactNativeWebView?: { postMessage: (message: string) => void };
  [RESULT_CALLBACK]?: (outcome: string) => void;
  [AVAILABILITY_FLAG]?: boolean;
}

function appWindow(): AppWindow {
  return window as AppWindow;
}

/**
 * 이 기기에서 공유 버튼을 눌러도 되는지 — 사실상 인스타그램 설치 여부다.
 *
 * 앱이 문서를 띄울 때마다 이 값을 다시 놓으므로, 그 사이 인스타를 설치하거나 지운
 * 경우도 다음 로드에 따라온다. 앱이 값을 놓기 전(또는 브라우저)에는 `undefined` 라
 * **모른다 = 불가능**으로 본다 — 눌러도 아무 일이 없는 버튼을 그리는 것보다 낫다.
 */
export function isInstagramShareAvailable(): boolean {
  return appWindow()[AVAILABILITY_FLAG] === true;
}

/**
 * 스티커를 얹은 채 인스타 스토리 편집기를 열어 달라고 앱에 부탁한다.
 *
 * 결과는 이 호출이 돌려주지 않는다 — 앱이 인스타로 넘어갔다 오는 사이에 화면이
 * 남아 있을지 알 수 없어서, 답은 `subscribeInstagramShareResult()` 로 따로 받는다.
 */
export function requestInstagramShare(payload: InstagramSharePayload): void {
  appWindow().ReactNativeWebView?.postMessage(
    JSON.stringify({ source: MESSAGE_SOURCE, ...payload }),
  );
}

/**
 * 앱이 알려주는 공유 결과를 받는다. 돌려받은 함수를 부르면 구독이 끊긴다.
 *
 * 앱은 전역 함수를 직접 호출하는 방식으로 값을 밀어 넣으므로(`injectJavaScript`),
 * 이 함수가 그 전역을 만들고 치우는 일까지 맡는다 — 알림 권한을 받는 방식과 같다.
 * 공유 화면을 떠난 뒤에는 전역이 없고, 앱은 그때 조용히 넘어간다.
 */
export function subscribeInstagramShareResult(
  listener: (outcome: InstagramShareOutcome) => void,
): () => void {
  const target = appWindow();

  target[RESULT_CALLBACK] = (outcome: string) => {
    if (
      outcome === 'opened' ||
      outcome === 'instagram-missing' ||
      outcome === 'not-configured' ||
      outcome === 'failed'
    ) {
      listener(outcome);
    }
  };

  return () => {
    delete target[RESULT_CALLBACK];
  };
}
