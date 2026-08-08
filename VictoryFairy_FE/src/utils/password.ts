/**
 * 회원가입 비밀번호 입력 규칙.
 *
 * 8~12자 · 영문 대문자 · 숫자 · 특수문자를 각각 1자 이상 포함해야 한다.
 * 판정은 전부 이 파일 안에서 끝난다 — `POST /auth/password/validate` 는 쓰지 않는다.
 * 타이핑할 때마다 왕복이 생기고, 어차피 같은 규칙을 화면에서 즉시 보여줘야 하기 때문이다.
 *
 * 문자 조건은 "이 집합의 글자가 하나라도 있는가"라 정규식 `test` 로 판정한다.
 * 길이만 정규식에 넣지 않고 따로 비교하는데, 길이 위반과 문자 위반의 안내 문구가 달라
 * 한 패턴으로 합치면 어느 쪽이 틀렸는지 되짚어야 하기 때문이다.
 *
 * 서버의 가입 검증(`POST /auth/signup`)은 문자 조건이 "영문(대소문자 무관) + 숫자 +
 * 특수문자"라 여기보다 느슨하다. 대문자는 영문의 부분집합이므로 **이 검사를 통과한
 * 비밀번호는 서버도 반드시 통과한다** — 화면에서 사용 가능하다고 한 값이 가입에서
 * 거부되는 일은 없다. 반대 방향(서버만 통과)은 있을 수 있고, 그건 의도된 차이다.
 */

export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 12;

const UPPERCASE_PATTERN = /[A-Z]/;

const DIGIT_PATTERN = /\d/;

/**
 * 특수문자로 인정하는 문자. 백엔드 PasswordPolicy 의 문자 클래스를 그대로 옮겼다 —
 * 여기서만 통과하는 문자가 생기면 가입 단계에서 이유 없이 거부당한다.
 */
const SPECIAL_CHARACTER_PATTERN = /[!@#$%^&*()_+=\-[\]{};:'",.<>/?\\|`~]/;

/** 비밀번호 확인 칸 문구. Figma: [Sign In] 일반 회원가입-비밀번호확인오류 / -오류없음 */
export const PASSWORD_CONFIRM_MESSAGE = {
  MATCH: '비밀번호가 일치합니다',
  MISMATCH: '비밀번호가 일치하지 않습니다',
} as const;

export const PASSWORD_MESSAGE = {
  LENGTH: `비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자로 입력해주세요`,
  UPPERCASE: '영문 대문자를 포함해주세요',
  DIGIT: '숫자를 포함해주세요',
  SPECIAL: '특수문자를 포함해주세요',
  VALID: '사용 가능한 비밀번호입니다',
} as const;

export interface PasswordCheck {
  valid: boolean;
  message: string;
}

/**
 * 여러 조건을 동시에 어겨도 메시지는 하나만 돌려준다(길이 → 대문자 → 숫자 → 특수문자 순).
 * 안내문 자리가 한 줄이고, 한 번에 하나씩 고치는 편이 읽기 쉽다.
 *
 * 상한(12자)은 입력에서 `maxLength` 로 막으므로 이 메시지가 뜰 일이 거의 없다.
 * 다만 자동완성으로 넘어올 수 있어 길이 위반으로 함께 처리한다.
 *
 * 빈 문자열도 길이 위반이다 — 아직 아무것도 입력하지 않았을 때 이 함수를 부르지 않는 것은
 * 화면 몫이다(그때는 판정이 아니라 기본 안내문을 보여준다).
 */
export function checkPassword(password: string): PasswordCheck {
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    return { valid: false, message: PASSWORD_MESSAGE.LENGTH };
  }
  if (!UPPERCASE_PATTERN.test(password)) {
    return { valid: false, message: PASSWORD_MESSAGE.UPPERCASE };
  }
  if (!DIGIT_PATTERN.test(password)) {
    return { valid: false, message: PASSWORD_MESSAGE.DIGIT };
  }
  if (!SPECIAL_CHARACTER_PATTERN.test(password)) {
    return { valid: false, message: PASSWORD_MESSAGE.SPECIAL };
  }
  return { valid: true, message: PASSWORD_MESSAGE.VALID };
}

/**
 * 비밀번호 확인 칸 일치 검사.
 *
 * 원본이 규칙을 만족하는지는 보지 않는다 — 그건 위 칸이 이미 자기 문구로 알리고 있고,
 * 여기까지 같은 말을 반복하면 두 줄이 동시에 빨개져 무엇을 고쳐야 하는지 흐려진다.
 * 이 칸은 "위에 친 것과 같은가"만 답한다.
 */
export function checkPasswordConfirm(password: string, confirmation: string): PasswordCheck {
  const matched = password === confirmation;
  return {
    valid: matched,
    message: matched ? PASSWORD_CONFIRM_MESSAGE.MATCH : PASSWORD_CONFIRM_MESSAGE.MISMATCH,
  };
}
