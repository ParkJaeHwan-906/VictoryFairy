import { create } from 'zustand';
import { ApiError, getMyProfile } from '../api';
import type { MyProfile, SupportTeam } from '../types/account';
import { useAuthStore } from './useAuthStore';

/**
 * 로그인 사용자 프로필 스토어.
 *
 * 서버 데이터인데도 전역에 두는 이유는 **쓰기가 있기 때문**이다. 닉네임을 마이
 * 화면에서 바꾸면 NavBar·라운지 랭킹의 내 이름도 함께 바뀌어야 하는데, 화면마다
 * 따로 받아오는 구조로는 그게 보장되지 않는다. (경기·랭킹·문제처럼 읽기만 하는
 * 데이터는 전역이 아니라 화면에서 받는다 — docs/progress.md 2026-08-05 항목 참고)
 *
 * ── 저장하지 않는다 ───────────────────────────────────────────────────
 * 토큰과 달리 persist 하지 않는다. 서버가 소유한 값이라 복원해 봐야 "저장된 것과
 * 방금 받은 것 중 뭐가 맞나"를 따져야 하고, 어차피 앱을 열 때 한 번 받는다.
 * 그래서 새로고침하면 비어 있는 상태로 시작해 `fetchProfile()` 로 채운다.
 * ──────────────────────────────────────────────────────────────────────
 */
type AccountStatus = 'idle' | 'loading' | 'ready' | 'error';

type AccountState = {
  profile: MyProfile | null;
  status: AccountStatus;
  /** 마지막 조회 실패 원인. 성공하면 비운다. */
  error: ApiError | null;
  /**
   * 내 프로필을 받아 채운다.
   *
   * **실패해도 reject 하지 않는다** — 호출부(스플래시 등)가 try/catch 대신
   * `status` 로 분기하도록 통일한다. 진행 중에 다시 부르면 같은 요청에 합류한다.
   */
  fetchProfile: () => Promise<void>;
  /** 로그아웃 시 초기화. 아래 구독이 자동으로 부른다. */
  clear: () => void;
};

/**
 * 진행 중인 조회. 스플래시와 화면이 동시에 부를 수 있어 하나로 합친다
 * (httpClient 의 refresh 회전이 `refreshPromise` 로 합치는 것과 같은 방식).
 */
let inFlight: Promise<void> | null = null;

export const useAccountStore = create<AccountState>((set) => ({
  profile: null,
  status: 'idle',
  error: null,

  fetchProfile: () => {
    if (inFlight) {
      return inFlight;
    }

    set({ status: 'loading', error: null });

    inFlight = getMyProfile()
      .then((profile) => {
        set({ profile, status: 'ready', error: null });
      })
      .catch((error: unknown) => {
        // 재조회 실패면 이전 프로필은 남긴다 — 빈 화면보다 낡은 값이 낫다.
        set({ status: 'error', error: error instanceof ApiError ? error : null });
      })
      .finally(() => {
        inFlight = null;
      });

    return inFlight;
  },

  clear: () => set({ profile: null, status: 'idle', error: null }),
}));

/** 프로필 전체. 닉네임 하나만 필요하면 좁혀 구독하는 편이 리렌더가 적다. */
export const useMyProfile = (): MyProfile | null => useAccountStore((state) => state.profile);

/** 응원 구단 미선택(온보딩 중) 여부. 프로필이 아직 없으면 판단하지 않고 false. */
export const useHasSupportTeam = (): boolean =>
  useAccountStore((state) => state.profile?.supportTeam != null);

/**
 * 응원 구단(`{ id, name }`). 아직 고르지 않았거나 프로필 전이면 `null`.
 *
 * 구단별 채팅방을 찾을 때 이 값을 쓴다 — 라운지 채팅은 "내 응원 구단 방"이라
 * 화면이 teamId 를 따로 들고 다니지 않고 여기서 읽는다.
 * 프로필 객체가 바뀔 때만 새 참조가 되므로 그대로 구독해도 리렌더가 늘지 않는다.
 */
export const useSupportTeam = (): SupportTeam | null =>
  useAccountStore((state) => state.profile?.supportTeam ?? null);

/** 로그인 사용자 닉네임. 채팅에서 내 메시지를 가려내는 기준이다(닉네임은 중복 불가). */
export const useMyNickname = (): string | null =>
  useAccountStore((state) => state.profile?.nickname ?? null);

/*
 * 토큰이 비워지면 프로필도 함께 비운다.
 *
 * 명시적 로그아웃뿐 아니라 **인터셉터가 재발급에 실패해 토큰을 비우는 경로**까지
 * 한 곳에서 잡으려고 스토어 구독으로 건다. 로그아웃 함수에 넣으면 후자를 놓친다.
 * 모듈 스코프에 두어도 안전하다 — 이 모듈을 아무도 쓰지 않으면 비울 프로필도 없다.
 */
useAuthStore.subscribe((state, previous) => {
  if (previous.accessToken !== null && state.accessToken === null) {
    useAccountStore.getState().clear();
  }
});
