import type { AxiosResponse } from 'axios';
import { httpClient } from './httpClient';
import type { ApiResponse } from '../types/api';
import type {
  EmailSendCodeRequest,
  EmailVerifyRequest,
  LoginRequest,
  NicknameValidationRequest,
  NicknameValidationResponse,
  PasswordValidationRequest,
  PasswordValidationResponse,
  SignupRequest,
  TokenRequest,
  TokenResponse,
} from '../types/auth';

/**
 * ApiResponse로 감싸인 성공 응답의 `data`를 벗겨낸다.
 * validate/duplicate 엔드포인트는 항상 data가 존재하므로 non-null 단언한다.
 * (raw 응답 엔드포인트는 이 헬퍼를 쓰지 않고 res.data를 그대로 반환한다.)
 */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 사전 검사 — 성공 시 ApiResponse 래핑, 항상 200(정책 위반/중복도 200)
 * ------------------------------------------------------------------ */

/** POST /api/auth/password/validate — 항상 200. 결과는 반환값의 valid/message로만 판정. */
export function validatePassword(
  body: PasswordValidationRequest,
): Promise<PasswordValidationResponse> {
  return httpClient
    .post<ApiResponse<PasswordValidationResponse>>('/api/auth/password/validate', body)
    .then(unwrap);
}

/** POST /api/auth/nickname/validate — 정책+중복 2단 검사. 항상 200. */
export function validateNickname(
  body: NicknameValidationRequest,
): Promise<NicknameValidationResponse> {
  return httpClient
    .post<ApiResponse<NicknameValidationResponse>>('/api/auth/nickname/validate', body)
    .then(unwrap);
}

/** POST /api/auth/nickname/duplicate — 중복만 검사(정책 미검사). 항상 200. */
export function checkNicknameDuplicate(
  body: NicknameValidationRequest,
): Promise<NicknameValidationResponse> {
  return httpClient
    .post<ApiResponse<NicknameValidationResponse>>('/api/auth/nickname/duplicate', body)
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 이메일 인증 — 성공 시 ApiResponse<void>(data=null), 200
 * ------------------------------------------------------------------ */

/** POST /api/auth/email/send-code — 에러: 400(형식)/409(DUPLICATE_EMAIL)/429(EMAIL_SEND_COOLDOWN). */
export async function sendEmailCode(body: EmailSendCodeRequest): Promise<void> {
  await httpClient.post<ApiResponse<null>>('/api/auth/email/send-code', body);
}

/** POST /api/auth/email/verify — 에러: 400(EXPIRED/INVALID_VERIFICATION_CODE, ATTEMPTS_EXCEEDED). */
export async function verifyEmailCode(body: EmailVerifyRequest): Promise<void> {
  await httpClient.post<ApiResponse<null>>('/api/auth/email/verify', body);
}

/* ------------------------------------------------------------------ *
 * 인증/계정 — 성공 시 raw(ApiResponse 미래핑)
 * ------------------------------------------------------------------ */

/**
 * POST /api/auth/signup — 성공 시 raw boolean(201).
 * 에러: 400(검증/EMAIL_NOT_VERIFIED), 409(DUPLICATE_EMAIL/TEL/NICKNAME).
 */
export async function signup(body: SignupRequest): Promise<boolean> {
  const res = await httpClient.post<boolean>('/api/auth/signup', body);
  return res.data;
}

/**
 * POST /api/auth/login — 성공 시 raw TokenResponse(200).
 * 토큰 저장은 store-agent 소관이므로 여기서는 persist하지 않고 반환만 한다.
 * 에러: 401 INVALID_CREDENTIALS.
 */
export async function login(body: LoginRequest): Promise<TokenResponse> {
  const res = await httpClient.post<TokenResponse>('/api/auth/login', body);
  return res.data;
}

/**
 * POST /api/auth/refresh — 성공 시 raw TokenResponse(200), refresh도 rotate된다.
 * 주의: refreshToken을 본문에 싣는다(Authorization 헤더 아님).
 * 저장은 호출자(store-agent) 책임. 인터셉터의 자동 회전은 내부에서 별도로 저장한다.
 * 에러: 401 INVALID_REFRESH_TOKEN / EXPIRED_REFRESH_TOKEN.
 */
export async function refresh(body: TokenRequest): Promise<TokenResponse> {
  const res = await httpClient.post<TokenResponse>('/api/auth/refresh', body);
  return res.data;
}

/** POST /api/auth/logout — 204 무본문. 멱등(존재하지 않는 토큰도 성공). */
export async function logout(body: TokenRequest): Promise<void> {
  await httpClient.post<void>('/api/auth/logout', body);
}

/**
 * DELETE /api/users/me — 회원탈퇴. 유일한 인증 필요 요청(requiresAuth).
 * 성공 시 204 무본문. 에러: 401 UNAUTHENTICATED.
 */
export async function withdraw(): Promise<void> {
  await httpClient.delete<void>('/api/users/me', { requiresAuth: true });
}
