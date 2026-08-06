import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { setTokenStorage, type TokenStorage } from '../api';
import type { TokenResponse } from '../types/auth';

/**
 * 인증 토큰 스토어.
 *
 * API 계층(`src/api/tokenStorage.ts`)이 인터페이스만 두고 구현을 비워 둔 자리를
 * 채운다. 아래 `installAuthTokenStorage()` 로 주입하면 그때부터 httpClient 인터셉터
 * (Authorization 주입 · 401 refresh 회전)와 SSE 구독이 이 스토어를 읽는다.
 *
 * ── 저장 위치에 관하여 ────────────────────────────────────────────────
 * refresh 토큰까지 localStorage 에 둔다. 이 API 는 refresh 를 **요청 본문**으로
 * 받으므로(httpOnly 쿠키가 아님) 자바스크립트가 읽을 수 있는 곳에 둘 수밖에 없고,
 * 그래서 XSS 에 노출된다. 더 안전하게 가려면 백엔드가 쿠키 방식을 지원해야 한다.
 * 새로고침해도 로그인이 유지되어야 한다는 요구를 만족시키는 선에서의 선택이다.
 * ──────────────────────────────────────────────────────────────────────
 */
type AuthState = {
  accessToken: string | null;
  refreshToken: string | null;
  /** login · refresh 응답 저장. refresh 는 rotate 이므로 두 토큰을 함께 갈아끼운다. */
  setTokens: (tokens: TokenResponse) => void;
  /** 로그아웃 · 재발급 실패 시 초기화. */
  clear: () => void;
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      setTokens: ({ accessToken, refreshToken }) => set({ accessToken, refreshToken }),
      clear: () => set({ accessToken: null, refreshToken: null }),
    }),
    {
      name: 'victoryfairy.auth',
      /*
       * localStorage 는 동기라 스토어가 만들어지는 시점(모듈 로드)에 복원이 끝난다.
       * 덕분에 첫 요청이 나가기 전에 토큰이 준비된다 — 비동기 저장소로 바꾸면
       * 복원 완료를 기다리는 처리가 따로 필요해진다.
       */
      storage: createJSONStorage(() => localStorage),
      /** 액션은 빼고 토큰만 저장한다. */
      partialize: ({ accessToken, refreshToken }) => ({ accessToken, refreshToken }),
      version: 1,
    },
  ),
);

/**
 * 로그인 여부. 토큰 문자열 자체를 구독하면 값이 바뀔 때마다 리렌더되므로,
 * boolean 으로 좁혀 로그인/로그아웃 순간에만 반응하게 한다.
 */
export const useIsLoggedIn = (): boolean => useAuthStore((state) => state.accessToken !== null);

/**
 * API 계층이 요구하는 TokenStorage 구현.
 * 인터셉터는 React 밖에서 돌기 때문에 훅이 아니라 `getState()` 로 읽는다.
 */
export const authTokenStorage: TokenStorage = {
  getAccessToken: () => useAuthStore.getState().accessToken,
  getRefreshToken: () => useAuthStore.getState().refreshToken,
  setTokens: (tokens) => useAuthStore.getState().setTokens(tokens),
  clear: () => useAuthStore.getState().clear(),
};

/**
 * 부트스트랩에서 한 번 호출한다(`src/main.tsx`).
 * 호출 전까지 API 계층은 메모리 스텁을 쓰므로 새로고침에 토큰이 날아간다.
 */
export function installAuthTokenStorage(): void {
  setTokenStorage(authTokenStorage);
}
