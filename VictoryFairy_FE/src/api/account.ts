import { userClient } from './httpClient';

/**
 * 계정 API (user 모듈).
 *
 * `/auth/*` 가 인증 절차(로그인·토큰 회전·가입 전 검사)를 다루는 것과 달리,
 * 여기는 로그인한 본인의 계정 리소스(`/users/me`)를 다룬다. 리소스가 다르므로
 * auth 와 파일을 나눈다.
 */

/**
 * DELETE /users/me — 회원탈퇴. user 모듈에서 유일하게 인증이 필요한 요청이다
 * (`/auth/*` 는 로그인 전에 부르거나 refresh 토큰을 본문에 싣는다).
 * 성공 시 204 무본문. 에러: 401 UNAUTHENTICATED.
 */
export async function withdraw(): Promise<void> {
  await userClient.delete<void>('/users/me', { requiresAuth: true });
}
