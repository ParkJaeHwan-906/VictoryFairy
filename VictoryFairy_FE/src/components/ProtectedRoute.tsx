import { Navigate, Outlet } from 'react-router-dom';
import { useIsLoggedIn } from '../stores/useAuthStore';
import { ROUTES } from '../routes';

/**
 * ProtectedRoute — 로그인한 사용자만 통과시키는 라우트 껍데기.
 *
 * 주소를 직접 쳐서 들어오는 경로를 막는다. 토큰이 없으면 화면을 그리기 전에
 * 로그인으로 돌려보내므로, 안쪽 페이지가 "토큰이 있다"를 전제로 API 를 불러도 된다.
 *
 * `replace` 로 보내는 이유: 막힌 주소가 히스토리에 남으면 로그인 화면에서 뒤로 가기를
 * 눌렀을 때 다시 막힌 주소로 갔다가 튕겨 나오는 왕복이 생긴다.
 *
 * 토큰은 localStorage 에서 동기로 복원되므로(`useAuthStore` 참고) 첫 렌더에 이미
 * 판정이 끝나 있다 — 복원을 기다리는 로딩 상태가 따로 필요 없다.
 */
export default function ProtectedRoute() {
  const isLoggedIn = useIsLoggedIn();

  if (!isLoggedIn) {
    return <Navigate to={ROUTES.login} replace />;
  }

  return <Outlet />;
}
