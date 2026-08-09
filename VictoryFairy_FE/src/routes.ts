/**
 * 앱 라우트 경로.
 * App 과 각 페이지가 함께 참조하므로 별도 모듈로 둔다(순환 import 방지).
 */
export const ROUTES = {
  login: '/login',
  signup: '/signup',
  teamSelect: '/team-select',
  playerSelect: '/player-select',
  /** 온보딩 마지막 단계(완료 안내). 저장은 앞 단계에서 이미 끝난다. */
  complete: '/complete',
  community: '/community',
  main: '/main',
  game: '/game',
  my: '/my',
} as const;

/**
 * 구단 선택 → 선수 선택으로 넘길 값.
 *
 * 응원 선수는 응원 구단 소속이어야 해서(support 도메인 계약) 선수 화면이 방금 고른 구단을
 * 알아야 한다 — 모르면 전 구단 선수가 검색되고, 고른 선수가 소속이 아니면 저장이 400 이다.
 * 서버에 이미 저장된 값이라 다시 조회할 수도 있지만, 방금 받은 응답을 그대로 넘겨 왕복을 아낀다.
 *
 * 라우터 state 는 주소를 직접 치고 들어오면 비어 있다 — 받는 쪽은 없는 경우를 견뎌야 한다.
 */
export interface PlayerSelectState {
  teamId: number;
  teamName: string;
}
