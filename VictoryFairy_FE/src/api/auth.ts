import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { multipartConfig } from './profileImage';
import { getAppId } from '../utils/appId';
import type { ApiResponse } from '../types/api';
import type {
  EmailSendCodeRequest,
  EmailVerifyRequest,
  LoginRequest,
  NicknameValidationRequest,
  NicknameValidationResponse,
  PasswordValidationRequest,
  PasswordValidationResponse,
  ProfileImageUploadResponse,
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

/** POST /auth/password/validate — 항상 200. 결과는 반환값의 valid/message로만 판정. */
export function validatePassword(
  body: PasswordValidationRequest,
): Promise<PasswordValidationResponse> {
  return userClient
    .post<ApiResponse<PasswordValidationResponse>>('/auth/password/validate', body)
    .then(unwrap);
}

/** POST /auth/nickname/validate — 정책+중복 2단 검사. 항상 200. */
export function validateNickname(
  body: NicknameValidationRequest,
): Promise<NicknameValidationResponse> {
  return userClient
    .post<ApiResponse<NicknameValidationResponse>>('/auth/nickname/validate', body)
    .then(unwrap);
}

/** POST /auth/nickname/duplicate — 중복만 검사(정책 미검사). 항상 200. */
export function checkNicknameDuplicate(
  body: NicknameValidationRequest,
): Promise<NicknameValidationResponse> {
  return userClient
    .post<ApiResponse<NicknameValidationResponse>>('/auth/nickname/duplicate', body)
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 이메일 인증 — 성공 시 ApiResponse<void>(data=null), 200
 * ------------------------------------------------------------------ */

/** POST /auth/email/send-code — 에러: 400(형식)/409(DUPLICATE_EMAIL)/429(EMAIL_SEND_COOLDOWN). */
export async function sendEmailCode(body: EmailSendCodeRequest): Promise<void> {
  await userClient.post<ApiResponse<null>>('/auth/email/send-code', body);
}

/** POST /auth/email/verify — 에러: 400(EXPIRED/INVALID_VERIFICATION_CODE, ATTEMPTS_EXCEEDED). */
export async function verifyEmailCode(body: EmailVerifyRequest): Promise<void> {
  await userClient.post<ApiResponse<null>>('/auth/email/verify', body);
}

/* ------------------------------------------------------------------ *
 * 인증/계정 — 성공 시 raw(ApiResponse 미래핑)
 * ------------------------------------------------------------------ */

/**
 * POST /auth/signup — 성공 시 raw boolean(201).
 * 에러: 400(검증/EMAIL_NOT_VERIFIED), 409(DUPLICATE_EMAIL/TEL/NICKNAME).
 */
export async function signup(body: SignupRequest): Promise<boolean> {
  const res = await userClient.post<boolean>('/auth/signup', body);
  return res.data;
}

/**
 * POST /auth/profile-image — 가입 전 프로필 이미지 업로드. 성공 시 ApiResponse 래핑(200).
 *
 * **계정이 아직 없는 시점**에 쓰는 경로다. 파일은 `temp/` 에만 올라가고 어떤 계정도
 * 바뀌지 않는다 — 받은 EP 를 `signup({ ..., profileImgUrl })` 에 실어야 계정에 붙는다.
 * 인증은 필요 없고, 토큰을 함께 보내도 동작이 달라지지 않는다.
 *
 * 반환값은 EP(`temp/{uuid}.ext`)다. 미리보기로 쓰려면 `toAssetUrl()` 로 도메인을 붙인다
 * (`temp/` 도 CDN 으로 읽힌다). 다만 **가입이 끝나면 이 EP 는 죽는다** — 서버가 파일을
 * 새 이름으로 옮기고 원본을 지우므로, 가입 후에는 `GET /users/me` 의 값을 써야 한다.
 *
 * 같은 EP 로 두 번 가입할 수는 없다(원본이 이미 지워져 400).
 *
 * 에러:
 * - 400 `isProfileImageRequired` / `isInvalidProfileImageFormat`, 400 앱 식별자 누락
 * - 413 `isProfileImageTooLarge` / 429 `isProfileImageUploadLimit`(appId 기준 30분 10회)
 * - 화면 문구는 `toProfileImageMessage()` 로 옮긴다.
 *
 * @param image JPEG · PNG · WebP, 최대 5MiB. 형식은 서버가 매직 넘버로 판정한다.
 * @param appId 이 기기의 앱 식별자. 한도를 세는 유일한 키라 기본값(저장된 값)을 그대로 쓴다.
 */
export async function uploadSignupProfileImage(
  image: File,
  appId: string = getAppId(),
): Promise<string> {
  const form = new FormData();
  form.append('appId', appId);
  form.append('image', image);

  const res = await userClient.post<ApiResponse<ProfileImageUploadResponse>>(
    '/auth/profile-image',
    form,
    multipartConfig(false),
  );

  return unwrap(res).profileImgUrl;
}

/**
 * POST /auth/login — 성공 시 raw TokenResponse(200).
 * 토큰 저장은 store-agent 소관이므로 여기서는 persist하지 않고 반환만 한다.
 * 에러: 401 INVALID_CREDENTIALS.
 */
export async function login(body: LoginRequest): Promise<TokenResponse> {
  const res = await userClient.post<TokenResponse>('/auth/login', body);
  return res.data;
}

/**
 * POST /auth/refresh — 성공 시 raw TokenResponse(200), refresh도 rotate된다.
 * 주의: refreshToken을 본문에 싣는다(Authorization 헤더 아님).
 * 저장은 호출자(store-agent) 책임. 인터셉터의 자동 회전은 내부에서 별도로 저장한다.
 * 에러: 401 INVALID_REFRESH_TOKEN / EXPIRED_REFRESH_TOKEN.
 */
export async function refresh(body: TokenRequest): Promise<TokenResponse> {
  const res = await userClient.post<TokenResponse>('/auth/refresh', body);
  return res.data;
}

/** POST /auth/logout — 204 무본문. 멱등(존재하지 않는 토큰도 성공). */
export async function logout(body: TokenRequest): Promise<void> {
  await userClient.post<void>('/auth/logout', body);
}
