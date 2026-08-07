import axios from 'axios';
import type { ApiErrorResponse, FieldErrors } from '../types/api';

/**
 * 401 응답 4종은 HTTP 상태(모두 401)만으로는 구분되지 않는다.
 * 백엔드 ErrorCode enum 이름은 응답에 오지 않으며, 프론트가 받는 건 `message` 문자열뿐이다.
 * 따라서 아래 message 상수와 정확히 문자열 비교해 종류를 판별한다.
 * (백엔드 메시지 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const AUTH_ERROR_MESSAGE = {
  /** 토큰 없음/무효/계정 소멸(탈퇴 포함). access 재발급 시도 신호. */
  UNAUTHENTICATED: '인증이 필요합니다.',
  /** 로그인 자격 오답 또는 탈퇴 계정. 로그인 폼 재입력 신호. */
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  /** refresh 서명/만료 무효 또는 access 토큰 오용. 재로그인 유도. */
  INVALID_REFRESH_TOKEN: '유효하지 않은 리프레시 토큰입니다.',
  /** DB에 없거나 만료된 refresh 토큰, 탈퇴 계정의 토큰. 재로그인 유도. */
  EXPIRED_REFRESH_TOKEN: '만료되었거나 이미 무효화된 리프레시 토큰입니다.',
} as const;

export type AuthErrorKind = keyof typeof AUTH_ERROR_MESSAGE | 'UNKNOWN';

/** message 문자열로 401 종류를 판별한다. 매칭 실패 시 'UNKNOWN'. */
export function resolveAuthErrorKind(message: string | null | undefined): AuthErrorKind {
  const entry = (Object.keys(AUTH_ERROR_MESSAGE) as Array<keyof typeof AUTH_ERROR_MESSAGE>).find(
    (key) => AUTH_ERROR_MESSAGE[key] === message,
  );
  return entry ?? 'UNKNOWN';
}

/** 에러 응답 본문이 UNAUTHENTICATED(재발급 시도 대상)인지 판정. */
export function isUnauthenticated(data: unknown): boolean {
  return (
    typeof data === 'object' &&
    data !== null &&
    (data as ApiErrorResponse).message === AUTH_ERROR_MESSAGE.UNAUTHENTICATED
  );
}

interface ApiErrorParams {
  message: string;
  status: number | null;
  fieldErrors: FieldErrors | null;
  isNetworkError: boolean;
  authKind: AuthErrorKind | null;
}

/**
 * 정규화된 에러. 네트워크/HTTP/도메인 에러를 상위 계층이 일관되게 다루도록 한 형태로 모은다.
 * - status: HTTP 상태(네트워크 에러면 null)
 * - fieldErrors: Bean Validation(400) 실패 시 필드별 메시지 맵, 그 외 null
 * - authKind: 401일 때 message로 판별한 종류, 그 외 null
 */
export class ApiError extends Error {
  readonly status: number | null;
  readonly fieldErrors: FieldErrors | null;
  readonly isNetworkError: boolean;
  readonly authKind: AuthErrorKind | null;

  constructor({ message, status, fieldErrors, isNetworkError, authKind }: ApiErrorParams) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.isNetworkError = isNetworkError;
    this.authKind = authKind;
    Object.setPrototypeOf(this, ApiError.prototype);
  }
}

/** 응답 없이 끝난 요청(네트워크/타임아웃/취소·스트림 끊김)용 에러. */
export function networkError(
  message = '네트워크 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
): ApiError {
  return new ApiError({
    message,
    status: null,
    fieldErrors: null,
    isNetworkError: true,
    authKind: null,
  });
}

/**
 * HTTP 상태 + ApiResponse 형태의 실패 본문을 ApiError로 만든다.
 * axios 경로(normalizeError)와 fetch 기반 SSE 경로가 같은 규칙을 쓰도록 공유한다.
 */
export function apiErrorFromResponse(
  status: number,
  body: ApiErrorResponse | null | undefined,
  fallbackMessage = '요청 처리 중 오류가 발생했습니다.',
): ApiError {
  const message = body?.message ?? fallbackMessage;
  const fieldErrors =
    body && body.data !== null && typeof body.data === 'object' ? (body.data as FieldErrors) : null;

  return new ApiError({
    message,
    status,
    fieldErrors,
    isNetworkError: false,
    authKind: status === 401 ? resolveAuthErrorKind(message) : null,
  });
}

/**
 * axios 에러/기타 에러를 ApiError로 정규화한다.
 * 실패 응답은 항상 ApiResponse 형태이므로 body.message / body.data를 신뢰해 파싱한다.
 */
export function normalizeError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const response = error.response;

    // 응답 자체가 없으면 네트워크/타임아웃/취소 계열.
    if (!response) {
      return networkError();
    }

    return apiErrorFromResponse(
      response.status,
      response.data as ApiErrorResponse | undefined,
      error.message,
    );
  }

  if (error instanceof Error) {
    return new ApiError({
      message: error.message,
      status: null,
      fieldErrors: null,
      isNetworkError: false,
      authKind: null,
    });
  }

  return new ApiError({
    message: '알 수 없는 오류가 발생했습니다.',
    status: null,
    fieldErrors: null,
    isNetworkError: false,
    authKind: null,
  });
}
