/**
 * Vite 환경 변수 타입 선언.
 * tsconfig/`vite/client` 타입 부재를 대비해 최소한으로 직접 선언한다.
 */
interface ImportMetaEnv {
  /** user 모듈 base — 인증·계정. 예) https://victoryfairy/api */
  readonly VITE_API_USER_BASE_URL?: string;
  /** game 모듈 base — 구단별 채팅. 예) https://victoryfairy/rt */
  readonly VITE_API_GAME_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
