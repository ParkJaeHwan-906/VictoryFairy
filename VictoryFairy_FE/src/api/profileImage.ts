import type { AxiosRequestConfig } from 'axios';
import { ApiError } from './errors';

/**
 * 프로필 이미지 업로드가 두 경로에서 함께 쓰는 것들.
 *
 * 엔드포인트는 도메인대로 나뉘어 있다 — 가입 전은 `auth.ts`(`POST /auth/profile-image`),
 * 가입 후는 `account.ts`(`POST /users/me/profile-image`). 하지만 **파일 규칙과 실패
 * 판별은 둘이 똑같아서**(같은 검증기·같은 예외 핸들러) 여기 한 곳에 둔다.
 *
 * ── 두 경로의 차이는 저장 위치와 확정 시점뿐이다 ──────────────────────
 * - 가입 전 — `temp/{uuid}.ext` 에 올라가고 어떤 계정도 바뀌지 않는다. 이 EP 를
 *   가입 요청(`SignupRequest.profileImgUrl`)에 실어야 비로소 계정에 붙는다.
 *   그때 서버가 `user-profile-img/{새 uuid}.ext` 로 옮기므로 **EP 문자열이 통째로
 *   바뀐다** — temp EP 를 프로필 사진 주소로 들고 있으면 안 된다(docs/account.md).
 * - 가입 후 — 처음부터 `user-profile-img/` 에 올라가고 **업로드가 곧 변경 확정**이다.
 *   확정·취소 단계가 없으므로 "저장" 버튼을 두려면 전송 자체를 그 버튼에 걸어야 한다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 서버가 받는 최대 크기(5MiB). 넘기면 413 이다. */
export const PROFILE_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/**
 * 서버가 받는 형식. **판정 근거는 확장자나 Content-Type 이 아니라 파일 선두의 매직
 * 넘버**라, 확장자만 바꾼 파일은 여기를 통과해도 서버에서 400 이다(HEIC 미지원).
 */
export const PROFILE_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;

/** `<input type="file">` 의 accept 값. 파일 선택창을 좁혀 줄 뿐 검증은 아니다. */
export const PROFILE_IMAGE_ACCEPT = PROFILE_IMAGE_MIME_TYPES.join(',');

/**
 * 프로필 이미지 실패 메시지.
 * 백엔드 ErrorCode 이름은 응답에 오지 않으므로 상태 코드 + message 문자열로 판별한다
 * (account · support 도메인과 같은 방식).
 */
export const PROFILE_IMAGE_ERROR_MESSAGE = {
  /** 400 PROFILE_IMAGE_REQUIRED — 파일 파트가 비었다. */
  REQUIRED: '프로필 이미지를 첨부해 주세요.',
  /** 400 INVALID_PROFILE_IMAGE_FORMAT — 선두 바이트가 JPEG·PNG·WebP 어느 것도 아니다. */
  INVALID_FORMAT: 'JPG, PNG, WEBP 이미지만 업로드할 수 있습니다.',
  /** 413 PROFILE_IMAGE_TOO_LARGE — 5MiB 초과. */
  TOO_LARGE: '이미지 크기는 5MB를 넘을 수 없습니다.',
  /** 429 PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED — 가입 전 경로에만 있다(appId 기준 30분 10회). */
  UPLOAD_LIMIT: '이미지 등록 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요.',
  /** 400 INVALID_APP_ID — 가입 전 경로에만 있다. */
  INVALID_APP_ID: '앱 식별자가 필요합니다.',
  /** 415 — 본문이 multipart/form-data 가 아니다. 우리 쪽 실수일 때만 난다. */
  UNSUPPORTED_CONTENT_TYPE: '지원하지 않는 요청 형식(Content-Type)입니다.',
  /**
   * 400 INVALID_PROFILE_IMAGE_ENDPOINT — **가입 요청**이 받은 temp EP 가 모양이 틀렸거나
   * 그 객체가 버킷에 없다(이미 가입에 쓴 EP 재사용 포함). 업로드가 아니라 가입이 막힌다.
   */
  INVALID_ENDPOINT: '유효하지 않은 프로필 이미지입니다.',
} as const;

function isImageError(error: unknown, status: number, message: string): boolean {
  return error instanceof ApiError && error.status === status && error.message === message;
}

/** 400 — 파일이 실리지 않았다. */
export function isProfileImageRequired(error: unknown): boolean {
  return isImageError(error, 400, PROFILE_IMAGE_ERROR_MESSAGE.REQUIRED);
}

/** 400 — JPEG·PNG·WebP 가 아니다(확장자를 바꿔도 매직 넘버로 걸린다). */
export function isInvalidProfileImageFormat(error: unknown): boolean {
  return isImageError(error, 400, PROFILE_IMAGE_ERROR_MESSAGE.INVALID_FORMAT);
}

/** 413 — 5MiB 초과. 400 이 아니라 413 이라 상태 코드로도 갈린다. */
export function isProfileImageTooLarge(error: unknown): boolean {
  return isImageError(error, 413, PROFILE_IMAGE_ERROR_MESSAGE.TOO_LARGE);
}

/** 429 — 가입 전 업로드 한도(appId 기준). 잠시 뒤 다시 시도하면 풀린다. */
export function isProfileImageUploadLimit(error: unknown): boolean {
  return isImageError(error, 429, PROFILE_IMAGE_ERROR_MESSAGE.UPLOAD_LIMIT);
}

/**
 * 400 — 가입이 거절됐다. 넘긴 temp EP 가 모양이 틀렸거나 객체가 이미 없다.
 *
 * **업로드 오류가 아니라 가입 오류다.** 사진을 다시 올려 받은 새 EP 로 다시 가입하면 된다.
 */
export function isInvalidProfileImageEndpoint(error: unknown): boolean {
  return isImageError(error, 400, PROFILE_IMAGE_ERROR_MESSAGE.INVALID_ENDPOINT);
}

/**
 * 업로드 실패를 화면에 띄울 한 줄로 옮긴다.
 *
 * 서버 문구를 그대로 쓰는 편이 낫다 — 무엇이 잘못됐는지(형식·크기·한도)를 이미 담고
 * 있어 프론트에서 새로 지어내면 되레 흐려진다. 다만 415·500 은 사용자가 손쓸 수 없는
 * 우리 쪽·서버 쪽 문제라 그대로 보여주지 않는다.
 */
export function toProfileImageMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status !== null && error.status >= 500) {
      return '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.';
    }

    if (error.status === 415) {
      return '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.';
    }

    return error.message;
  }

  return '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.';
}

/**
 * 보내기 전에 화면에서 먼저 거르는 검사. 통과하면 `null`.
 *
 * 서버 판정을 대신하지는 못한다(형식은 매직 넘버로 본다) — 왕복을 아끼고, 가입 전
 * 경로에서는 한도까지 아끼려는 것이다. 그래서 **확실히 아닌 것만** 막는다:
 * 형식을 알 수 없는 파일(`type` 이 빈 문자열)은 서버에 맡긴다.
 */
export function validateProfileImageFile(file: File): string | null {
  if (file.size === 0) {
    return PROFILE_IMAGE_ERROR_MESSAGE.REQUIRED;
  }

  if (file.size > PROFILE_IMAGE_MAX_BYTES) {
    return PROFILE_IMAGE_ERROR_MESSAGE.TOO_LARGE;
  }

  const mime = file.type as (typeof PROFILE_IMAGE_MIME_TYPES)[number];
  if (file.type.length > 0 && !PROFILE_IMAGE_MIME_TYPES.includes(mime)) {
    return PROFILE_IMAGE_ERROR_MESSAGE.INVALID_FORMAT;
  }

  return null;
}

/**
 * multipart 요청 설정.
 *
 * ⚠️ 클라이언트 기본 헤더가 `application/json` 이라 그대로 두면 **axios 가 FormData 를
 * JSON 으로 바꿔 보낸다** — `transformRequest` 가 Content-Type 이 json 이면
 * `formDataToJSON(data)` 를 돌린다(파일은 사라지고 서버는 415 를 준다).
 *
 * 그래서 헤더를 `null` 로 지운다. 지우면 axios 가 값이 `null` 인 헤더를 빼고 보내고,
 * 브라우저가 boundary 를 붙인 `multipart/form-data` 를 스스로 넣는다.
 * 문자열로 `'multipart/form-data'` 를 직접 넣으면 **boundary 가 빠져** 서버가 파싱하지
 * 못하므로, 반드시 지우는 쪽이어야 한다.
 */
export function multipartConfig(requiresAuth: boolean): AxiosRequestConfig {
  return {
    requiresAuth,
    headers: { 'Content-Type': null },
  };
}
