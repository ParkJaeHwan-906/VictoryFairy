import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { installAuthTokenStorage } from './stores/useAuthStore';
import { getAppId } from './utils/appId';
import { blockPinchZoom } from './utils/pinchZoom';
import { isInApp } from './utils/pushNotification';
import './styles/global.css';

/*
 * API 계층에 토큰 저장소를 주입한다. 첫 요청보다 먼저 끝나야 하므로 렌더 전에 부른다.
 * 이걸 빼면 인터셉터가 메모리 스텁을 계속 쓰게 되어 새로고침마다 로그인이 풀린다.
 */
installAuthTokenStorage();

/*
 * 이 기기의 앱 식별자를 앱을 열 때 한 번 만들어 둔다(이미 있으면 그대로 쓴다).
 * 가입 전 프로필 이미지 업로드가 이 값으로만 한도를 세므로, 첫 업로드보다 먼저 있어야 한다.
 */
getAppId();

/*
 * 모바일 사파리에서만 남아 있는 핀치 확대를 막는다. 나머지 브라우저와 앱의 WebView 는
 * viewport 메타(`index.html`)로 이미 막혀 있어, 그쪽에서는 이 호출이 하는 일이 없다.
 */
blockPinchZoom();

/*
 * 앱 WebView 안에서 열렸다는 표시. 하단 안전 영역은 앱이 맡으므로 CSS 가 이 표시를 보고
 * 웹 쪽 여백을 끈다(styles/tokens.css 의 `--safe-area-bottom`). 첫 페인트부터 맞아야 해서
 * 렌더 전에 붙인다. UA 꼬리표(`applicationNameForUserAgent`)는 문서가 뜨기 전부터 있다.
 */
if (isInApp() || navigator.userAgent.includes('VictoryFairyApp')) {
  document.documentElement.dataset.shell = 'app';
}

const container = document.getElementById('root');

if (!container) {
  throw new Error('#root 엘리먼트를 찾을 수 없습니다.');
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
