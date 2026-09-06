/**
 * 푸시 알림 수신 설정 — **값은 웹이 들고, 알림은 앱이 보낸다**.
 *
 * 알림 자체는 웹이 할 수 없는 일이라 앱(`VictoryFairy_APP`)이 기기에 예약한다. 그런데
 * 그 예약을 걸지 말지를 정하는 것은 사용자의 설정이고, 설정 화면은 웹에 있다. 그래서
 * 값은 여기(localStorage)에 두고 앱이 예약을 걸기 직전에 그 값을 읽어 간다 —
 * 앱에 따로 저장해 두면 웹의 토글과 앱의 저장값이 어긋날 자리가 생긴다.
 *
 * ⚠️ 저장 키와 형태(`{ enabled }`)는 앱의 `src/notifications/bridge.ts` 가 주입 스크립트로
 * 그대로 읽는다. **한쪽만 바꾸면 앱은 조용히 기본값(켜짐)으로 되돌아간다.**
 *
 * 웹 브라우저에서 이 값을 바꾸는 것은 아무 데도 닿지 않는다 — 브라우저의 localStorage 는
 * 앱 WebView 의 것과 다른 저장소이고, 애초에 브라우저는 알림을 예약하지 않는다.
 */

/** 앱의 주입 스크립트가 같은 문자열을 들고 있다. 함께 고쳐야 한다. */
const STORAGE_KEY = 'victoryfairy.pushNotification';

/** 앱이 권한 결과를 돌려줄 때 부르는 전역 함수의 이름. 앱의 `bridge.ts` 와 짝이다. */
const PERMISSION_CALLBACK = '__victoryFairyPushPermission';

/** 웹이 앱으로 보내는 메시지에 붙이는 표식. 앱이 이 값으로 다른 메시지와 가른다. */
const MESSAGE_SOURCE = 'victoryfairy-app/push-preference';

/** 저장된 설정. 지금은 스위치 하나뿐이지만, 종류가 늘어도 키를 새로 파지 않으려고 객체로 둔다. */
interface PushPreference {
  enabled: boolean;
}

/**
 * 기기의 OS 알림 권한 상태 — 앱만이 알 수 있다.
 *
 * `denied` 는 "권한이 없다"는 뜻이고, 사용자가 방금 거절했든 예전에 거절해 시스템
 * 대화상자가 더 뜨지 않든 화면이 할 말은 같다(기기 설정에서 켜 달라).
 */
export type PushPermission = 'granted' | 'denied';

/** WebView 가 웹으로 메시지를 밀어 넣을 때 쓰는 전역. 앱 안에서만 있다. */
interface AppWindow extends Window {
  ReactNativeWebView?: { postMessage: (message: string) => void };
  [PERMISSION_CALLBACK]?: (permission: string) => void;
}

function appWindow(): AppWindow {
  return window as AppWindow;
}

/** 지금 이 화면이 앱 WebView 안에서 돌고 있는지. 브라우저면 `false`. */
export function isInApp(): boolean {
  return typeof appWindow().ReactNativeWebView?.postMessage === 'function';
}

/**
 * 저장된 설정. **아직 한 번도 만진 적이 없으면 켜짐이다.**
 *
 * 끄는 쪽을 기본으로 두면, 이미 알림 권한을 허용해 둔 사용자가 이 화면이 생긴 배포
 * 이후로 아무것도 하지 않았는데 알림이 멎는다. 읽기에 실패했을 때도 같은 이유로 켜짐이다.
 */
export function readPushEnabled(): boolean {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === null) return true;

    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return true;

    return (parsed as Partial<PushPreference>).enabled !== false;
  } catch {
    return true;
  }
}

/** 설정을 저장한다. 저장에 실패해도 이번 화면의 토글은 움직인 채로 둔다. */
export function writePushEnabled(enabled: boolean): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ enabled } satisfies PushPreference));
  } catch {
    /* 사파리 비공개 모드 등 — 다음에 열면 기본값으로 보일 뿐이다 */
  }
}

/**
 * 설정이 바뀌었다고 앱에 알린다. 브라우저에서는 아무 일도 하지 않는다.
 *
 * 앱은 이 신호를 받고 곧바로 예약을 다시 맞춘다 — 알리지 않으면 다음에 앱으로 돌아올
 * 때까지 방금 끈 알림이 그대로 남는다.
 *
 * @param requestPermission 켜는 경우에만 `true`. 앱이 OS 권한 대화상자를 띄우거나,
 *   이미 거절당한 상태면 기기 설정 화면으로 보낸다. 끌 때는 물을 것이 없다.
 */
export function notifyAppPushPreference(enabled: boolean, requestPermission: boolean): void {
  appWindow().ReactNativeWebView?.postMessage(
    JSON.stringify({ source: MESSAGE_SOURCE, enabled, requestPermission }),
  );
}

/**
 * 앱이 알려주는 권한 결과를 받는다. 돌려받은 함수를 부르면 구독이 끊긴다.
 *
 * 앱은 전역 함수를 직접 호출하는 방식으로 값을 밀어 넣으므로(`injectJavaScript`),
 * 이 함수가 그 전역을 만들고 치우는 일까지 맡는다. 설정 화면이 떠 있지 않으면
 * 전역이 없고, 앱은 그때 조용히 넘어간다.
 */
export function subscribeAppPushPermission(listener: (permission: PushPermission) => void): () => void {
  const target = appWindow();

  target[PERMISSION_CALLBACK] = (permission: string) => {
    if (permission === 'granted' || permission === 'denied') listener(permission);
  };

  return () => {
    delete target[PERMISSION_CALLBACK];
  };
}
