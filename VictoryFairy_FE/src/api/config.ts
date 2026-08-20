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
 *
 * 이미지 CDN(`ASSET_BASE_URL`)은 이 둘과 별개다 — 아래 주석 참고.
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

/**
 * 이미지 CDN 루트 — 프로필 이미지를 실제로 읽어 오는 곳.
 *
 * **서버는 완성된 URL 을 주지 않는다.** 프로필 이미지는 언제나 BaseURL 을 뺀
 * EP(오브젝트 키, `user-profile-img/{uuid}.png`)로만 오고, 도메인을 붙이는 것은
 * 전적으로 클라이언트 몫이다 — 이 값은 백엔드 설정·코드 어디에도 없다(docs/account.md).
 *
 * API base(`.../api`)와 **다른 값이다.** 도메인이 같아도 이쪽은 CloudFront 배포이고,
 * 읽히는 경로도 `/user-profile-img/*` 와 `/temp/*` 둘뿐이다. 버킷은 퍼블릭 액세스가
 * 막혀 있어 S3 직접 주소(`....s3.ap-northeast-2.amazonaws.com`)로는 열리지 않는다.
 */
export const ASSET_BASE_URL = normalize(
  import.meta.env.VITE_ASSET_BASE_URL ?? 'https://victoryfairy.com',
);

/**
 * EP 를 실제 이미지 주소로 만든다. 이미지가 없으면 `null` —
 * 기본 이미지로 대신하는 것은 화면 몫이다(서버는 빈 문자열도 기본 URL 도 주지 않는다).
 *
 * EP 에는 선행 슬래시가 없어 그냥 이어 붙이면 `victoryfairy.comuser-profile-img/...`
 * 가 된다 — 문서가 "흔한 실수"로 못 박아 둔 부분이라 여기서 한 겹으로 맞춘다.
 */
export function toAssetUrl(endpoint: string | null | undefined): string | null {
  const trimmed = endpoint?.trim() ?? '';

  if (trimmed.length === 0) {
    return null;
  }

  // 언젠가 서버가 완성된 URL 을 주더라도 도메인을 두 번 붙이지 않는다.
  if (/^https?:\/\//i.test(trimmed)) {
    return trimmed;
  }

  return `${ASSET_BASE_URL}/${trimmed.replace(/^\/+/, '')}`;
}
