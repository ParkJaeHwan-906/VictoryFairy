import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  isInApp,
  notifyAppPushPreference,
  readPushEnabled,
  subscribeAppPushPermission,
  writePushEnabled,
} from '../utils/pushNotification';
import '../styles/NotificationSettingPage.css';

/**
 * NotificationSettingPage — 알림 설정.
 * Figma: SWM / [My] 알림 설정 (node 1365:17413)
 *
 * 마이페이지 "설정 > 알림 설정" 으로 들어온다. 디자인에 NavBar 가 없어 레이아웃 밖
 * 전체 화면이고, 뒤로가기로 마이페이지로 돌아간다.
 *
 * ── 이 화면이 실제로 끄고 켜는 것 ──────────────────────────────────────
 * 지금 앱이 보내는 알림은 **응원 구단 경기 시작 30분 전 로컬 알림** 하나뿐이다
 * (`VictoryFairy_APP/src/notifications/`). 디자인의 설명문이 말하는 퀴즈 업데이트는
 * 아직 보내는 곳이 없다 — 문구는 디자인 그대로 두되, 종류가 늘면 이 토글이 그것까지
 * 함께 덮는다(저장 형태를 객체로 둔 이유다).
 *
 * 값은 웹이 들고 앱이 읽어 간다(`utils/pushNotification.ts` 머리말). 그래서 이 화면은
 * 알림을 직접 예약하지 않고, 값을 저장한 뒤 앱에 "다시 맞춰라"라고 알리기만 한다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * ── 토글과 OS 권한은 **다른 축**이다 ───────────────────────────────────
 * 토글은 "알림을 받겠다"는 사용자의 뜻이고, 권한은 기기가 그걸 허락했는지다. 둘을
 * 한 값으로 합치지 않는다 — 권한이 없다고 토글을 대신 꺼 버리면, 사용자가 기기 설정에서
 * 알림을 켜고 돌아왔을 때 토글은 꺼진 채라 결국 두 번 켜야 한다.
 *
 * 그래서 권한이 없을 때는 토글을 그대로 두고 안내문만 덧붙인다. 기기 설정에서 켜고
 * 돌아오면 앱이 다시 알려주고(복귀 시점) 안내문만 사라진다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function NotificationSettingPage() {
  const navigate = useNavigate();

  const [enabled, setEnabled] = useState(readPushEnabled);
  /** 기기가 알림을 막고 있는지. 앱만 알 수 있어서, 알려주기 전까지는 `false`(문제 없음)로 본다. */
  const [isBlocked, setIsBlocked] = useState(false);

  /**
   * 브라우저인지 앱인지는 첫 렌더에 이미 정해져 있고 바뀌지 않는다.
   * 렌더마다 다시 묻지 않도록 state 초기값으로 한 번만 읽는다.
   */
  const [isApp] = useState(isInApp);

  /*
   * 권한 결과는 세 시점에 온다 — 화면에 들어왔을 때(아래에서 물어본다), 토글을 켜서
   * 앱이 권한을 물은 직후, 그리고 기기 설정에 다녀와 앱으로 돌아왔을 때. 누른 자리에서
   * 답을 기다리지 않고 화면이 떠 있는 동안 계속 듣는 이유가 세 번째다.
   */
  useEffect(() => {
    const unsubscribe = subscribeAppPushPermission((permission) => {
      setIsBlocked(permission === 'denied');
    });

    /*
     * 화면에 들어온 김에 지금 권한 상태를 물어본다. 값은 바꾸지 않으므로 저장한 값을
     * 그대로 실어 보내고, 권한 대화상자도 띄우지 않는다(`requestPermission: false`) —
     * 설정을 보러 들어왔을 뿐인 사람에게 시스템 대화상자를 띄울 일은 아니다.
     */
    notifyAppPushPreference(readPushEnabled(), false);

    return unsubscribe;
  }, []);

  const handleToggle = () => {
    const next = !enabled;

    setEnabled(next);
    writePushEnabled(next);
    // 켤 때만 권한을 묻는다. 끄는 데에는 물어볼 것이 없고, 알림을 끄겠다는 사람에게
    // 권한 대화상자를 띄우면 무엇을 묻는 화면인지 알 수 없게 된다.
    notifyAppPushPreference(next, next);
  };

  return (
    <main className="notification-setting-page">
      <header className="notification-setting-page__topbar">
        <button
          className="notification-setting-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="notification-setting-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="notification-setting-page__topbar-title">알림 설정</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="notification-setting-page__topbar-spacer" aria-hidden="true" />
      </header>

      <div className="notification-setting-page__row">
        <div className="notification-setting-page__text">
          <p className="notification-setting-page__label" id="push-toggle-label">
            푸시 알림 수신
          </p>
          <p className="notification-setting-page__description" id="push-toggle-description">
            진행중인 퀴즈 업데이트, 결과 확인 등 푸시알림을 받습니다.
          </p>
          {/*
            안내문은 알림이 오지 않는 이유가 있을 때만 자리를 차지한다. 늘 보이는 줄로 두면
            디자인에 없는 여백이 생기고, 아무 문제 없는 보통 상태에서 읽을 것이 늘어난다.
            토글이 꺼져 있으면 권한 이야기는 꺼낼 필요가 없다 — 안 오는 이유가 이미 있다.
          */}
          {isApp && enabled && isBlocked && (
            <p className="notification-setting-page__note" role="status">
              기기 설정에서 알림이 꺼져 있어요. 설정 &gt; 알림에서 승리요정을 허용해 주세요.
            </p>
          )}
          {!isApp && (
            <p className="notification-setting-page__note">푸시 알림은 앱에서만 받을 수 있어요.</p>
          )}
        </div>

        {/*
          `role="switch"` 라 낭독기가 "켜짐 / 꺼짐"까지 읽는다. 체크박스로 그리면
          "선택됨"이 되어 이 줄이 무엇을 하는 스위치인지가 덜 분명해진다.
        */}
        <button
          className="notification-setting-page__toggle"
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-labelledby="push-toggle-label"
          aria-describedby="push-toggle-description"
          onClick={handleToggle}
        >
          <span className="notification-setting-page__toggle-knob" aria-hidden="true" />
        </button>
      </div>
    </main>
  );
}
