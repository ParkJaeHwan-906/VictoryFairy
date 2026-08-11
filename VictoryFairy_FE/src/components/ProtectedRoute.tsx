import { useEffect } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useIsLoggedIn } from '../stores/useAuthStore';
import { useAccountStore } from '../stores/useAccountStore';
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
 *
 * 프로필 부트스트랩도 함께 맡는다. 자세한 이유는 아래 effect 주석 참고.
 */
export default function ProtectedRoute() {
  const isLoggedIn = useIsLoggedIn();
  const profileStatus = useAccountStore((state) => state.status);
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  /*
   * 로그인 상태로 보호 구역에 들어왔는데 프로필이 비어 있으면 여기서 채운다.
   *
   * 토큰은 persist 되지만 프로필은 아니라(`useAccountStore` 의 결정), 새로고침·북마크로
   * 메인이 아닌 화면에 바로 들어오면 토큰만 있고 프로필은 없는 상태가 된다. 그러면
   * 응원 구단으로 동작을 정하는 화면들이 "구단을 아직 안 골랐다"로 오해한다
   * (라운지 채팅이 그랬다 — 새로고침 후 채팅을 열면 구단 선택 안내가 떴다).
   *
   * 화면마다 채우게 두면 화면이 늘 때마다 같은 실수가 반복되므로, 보호 구역 전체가
   * 반드시 지나는 이 자리에서 한 번만 부른다. 여기가 "로그인했다"를 아는 유일한
   * 공통 지점이다.
   *
   * `idle` 일 때만 부른다 — 화면을 옮겨 다닐 때마다 `users/me` 를 다시 부르지 않기
   * 위해서다. `error` 도 다시 부르지 않는다. 여기서 재시도하면 실패가 곧바로 다음
   * 렌더의 재요청이 되어 요청 루프가 된다.
   */
  useEffect(() => {
    if (isLoggedIn && profileStatus === 'idle') {
      void fetchProfile();
    }
  }, [isLoggedIn, profileStatus, fetchProfile]);

  if (!isLoggedIn) {
    return <Navigate to={ROUTES.login} replace />;
  }

  /*
   * 프로필 도착을 기다리지 않고 바로 그린다. 여기서 막으면 보호 화면 전부가 `users/me`
   * 왕복만큼 빈 화면이 되는데, 대부분의 화면은 프로필이 없어도 그릴 게 있다.
   * 프로필이 필요한 화면은 각자 `status` 를 보고 로딩을 표시한다.
   */
  return <Outlet />;
}
