import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from 'react-native';
import { WebView } from 'react-native-webview';
import type { WebViewMessageEvent, WebViewNavigation } from 'react-native-webview';

import { WEB_URL } from './config';
import PermissionSheet from './notifications/PermissionSheet';
import useGameReminders from './notifications/useGameReminders';
import { COLORS } from './theme';
import {
  CLOSE_SHEET_SCRIPT,
  GO_BACK_SCRIPT,
  isTabRoot,
  OBSERVE_WEB_SCREEN_SCRIPT,
  parseWebScreen,
  UNKNOWN_SCREEN,
} from './webScreen';
import type { WebScreen } from './webScreen';

/**
 * 첫 화면에서 백 버튼을 두 번 눌러야 종료되게 하는 간격.
 *
 * `ToastAndroid.SHORT`가 약 2초라, 안내가 보이는 동안만 두 번째 입력을 받는다.
 * 더 길게 잡으면 종료할 뜻으로 누른 게 아닌 입력까지 종료로 이어진다.
 */
const EXIT_CONFIRM_INTERVAL_MS = 2000;

/**
 * 배포된 React 웹을 감싸는 앱의 본문.
 *
 * 화면·라우팅·상태는 전부 웹이 들고 있고, 앱은 (1) 웹이 뜨기 전 흰 화면을 가리는
 * 로딩 표시, (2) 아예 뜨지 못했을 때의 재시도 경로, 그리고 (3) 웹이 만들 수 없는
 * 경기 알림(`src/notifications`)만 책임진다.
 */
export default function WebAppView() {
  const [isLoading, setIsLoading] = useState(true);
  const [hasError, setHasError] = useState(false);

  const webViewRef = useRef<WebView>(null);
  // 알림 예약은 웹이 아는 것(응원 구단)과 앱만 할 수 있는 것(예약)을 잇는 일이라
  // WebView의 생명주기에 얹혀 있다. 배선만 여기서 하고 판단은 훅 안에서 한다.
  const reminders = useGameReminders(webViewRef);
  // 백 버튼 리스너는 한 번만 등록하고 싶은데, state로 두면 값이 바뀔 때마다 리스너를
  // 떼었다 붙여야 한다. 리스너 안에서 최신 값만 읽으면 되므로 ref로 들고 있는다.
  const canGoBackRef = useRef(false);
  /** 웹이 알려준 지금 화면(경로 · 히스토리 위치 · 시트 열림). 백 버튼이 이 값으로 판단한다. */
  const screenRef = useRef<WebScreen>(UNKNOWN_SCREEN);
  /** 첫 화면에서 백 버튼을 마지막으로 누른 시각. 0이면 아직 누른 적 없다. */
  const lastBackPressAtRef = useRef(0);

  const handleRetry = useCallback(() => {
    // WebView를 언마운트했다가 다시 마운트해 처음부터 로드한다. 로드에 실패한
    // 인스턴스에 reload()를 거는 것보다 결과가 확실하다.
    setHasError(false);
    setIsLoading(true);
  }, []);

  const handleError = useCallback(() => {
    // 오류 화면에서는 WebView가 사라지므로 직전 히스토리 정보는 더 이상 유효하지 않다.
    // 남겨 두면 백 버튼이 없는 WebView에 뒤로 가기를 걸어 아무 반응이 없게 된다.
    canGoBackRef.current = false;
    screenRef.current = UNKNOWN_SCREEN;
    setHasError(true);
  }, []);

  /**
   * 지금 돌아갈 곳이 남았는지.
   *
   * 웹이 알려준 위치가 있으면 그 값만이 정확하다 — WebView가 세는 히스토리는 pushState로
   * 쌓인 화면을 다르게 세기 때문에 웹의 화면 수와 어긋난다. 위치를 모르는 문서
   * (소셜 로그인 등 react-router 밖)에서만 WebView의 셈을 대신 쓴다.
   */
  const canGoBack = useCallback(() => {
    const { historyIndex } = screenRef.current;
    return historyIndex === null ? canGoBackRef.current : historyIndex > 0;
  }, []);

  useEffect(() => {
    // iOS에는 하드웨어 백 버튼이 없다(가장자리 스와이프로 대신한다).
    if (Platform.OS !== 'android') {
      return;
    }

    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      const screen = screenRef.current;

      // 시트는 라우트가 아니라 화면 위에 얹힌 겹이다. 뒤로가기는 그 겹부터 걷어낸다.
      if (screen.isSheetOpen) {
        webViewRef.current?.injectJavaScript(CLOSE_SHEET_SCRIPT);
        return true;
      }

      // NavBar가 있는 화면은 탭의 뿌리라 돌아갈 곳으로 치지 않는다. 웹은 탭을 옮길
      // 때마다 히스토리를 쌓지만, 그걸 거슬러 올라가면 사용자가 기억하지 못하는
      // 순서로 탭 사이를 오간다. 자세한 것은 `webScreen.ts` 참고.
      if (!isTabRoot(screen.path) && canGoBack()) {
        // WebView의 goBack() 대신 페이지 안에서 되감는다. SPA의 화면 이동은 문서 로드가
        // 아니라 pushState라, 문서 단위로 되감으면 몇 단계를 지나왔든 첫 문서로 간다.
        webViewRef.current?.injectJavaScript(GO_BACK_SCRIPT);
        return true;
      }

      // 여기까지 왔으면 앱을 나가는 자리다. 웹 앱은 뒤로가기를 자주 쓰게 되므로 한 번의
      // 오조작으로 앱이 닫히지 않게 두 번 눌러야 종료되도록 한다.
      const now = Date.now();
      if (now - lastBackPressAtRef.current < EXIT_CONFIRM_INTERVAL_MS) {
        // false를 반환해 기본 동작에 맡긴다. exitApp()으로 강제 종료하는 것과 달리
        // 액티비티가 안드로이드의 정상 종료 경로를 타므로 최근 앱 목록에도 제대로 남는다.
        return false;
      }

      lastBackPressAtRef.current = now;
      ToastAndroid.show('한 번 더 누르면 종료돼요', ToastAndroid.SHORT);
      return true;
    });

    return () => subscription.remove();
  }, [canGoBack]);

  const handleNavigationStateChange = useCallback(
    (navState: WebViewNavigation) => {
      canGoBackRef.current = navState.canGoBack;
      reminders.handleWebNavigated();
    },
    [reminders],
  );

  /**
   * 웹에서 올라온 메시지를 나눠 준다.
   *
   * 통로(`onMessage`)는 하나뿐이라 보낸 쪽을 표식으로 가른다. 어느 쪽도 아니면
   * 웹이 다른 용도로 보낸 것이므로 그냥 흘려보낸다.
   */
  const handleMessage = useCallback(
    (event: WebViewMessageEvent) => {
      const screen = parseWebScreen(event.nativeEvent.data);
      if (screen) {
        screenRef.current = screen;
        return;
      }

      reminders.handleWebMessage(event);
    },
    [reminders],
  );

  const handleLoadEnd = useCallback(() => {
    setIsLoading(false);
    reminders.handleWebLoaded();
  }, [reminders]);

  if (hasError) {
    return <ConnectionErrorView onRetry={handleRetry} />;
  }

  return (
    <View style={styles.container}>
      <WebView
        ref={webViewRef}
        source={{ uri: WEB_URL }}
        style={styles.webView}
        // 로드 성공·실패 모두에서 호출된다. 실패면 아래 onError가 오류 화면으로 덮는다.
        onLoadEnd={handleLoadEnd}
        // 주입한 스크립트가 히스토리 위치·알림 정보를 보내는 통로.
        onMessage={handleMessage}
        // DNS 실패·오프라인 등 문서를 아예 받지 못한 경우. HTTP 4xx/5xx를 잡는
        // onHttpError는 하위 리소스에도 발생하는지가 문서에 명시돼 있지 않아,
        // 이미지 하나 404에 전체 오류 화면이 뜨는 오탐을 피하려고 쓰지 않는다.
        onError={handleError}
        // 페이지가 로드될 때마다 실행된다. 백 버튼이 쓸 화면 정보를 웹이 알려주게 한다.
        injectedJavaScript={OBSERVE_WEB_SCREEN_SCRIPT}
        // WebView가 세는 히스토리. 웹이 위치를 알려주지 못하는 문서에서만 백 버튼이 쓴다.
        // react-router의 pushState 이동에서도 호출된다.
        onNavigationStateChange={handleNavigationStateChange}
        // iOS 가장자리 스와이프로 웹 히스토리 뒤로 가기. 안드로이드는 위 BackHandler가 맡는다.
        allowsBackForwardNavigationGestures
        // 기본 UA 뒤에 붙는다. 웹에서 앱으로 열렸는지 판별할 때 쓴다.
        applicationNameForUserAgent="VictoryFairyApp"
      />
      {isLoading && (
        <View style={styles.loadingOverlay}>
          <ActivityIndicator size="large" color={COLORS.primary} />
        </View>
      )}
      {reminders.isPromptVisible && (
        <PermissionSheet onAllow={reminders.allowReminders} onSnooze={reminders.snoozePrompt} />
      )}
    </View>
  );
}

function ConnectionErrorView({ onRetry }: { onRetry: () => void }) {
  return (
    <View style={styles.errorContainer}>
      <Text style={styles.errorTitle}>연결할 수 없어요</Text>
      <Text style={styles.errorDescription}>
        네트워크 상태를 확인한 뒤 다시 시도해 주세요.
      </Text>
      <Pressable
        onPress={onRetry}
        accessibilityRole="button"
        style={({ pressed }) => [styles.retryButton, pressed && styles.retryButtonPressed]}
      >
        <Text style={styles.retryButtonLabel}>다시 시도</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: COLORS.background,
  },
  webView: {
    flex: 1,
  },
  // 웹이 첫 페인트를 그리기 전의 빈 화면을 덮는다.
  loadingOverlay: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: COLORS.background,
  },
  errorContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
    backgroundColor: COLORS.background,
  },
  errorTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: COLORS.labelNormal,
  },
  errorDescription: {
    marginTop: 8,
    fontSize: 14,
    lineHeight: 20,
    textAlign: 'center',
    color: COLORS.labelNeutral,
  },
  retryButton: {
    marginTop: 24,
    paddingVertical: 12,
    paddingHorizontal: 28,
    borderRadius: 8,
    backgroundColor: COLORS.primary,
  },
  retryButtonPressed: {
    opacity: 0.8,
  },
  retryButtonLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: COLORS.onPrimary,
  },
});
