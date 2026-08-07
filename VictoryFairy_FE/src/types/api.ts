/**
 * 백엔드 공통 응답 래퍼.
 *
 * 이 API 모듈은 "성공은 비대칭, 실패는 항상 ApiResponse" 구조다.
 * - 성공 raw(ApiResponse 미래핑): signup(boolean, 201), login/refresh(TokenResponse, 200),
 *   logout(204 무본문), DELETE /users/me(204 무본문)
 * - 성공 래핑: password/nickname validate·duplicate(ApiResponse<...ValidationResponse>),
 *   email send-code/verify(ApiResponse<void> = data:null)
 * - 실패: BusinessException·Bean Validation 모두 ApiResponse
 */
export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  message: string | null;
}

/**
 * Bean Validation(400, MethodArgumentNotValidException) 실패 시
 * `data`에 담기는 "위반한 필드명 → 메시지" 맵.
 * 예: { "password": "비밀번호는 8~12자여야 합니다." }
 */
export type FieldErrors = Record<string, string>;

/**
 * 에러 응답 본문.
 * - BusinessException → data: null
 * - Bean Validation 실패 → data: FieldErrors, message: "입력값이 올바르지 않습니다."
 */
export type ApiErrorResponse = ApiResponse<FieldErrors>;

/** 본문 없는 성공 응답(email send-code/verify)의 ApiResponse 형태. data는 항상 null. */
export type EmptyApiResponse = ApiResponse<null>;
