import { useEffect } from 'react';
import { useAccountStore } from '../stores/useAccountStore';
import { useIsLoggedIn } from '../stores/useAuthStore';

/**
 * 로그인 상태인데 프로필이 비어 있으면 채운다. App 에서 한 번만 부른다.
 *
 * 토큰은 persist 되지만 프로필은 아니라(`useAccountStore` 의 결정), 새로고침·북마크로
 * 메인이 아닌 화면에 바로 들어오면 토큰만 있고 프로필은 없는 상태가 된다. 그러면
 * 응원 구단으로 동작을 정하는 화면들이 "구단을 아직 안 골랐다"로 오해한다
 * (라운지 채팅이 그랬다 — 새로고침 후 채팅을 열면 구단 선택 안내가 떴다).
 *
 * 예전에는 보호 라우트(`ProtectedRoute`)가 맡았지만, 홈·경기가 로그인 없이도 열리는
 * 공개 화면이 되면서 그 자리를 지나지 않는 로그인 사용자가 생겼다. 그래서 모든 화면이
 * 지나는 App 으로 올렸다 — 로그인 여부는 여기서 토큰으로 판정한다.
 *
 * `idle` 일 때만 부른다 — 화면을 옮겨 다닐 때마다 `users/me` 를 다시 부르지 않기
 * 위해서다. `error` 도 다시 부르지 않는다. 여기서 재시도하면 실패가 곧바로 다음
 * 렌더의 재요청이 되어 요청 루프가 된다.
 */
export function useProfileBootstrap(): void {
  const isLoggedIn = useIsLoggedIn();
  const profileStatus = useAccountStore((state) => state.status);
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  useEffect(() => {
    if (isLoggedIn && profileStatus === 'idle') {
      void fetchProfile();
    }
  }, [isLoggedIn, profileStatus, fetchProfile]);
}
