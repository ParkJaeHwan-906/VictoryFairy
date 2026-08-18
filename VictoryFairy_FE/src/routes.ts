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
   * 퀴즈 결과(이닝별). 종료된 경기 상세 시트의 "퀴즈 결과 확인하기"로 들어온다.
   *
   * **`QuizResultPageState` 를 반드시 넘겨야 한다** — 결과 조회가 경기 단위라
   * `gameId` 없이는 아무것도 부를 수 없다(2026-08-13 계약 교체).
   */
  quizResult: '/quiz-result',
  my: '/my',
  /**
   * 프로필 수정(닉네임). 마이페이지 프로필 사진의 연필 버튼으로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   */
  profileEdit: '/my/profile',
  /**
   * 계정 설정(비밀번호 변경). 마이페이지 "설정 > 계정 설정"으로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   */
  accountSetting: '/my/account',
  /**
   * 문의하기(자주 묻는 질문). 마이페이지 "센터 > 문의하기"로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   */
  inquiry: '/inquiry',
} as const;

/**
 * 구단·선수 선택 화면을 **어떤 흐름에서** 열었는지.
 *
 * 두 화면(`TeamSelectPage` · `PlayerSelectPage`)은 온보딩과 마이페이지 수정이 함께 쓴다 —
 * 디자인도 API 도 같고 다른 것은 앞뒤뿐이다.
 * - `onboarding`(기본): 가입 직후. 저장된 선호가 없어 빈 상태에서 시작하고, 끝나면 완료 화면.
 * - `edit`: 마이페이지 "○○ 응원중!" 에서 들어온다. **저장된 선호를 채운 채로 시작**하고,
 *   끝나면 마이페이지로 돌아간다. 선수 화면은 해제까지 반영해야 한다(추가만으로는 못 지운다).
 *
 * state 가 비어 있으면(주소 직접 입력·옛 history) `onboarding` 으로 본다 — 온보딩 쪽이
 * 아무것도 지우지 않는 안전한 기본값이다.
 */
export type SupportFlowMode = 'onboarding' | 'edit';

/** 마이페이지 → 구단 선택으로 넘길 값. 흐름 표식뿐이고 선호 값은 스토어에서 읽는다. */
export interface TeamSelectState {
  mode: SupportFlowMode;
}

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
  /** 없으면 `onboarding`. 구단 화면이 자기가 열린 흐름을 그대로 이어 넘긴다. */
  mode?: SupportFlowMode;
}

/** 라우터 state 에서 흐름을 읽는다. 형태가 맞지 않으면 안전한 쪽(`onboarding`)으로 본다. */
export function readSupportFlowMode(state: unknown): SupportFlowMode {
  if (typeof state !== 'object' || state === null) return 'onboarding';

  return (state as { mode?: unknown }).mode === 'edit' ? 'edit' : 'onboarding';
}

/**
 * 경기 상세 → 퀴즈 화면으로 넘길 값.
 *
 * ⚠️ **`gameId` 는 이제 조회 조건이다**(2026-08-12) — `GET /quizzes/today` 가 이 값으로
 * "지금 보고 있는 경기"를 검증한 뒤에만 세트를 준다(docs/quiz.md). 없으면 퀴즈를 아예
 * 부를 수 없으므로, 문맥 없이 들어온 화면은 조회를 시도하지 말고 돌아가라고 안내해야 한다.
 * 구단명 둘은 여전히 카드 머리말에 쓰는 표시용 값이라 없어도 화면은 성립한다.
 *
 * 라우터 state 는 주소를 직접 치고 들어오면 비어 있다 — 받는 쪽은 없는 경우를 견뎌야 한다.
 */
export interface QuizPageState {
  /** `Game.gameId`(내부 PK 가 아니라 `naver_game_id` 문자열). 퀴즈 조회의 필수 조건이다. */
  gameId: string;
  /** 서버의 짧은 구단명(`Game.awayTeam`). 표시용 정식 명칭 변환은 화면에서 한다. */
  awayTeam: string;
  homeTeam: string;
}

/**
 * 경기 상세 → 퀴즈 결과 화면으로 넘길 값.
 *
 * 결과도 경기 단위가 됐다(2026-08-13) — `GET /quizzes/submissions` 가 `gameId` 로
 * 그 경기의 이닝별 결산을 준다. 풀이 화면과 필요한 값이 같아 형태를 그대로 맞춘다.
 */
export type QuizResultPageState = QuizPageState;

/**
 * 라우터 state 에서 퀴즈 경기 문맥을 읽는다. 형태가 맞지 않으면 `null`.
 *
 * 주소를 직접 치면 비어 있고, history 에 남은 옛 형태(`gameId` 없던 시절)가 되살아날
 * 수도 있다 — **없으면 조회 자체를 걸 수 없으므로** 받는 쪽은 반드시 null 을 다뤄야 한다.
 * 풀이·결과 두 화면이 같은 판정을 쓰므로 state 계약 옆에 둔다.
 */
export function readQuizPageState(state: unknown): QuizPageState | null {
  if (typeof state !== 'object' || state === null) return null;

  const { gameId, awayTeam, homeTeam } = state as Partial<QuizPageState>;
  return typeof gameId === 'string' && typeof awayTeam === 'string' && typeof homeTeam === 'string'
    ? { gameId, awayTeam, homeTeam }
    : null;
}
