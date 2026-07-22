import type { TokenResponse } from '../types/auth';

/**
 * 토큰 저장 추상화.
 *
 * ── store-agent hand-off 지점 ──────────────────────────────────────────
 * 토큰의 실제 영속화(zustand persist)는 store-agent 소관이다. 이 API 계층은
 * 저장소를 직접 구현하지 않고 이 인터페이스에만 의존한다.
 * store-agent는 zustand persist 스토어로 이 인터페이스를 구현한 뒤,
 * 앱 부트스트랩 시점에 `setTokenStorage(myStore)` 로 주입하면 된다.
 * 그때부터 인터셉터(Authorization 주입 / refresh 회전)가 그 스토어를 사용한다.
 * ──────────────────────────────────────────────────────────────────────
 */
export interface TokenStorage {
  getAccessToken(): string | null;
  getRefreshToken(): string | null;
  /** login/refresh 로 새로 받은 토큰쌍 저장. refresh는 rotate이므로 refreshToken도 갱신된다. */
  setTokens(tokens: TokenResponse): void;
  /** 로그아웃/재로그인 유도 시 토큰 초기화. */
  clear(): void;
}

/**
 * 기본 구현: 메모리 스텁.
 * store-agent가 `setTokenStorage()`로 zustand persist 구현을 주입하기 전까지만 쓰인다.
 * 새로고침 시 사라지므로 프로덕션 지속성은 store-agent 구현에 의존한다.
 */
class InMemoryTokenStorage implements TokenStorage {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  getAccessToken(): string | null {
    return this.accessToken;
  }

  getRefreshToken(): string | null {
    return this.refreshToken;
  }

  setTokens(tokens: TokenResponse): void {
    this.accessToken = tokens.accessToken;
    this.refreshToken = tokens.refreshToken;
  }

  clear(): void {
    this.accessToken = null;
    this.refreshToken = null;
  }
}

let currentStorage: TokenStorage = new InMemoryTokenStorage();

/**
 * store-agent 주입 진입점.
 * zustand persist 기반 TokenStorage 구현을 여기로 주입한다.
 */
export function setTokenStorage(storage: TokenStorage): void {
  currentStorage = storage;
}

/** 인터셉터/엔드포인트 함수가 현재 활성 저장소를 얻는 통로. */
export function getTokenStorage(): TokenStorage {
  return currentStorage;
}
