/**
 * Vite 환경 변수 타입 선언.
 * tsconfig/`vite/client` 타입 부재를 대비해 최소한으로 직접 선언한다.
 */
interface ImportMetaEnv {
  /** user 모듈 base — 인증·계정. 예) https://victoryfairy/api */
  readonly VITE_API_USER_BASE_URL?: string;
  /** game 모듈 base — 구단별 채팅. 예) https://victoryfairy/rt */
  readonly VITE_API_GAME_BASE_URL?: string;
  /**
   * 이미지 CDN 루트 — 프로필 이미지 EP 앞에 붙는다. 예) https://victoryfairy.com
   * API base 와 달리 `/api` 가 붙지 않는다(CloudFront 루트).
   */
  readonly VITE_ASSET_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
