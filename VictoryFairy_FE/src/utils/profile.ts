/**
 * 회원가입 프로필 입력 규칙 — 이름·전화번호.
 *
 * `POST /auth/signup` 은 email·password·nickname 외에 name·tel·gender 를 함께 요구한다
 * (`src/types/auth.ts` 의 `SignupRequest`). 앞의 셋은 화면에 전용 확인 버튼(인증 요청·
 * 중복확인)이 있지만 이 둘은 왕복할 것이 없어 입력 형식만 화면에서 판정한다.
 *
 * 판정 결과의 모양(`{ valid, message }`)은 `utils/password.ts` 와 맞췄다 —
 * SignupPage 의 안내문 한 줄이 어느 칸에서 왔든 같은 방식으로 렌더되기 때문이다.
 */

import type { PasswordCheck } from './password';

/** 이름·전화번호 판정 결과. 안내문 렌더가 같아 비밀번호 쪽과 같은 형태를 쓴다. */
export type ProfileCheck = PasswordCheck;

/**
 * 전화번호 자릿수. `SignupRequest.tel` 이 "숫자만 10~11자리"로 규정돼 있다 —
 * 하이픈이나 국가번호(+82)를 그대로 실어 보내면 가입 단계에서 거부당한다.
 */
export const TEL_MIN_LENGTH = 10;
export const TEL_MAX_LENGTH = 11;

export const NAME_MESSAGE = {
  REQUIRED: '이름을 입력해주세요',
  VALID: '사용 가능한 이름입니다',
} as const;

export const TEL_MESSAGE = {
  LENGTH: `숫자만 ${TEL_MIN_LENGTH}~${TEL_MAX_LENGTH}자리로 입력해주세요`,
  VALID: '사용 가능한 전화번호입니다',
} as const;

/**
 * 입력값에서 숫자만 남긴다. 하이픈을 섞어 치거나 붙여넣는 것을 막지 않고,
 * 저장하는 시점에 걷어낸다 — 입력 자체를 거부하면 왜 안 쳐지는지 알 수 없다.
 */
export function toTelDigits(value: string): string {
  return value.replace(/\D/g, '').slice(0, TEL_MAX_LENGTH);
}

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

/**
 * 전화번호 판정. 입력 단계에서 이미 숫자만 남기므로 여기서는 자릿수만 본다.
 * (`toTelDigits` 를 거치지 않은 값이 들어와도 안전하도록 숫자 여부도 함께 확인한다)
 */
export function checkTel(tel: string): ProfileCheck {
  const isDigitsOnly = /^\d*$/.test(tel);
  const inRange = tel.length >= TEL_MIN_LENGTH && tel.length <= TEL_MAX_LENGTH;

  return isDigitsOnly && inRange
    ? { valid: true, message: TEL_MESSAGE.VALID }
    : { valid: false, message: TEL_MESSAGE.LENGTH };
}
