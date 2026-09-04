import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { installAuthTokenStorage } from './stores/useAuthStore';
import { getAppId } from './utils/appId';
import { blockPinchZoom } from './utils/pinchZoom';
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
