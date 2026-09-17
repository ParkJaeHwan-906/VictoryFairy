/**
 * WebView가 로드할 웹 오리진.
 *
 * 앱은 배포된 React 웹(`VictoryFairy_FE`)을 그대로 감싸므로 여기 주소 하나가
 * 앱 화면 전부를 결정한다. 값은 `.env`의 `EXPO_PUBLIC_WEB_URL`로 주입한다.
 *
 * `EXPO_PUBLIC_` 접두사가 없으면 Expo가 번들에 넣지 않아 런타임에 undefined가
 * 되고, `process.env.EXPO_PUBLIC_WEB_URL` 형태로 **정적으로** 참조해야 한다.
 * 구조 분해나 대괄호 접근은 인라인되지 않는다.
 *
 * 로컬 웹 개발 서버를 붙일 때는 실기기에서 `localhost`가 기기 자신을 가리키므로
 * PC의 LAN IP를 써야 한다 — 예: `EXPO_PUBLIC_WEB_URL=http://192.168.0.10:5173`
 */

/** 운영 도메인. dev 전용 서브도메인은 아직 없고 이 주소 하나로 서비스한다. */
const PRODUCTION_WEB_URL = 'https://victoryfairy.com';

/** 후행 슬래시를 제거해 뒤에 경로를 붙일 때 슬래시가 겹치지 않게 한다. */
function normalize(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

export const WEB_URL = normalize(process.env.EXPO_PUBLIC_WEB_URL ?? PRODUCTION_WEB_URL);

/**
 * 백엔드 **user 모듈**의 base URL.
 *
 * ── 백엔드는 모듈마다 base가 다르다 ───────────────────────────────────
 * 웹(`VictoryFairy_FE/src/api/config.ts`)은 둘을 나눠 들고 있다.
 *
 *   user 모듈  `.../api`  인증 · 계정(`/users/me`) · 경기(`/games`) · 구단 · 선수
 *   game 모듈  `.../rt`   구단 채팅 · 퀴즈  ← realtime이며 `/api` 아래가 아니다
 *
 * 앱이 부르는 건 응원 구단(`/users/me`)과 경기 일정(`/games`) 둘뿐이고 **둘 다 user
 * 모듈**이라 여기 하나만 둔다. 나중에 퀴즈나 채팅을 건드리게 되면 `/rt` base를 따로
 * 만들어야 한다 — 이 값 뒤에 `/quizzes`를 붙이면 `/api/quizzes`가 되어 404다.
 * 이름에 `USER`를 남겨 둔 것이 그 경계 표시다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 운영에서는 웹과 같은 오리진(`victoryfairy.com`) 아래에 있지만, 로컬 웹 개발 서버
 * (`http://<LAN IP>:5173`)에 붙였을 때는 웹 주소에서 유도할 수 없어 별도 값으로 둔다.
 * 값은 `.env`의 `EXPO_PUBLIC_API_USER_BASE_URL`로 주입한다.
 *
 * 이 주소로 요청을 보내는 건 앱이 아니라 WebView 안에 주입한 스크립트다
 * (`src/notifications/bridge.ts` 참고) — 액세스 토큰을 네이티브로 꺼내지 않으려는
 * 선택이며, 그래서 운영에서는 동일 오리진 요청이라 CORS가 끼어들지 않는다.
 */
const PRODUCTION_API_USER_BASE_URL = 'https://victoryfairy.com/api';

export const API_USER_BASE_URL = normalize(
  process.env.EXPO_PUBLIC_API_USER_BASE_URL ?? PRODUCTION_API_USER_BASE_URL,
);

/**
 * 인스타 스토리 공유에 붙이는 Meta 앱 ID(`source_application`).
 *
 * 2023년 1월부터 이 값이 없으면 인스타가 공유 요청을 그냥 무시한다 — 오류도 나지 않고
 * 편집기가 열리지 않는다. 비밀이 아니라 공유 요청의 출처 표시라서 APK에 평문으로
 * 들어가도 되고, 그래서 `EXPO_PUBLIC_` 값으로 둔다.
 *
 * 값이 없으면 앱은 공유가 불가능하다고 답한다(`src/share/instagram.ts`) — 넘겨 봐야
 * 인스타가 버리므로, 눌러도 아무 일이 없는 버튼을 웹에 그리게 하는 것보다 낫다.
 * 운영 폴백을 두지 않은 것도 같은 이유다: 틀린 ID로 조용히 실패하느니 없는 게 낫다.
 */
export const INSTAGRAM_APP_ID = (process.env.EXPO_PUBLIC_INSTAGRAM_APP_ID ?? '').trim();
