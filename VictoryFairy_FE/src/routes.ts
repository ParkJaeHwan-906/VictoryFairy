/**
 * 앱 라우트 경로.
 * App 과 각 페이지가 함께 참조하므로 별도 모듈로 둔다(순환 import 방지).
 */
export const ROUTES = {
  login: '/login',
  signup: '/signup',
  community: '/community',
  main: '/main',
  game: '/game',
  my: '/my',
} as const;
