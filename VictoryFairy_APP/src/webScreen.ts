/**
 * 앱 백 버튼이 웹 화면을 어떻게 다룰지 정하는 데 필요한 것들.
 *
 * ── 규칙 ──────────────────────────────────────────────────────────────
 * 1. 바텀시트가 열려 있으면 시트만 닫는다.
 * 2. NavBar가 있는 화면(탭의 뿌리)이면 종료 확인으로 간다.
 * 3. 그 밖에는 이전 화면으로 돌아간다.
 *
 * 2번이 핵심이다. 웹의 NavBar는 탭을 옮길 때마다 히스토리를 쌓아서, 되감으면
 * 사용자가 기억하지 못하는 순서로 탭 사이를 오간다("홈 → 경기 → 라운지 → 홈 → …").
 * 안드로이드 앱에서 탭은 각자 뿌리이고 뒤로가기는 앱을 닫는 쪽에 가깝다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 되감기 자체는 `webViewRef.goBack()`이 아니라 페이지 안에서 한다. 웹은 SPA라
 * 화면 이동이 `pushState`로만 일어나는데, WebView가 세는 히스토리는 그 이동을
 * 문서 단위로 뭉뚱그려 몇 단계를 지나왔든 첫 문서로 돌아가 버린다.
 */

/** 웹이 알려주는 지금 화면. 앱은 이 값만 보고 백 버튼을 판단한다. */
export interface WebScreen {
  /** `window.location.pathname`. 아직 한 번도 못 받았으면 `null`. */
  path: string | null;
  /**
   * react-router가 `history.state`에 넣는 `idx` — 이 히스토리 세션에서 몇 번째인지.
   * `0`이면 돌아갈 곳이 없다. react-router 밖 문서(소셜 로그인 등)에서는 `null`이다.
   */
  historyIndex: number | null;
  /** 바텀시트가 화면에 떠 있는지. */
  isSheetOpen: boolean;
}

/** 아직 웹에서 아무 소식도 못 받은 상태. */
export const UNKNOWN_SCREEN: WebScreen = {
  path: null,
  historyIndex: null,
  isSheetOpen: false,
};

/** 웹이 보낸 다른 메시지와 섞이지 않도록 붙이는 표식. */
const MESSAGE_SOURCE = 'victoryfairy-app/screen';

/**
 * 하단 NavBar가 붙는 화면들 — 탭의 뿌리다.
 *
 * `VictoryFairy_FE`의 `AppLayout` 안에 놓인 라우트와 같다(`src/routes.ts`의
 * `main`·`game`·`community`·`my`). FE에서 탭을 늘리면 여기도 같이 늘려야 한다.
 * 클래스 이름(`.nav-bar`)을 찾는 대신 경로로 판단하는 이유는, 스타일은 개편돼도
 * 경로는 화면의 정체라 잘 바뀌지 않기 때문이다.
 */
const TAB_ROOT_PATHS = ['/main', '/game', '/community', '/my'];

/** 지금 화면이 탭의 뿌리인지. 경로를 아직 모르면 아니라고 본다(뒤로가기를 살려 둔다). */
export function isTabRoot(path: string | null): boolean {
  if (path === null) {
    return false;
  }
  // 후행 슬래시(`/main/`)까지 같은 화면으로 본다.
  const normalized = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return TAB_ROOT_PATHS.includes(normalized);
}

/**
 * 페이지가 로드될 때마다 주입해 두는 화면 보고기(`injectedJavaScript`).
 *
 * 값을 물어보는 대신 **바뀔 때마다 웹이 먼저 알려주는** 쪽으로 만든다. 백 버튼은
 * 눌린 그 자리에서 답을 내야 해서, 그때 물어보면 왕복을 기다리는 사이에 이미 늦다.
 *
 * 지켜볼 것이 둘이다. 화면 이동은 `pushState`·`replaceState`에 이벤트가 없어
 * (`popstate`는 뒤로 갈 때만 난다) 두 함수를 감싸고 — 원본을 그대로 호출하므로 웹의
 * 동작은 달라지지 않는다 — 시트는 라우팅 없이 열리고 닫혀서 DOM을 지켜본다.
 *
 * 앱 코드가 아니라 페이지에서 도는 코드라 ES5 문법으로 쓴다.
 * 마지막 `true;`는 iOS에서 반환값 경고가 뜨지 않게 하는 관용구다.
 */
export const OBSERVE_WEB_SCREEN_SCRIPT = `
(function () {
  var SOURCE = ${JSON.stringify(MESSAGE_SOURCE)};
  var lastPayload = null;
  var isScheduled = false;

  function report() {
    if (!window.ReactNativeWebView) {
      return;
    }
    var state = window.history.state;
    var payload = JSON.stringify({
      source: SOURCE,
      path: window.location.pathname,
      historyIndex: state && typeof state.idx === 'number' ? state.idx : null,
      isSheetOpen: !!window.__victoryFairyOpenSheet()
    });
    // DOM은 시트와 무관한 일로도 계속 흔들린다. 값이 그대로면 보내지 않는다.
    if (payload === lastPayload) {
      return;
    }
    lastPayload = payload;
    window.ReactNativeWebView.postMessage(payload);
  }

  function scheduleReport() {
    if (isScheduled) {
      return;
    }
    isScheduled = true;
    window.requestAnimationFrame(function () {
      isScheduled = false;
      report();
    });
  }

  if (!window.__victoryFairyScreenObserver) {
    window.__victoryFairyScreenObserver = true;

    // 시트는 클래스가 아니라 역할로 찾는다. 웹의 시트가 모두 role="dialog" +
    // aria-modal="true" 로 열리고, 클래스 규칙은 개편된 적이 있어 덜 믿을 만하다.
    window.__victoryFairyOpenSheet = function () {
      var dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
      for (var i = dialogs.length - 1; i >= 0; i--) {
        // 화면에 실제로 그려진 것만 — 닫힌 시트를 DOM에 남겨 두는 구현도 견딘다.
        if (dialogs[i].getClientRects().length > 0) {
          return dialogs[i];
        }
      }
      return null;
    };

    ['pushState', 'replaceState'].forEach(function (name) {
      var original = window.history[name];
      window.history[name] = function () {
        var result = original.apply(this, arguments);
        report();
        return result;
      };
    });
    window.addEventListener('popstate', report);

    if (document.body) {
      new MutationObserver(scheduleReport).observe(document.body, {
        childList: true,
        subtree: true
      });
    }
  }

  report();
})();
true;
`;

/** 백 버튼이 이전 화면으로 돌아갈 때 주입한다. */
export const GO_BACK_SCRIPT = 'window.history.back(); true;';

/**
 * 백 버튼이 시트를 닫을 때 주입한다.
 *
 * 시트를 덮는 딤이 곧 닫기 버튼이라(웹의 시트가 모두 같은 구조다) 그것을 대신 누른다.
 * 시트의 상태는 웹이 들고 있어서 밖에서 닫을 다른 방법이 없다.
 */
export const CLOSE_SHEET_SCRIPT = `
(function () {
  var sheet = window.__victoryFairyOpenSheet && window.__victoryFairyOpenSheet();
  if (!sheet || !sheet.parentElement) {
    return;
  }
  var dim = sheet.parentElement.querySelector('button[class*="dim"]');
  if (dim) {
    dim.click();
  }
})();
true;
`;

/**
 * `onMessage`로 올라온 문자열이 화면 보고인지 보고, 맞으면 그 내용을 돌려준다.
 *
 * 표식이 없거나 형태가 어긋나면 `null` — 웹이 다른 용도로 보낸 메시지다.
 */
export function parseWebScreen(raw: string): WebScreen | null {
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

  return {
    path: typeof message.path === 'string' ? message.path : null,
    historyIndex: typeof message.historyIndex === 'number' ? message.historyIndex : null,
    isSheetOpen: message.isSheetOpen === true,
  };
}
