import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useIsLoggedIn } from '../stores/useAuthStore';
import { ROUTES, type LoginPageState } from '../routes';

/**
 * ProtectedRoute — 로그인한 사용자만 통과시키는 라우트 껍데기.
 *
 * **계정 기능 화면에만 씌운다**(라운지 · 마이 · 퀴즈 · 꾸미기 · 설정류). 홈과 경기(목록 · 상세)는
 * 로그인 없이 둘러볼 수 있어야 하므로 이 바깥에 둔다 — App Store 심사 5.1.1(v) 가
 * "계정과 무관한 기능에 가입을 요구하지 말라"며 반려한 자리다.
 *
 * 주소를 직접 쳐서 들어오는 경로도 막는다. 토큰이 없으면 화면을 그리기 전에
 * 로그인으로 돌려보내므로, 안쪽 페이지가 "토큰이 있다"를 전제로 API 를 불러도 된다.
 * 막힌 주소는 `LoginPageState.from` 으로 실어 보내 로그인 뒤 그 화면으로 이어지게 한다.
 *
 * `replace` 로 보내는 이유: 막힌 주소가 히스토리에 남으면 로그인 화면에서 뒤로 가기를
 * 눌렀을 때 다시 막힌 주소로 갔다가 튕겨 나오는 왕복이 생긴다.
 *
 * 토큰은 localStorage 에서 동기로 복원되므로(`useAuthStore` 참고) 첫 렌더에 이미
 * 판정이 끝나 있다 — 복원을 기다리는 로딩 상태가 따로 필요 없다.
 *
 * 프로필 채우기는 여기서 하지 않는다 — 공개 화면(홈·경기)에서도 로그인 상태면 프로필이
 * 있어야 해서, 앱 전체가 지나는 `useProfileBootstrap`(App)으로 옮겼다.
 */
export default function ProtectedRoute() {
  const isLoggedIn = useIsLoggedIn();
  const location = useLocation();

  if (!isLoggedIn) {
    const state: LoginPageState = {
      from: `${location.pathname}${location.search}${location.hash}`,
    };
    return <Navigate to={ROUTES.login} replace state={state} />;
  }

  return <Outlet />;
}
