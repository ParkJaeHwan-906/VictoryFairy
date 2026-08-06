import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import type { ApiResponse } from '../types/api';
import type { Player, PlayerListParams } from '../types/player';

/**
 * 선수 API (user 모듈).
 *
 * 구단·경기와 같은 **공개 참조 데이터**라 GET 한정으로 열려 있고 인증이 없다.
 * `requiresAuth` 를 붙이면 안 된다 — team·game 과 같은 이유이며, 여기서는 이유가 하나 더 있다.
 *
 * **Authorization 헤더 유무로 같은 URL 의 결과가 갈린다.** 유효한 access 토큰 계정에
 * 활성 응원 구단이 있으면 서버가 요청의 `teamId` 를 조용히 무시하고 응원 구단으로 필터링한다
 * (400·403 이 아니라 200 이고, 오버라이딩을 알리는 전용 필드도 없다).
 * `requiresAuth` 를 붙이지 않는 한 이 클라이언트는 토큰을 싣지 않으므로
 * **`teamId` 는 항상 보낸 그대로 적용된다** — 화면이 예측 가능한 쪽이다.
 * 응원 구단 자동 필터가 필요해지면 이 파일만 고칠 게 아니라 백엔드와 계약을 합의하고 시작한다.
 *
 * 페이징이 없고, 빈 배열(`[]`)은 오류가 아니라 정상 200 이다 —
 * 검색 결과 없음도, 존재하지 않는 `teamId` 도 404 가 아니라 빈 배열이다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * GET /players — 선수 목록 조회 · 이름 검색. 성공 시 ApiResponse 래핑(200).
 *
 * @param params `teamId`(구단 필터) · `name`(이름 부분 일치). 둘 다 선택이고 함께 주면 AND.
 *               아무것도 주지 않으면 전체 선수를 반환한다.
 *
 * 정렬은 검색 여부와 무관하게 **`playerName` 오름차순 고정**(관련도 순이 아니다)이고 페이징이 없다.
 *
 * `name` 은 앞뒤 공백을 제거한 뒤 보내고, 비거나 공백뿐이면 파라미터 자체를 뺀다.
 * 서버도 같은 규칙으로 처리하지만(빈 값 = 미전달), 여기서 걸러 두면 검색어를 지웠을 때
 * 캐시 키·요청 URL 이 "조건 없음"과 같아져 화면 상태와 어긋나지 않는다.
 *
 * 검색어를 타이핑할 때마다 호출해도 되지만 **페이징이 없다** — 조건이 넓으면 전체 선수
 * (2026-08-06 기준 558명)가 한 번에 내려오므로 디바운스나 최소 글자 수를 화면에서 두는 게 좋다.
 *
 * 에러: 도메인 404 가 없어 판별 헬퍼도 없다. 400 은 `teamId` 가 숫자가 아닐 때뿐인데
 * 인자 타입이 `number` 라 정상 호출에서는 발생하지 않는다(그 400 만 `{success, data, message}`
 * 형식이 아니라 스프링 기본 오류 응답이 그대로 나가므로, 혹시 마주치면 문자열이 아니라
 * status 로만 다룬다). `name` 에는 400 이 없다 — 어떤 값이든 200 이다.
 */
export function getPlayerList(params: PlayerListParams = {}): Promise<Player[]> {
  const name = params.name?.trim();
  const query: PlayerListParams = {};

  if (params.teamId !== undefined) {
    query.teamId = params.teamId;
  }
  if (name) {
    query.name = name;
  }

  return userClient
    .get<ApiResponse<Player[]>>('/players', {
      params: Object.keys(query).length > 0 ? query : undefined,
    })
    .then(unwrap);
}
