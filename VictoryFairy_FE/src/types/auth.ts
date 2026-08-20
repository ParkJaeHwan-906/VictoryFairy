import type { ApiResponse } from './api';

/** 성별. 백엔드는 ORDINAL 저장(MALE=0, FEMALE=1)이지만 API 계약은 문자열이다. */
export type Gender = 'MALE' | 'FEMALE';

/* ------------------------------------------------------------------ *
 * 요청 DTO
 * ------------------------------------------------------------------ */

/** POST /auth/password/validate */
export interface PasswordValidationRequest {
  password: string;
}

/** POST /auth/nickname/validate 와 /auth/nickname/duplicate 가 공유하는 DTO */
export interface NicknameValidationRequest {
  nickname: string;
}

/** POST /auth/email/send-code */
export interface EmailSendCodeRequest {
  email: string;
}

/** POST /auth/email/verify — code는 6자리 숫자 문자열 */
export interface EmailVerifyRequest {
  email: string;
  code: string;
}

/** POST /auth/signup */
export interface SignupRequest {
  name: string;
  /** 숫자만 10~11자리 */
  tel: string;
  email: string;
  gender: Gender;
  nickname: string;
  /** 평문. 서버에서 BCrypt 인코딩 후 저장 */
  password: string;
  /**
   * 가입 전 업로드로 받은 EP(2026-08-20 신설, 선택). `temp/{uuid}.{jpg|png|webp}` 형태여야 한다.
   *
   * 생략·`null` 이면 사진 없는 계정이다. 모양이 어긋나거나 그 객체가 버킷에 없으면
   * **가입 자체가 400 으로 막힌다**(이미 가입에 쓴 EP 재사용도 여기서 걸린다).
   *
   * 반대로 형태·존재 검사를 통과한 뒤 S3 이동이 실패하면 **가입은 201 로 성공하고
   * 사진만 `null` 이 된다** — 사진 한 장 때문에 이메일 인증부터 다시 시키지 않으려는
   * 의도된 설계다. 그래서 화면은 가입 응답이 아니라 `GET /users/me` 를 정본으로 삼아야 한다.
   */
  profileImgUrl?: string | null;
}

/** POST /auth/login */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * POST /auth/refresh 와 /auth/logout 가 공유하는 DTO.
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

/**
 * `POST /auth/profile-image` 와 `POST /users/me/profile-image` 의 data.
 * 두 경로가 같은 모양이라 하나로 쓴다 — 다른 것은 저장 위치(접두사)뿐이다.
 */
export interface ProfileImageUploadResponse {
  /**
   * 저장된 객체의 **EP**(BaseURL 을 뺀 오브젝트 키).
   * 가입 전은 `temp/{uuid}.ext`, 가입 후는 `user-profile-img/{uuid}.ext`.
   * 이대로는 이미지 주소가 아니다 — `toAssetUrl()` 로 도메인을 붙여야 한다.
   */
  profileImgUrl: string;
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
