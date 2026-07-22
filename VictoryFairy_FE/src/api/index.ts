/**
 * API 계층 공개 진입점.
 * 컴포넌트/스토어는 raw axios가 아니라 여기서 import한다.
 */

// axios 인스턴스(고급 사용/직접 요청용). 일반적으로는 authApi 함수를 쓴다.
export { httpClient } from './httpClient';

// 엔드포인트 함수
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
  withdraw,
} from './authApi';

// 토큰 저장 추상화 — store-agent가 setTokenStorage로 zustand persist 구현 주입
export { setTokenStorage, getTokenStorage } from './tokenStorage';
export type { TokenStorage } from './tokenStorage';

// 에러 정규화
export { ApiError, AUTH_ERROR_MESSAGE, resolveAuthErrorKind, normalizeError } from './errors';
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
