import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import type { ApiResponse } from '../types/api';
import type { MyProfile } from '../types/account';

/**
 * 계정 API (user 모듈).
 *
 * `/auth/*` 가 인증 절차(로그인·토큰 회전·가입 전 검사)를 다루는 것과 달리,
 * 여기는 로그인한 본인의 계정 리소스(`/users/me`)를 다룬다. 리소스가 다르므로
 * auth 와 파일을 나눈다.
 *
 * 두 엔드포인트 모두 대상을 URL 이 아니라 **access 토큰으로만** 식별한다.
 * 그래서 경로에 식별자가 없고 파라미터도 없다 — 대신 인증이 필수다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(auth·chat 과 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * GET /users/me — 내 프로필 조회(닉네임 · 응원 구단 · 포인트 · 누적 점수).
 * 읽기 전용이라 어떤 행도 만들거나 고치지 않는다. 성공 시 ApiResponse 래핑(200).
 *
 * 온보딩 중간 상태도 오류가 아니다 — 구단 미선택이면 `supportTeam: null`,
 * 누적 점수 행이 없으면 `bqScore: 0` 으로 그냥 200 이 온다.
 *
 * 에러: 401 UNAUTHENTICATED(토큰 없음·무효, refresh 토큰 오용, 탈퇴 계정).
 * 참고: prod 는 user 앱이 관련 스키마를 만들기 전까지 500 을 낸다(배포 이슈, 계약 아님).
 */
export function getMyProfile(): Promise<MyProfile> {
  return userClient.get<ApiResponse<MyProfile>>('/users/me', { requiresAuth: true }).then(unwrap);
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
