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
  /** 데일리 퀴즈 풀이. 경기 상세 시트의 "퀴즈 풀러 가기"로 들어온다(NavBar 없는 전체 화면). */
  quiz: '/quiz',
  /**
   * 퀴즈 결과. 종료된 경기 상세 시트의 "퀴즈 결과 확인하기"로 들어온다.
   *
   * 넘기는 state 가 없다 — 퀴즈 결과는 경기로 좁혀지지 않고(`QuizPageState` 주석 참고)
   * 화면에도 경기 이름이 나오지 않아, 들고 갈 문맥 자체가 없다.
   */
  quizResult: '/quiz-result',
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

/**
 * 경기 상세 → 퀴즈 화면으로 넘길 값.
 *
 * 퀴즈 API 는 경기와 묶여 있지 않다 — `GET /quizzes/today` 는 그날의 세트를 줄 뿐
 * `gameId` 로 좁히는 파라미터가 없다(docs/quiz.md). 그래서 이 값은 조회 조건이 아니라
 * **상단 바에 "NC 다이노스 VS LG 트윈스"를 쓰기 위한 표시용 문맥**이다.
 *
 * 라우터 state 는 주소를 직접 치고 들어오면 비어 있다 — 받는 쪽은 없는 경우를 견뎌야 한다.
 */
export interface QuizPageState {
  /** 어느 경기에서 들어왔는지. 지금은 표시에 쓰지 않지만 결과 화면이 생기면 필요하다. */
  gameId: string;
  /** 서버의 짧은 구단명(`Game.awayTeam`). 표시용 정식 명칭 변환은 화면에서 한다. */
  awayTeam: string;
  homeTeam: string;
}
