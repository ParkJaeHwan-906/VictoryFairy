import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import type { ApiResponse } from '../types/api';
import type { Team } from '../types/team';

/**
 * 구단 API (user 모듈).
 *
 * 회원가입의 구단 선택 등 **로그인 이전 화면**에서 쓰이므로 인증이 없다.
 * `requiresAuth` 를 붙이면 안 된다 — 토큰이 낭비될 뿐 아니라, 401 이 나면
 * httpClient 의 refresh 회전 분기를 타서 저장된 토큰을 비워버린다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * GET /teams — 구단 전체 목록 조회. 성공 시 ApiResponse 래핑(200).
 *
 * 읽기 전용이라 어떤 행도 만들거나 고치지 않는다.
 * 페이징이 없어 항상 전체를 배열 하나로 반환한다 — `?page=`·`?size=` 를 붙여도 무시된다.
 *
 * 빈 배열(`[]`)은 오류가 아니라 구단 데이터가 없다는 정상 200 이다.
 *
 * 정렬 계약은 `name` 오름차순에만 있다. DB 콜레이션 기준이라 영문 구단명이
 * 한글보다 먼저 온다(`KIA, KT, LG, NC, SSG, 두산, ...`) — 완전한 가나다순이 아니어도 정상이다.
 * `id` 채번 순서는 계약이 아니므로 값에 의존하지 않는다.
 */
export function getTeamList(): Promise<Team[]> {
  return userClient.get<ApiResponse<Team[]>>('/teams').then(unwrap);
}
