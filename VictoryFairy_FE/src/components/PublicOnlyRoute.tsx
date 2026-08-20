import { useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../stores/useAuthStore';
import { ROUTES } from '../routes';

/**
 * PublicOnlyRoute — 이미 로그인한 사용자는 통과시키지 않는 라우트 껍데기.
 *
 * `ProtectedRoute` 의 반대편이다. 토큰이 localStorage 에 남아 있으면(`useAuthStore` 참고)
 * 앱을 다시 켜도 로그인은 유지되는데, 진입 경로가 로그인 화면으로 고정돼 있어서 매번
 * 로그인 폼을 마주하게 된다. 여기서 메인으로 돌려보내는 것이 "자동 로그인"의 실체다.
 *
 * `replace` 로 보내는 이유는 `ProtectedRoute` 와 같다 — 지나온 로그인 화면이 히스토리에
 * 남으면 뒤로 가기가 다시 이 껍데기로 들어와 왕복이 생긴다.
 */
export default function PublicOnlyRoute() {
  /*
   * 토큰을 **구독하지 않고** 진입 시점에 한 번만 읽는다.
   *
   * 로그인·회원가입은 이 구역 *안에서* 토큰을 받는 화면이다. `useIsLoggedIn()` 으로
   * 구독하면 `setTokens()` 가 곧 리렌더가 되어, 화면이 제 갈 곳으로 이동하기 전에
   * 여기서 먼저 메인으로 튕겨 낸다 — 회원가입은 그 뒤에 이어지는 온보딩(구단·선수 선택)을
   * 통째로 건너뛰게 된다. 이 껍데기가 판단할 것은 "들어올 때 이미 세션이 있었는가"뿐이다.
   *
   * localStorage 복원은 동기라 첫 렌더에 이미 끝나 있다. 복원을 기다리는 로딩 상태는 없다.
   */
  const [hadSessionOnEntry] = useState(() => useAuthStore.getState().accessToken !== null);

  if (hadSessionOnEntry) {
    return <Navigate to={ROUTES.main} replace />;
  }

  return <Outlet />;
}
