/**
 * 모듈별 API Base URL.
 *
 * 백엔드는 모듈마다 base가 다르다 — user(인증·계정)는 `.../api`,
 * game(채팅)은 `.../rt`. 엔드포인트 함수는 **모듈 base 기준 상대 경로만** 쓴다.
 * 경로에 base 세그먼트를 다시 붙이면 `.../api/api/auth/login`이 되어 404가 난다.
 *
 *   USER_BASE_URL + '/auth/login'  → https://victoryfairy/api/auth/login
 *   GAME_BASE_URL + '/chat/rooms'  → https://victoryfairy/rt/chat/rooms
 *
 * 값은 `.env`에서 주입하며, 미설정 시 로컬 개발 서버로 폴백한다.
 */

const LOCAL_ORIGIN = 'http://localhost:8080';

/** 후행 슬래시를 제거해 `base + '/path'` 결합이 항상 한 겹의 슬래시가 되게 한다. */
function normalize(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

/** user 모듈 — 인증(`auth`) · 계정(`account`) */
export const USER_BASE_URL = normalize(
  import.meta.env.VITE_API_USER_BASE_URL ?? `${LOCAL_ORIGIN}/api`,
);

/** game 모듈 — 구단별 채팅 (`chat`). base `/rt`은 realtime이며 `/api` 아래가 아니다. */
export const GAME_BASE_URL = normalize(
  import.meta.env.VITE_API_GAME_BASE_URL ?? `${LOCAL_ORIGIN}/rt`,
);
