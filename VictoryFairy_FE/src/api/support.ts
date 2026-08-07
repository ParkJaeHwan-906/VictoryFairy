import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type {
  SupportPlayer,
  SupportPlayersRequest,
  SupportTeamRequest,
  SupportTeamSelection,
} from '../types/support';

/**
 * 응원 API (user 모듈).
 *
 * 세 엔드포인트 전부 **인증이 필수**라 모두 `requiresAuth: true` 로 보낸다.
 * 대상 계정은 본문·경로가 아니라 access 토큰으로만 정해지므로 계정 식별자를 넘기지 않는다
 * (account 의 `/users/me` 와 같은 방식).
 *
 * 이 도메인만의 계약 셋:
 * - **구단이 선수보다 앞선다.** 구단 미선택 상태에서 선수를 추가하면 404 가 아니라 400 이다.
 * - **삭제가 아니라 상태 전이다.** 취소는 행을 지우지 않고 취소 시각을 기록하므로
 *   `DELETE` 가 아니라 `PUT` 이고, 재선택 시 기존 행이 되살아난다. 그래서 전부 멱등하다.
 * - **응답은 언제나 반영 후 전체 상태다.** 방금 바꾼 항목만 오지 않으므로
 *   호출 뒤 `GET /users/me` 를 다시 부를 필요가 없다 — 반환값을 그대로 화면 상태에 반영한다.
 *
 * 🚨 **구단이 실제로 바뀌면 그 계정의 응원 선수가 전원 자동 취소된다 — 경고 없이.**
 * 이 계층은 그 사실을 알릴 방법이 없다(응답에 전용 필드가 없고 구단만 돌아온다).
 * `selectSupportTeam` 주석 참고 — 화면이 변경 전 고지와 선수 목록 비우기를 책임진다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/**
 * 활성 응원 선수 상한
 */
export const SUPPORT_PLAYER_MAX = 4;

/**
 * 응원 도메인 실패 메시지.
 * 실 사용 여부는 불확실하지만 디버깅·테스트용으로 남겨 두고, 실제 화면에는 `isSupport*` 판별을 쓰는 게 안전하다.
 */
export const SUPPORT_ERROR_MESSAGE = {
  TEAM_NOT_SELECTED: '응원하는 구단을 먼저 선택해 주세요.',
  PLAYER_TEAM_MISMATCH: '응원하는 구단 소속 선수만 선택할 수 있습니다.',
  PLAYER_LIMIT_EXCEEDED: '응원 선수는 최대 4명까지 선택할 수 있습니다.',
  TEAM_NOT_FOUND: '존재하지 않는 구단입니다.',
  PLAYER_NOT_FOUND: '존재하지 않는 선수입니다.',
} as const;

function isSupportError(error: unknown, status: number, message: string): boolean {
  return error instanceof ApiError && error.status === status && error.message === message;
}

/** 404 — 없는 `teamId`. 구단 목록을 다시 받아야 한다는 신호. */
export function isSupportTeamNotFound(error: unknown): boolean {
  return isSupportError(error, 404, SUPPORT_ERROR_MESSAGE.TEAM_NOT_FOUND);
}

/** 400 — 구단 미선택. 선수 선택 화면 이전(구단 선택)으로 돌려보내는 신호. */
export function isSupportTeamNotSelected(error: unknown): boolean {
  return isSupportError(error, 400, SUPPORT_ERROR_MESSAGE.TEAM_NOT_SELECTED);
}

/**
 * 400 — 응원 구단 소속이 아닌 선수 포함.
 */
export function isSupportPlayerTeamMismatch(error: unknown): boolean {
  return isSupportError(error, 400, SUPPORT_ERROR_MESSAGE.PLAYER_TEAM_MISMATCH);
}

/** 400 — 상한(`SUPPORT_PLAYER_MAX`) 초과. 부분 반영 없이 요청 전체가 거부된 상태다. */
export function isSupportPlayerLimitExceeded(error: unknown): boolean {
  return isSupportError(error, 400, SUPPORT_ERROR_MESSAGE.PLAYER_LIMIT_EXCEEDED);
}

/**
 * 404 — 없는 `playerId` 포함. 추가·취소 양쪽에서 난다.
 */
export function isSupportPlayerNotFound(error: unknown): boolean {
  return isSupportError(error, 404, SUPPORT_ERROR_MESSAGE.PLAYER_NOT_FOUND);
}

/**
 * 요청 본문의 `playerIds` 를 만든다.
 *
 * 서버도 중복을 제거하지만 여기서 미리 제거한다 — 상한 판정이 distinct 기준이라
 * 화면이 `SUPPORT_PLAYER_MAX` 와 비교하는 개수와 실제로 보내는 개수를 같게 맞춰 둔다.
 * 빈 배열은 걸러내지 않는다(오류가 아니라 "변경 없음 200" 이라 그대로 보낸다).
 */
function playersBody(playerIds: number[]): SupportPlayersRequest {
  return { playerIds: [...new Set(playerIds)] };
}

/* ------------------------------------------------------------------ *
 * 엔드포인트 — 성공 시 ApiResponse 래핑(200)
 * ------------------------------------------------------------------ */

/**
 * POST /support/team — 응원 구단 선택. 성공 시 ApiResponse 래핑(200).
 *
 * **최초 선택·변경·재선택을 이 하나가 모두 처리**하므로 호출 전에 현재 상태를 알 필요가 없다.
 * 같은 구단을 다시 보내면 아무 변경 없이 200(멱등), 과거에 응원하다 바꾼 구단을 다시 고르면
 * 새 행이 아니라 기존 이력이 재활성화된다.
 *
 * 🚨 **구단이 실제로 바뀌면 그 계정의 응원 선수가 전원 자동 취소된다.**
 * 응답에는 구단만 오고 이를 알리는 필드가 없으므로, 호출 측이 다음 둘을 책임져야 한다.
 * 1. 변경 **전에** "응원 선수 선택도 초기화됩니다" 고지
 * 2. 성공 후 화면·스토어의 응원 선수 목록 비우기(재조회가 필요하면 `GET /users/me`)
 * 같은 구단을 다시 선택한 경우에는 선수를 건드리지 않는다 — 즉 "바뀐 경우"에만 해당한다.
 *
 * @param teamId 응원할 구단 PK(`GET /teams` 의 `id`). 1~10 이 아니라 16~25 범위이므로 하드코딩 금지.
 */
export function selectSupportTeam(teamId: number): Promise<SupportTeamSelection> {
  const body: SupportTeamRequest = { teamId };

  return userClient
    .post<ApiResponse<SupportTeamSelection>>('/support/team', body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * POST /support/players — 응원 선수 추가. 성공 시 ApiResponse 래핑(200).
 *
 * **전체 교체가 아니라 추가**다. 요청에 없는 선수는 취소되지 않으므로,
 * 화면의 선택 상태를 그대로 보내면 해제한 선수가 남는다 — 해제는 `cancelSupportPlayers` 담당이다.
 *
 * 반환값은 이번에 추가한 선수만이 아니라 **현재 응원 중인 선수 전체**(`playerName` 오름차순)라
 * 별도 재조회 없이 그대로 화면 상태로 쓰면 된다. 이미 응원 중인 선수를 다시 보내도 결과가 같다(멱등).
 *
 * @param playerIds 추가할 선수 PK 목록. 중복은 여기서 제거해 보낸다.
 *                  빈 배열은 오류가 아니라 변경 없는 200 이다(왕복을 아끼려면 호출 측에서 건너뛴다).
 */
export function addSupportPlayers(playerIds: number[]): Promise<SupportPlayer[]> {
  return userClient
    .post<ApiResponse<SupportPlayer[]>>('/support/players', playersBody(playerIds), {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * PUT /support/players/oppose — 응원 선수 취소. 성공 시 ApiResponse 래핑(200).
 *
 * 행을 지우는 게 아니라 취소 상태로 전환하는 것이라 두 번 보내도 결과가 같다(멱등, 최초 취소 시각 보존).
 * `DELETE` 가 아니라 `PUT` 인 이유이며, 본문 형태는 추가와 동일하다.
 *
 * **응원 구단은 이 호출로 바뀌지 않는다** — 선수를 전원 취소해도 구단 응원은 유지된다
 * (구단은 필수라 취소 API 자체가 없고 `selectSupportTeam` 으로 변경만 가능하다).
 *
 * @param playerIds 취소할 선수 PK 목록. 중복은 여기서 제거해 보낸다.
 */
export function cancelSupportPlayers(playerIds: number[]): Promise<SupportPlayer[]> {
  return userClient
    .put<ApiResponse<SupportPlayer[]>>('/support/players/oppose', playersBody(playerIds), {
      requiresAuth: true,
    })
    .then(unwrap);
}
