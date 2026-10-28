import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type { BlockedUser, BlockUserRequest } from '../types/block';

/**
 * 차단 API (user 모듈).
 *
 * 두 엔드포인트 전부 **인증이 필수**라 모두 `requiresAuth: true`로 보낸다.
 * 차단 주체·조회 주체는 항상 access 토큰 principal 본인이므로 계정 식별자를 넘기지 않고,
 * 차단 대상은 언제나 닉네임(`targetNickname`)으로만 가리킨다(docs/block.md).
 *
 * 🚫 **차단 해제(unblock) API는 이 스코프에 없다.** 한번 만든 차단 행은 계정 하드 삭제
 * (탈퇴 30일 경과) CASCADE 전까지 지울 방법이 없다 — 여기 없는 걸 임의로 추가하지 않는다.
 */

/** ApiResponse로 감싸인 성공 응답의 `data`를 벗겨낸다(support·chat과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/**
 * 차단 도메인 실패 메시지.
 * 실 사용 여부는 불확실하지만 디버깅·테스트용으로 남겨 두고, 실제 화면에는 `isXxx` 판별을 쓰는 게 안전하다.
 */
export const BLOCK_ERROR_MESSAGE = {
  SELF_BLOCK_NOT_ALLOWED: '자기 자신은 차단할 수 없습니다.',
  BLOCK_TARGET_NOT_FOUND: '존재하지 않는 사용자입니다.',
} as const;

function isBlockError(error: unknown, status: number, message: string): boolean {
  return error instanceof ApiError && error.status === status && error.message === message;
}

/** 400 — 자기 자신을 차단하려는 시도. 판정 순서상 대상 존재·활성 확인 다음이다. */
export function isSelfBlockNotAllowed(error: unknown): boolean {
  return isBlockError(error, 400, BLOCK_ERROR_MESSAGE.SELF_BLOCK_NOT_ALLOWED);
}

/** 404 — `targetNickname`에 해당하는 활성 계정이 없음(탈퇴 포함). */
export function isBlockTargetNotFound(error: unknown): boolean {
  return isBlockError(error, 404, BLOCK_ERROR_MESSAGE.BLOCK_TARGET_NOT_FOUND);
}

/* ------------------------------------------------------------------ *
 * 엔드포인트 — 성공 시 ApiResponse 래핑(200)
 * ------------------------------------------------------------------ */

/**
 * POST /users/me/blocks — 대상 닉네임 차단.
 *
 * **최초 차단·이미 차단한 대상 재요청 모두 200**이다(멱등, 에러 아님) — 201로 고정하지 않는 것이
 * 승인된 계약이다. 판정 순서(고정 3단계): ①차단 주체 계정 조회 → ②`targetNickname`의 활성 계정
 * 조회(없으면 404) → ③자기 자신 여부(같으면 400). 셋을 통과하면 기존 차단이 없을 때만 새 행을 만든다.
 *
 * 응답의 `nickname`은 서버가 다시 조회해 확인한 값이다.
 * 에러: 400 SELF_BLOCK_NOT_ALLOWED, 404 BLOCK_TARGET_NOT_FOUND,
 * 400(fieldErrors.targetNickname — 빈 닉네임), 401 UNAUTHENTICATED.
 *
 * @param targetNickname 차단할 대상의 현재 닉네임.
 */
export function blockUser(targetNickname: string): Promise<BlockedUser> {
  const body: BlockUserRequest = { targetNickname };

  return userClient
    .post<ApiResponse<BlockedUser>>('/users/me/blocks', body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /users/me/blocks — 내가 차단한 대상 전원 조회(자신을 차단한 사람은 포함하지 않는다).
 *
 * **차단한 시각 오름차순**(먼저 차단한 대상이 앞)이며, 차단 이력이 없으면 빈 배열(200, 에러 아님).
 * 쿼리·바디 없음.
 * 에러: 401 UNAUTHENTICATED.
 */
export function getMyBlockedUsers(): Promise<BlockedUser[]> {
  return userClient
    .get<ApiResponse<BlockedUser[]>>('/users/me/blocks', { requiresAuth: true })
    .then(unwrap);
}
