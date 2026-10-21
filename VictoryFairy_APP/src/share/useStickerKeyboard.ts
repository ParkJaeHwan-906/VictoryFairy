import { useCallback, useMemo } from 'react';
import type { RefObject } from 'react';
import type { WebView, WebViewMessageEvent } from 'react-native-webview';

import { keyboardReportScript, parseKeyboardRequest } from './keyboardBridge';
import type { KeyboardRequest } from './keyboardBridge';
import {
  getKeyboardReportAsync,
  openStickerKeyboardSettingsAsync,
  shareWithKeyboardAsync,
} from './stickerKeyboard';
import type { KeyboardShareOutcome } from './stickerKeyboard';

/**
 * 승요 스티커 키보드를 웹 화면에 붙이는 배선. 모양은 `useInstagramShare`와 같다.
 *
 * 키보드를 켰는지는 설정 화면에 다녀와야 바뀌는 값이라, 앱이 따로 지켜보지 않는다.
 * 웹이 화면으로 돌아왔을 때(visibilitychange) 상태를 다시 묻는다.
 */

interface StickerKeyboardWiring {
  /** `WebView.onMessage`에 연결한다. 키보드 요청이었으면 `true`. */
  handleWebMessage: (event: WebViewMessageEvent) => boolean;
}

export default function useStickerKeyboard(webViewRef: RefObject<WebView | null>): StickerKeyboardWiring {
  const respond = useCallback(
    async (request: KeyboardRequest) => {
      if (request.type === 'open-settings') {
        await openStickerKeyboardSettingsAsync();
        // 설정 화면이 앞을 덮었을 뿐 답은 아직 없다. 돌아온 뒤 웹이 다시 묻는다.
        return;
      }

      let shareOutcome: KeyboardShareOutcome | undefined;
      if (request.type === 'share') {
        shareOutcome = await shareWithKeyboardAsync(request.stickers);
      }

      const report = await getKeyboardReportAsync();
      webViewRef.current?.injectJavaScript(keyboardReportScript({ ...report, shareOutcome }));
    },
    [webViewRef],
  );

  const handleWebMessage = useCallback(
    (event: WebViewMessageEvent) => {
      const request = parseKeyboardRequest(event.nativeEvent.data);
      if (!request) {
        return false;
      }

      void respond(request);
      return true;
    },
    [respond],
  );

  return useMemo(() => ({ handleWebMessage }), [handleWebMessage]);
}
