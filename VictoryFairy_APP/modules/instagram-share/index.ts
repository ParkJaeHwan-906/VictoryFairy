// 네이티브 모듈을 다시 내보낸다. 웹에서는 InstagramShareModule.web.ts 로,
// 네이티브에서는 InstagramShareModule.ts 로 풀린다.
export { default } from './src/InstagramShareModule';
export * from './src/InstagramShare.types';
