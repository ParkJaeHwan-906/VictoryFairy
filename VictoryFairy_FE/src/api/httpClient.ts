import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import type { TokenResponse } from '../types/auth';
import { getTokenStorage } from './tokenStorage';
import { isUnauthenticated, normalizeError } from './errors';

/**
 * axios 요청 설정 확장.
 * - requiresAuth: 요청 인터셉터가 Authorization: Bearer <accessToken>를 주입할지 여부.
 *   현재 인증 필요 요청은 DELETE /users/me 하나뿐이지만, 향후 인증 엔드포인트
 *   확장을 고려해 플래그 기반으로 일반화한다.
 * - _retry: refresh 회전 재시도 1회 제한용 내부 플래그(무한루프 방지).
 */
declare module 'axios' {
  export interface AxiosRequestConfig {
    requiresAuth?: boolean;
    _retry?: boolean;
  }
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export const httpClient: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
});

/* ------------------------------------------------------------------ *
 * 요청 인터셉터 — 인증 필요 요청에만 access 토큰 주입
 * ------------------------------------------------------------------ */
httpClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (config.requiresAuth) {
    const token = getTokenStorage().getAccessToken();
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }
  }
  return config;
});

/* ------------------------------------------------------------------ *
 * refresh 회전 — 동시 다발 401을 하나의 refresh 호출로 합류시킨다
 * ------------------------------------------------------------------ */
let refreshPromise: Promise<TokenResponse> | null = null;

function rotateTokens(): Promise<TokenResponse> {
  const storage = getTokenStorage();
  const refreshToken = storage.getRefreshToken();

  if (!refreshToken) {
    return Promise.reject(new Error('저장된 refresh 토큰이 없습니다.'));
  }

  if (refreshPromise) {
    return refreshPromise;
  }

  // httpClient가 아닌 bare axios로 호출해 인터셉터 재귀를 피한다.
  // refresh는 rotate이므로 새 refreshToken까지 반드시 저장해야 한다.
  const promise = axios
    .post<TokenResponse>(`${BASE_URL}/auth/refresh`, { refreshToken })
    .then((res) => {
      storage.setTokens(res.data);
      return res.data;
    })
    .finally(() => {
      refreshPromise = null;
    });

  refreshPromise = promise;
  return promise;
}

/* ------------------------------------------------------------------ *
 * 응답 인터셉터 — 성공은 그대로 통과(언랩은 엔드포인트별로 처리),
 * 실패는 정규화. 인증 필요 요청의 UNAUTHENTICATED(401)면 refresh 1회 시도.
 * ------------------------------------------------------------------ */
httpClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig | undefined;

    // 인증 필요 요청이 401 & UNAUTHENTICATED면 저장된 refreshToken으로 재발급 후 1회 재시도.
    // 401 4종은 message로만 구분되므로 isUnauthenticated(message 비교)로 판별한다.
    // refresh 자체가 401(INVALID/EXPIRED_REFRESH_TOKEN)이면 rotateTokens가 reject되고,
    // 여기서 재시도하지 않고 storage.clear() 후 정규화 에러를 그대로 전파(로그아웃 신호)한다.
    if (
      original &&
      original.requiresAuth &&
      !original._retry &&
      error.response?.status === 401 &&
      isUnauthenticated(error.response?.data)
    ) {
      original._retry = true;
      try {
        const tokens = await rotateTokens();
        original.headers.set('Authorization', `Bearer ${tokens.accessToken}`);
        return httpClient(original);
      } catch {
        getTokenStorage().clear();
        // 재발급 실패 → 아래 정규화로 흘려보내 로그아웃/재로그인 처리를 상위에 위임.
      }
    }

    return Promise.reject(normalizeError(error));
  },
);
