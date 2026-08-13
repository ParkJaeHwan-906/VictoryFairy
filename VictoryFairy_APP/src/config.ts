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
