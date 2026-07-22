/**
 * Vite 환경 변수 타입 선언.
 * tsconfig/`vite/client` 타입 부재를 대비해 최소한으로 직접 선언한다.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
