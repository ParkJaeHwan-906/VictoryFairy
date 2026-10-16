/**
 * 앱 라우트 경로.
 * App 과 각 페이지가 함께 참조하므로 별도 모듈로 둔다(순환 import 방지).
 */
import type { CommunityPostDetail } from './types/community';
import type { Game } from './types/game';

export const ROUTES = {
  login: '/login',
  signup: '/signup',
  teamSelect: '/team-select',
  playerSelect: '/player-select',
  /** 온보딩 마지막 단계(완료 안내). 저장은 앞 단계에서 이미 끝난다. */
  complete: '/complete',
  /**
   * 라운지. 랭킹 · 게시판 · 퀴즈방 세 탭이고, 어느 탭인지는 `?tab=` 으로 남긴다
   * (`communityBoardPath` · `communityQuizRoomsPath`).
   * 주소의 경로는 그대로라 NavBar 의 라운지 탭 표시와 앱의 "탭 뿌리" 판정이 바뀌지 않는다.
   */
  community: '/community',
  /**
   * 게시글 상세. **라우트 정의에만 쓰는 패턴**이고 실제 주소는 `communityPostPath` 로 만든다.
   * NavBar 없는 전체 화면이다(아래에 댓글 입력창이 붙는다).
   */
  communityPost: '/community/posts/:postId',
  /** 게시글 작성. NavBar 없는 전체 화면이다. */
  communityWrite: '/community/write',
  /** 게시글 수정. 실제 주소는 `communityEditPath` 로 만든다. */
  communityEdit: '/community/posts/:postId/edit',
  /**
   * 퀴즈방 만들기. NavBar 없는 전체 화면이다.
   *
   * 아래 `quizRoom`(`:roomId`)과 같은 깊이지만 **정적 세그먼트가 동적 세그먼트보다 먼저
   * 매칭**되므로(React Router 순위 규칙) `write` 가 roomId 로 읽히지 않는다.
   */
  quizRoomWrite: '/community/quiz-rooms/write',
  /**
   * 퀴즈방 상세 · 풀이 · 결과. NavBar 없는 전체 화면이다.
   * **라우트 정의에만 쓰는 패턴**이고 실제 주소는 `quizRoomPath` 로 만든다.
   */
  quizRoom: '/community/quiz-rooms/:roomId',
  /** 내 게시글(블라인드 포함). 마이페이지 "활동 > 내 게시글"로 들어온다. */
  myCommunityPosts: '/my/posts',
  main: '/main',
  game: '/game',
  /**
   * 경기 상세(정보 · 라인업 · 경기 채팅). 경기 카드를 누르면 들어온다(NavBar 없는 전체 화면).
   * **라우트 정의에만 쓰는 패턴**이고, 실제 주소는 `gameDetailPath` 로 만든다.
   */
  gameDetail: '/game/:gameId',
  /** 데일리 퀴즈 풀이. 경기 상세의 "퀴즈 풀러 가기"로 들어온다(NavBar 없는 전체 화면). */
  quiz: '/quiz',
  /**
   * 퀴즈 결과(이닝별). 종료된 경기 상세의 "퀴즈 결과 확인하기"로 들어온다.
   *
   * **`QuizResultPageState` 를 반드시 넘겨야 한다** — 결과 조회가 경기 단위라
   * `gameId` 없이는 아무것도 부를 수 없다(2026-08-13 계약 교체).
   */
  quizResult: '/quiz-result',
  /**
   * 캐릭터 꾸미기(상점 겸 착용). 홈 히어로 왼쪽 아래의 옷 버튼으로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이고, 뒤로가기로 홈으로 돌아간다.
   */
  characterCustom: '/character',
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
   * 알림 설정(푸시 알림 수신 온·오프). 마이페이지 "설정 > 알림 설정"으로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   *
   * 여기서 끄고 켜는 값은 **앱이 읽어 가는 설정**이다(`utils/pushNotification.ts`) —
   * 알림을 보내는 것은 웹이 아니라 앱이라, 이 화면은 값만 남기고 앱에 알린다.
   */
  notificationSetting: '/my/notification',
  /**
   * 승리요정 스티커(인스타그램 스토리 공유). 홈 배너와 마이페이지 "설정 > 스토리 공유"로
   * 들어온다. 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   *
   * 어떤 스티커를 넘길지는 화면 안에서 고르므로 넘겨받는 state 가 없다.
   */
  storyShare: '/share',
  /**
   * 문의하기(자주 묻는 질문). 마이페이지 "센터 > 문의하기"로 들어온다.
   * 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다.
   */
  inquiry: '/inquiry',
  /**
   * 공지사항 목록. 마이페이지 "센터 > 공지사항"으로 들어온다.
   * 문의하기와 같이 NavBar 없는 전체 화면이다.
   */
  notice: '/notice',
  /**
   * 공지사항 한 건. **라우트 정의에만 쓰는 패턴**이고, 실제 주소는 `noticeDetailPath` 로 만든다.
   */
  noticeDetail: '/notice/:noticeId',
} as const;

/**
 * 보호 화면 → 로그인으로 넘길 값 — **로그인한 뒤 돌아갈 주소**.
 *
 * 홈·경기는 로그인 없이 둘러볼 수 있어(App Store 심사 5.1.1(v)), 로그인은 계정 기능
 * (라운지·마이·퀴즈·꾸미기 등)에 처음 들어가려 할 때 일어난다. 로그인을 마치면 메인이 아니라
 * 들어가려던 그 화면으로 이어 보내야 흐름이 끊기지 않는다.
 *
 * 라우터 state 는 주소를 직접 치고 들어오면 비어 있다 — 그때는 메인으로 간다.
 */
export interface LoginPageState {
  from: string;
}

/**
 * 라우터 state 에서 로그인 후 돌아갈 주소를 읽는다. 없거나 이상하면 메인.
 *
 * 앱 안의 경로(`/` 로 시작, `//` 는 아님)만 받는다 — 바깥 주소로 튕겨 나가는 길을 막는다.
 * 로그인·회원가입 자신으로 돌아가면 제자리걸음이라 그것도 메인으로 본다.
 */
export function readLoginRedirect(state: unknown): string {
  if (typeof state !== 'object' || state === null) return ROUTES.main;

  const from = (state as Partial<LoginPageState>).from;
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//')) {
    return ROUTES.main;
  }

  const pathname = from.split(/[?#]/)[0];
  return pathname === ROUTES.login || pathname === ROUTES.signup ? ROUTES.main : from;
}

/** 경기 상세로 가는 주소. `ROUTES.gameDetail` 의 `:gameId` 를 채운 값이다. */
export function gameDetailPath(gameId: string): string {
  return `/game/${encodeURIComponent(gameId)}`;
}

/**
 * 경기 카드 → 경기 상세로 넘길 값 — 누른 카드의 경기 그대로.
 *
 * 상세 화면은 주소의 `gameId` 만으로도 목록을 다시 받아 경기를 찾지만, 그 응답이 오기
 * 전까지 빈 화면이 되지 않도록 이미 들고 있는 값을 먼저 그린다. 이후 실시간 갱신은 목록 쪽이 덮는다.
 * 라우터 state 는 주소를 직접 치고 들어오면 비어 있다 — 받는 쪽은 없는 경우를 견뎌야 한다.
 */
export interface GameDetailPageState {
  game: Game;
}

/** 라우터 state 에서 경기를 읽는다. 형태가 맞지 않으면 `null`. */
export function readGameDetailPageState(state: unknown): Game | null {
  if (typeof state !== 'object' || state === null) return null;

  const game = (state as Partial<GameDetailPageState>).game;
  return typeof game === 'object' && game !== null && typeof game.gameId === 'string'
    ? game
    : null;
}

/** 라운지 게시판 탭 주소. 글 상세에서 뒤로 돌아올 때 랭킹이 아니라 게시판으로 온다. */
export const communityBoardPath = '/community?tab=board';

/** 라운지 퀴즈방 탭 주소. 퀴즈방에서 뒤로 돌아올 때 랭킹이 아니라 퀴즈방 목록으로 온다. */
export const communityQuizRoomsPath = '/community?tab=quiz';

/** 퀴즈방 상세로 가는 주소. `ROUTES.quizRoom` 의 `:roomId` 를 채운 값이다. */
export function quizRoomPath(roomId: number): string {
  return `/community/quiz-rooms/${roomId}`;
}

/** 게시글 상세로 가는 주소. `ROUTES.communityPost` 의 `:postId` 를 채운 값이다. */
export function communityPostPath(postId: number): string {
  return `/community/posts/${postId}`;
}

/** 게시글 수정으로 가는 주소. `ROUTES.communityEdit` 의 `:postId` 를 채운 값이다. */
export function communityEditPath(postId: number): string {
  return `/community/posts/${postId}/edit`;
}

/**
 * 글 상세 → 수정 화면으로 넘길 값 — 지금 보고 있는 글 그대로.
 *
 * ⚠️ 상세 조회(`GET /community/posts/{id}`)는 **조회수를 올리는 GET** 이라 수정 화면이 같은
 * 호출로 내용을 다시 받으면 안 된다(작성자 본인은 세지 않지만, 굳이 부를 이유가 없다).
 * 그래서 상세가 들고 있던 값을 그대로 넘긴다. 주소를 직접 쳐서 state 가 비면 수정 화면은
 * 상세로 돌려보낸다.
 */
export interface CommunityEditState {
  post: CommunityPostDetail;
}

/** 라우터 state 에서 수정할 글을 읽는다. 형태가 맞지 않으면 `null`. */
export function readCommunityEditState(
  state: unknown,
  postId: number,
): CommunityPostDetail | null {
  if (typeof state !== 'object' || state === null) return null;

  const post = (state as Partial<CommunityEditState>).post;
  return typeof post === 'object' && post !== null && post.postId === postId ? post : null;
}

/**
 * 글쓰기 · 수정 · 신고 · 삭제 뒤 이동하면서 실어 보내는 한 줄 안내.
 * 도착한 화면이 `NoticeSheet` 로 한 번 띄우고 history state 를 비운다(새로고침 때 다시 뜨지 않게).
 */
export interface CommunityNoticeState {
  notice: string;
}

/** 라우터 state 에서 안내 문구를 읽는다. 없으면 `null`. */
export function readCommunityNotice(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null;

  const notice = (state as Partial<CommunityNoticeState>).notice;
  return typeof notice === 'string' && notice.length > 0 ? notice : null;
}

/** 공지 한 건으로 가는 주소. `ROUTES.noticeDetail` 의 `:noticeId` 를 채운 값이다. */
export function noticeDetailPath(noticeId: string): string {
  return `/notice/${encodeURIComponent(noticeId)}`;
}

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
