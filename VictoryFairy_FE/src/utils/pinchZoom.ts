/**
 * iOS 사파리에서 핀치 확대를 막는다.
 *
 * 확대 차단은 원래 viewport 메타(`index.html`)의 몫이다. 그런데 **iOS 10 부터 모바일
 * 사파리는 `user-scalable=no` 와 `maximum-scale` 을 일부러 무시한다** — 사이트가 확대를
 * 못 막게 해서 시력이 나쁜 사용자를 지키려는 결정이다. 그래서 사파리로 열면 메타만으로는
 * 핀치가 그대로 살아 있다.
 *
 * 앱(`../VictoryFairy_APP` 의 WKWebView)에서는 메타가 그대로 먹으므로 이 코드는 걸어만
 * 두고 아무 일도 하지 않는다. 여기서 실제로 막는 것은 **모바일 사파리로 직접 연 경우**뿐이다.
 *
 * 더블탭 확대는 이쪽이 아니라 `styles/global.css` 의 `touch-action: manipulation` 이 맡는다.
 */

/**
 * WebKit 전용 제스처 이벤트. 표준이 아니라 `lib.dom` 에 타입이 없고, 다른 브라우저에는
 * 아예 오지 않는다 — 그래서 기기를 가려낼 필요 없이 그냥 걸어 두면 된다.
 *
 * 셋을 모두 막는다. `gesturestart` 만 막아도 대개는 확대가 시작되지 않지만, iOS 버전에
 * 따라 이미 시작된 제스처가 `gesturechange` 로 이어지는 경우가 보고돼 있다.
 */
const GESTURE_EVENTS = ['gesturestart', 'gesturechange', 'gestureend'];

/** 앱을 열 때 한 번 부른다(`main.tsx`). 여러 번 불러도 리스너만 겹칠 뿐 동작은 같다. */
export function blockPinchZoom(): void {
  for (const type of GESTURE_EVENTS) {
    document.addEventListener(type, (event) => event.preventDefault(), { passive: false });
  }
}
