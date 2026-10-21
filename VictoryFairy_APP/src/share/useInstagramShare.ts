import { useCallback, useMemo } from 'react';
import type { RefObject } from 'react';
import type { WebView, WebViewMessageEvent } from 'react-native-webview';

import { parseShareRequest, shareAvailabilityScript, shareResultScript } from './bridge';
import { isInstagramShareAvailableAsync, shareStickerAsync } from './instagram';

/**
 * 인스타 스토리 공유를 웹 화면에 붙이는 배선.
 *
 * 웹이 아는 것(무엇을 그릴지)과 앱만 할 수 있는 것(인스타에 넘기기)을 잇는 자리다.
 * `WebAppView`는 여기서 돌려주는 핸들러를 WebView에 연결하기만 한다.
 *
 * 알림(`useGameReminders`)과 달리 들고 있는 상태가 없다. 공유는 사용자가 누른 그
 * 순간에 시작해 인스타로 넘어가면 끝나는 일이라, 앱이 기억해 둘 것이 생기지 않는다.
 */

interface InstagramSharing {
  /** `WebView.onMessage`에 연결한다. 공유 요청이었으면 `true`. */
  handleWebMessage: (event: WebViewMessageEvent) => boolean;
  /** `WebView.onLoadEnd`에서 호출한다. */
  handleWebLoaded: () => void;
}

export default function useInstagramShare(webViewRef: RefObject<WebView | null>): InstagramSharing {
  const handleWebLoaded = useCallback(() => {
    void (async () => {
      const isAvailable = await isInstagramShareAvailableAsync();
      webViewRef.current?.injectJavaScript(shareAvailabilityScript(isAvailable));
    })();
  }, [webViewRef]);

  const handleWebMessage = useCallback(
    (event: WebViewMessageEvent) => {
      const request = parseShareRequest(event.nativeEvent.data);
      if (!request) {
        return false;
      }

      void (async () => {
        const outcome = await shareStickerAsync(request);
        webViewRef.current?.injectJavaScript(shareResultScript(outcome));
      })();

      return true;
    },
    [webViewRef],
  );

  // 이 객체를 그대로 WebView 핸들러의 의존성으로 쓰므로, 매 렌더 새로 만들면
  // 핸들러가 통째로 갈린다. 핸들러가 바뀔 때만 바뀌게 묶어 둔다.
  return useMemo(
    () => ({ handleWebMessage, handleWebLoaded }),
    [handleWebLoaded, handleWebMessage],
  );
}
