import * as Notifications from 'expo-notifications';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { AppState, Linking } from 'react-native';
import type { WebView, WebViewMessageEvent } from 'react-native-webview';

import {
  COLLECT_REMINDERS_SCRIPT,
  parsePushPreference,
  parseReminderSnapshot,
  pushPermissionScript,
} from './bridge';
import type { PushPreferenceMessage, ReminderSnapshot, UpcomingGame } from './bridge';
import { isPromptSnoozedAsync, snoozePromptAsync } from './prompt';
import {
  canAskNotificationPermissionAsync,
  ensureNotificationChannelAsync,
  hasNotificationPermissionAsync,
  requestNotificationPermissionAsync,
  syncGameRemindersAsync,
} from './reminders';

/**
 * 경기 알림 예약을 웹 화면에 붙이는 배선.
 *
 * WebView가 알고 있는 것(누가 로그인했고 어느 구단을 응원하는지)과 앱만 할 수 있는 것
 * (알림 예약)을 잇는 자리다. `WebAppView`는 여기서 돌려주는 핸들러를 WebView에 연결하기만 한다.
 */

/** 웹의 경기 목록 경로(`VictoryFairy_FE/src/routes.ts`의 `ROUTES.game`). */
const GAME_PATH = '/game';

interface GameReminders {
  /** `WebView.onMessage`에 연결한다. */
  handleWebMessage: (event: WebViewMessageEvent) => void;
  /** `WebView.onLoadEnd`에서 호출한다. */
  handleWebLoaded: () => void;
  /** `WebView.onNavigationStateChange`에서 호출한다. */
  handleWebNavigated: () => void;
  /** 권한 안내 시트를 띄울 차례인지. */
  isPromptVisible: boolean;
  /** 시트의 "알림 받기". */
  allowReminders: () => void;
  /** 시트의 "다음에 할게요" · 딤 탭 · 백 버튼. */
  snoozePrompt: () => void;
}

export default function useGameReminders(webViewRef: RefObject<WebView | null>): GameReminders {
  const [isPromptVisible, setIsPromptVisible] = useState(false);
  const [isWebLoaded, setIsWebLoaded] = useState(false);

  /**
   * 마지막으로 확인한 조회 결과.
   *
   * 화면 이동마다 다시 확인할지를 이 값으로 정한다 — 로그인·구단 선택이 끝나기 전에는
   * 예약할 게 없으니 자주 확인해도 공짜지만, 한 번 구단을 알아낸 뒤로는 화면이 바뀔
   * 때마다 일정을 다시 긁을 이유가 없다.
   */
  const lastStatusRef = useRef<ReminderSnapshot['status'] | null>(null);
  /** 권한을 막 허용했을 때 곧바로 예약하기 위해 들고 있는, 가장 최근 조회 결과. */
  const knownGamesRef = useRef<UpcomingGame[]>([]);

  const requestSync = useCallback(() => {
    webViewRef.current?.injectJavaScript(COLLECT_REMINDERS_SCRIPT);
  }, [webViewRef]);

  /**
   * 지금 권한 상태를 웹의 알림 설정 화면에 알린다.
   *
   * 토글은 웹에 있고 권한은 앱만 안다 — 알려주지 않으면 사용자가 거절한 뒤에도 화면은
   * 아무 일 없는 얼굴을 하고 있게 된다. 설정 화면이 떠 있지 않으면 웹 쪽에서 조용히
   * 넘어가므로 아무 때나 불러도 된다.
   */
  const reportPermission = useCallback(async () => {
    const isGranted = await hasNotificationPermissionAsync();
    webViewRef.current?.injectJavaScript(pushPermissionScript(isGranted));
  }, [webViewRef]);

  /**
   * 웹에서 알림 설정을 만졌을 때.
   *
   * 값은 이미 웹이 저장했다. 앱이 할 일은 (켜는 경우) 권한을 확보하고, 지금 상태를
   * 웹에 돌려준 뒤, 예약을 새 설정에 맞추는 것이다. 마지막의 `requestSync`가 저장된
   * 값을 다시 읽어 가므로 여기서 `enabled`를 따로 들고 있을 필요는 없다.
   */
  const applyPushPreference = useCallback(
    (preference: PushPreferenceMessage) => {
      void (async () => {
        if (preference.requestPermission && !(await hasNotificationPermissionAsync())) {
          if (await canAskNotificationPermissionAsync()) {
            await requestNotificationPermissionAsync();
          } else {
            // 이미 거절당한 뒤라 시스템 대화상자는 다시 뜨지 않는다 — 켤 수 있는 곳은
            // 기기 설정뿐이라 그리로 보낸다. 돌아오면 AppState 변화가 다시 확인한다.
            await Linking.openSettings();
          }
        }

        await reportPermission();
        requestSync();
      })();
    },
    [reportPermission, requestSync],
  );

  // 채널은 알림이 도착하기 전에 있어야 한다. 재실행에도 안전해서 시작할 때 한 번 만든다.
  useEffect(() => {
    void ensureNotificationChannelAsync();
  }, []);

  // 앱으로 돌아올 때마다 다시 맞춘다 — 그 사이 경기가 취소되거나 새 일정이 열렸을 수 있다.
  // 권한도 함께 알린다: 기기 설정에서 알림을 켜고 돌아오는 경로가 바로 이 시점이라,
  // 여기서 알리지 않으면 설정 화면의 안내문이 해결된 뒤에도 남는다.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        requestSync();
        void reportPermission();
      }
    });
    return () => subscription.remove();
  }, [reportPermission, requestSync]);

  const handleWebLoaded = useCallback(() => {
    setIsWebLoaded(true);
    requestSync();
  }, [requestSync]);

  const handleWebNavigated = useCallback(() => {
    const status = lastStatusRef.current;
    // 로그인 전(`signed-out`)에는 요청이 아예 나가지 않고, 구단 선택 전(`no-team`)에도
    // 프로필 한 번이면 끝난다. 그 두 상태에서만 화면 이동을 다시 확인할 신호로 쓴다.
    if (status === 'signed-out' || status === 'no-team') {
      requestSync();
    }
  }, [requestSync]);

  const handleWebMessage = useCallback(
    (event: WebViewMessageEvent) => {
      // 알림 설정 화면이 말을 건 것과 조회 결과가 같은 통로로 올라온다. 표식으로 가른다.
      const preference = parsePushPreference(event.nativeEvent.data);
      if (preference) {
        applyPushPreference(preference);
        return;
      }

      const snapshot = parseReminderSnapshot(event.nativeEvent.data);
      if (!snapshot) {
        return;
      }

      lastStatusRef.current = snapshot.status;
      // 조회에 실패한 것뿐이라면 예약을 건드리지 않는다. 여기서 빈 목록으로 취급하면
      // 잠깐 끊긴 네트워크 때문에 예약이 지워진다.
      if (snapshot.status === 'unavailable') {
        return;
      }

      // `disabled`(설정에서 껐다)도 빈 목록으로 떨어진다 — 예약이 지워지고, 아래에서
      // 권한 시트도 뜨지 않는다. 끄겠다는 사람에게 권한을 묻는 것만큼 엇나간 것이 없다.
      const games = snapshot.status === 'ok' ? snapshot.games : [];
      knownGamesRef.current = games;

      void (async () => {
        await syncGameRemindersAsync(games);

        // 알릴 경기가 실제로 있을 때만 권한을 묻는다 — 로그인도 안 한 첫 실행에
        // 알림부터 요구하면 거절당하고, 시스템 대화상자는 그 뒤로 다시 뜨지 않는다.
        if (games.length === 0) {
          return;
        }
        if (!(await canAskNotificationPermissionAsync())) {
          return;
        }
        if (await isPromptSnoozedAsync()) {
          return;
        }
        setIsPromptVisible(true);
      })();
    },
    [applyPushPreference],
  );

  const allowReminders = useCallback(() => {
    setIsPromptVisible(false);
    void (async () => {
      if (await requestNotificationPermissionAsync()) {
        await syncGameRemindersAsync(knownGamesRef.current);
      }
      // 거절이면 `canAskAgain`이 false가 되어 이 시트도 다시 뜨지 않는다.

      // 결과는 어느 쪽이든 웹에 알린다 — 이 시트가 알림 설정 화면 위에 떠 있었다면
      // 방금 정해진 권한이 그 화면의 안내문을 바꾼다.
      await reportPermission();
    })();
  }, [reportPermission]);

  const snoozePrompt = useCallback(() => {
    setIsPromptVisible(false);
    void snoozePromptAsync();
  }, []);

  /**
   * 알림을 눌러 들어온 경우 경기 목록으로 옮긴다.
   *
   * 앱이 꺼져 있었다면 알림 응답이 WebView보다 먼저 도착하므로, 로드가 끝난 뒤에
   * 옮긴다. `useLastNotificationResponse`는 그 응답을 계속 들고 있어 두 시점을 맞출 수 있다.
   */
  const lastResponse = Notifications.useLastNotificationResponse();
  useEffect(() => {
    if (!lastResponse || !isWebLoaded) {
      return;
    }

    // 같은 응답으로 두 번 옮기지 않도록 비운다. 비우면 이 효과가 한 번 더 돌지만
    // 그때는 응답이 없어 위에서 곧바로 빠져나간다.
    Notifications.clearLastNotificationResponse();

    // 웹은 SPA라 push 이동을 밖에서 흉내내기 어렵다. 주소를 바꿔 다시 그리게 하는 편이
    // 확실하고, 로딩 표시는 WebAppView가 이미 덮는다.
    webViewRef.current?.injectJavaScript(`window.location.assign('${GAME_PATH}'); true;`);
  }, [isWebLoaded, lastResponse, webViewRef]);

  // 이 객체를 그대로 WebView 핸들러의 의존성으로 쓰므로, 매 렌더 새로 만들면
  // 핸들러가 통째로 갈린다. 시트가 열리고 닫힐 때만 바뀌게 묶어 둔다.
  return useMemo(
    () => ({
      handleWebMessage,
      handleWebLoaded,
      handleWebNavigated,
      isPromptVisible,
      allowReminders,
      snoozePrompt,
    }),
    [allowReminders, handleWebLoaded, handleWebMessage, handleWebNavigated, isPromptVisible, snoozePrompt],
  );
}
