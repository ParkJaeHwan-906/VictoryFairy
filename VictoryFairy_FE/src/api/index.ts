/**
 * API 계층 공개 진입점.
 * 컴포넌트/스토어는 raw axios가 아니라 여기서 import한다.
 */

// 모듈별 base URL — 엔드포인트 함수는 이 base 기준 상대 경로를 쓴다.
export { USER_BASE_URL, GAME_BASE_URL } from './config';

// 모듈별 axios 인스턴스(고급 사용/직접 요청용). 일반적으로는 아래 엔드포인트 함수를 쓴다.
export { userClient, gameClient } from './httpClient';

// 인증(user 모듈) 엔드포인트 함수
export {
  validatePassword,
  validateNickname,
  checkNicknameDuplicate,
  sendEmailCode,
  verifyEmailCode,
  signup,
  login,
  refresh,
  logout,
} from './auth';

// 계정(user 모듈) 엔드포인트 함수
export { getMyProfile, withdraw } from './account';

// 구단(user 모듈) 엔드포인트 함수 — 인증 없이 호출한다
export { getTeamList } from './team';

// 경기(user 모듈) 엔드포인트 함수 — 인증 없이 호출한다
export { getGameList, getLineUp, isGameNotFound, GAME_ERROR_MESSAGE } from './game';

// 선수(user 모듈) 엔드포인트 함수 — 인증 없이 호출한다
export { getPlayerList } from './player';

// 응원(user 모듈) 엔드포인트 함수 — 세 개 전부 인증이 필수다
export {
  selectSupportTeam,
  addSupportPlayers,
  cancelSupportPlayers,
  isSupportTeamNotFound,
  isSupportTeamNotSelected,
  isSupportPlayerTeamMismatch,
  isSupportPlayerLimitExceeded,
  isSupportPlayerNotFound,
  SUPPORT_ERROR_MESSAGE,
  SUPPORT_PLAYER_MAX,
} from './support';

// 채팅(game 모듈) 엔드포인트 함수
export {
  getChatRooms,
  getChatRoom,
  findMyTeamChatRoom,
  subscribeChatRoom,
  leaveChatRoom,
  sendChatMessage,
  getChatMessages,
  reportChatMessage,
  validateChatMessageContent,
  isChatRoomNotFound,
  isChatMessageNotFound,
  isSelfReport,
  isSupportTeamRequired,
  isChatTeamMismatch,
  CHAT_ERROR_MESSAGE,
  CHAT_MESSAGE_PAGE_SIZE,
  CHAT_MESSAGE_MAX_LENGTH,
} from './chat';
export type { ChatSubscription, ChatSubscriptionHandlers } from './chat';

// 데일리 퀴즈(quiz 모듈 — 채팅과 같은 `/rt` base) 엔드포인트 함수 — 네 개 전부 인증이 필수다
export {
  getTodayQuizzes,
  getQuiz,
  submitQuiz,
  getQuizSubmissions,
  isQuizNotFound,
  isQuizAlreadySubmitted,
  isQuizOptionNotFound,
  isQuizOptionMissing,
  QUIZ_ERROR_MESSAGE,
  QUIZ_SUBMISSION_PAGE_SIZE,
  OX_OPTION_NO,
} from './quiz';

// 토큰 저장 추상화 — store-agent가 setTokenStorage로 zustand persist 구현 주입
export { setTokenStorage, getTokenStorage } from './tokenStorage';
export type { TokenStorage } from './tokenStorage';

// 에러 정규화
export {
  ApiError,
  AUTH_ERROR_MESSAGE,
  resolveAuthErrorKind,
  normalizeError,
  apiErrorFromResponse,
  networkError,
} from './errors';
export type { AuthErrorKind } from './errors';

// 타입 재노출(편의)
export type {
  Gender,
  PasswordValidationRequest,
  NicknameValidationRequest,
  EmailSendCodeRequest,
  EmailVerifyRequest,
  SignupRequest,
  LoginRequest,
  TokenRequest,
  PasswordValidationResponse,
  NicknameValidationResponse,
  TokenResponse,
} from '../types/auth';
export type { ApiResponse, FieldErrors, ApiErrorResponse } from '../types/api';
export type { MyProfile, SupportTeam } from '../types/account';
export type { Team } from '../types/team';
export type {
  Game,
  GameState,
  PositionName,
  TeamLineUp,
  LineUpPitcher,
  LineUpBatter,
} from '../types/game';
export type { Player, PlayerPosition, PlayerListParams } from '../types/player';
export type {
  SupportTeamRequest,
  SupportPlayersRequest,
  SupportTeamSelection,
  SupportPlayer,
} from '../types/support';
export type {
  ChatRoom,
  ChatMessage,
  ChatMessageEvent,
  ChatMessagePage,
  SendChatMessageRequest,
} from '../types/chat';
export type {
  QuizType,
  QuizDifficulty,
  QuizOption,
  DailyQuiz,
  QuizDetail,
  UnsolvedQuizDetail,
  SolvedQuizDetail,
  QuizSubmitRequest,
  QuizSubmitResult,
  QuizSubmission,
  QuizSubmissionSummary,
  QuizSubmissionPage,
  QuizSubmissionHistory,
} from '../types/quiz';
