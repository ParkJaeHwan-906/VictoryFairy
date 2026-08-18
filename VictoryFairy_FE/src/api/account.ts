import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { getTokenStorage } from './tokenStorage';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type { TokenResponse } from '../types/auth';
import type {
  ChangeNicknameRequest,
  ChangePasswordRequest,
  MyProfile,
  NicknameChangeCooldown,
} from '../types/account';

/**
 * 계정 API (user 모듈).
 *
 * `/auth/*` 가 인증 절차(로그인·토큰 회전·가입 전 검사)를 다루는 것과 달리,
 * 여기는 로그인한 본인의 계정 리소스(`/users/me`)를 다룬다. 리소스가 다르므로
 * auth 와 파일을 나눈다.
 *
 * 네 엔드포인트 모두 대상을 URL 이 아니라 **access 토큰으로만** 식별한다.
 * 그래서 경로에 식별자가 없고(탈퇴는 파라미터도 0개) 대신 인증이 필수다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 도메인 에러 판별
 * ------------------------------------------------------------------ */

/**
 * 프로필 수정 실패 메시지.
 * 백엔드 ErrorCode 이름은 응답에 오지 않으므로 상태 코드 + message 문자열로만 구분한다
 * (support 도메인과 같은 방식). 화면에서는 아래 `is*` 판별을 쓰는 게 안전하다.
 */
export const ACCOUNT_ERROR_MESSAGE = {
  /** 400 — 지금 쓰는 닉네임 그대로. 409 "이미 사용 중"이 **아니다**. */
  SAME_AS_CURRENT_NICKNAME: '현재 닉네임과 다른 닉네임을 사용해 주세요.',
  /** 409 — 다른 계정(탈퇴 계정 포함)이 점유 중. */
  DUPLICATE_NICKNAME: '이미 사용 중인 닉네임입니다.',
  /** 429 — 마지막 변경으로부터 30일 미경과. */
  NICKNAME_CHANGE_COOLDOWN: '닉네임은 30일에 한 번만 변경할 수 있습니다.',
  /** 400 — 현재 비밀번호 불일치·누락. 401 이 아니라 400 이라 401 인터셉터를 타지 않는다. */
  INVALID_CURRENT_PASSWORD: '현재 비밀번호가 올바르지 않습니다.',
  /** 400 — 새 비밀번호가 현재 비밀번호와 같음. */
  SAME_AS_CURRENT_PASSWORD: '현재 비밀번호와 다른 비밀번호를 사용해 주세요.',
} as const;

function isAccountError(error: unknown, status: number, message: string): boolean {
  return error instanceof ApiError && error.status === status && error.message === message;
}

/** 400 — 현재 닉네임과 동일. "이미 사용 중"(409)과 문구가 다르니 따로 안내한다. */
export function isSameAsCurrentNickname(error: unknown): boolean {
  return isAccountError(error, 400, ACCOUNT_ERROR_MESSAGE.SAME_AS_CURRENT_NICKNAME);
}

/** 409 — 타 계정이 점유한 닉네임. 다른 닉네임을 받아야 한다는 신호. */
export function isDuplicateNickname(error: unknown): boolean {
  return isAccountError(error, 409, ACCOUNT_ERROR_MESSAGE.DUPLICATE_NICKNAME);
}

/**
 * 429 — 닉네임 변경 쿨다운(30일).
 *
 * 판정 순서가 `길이 → 문자 → 자기 동일 → 타 계정 점유 → 쿨다운` 이라
 * **쿨다운 중이어도 이미 점유된 닉네임을 보내면 429 가 아니라 409 다.**
 * 즉 429 는 "형식 통과 + 미점유 + 쿨다운 중"일 때만 나온다.
 */
export function isNicknameChangeCooldown(error: unknown): boolean {
  return isAccountError(error, 429, ACCOUNT_ERROR_MESSAGE.NICKNAME_CHANGE_COOLDOWN);
}

/**
 * 쿨다운(429) 에러에서 다음 변경 가능 시각을 꺼낸다. 그 외 에러면 `null`.
 *
 * 실패 본문의 `data` 가 객체라 정규화 과정에서 `fieldErrors` 자리에 담긴다
 * (Bean Validation 맵과 같은 칸을 쓰지만 의미는 다르다 — 그래서 이 헬퍼로 감싼다).
 *
 * @returns `+09:00` 오프셋을 포함한 ISO-8601 문자열. `new Date()` 로 바로 파싱된다.
 */
export function getNicknameChangeableAt(error: unknown): string | null {
  if (!isNicknameChangeCooldown(error)) {
    return null;
  }

  const data = (error as ApiError).fieldErrors as NicknameChangeCooldown | null;
  return data?.nextChangeableAt ?? null;
}

/** 400 — 현재 비밀번호가 틀렸거나 비어 있음. 폼의 "현재 비밀번호" 칸에 붙일 오류다. */
export function isInvalidCurrentPassword(error: unknown): boolean {
  return isAccountError(error, 400, ACCOUNT_ERROR_MESSAGE.INVALID_CURRENT_PASSWORD);
}

/** 400 — 새 비밀번호가 현재 것과 동일. */
export function isSameAsCurrentPassword(error: unknown): boolean {
  return isAccountError(error, 400, ACCOUNT_ERROR_MESSAGE.SAME_AS_CURRENT_PASSWORD);
}

/* ------------------------------------------------------------------ *
 * 엔드포인트
 * ------------------------------------------------------------------ */

/**
 * GET /users/me — 내 프로필 조회(닉네임 · 응원 구단 · 응원 선수 · 포인트 · 누적 점수).
 * 읽기 전용이라 어떤 행도 만들거나 고치지 않는다. 성공 시 ApiResponse 래핑(200).
 *
 * 온보딩 중간 상태도 오류가 아니다 — 구단 미선택이면 `supportTeam: null`,
 * 응원 선수가 없으면 `supportPlayers: []`, 누적 점수 행이 없으면 `bqScore: 0` 으로 그냥 200 이 온다.
 *
 * 에러: 401 UNAUTHENTICATED(토큰 없음·무효, refresh 토큰 오용, 탈퇴 계정).
 * 참고: prod 는 user 앱이 관련 스키마를 만들기 전까지 500 을 낸다(배포 이슈, 계약 아님).
 */
export function getMyProfile(): Promise<MyProfile> {
  return userClient.get<ApiResponse<MyProfile>>('/users/me', { requiresAuth: true }).then(unwrap);
}

/**
 * PATCH /users/me/nickname — 닉네임 변경. 성공 시 204 무본문.
 *
 * **변경된 닉네임이 응답에 실리지 않는다** — 화면에 최신 값이 필요하면 보낸 값을 그대로 쓰거나
 * `getMyProfile()` 을 다시 부른다.
 *
 * 판정은 `①길이 → ②문자 구성 → ③현재 닉네임과 동일 → ④타 계정 점유 → ⑤쿨다운(30일)`
 * 순서로 **첫 위반 하나만** 돌아온다. 정책(①②)은 회원가입과 같으므로
 * `validateNickname()` 으로 미리 걸러 왕복을 줄일 수 있다.
 *
 * 에러:
 * - 400 Bean Validation — `error.fieldErrors.nickname` 에 사유 문자열(길이/허용 문자)
 * - 400 `isSameAsCurrentNickname` / 409 `isDuplicateNickname` / 429 `isNicknameChangeCooldown`
 *   (429 의 다음 변경 가능 시각은 `getNicknameChangeableAt()` 로 꺼낸다)
 * - 401 UNAUTHENTICATED
 *
 * @param nickname 새 닉네임. 1~10자, 한글·영문·숫자만.
 */
export async function changeNickname(nickname: string): Promise<void> {
  const body: ChangeNicknameRequest = { nickname };

  await userClient.patch<void>('/users/me/nickname', body, { requiresAuth: true });
}

/**
 * PATCH /users/me/password — 비밀번호 변경. 성공 시 ApiResponse 래핑(200).
 *
 * 🔐 **성공 즉시 그 이전에 발급된 access·refresh 토큰이 전부 무효화된다.**
 * 서버가 변경 기준 시각을 기록하고 이후 모든 요청에서 access 토큰의 `iat` 를 대조하므로,
 * 남은 유효기간과 무관하게 옛 토큰은 그 순간부터 401 이고 refresh 재발급도 401 이다.
 * 응답으로 받은 토큰쌍만이 이 대조를 통과하는 **유일하게 보장된 토큰**이라,
 * 저장을 호출자에게 미루면 그 사이 요청이 전부 401 로 죽고 자동 회전으로도 못 살린다.
 *
 * 그래서 이 함수만은 `login`/`refresh` 와 달리 **받은 토큰을 곧바로 TokenStorage 에 저장**한다
 * (인터셉터의 `rotateTokens` 와 같은 방식). 그러고도 토큰쌍을 반환하니
 * 스토어가 자체 상태를 함께 갱신하고 싶으면 반환값을 쓰면 된다.
 *
 * 닉네임과 달리 **쿨다운이 없다** — 유출 대응(즉시 교체)을 막지 않기 위한 의도된 비대칭이다.
 *
 * 판정은 `①새 비밀번호 형식 → ②현재 비밀번호 일치 → ③신·구 동일` 순서로 첫 위반 하나만 돌아온다.
 *
 * 에러:
 * - 400 Bean Validation — `error.fieldErrors.newPassword` 에 사유 문자열(길이/문자 구성)
 * - 400 `isInvalidCurrentPassword`(현재 비밀번호 오타는 401 이 아니라 400 이라
 *   401 인터셉터가 로그아웃을 유발하지 않는다) / 400 `isSameAsCurrentPassword`
 * - 401 UNAUTHENTICATED
 */
export async function changePassword(body: ChangePasswordRequest): Promise<TokenResponse> {
  const tokens = await userClient
    .patch<ApiResponse<TokenResponse>>('/users/me/password', body, { requiresAuth: true })
    .then(unwrap);

  getTokenStorage().setTokens(tokens);
  return tokens;
}

/**
 * DELETE /users/me — 회원탈퇴(soft delete). 성공 시 204 무본문.
 *
 * 즉시 확정되어 유예 기간도 취소도 없고, 그 계정의 refresh 토큰은 모두 만료된다.
 * 호출 성공 후에는 토큰을 반드시 비워야 한다 — 남은 access 토큰으로 다시 부르면
 * 유효기간과 무관하게 401 이다.
 *
 * 에러: 401 UNAUTHENTICATED.
 */
export async function withdraw(): Promise<void> {
  await userClient.delete<void>('/users/me', { requiresAuth: true });
}
