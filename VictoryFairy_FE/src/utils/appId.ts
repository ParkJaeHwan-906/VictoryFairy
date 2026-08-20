/**
 * 이 기기의 앱 설치를 가리키는 식별자.
 *
 * 가입 전 프로필 이미지 업로드(`POST /auth/profile-image`)는 인증이 없어, 서버가 업로드
 * 한도(30분 창에 성공 10회)를 **오직 이 값으로만** 센다. 계정과 무관하고 서버가 형식을
 * 검사하지도 않는다 — 비어 있지만 않으면 통과한다(docs/auth.md 10절).
 *
 * ── 한 번 만들면 다시 만들지 않는다 ───────────────────────────────────
 * 실행할 때마다 새로 만들면 한도가 사실상 사라져, 서버가 막으려던 것을 그대로 열어 준다.
 * 그래서 만들자마자 `localStorage` 에 남기고 다음 실행부터는 그 값을 그대로 쓴다.
 * 값 자체는 EP·파일명·응답 어디에도 실리지 않아, 남이 알아도 얻을 수 있는 것이 없다.
 * ──────────────────────────────────────────────────────────────────────
 */

const APP_ID_KEY = 'victoryfairy.appId';

/**
 * 저장소를 못 쓰는 환경(사파리 시크릿 모드 등)에서 쓰는 자리.
 * 새로고침하면 사라지지만, 적어도 한 세션 안에서는 같은 값이 유지된다.
 */
let memoryAppId: string | null = null;

function createAppId(): string {
  /*
   * `crypto.randomUUID` 는 보안 컨텍스트(https · localhost)에서만 있다.
   * 없으면 시각 + 난수로 대신한다 — 서버가 형식을 보지 않으므로 UUID 일 필요는 없고,
   * 기기끼리 겹치지만 않으면 된다.
   */
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 이 기기의 앱 식별자. 없으면 이때 만들어 저장한다.
 *
 * 앱을 열 때 한 번 부르면(`main.tsx`) 첫 업로드 전에 값이 준비되고, 그 뒤로는
 * 어디서 불러도 같은 값이 나온다.
 */
export function getAppId(): string {
  try {
    const saved = window.localStorage.getItem(APP_ID_KEY);
    if (saved !== null && saved.length > 0) {
      return saved;
    }

    const created = createAppId();
    window.localStorage.setItem(APP_ID_KEY, created);
    return created;
  } catch {
    // 저장소 접근 자체가 막힌 경우다. 업로드를 포기시키지는 않는다.
    memoryAppId ??= createAppId();
    return memoryAppId;
  }
}
