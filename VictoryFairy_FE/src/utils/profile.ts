/**
 * 회원가입 프로필 입력 규칙 — 이름.
 *
 * `POST /auth/signup` 은 email·password·nickname 외에 name 을 함께 요구한다
 * (`src/types/auth.ts` 의 `SignupRequest`). 앞의 셋은 화면에 전용 확인 버튼(인증 요청·
 * 중복확인)이 있지만 이름은 왕복할 것이 없어 입력 형식만 화면에서 판정한다.
 *
 * 판정 결과의 모양(`{ valid, message }`)은 `utils/password.ts` 와 맞췄다 —
 * SignupPage 의 안내문 한 줄이 어느 칸에서 왔든 같은 방식으로 렌더되기 때문이다.
 */

import type { PasswordCheck } from './password';

/** 이름 판정 결과. 안내문 렌더가 같아 비밀번호 쪽과 같은 형태를 쓴다. */
export type ProfileCheck = PasswordCheck;

export const NAME_MESSAGE = {
  REQUIRED: '이름을 입력해주세요',
  VALID: '사용 가능한 이름입니다',
} as const;

/**
 * 이름 판정. 공백만 친 경우를 걸러내는 것 외에 형식은 보지 않는다 —
 * 사람 이름의 길이·문자 범위를 프론트가 좁혀 잡으면 멀쩡한 이름이 막힌다.
 */
export function checkName(name: string): ProfileCheck {
  const trimmed = name.trim();
  return trimmed.length === 0
    ? { valid: false, message: NAME_MESSAGE.REQUIRED }
    : { valid: true, message: NAME_MESSAGE.VALID };
}
