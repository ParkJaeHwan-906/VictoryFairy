import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type { Game, TeamLineUp } from '../types/game';

/**
 * 경기 API (user 모듈).
 *
 * ── 인증이 경로마다 갈린다 ─────────────────────────────────────────
 * `/games` · `/games/lineup` 은 구단·선수와 같은 **공개 참조 데이터**라 GET 한정으로
 * 열려 있고 인증이 없다. 이 둘에 `requiresAuth` 를 붙이면 안 된다 — 토큰이 낭비될 뿐
 * 아니라, 401 이 나면 httpClient 의 refresh 회전 분기를 타서 저장된 토큰을 비워버린다.
 *
 * 반면 **`/games/support` 는 인증이 필수다**(2026-08-13 신규). 같은 `/games` 접두사
 * 아래에서 인증 정책이 갈리는 첫 사례라, 경로만 보고 짐작하지 말고 각 함수의 주석을 봐야 한다.
 *
 * 세 엔드포인트 모두 페이징이 없고, 빈 배열(`[]`)은 오류가 아니라 정상 200 이다.
 *
 * `/games/lineup` 은 `/games` 의 하위 경로지만 백엔드에서 공개 규칙이 따로 걸려 있다
 * (`/games` 의 공개 매처가 정확 경로 매칭이라 하위 경로를 커버하지 못한다).
 * 즉 둘은 서로의 공개 여부를 보장하지 않는다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * 경기 도메인 실패 메시지.
 * 백엔드 ErrorCode 이름은 응답에 오지 않고 프론트가 받는 건 `message` 문자열뿐이라
 * auth·chat 과 같이 문자열로 판별한다. (백엔드 문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const GAME_ERROR_MESSAGE = {
  GAME_NOT_FOUND: '존재하지 않는 경기입니다.',
} as const;

/**
 * 404 — 없는 `gameId`. 내부 PK 를 넣었거나 `?gameId=` 처럼 **값이 빈** 경우도 여기로 온다.
 *
 * `gameId` 파라미터 자체를 빠뜨린 것은 400 이라 이 판별에 걸리지 않는다(아래 `getLineUp` 참고).
 */
export function isGameNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.message === GAME_ERROR_MESSAGE.GAME_NOT_FOUND
  );
}

/**
 * GET /games — 날짜별 경기 목록. 성공 시 ApiResponse 래핑(200), 경기 시각 오름차순 고정.
 *
 * @param date `yyyy-MM-dd`. 생략하면 서버가 정한 "오늘"이 된다.
 *
 * **화면이 날짜를 알고 있다면 항상 넘겨야 한다.** 서버의 "오늘"은 `Asia/Seoul` 기준이라
 * 클라이언트 시간대·기기 시계와 어긋날 수 있고, 자정 경계에서는 같은 화면이 요청 시점에 따라
 * 다른 응답을 받는다. 생략은 편의 기능이지 권장 사용법이 아니다.
 *
 * 그 날짜에 경기가 없으면 빈 배열(200)이며 오류가 아니다.
 * 응답의 `stadium` 은 구장 미정 시 `null` 이므로 표기 처리가 필요하다.
 *
 * `cancelReason` 도 같은 성질이다 — 값이 없을 때 서버가 표시 문구로 채워 주지 않는다.
 * 서버가 채우면 "사유를 아는 경우"와 "모르는 경우"의 구분이 응답에서 사라지기 때문에 일부러 안 채운다.
 * 그래서 기본 문구 fallback 은 이 계층이 아니라 표시 계층(`getGameStateDisplay`)의 몫이다.
 *
 * `inning`·`inningHalf`(2026-08-11 신설)는 **지금 항상 `null` 이다** — 컬럼만 생겼고 값을
 * 채우는 수집기 구현이 아직 없다. 진행 중 경기라도 이닝을 못 받는다고 보고 화면을 짜야 한다.
 *
 * 형식이 어긋나면(`20260801`, `2026-13-01` 등) 400 인데, **이 400 만 `{success, data, message}`
 * 형식이 아니라 스프링 기본 오류 응답이 그대로 나간다** — `ApiError.message` 가 도메인 문구가
 * 아닐 수 있으니 문자열로 판별하지 말고 status 로만 다룬다.
 */
export function getGameList(date?: string): Promise<Game[]> {
  return userClient
    .get<ApiResponse<Game[]>>('/games', { params: date ? { date } : undefined })
    .then(unwrap);
}

/**
 * GET /games/support — 내 응원 구단이 홈 또는 원정으로 뛰는 경기만 걸러서 반환.
 * 성공 시 ApiResponse 래핑(200). **인증 필수**(2026-08-13 신규).
 *
 * @param date `yyyy-MM-dd`. 생략 규칙·경고는 `getGameList` 와 완전히 같다.
 *
 * 같은 날짜 `GET /games` 응답의 **부분집합**이다 — 겹치는 `gameId` 의 값이 전부 같고,
 * 이 경로 전용 필드·가공값은 없다. **어느 쪽이 내 구단인지도 알려주지 않으므로**
 * `homeTeamId`/`awayTeamId` 를 `GET /users/me` 의 `supportTeam.id` 와 대조해야 한다.
 *
 * `gameState` 로 거르지 않는다 — 취소·종료 경기도 그대로 들어온다.
 *
 * ⚠️ **빈 배열이 두 가지 뜻을 겸한다** — "그 날 응원 구단 경기가 없다"와 "응원 구단을
 * 고른 적이 없다(또는 취소했다)"가 똑같은 `[]` 200 으로 온다. 전용 오류 코드는 없다.
 * 둘을 갈라 안내해야 하는 화면은 이 응답이 아니라 `GET /users/me` 의 `supportTeam` 이
 * `null` 인지로 판별해야 한다.
 *
 * 에러: 401(토큰 없음·위조·만료·refresh 토큰 오용·탈퇴 계정을 구분하지 않는다).
 * 날짜 형식 오류 400 은 `getGameList` 와 같이 ApiResponse 형식이 아니다.
 */
export function getSupportGameList(date?: string): Promise<Game[]> {
  return userClient
    .get<ApiResponse<Game[]>>('/games/support', {
      params: date ? { date } : undefined,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /games/lineup — 경기 1건의 선발 라인업을 홈·원정 두 팀 함께 반환. 성공 시 ApiResponse 래핑(200).
 *
 * @param gameId `GET /games` 응답의 `gameId`(네이버 자연키 문자열).
 *
 * **내부 PK 가 아니다.** 내부 PK 를 넣으면 항상 404 다.
 *
 * 반환 배열은 `teamId` 오름차순이라 **순서로 홈/원정을 판단할 수 없다.**
 * 서버가 홈/원정을 판정하지 않으므로 호출자가 `Game.homeTeamId`/`awayTeamId` 와 대조해야 한다.
 *
 * 경기는 있으나 아직 선발이 공시되지 않았으면 빈 배열(200)이며 오류가 아니다 —
 * "없는 경기"(404)와 구분해서 표시해야 한다.
 *
 * 에러: 404 GAME_NOT_FOUND(`isGameNotFound`). `?gameId=` 처럼 값이 비어도 400 이 아니라 404 다.
 * 반면 `gameId` 파라미터를 아예 빠뜨리면 400 이고 그 응답만 ApiResponse 형식이 아니다 —
 * 이 함수는 인자를 필수로 받으므로 정상 호출에서는 발생하지 않는다.
 */
export function getLineUp(gameId: string): Promise<TeamLineUp[]> {
  return userClient
    .get<ApiResponse<TeamLineUp[]>>('/games/lineup', { params: { gameId } })
    .then(unwrap);
}
