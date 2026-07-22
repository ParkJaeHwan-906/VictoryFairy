import type { ApiResponse } from './api';

/** 성별. 백엔드는 ORDINAL 저장(MALE=0, FEMALE=1)이지만 API 계약은 문자열이다. */
export type Gender = 'MALE' | 'FEMALE';

/* ------------------------------------------------------------------ *
 * 요청 DTO
 * ------------------------------------------------------------------ */

/** POST /api/auth/password/validate */
export interface PasswordValidationRequest {
  password: string;
}

/** POST /api/auth/nickname/validate 와 /api/auth/nickname/duplicate 가 공유하는 DTO */
export interface NicknameValidationRequest {
  nickname: string;
}

/** POST /api/auth/email/send-code */
export interface EmailSendCodeRequest {
  email: string;
}

/** POST /api/auth/email/verify — code는 6자리 숫자 문자열 */
export interface EmailVerifyRequest {
  email: string;
  code: string;
}

/** POST /api/auth/signup */
export interface SignupRequest {
  name: string;
  /** 숫자만 10~11자리 */
  tel: string;
  email: string;
  gender: Gender;
  nickname: string;
  /** 평문. 서버에서 BCrypt 인코딩 후 저장 */
  password: string;
}

/** POST /api/auth/login */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * POST /api/auth/refresh 와 /api/auth/logout 가 공유하는 DTO.
 * 주의: refresh 토큰을 Authorization 헤더가 아니라 요청 본문에 싣는다.
 */
export interface TokenRequest {
  refreshToken: string;
}

/* ------------------------------------------------------------------ *
 * 응답 DTO
 * ------------------------------------------------------------------ */

/** password/validate 의 data */
export interface PasswordValidationResponse {
  valid: boolean;
  message: string;
}

/** nickname/validate · nickname/duplicate 의 data */
export interface NicknameValidationResponse {
  valid: boolean;
  message: string;
}

/** login · refresh 의 raw 응답 본문(ApiResponse 미래핑) */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
}

/* ------------------------------------------------------------------ *
 * 래핑된 응답 별칭 — 성공도 ApiResponse로 감싸는 엔드포인트용
 * ------------------------------------------------------------------ */

export type PasswordValidationApiResponse = ApiResponse<PasswordValidationResponse>;
export type NicknameValidationApiResponse = ApiResponse<NicknameValidationResponse>;
