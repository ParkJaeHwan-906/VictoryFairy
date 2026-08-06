/**
 * 정적 자산 모듈 선언.
 * `vite/client` 타입 부재를 대비해 프로젝트에서 실제로 import 하는 확장자만 직접 선언한다.
 * (env.d.ts 와 같은 방침)
 */

declare module '*.svg' {
  const src: string;
  export default src;
}

declare module '*.png' {
  const src: string;
  export default src;
}

declare module '*.css';
