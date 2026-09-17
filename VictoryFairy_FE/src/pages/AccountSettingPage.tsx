import { useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ApiError,
  changePassword,
  isInvalidCurrentPassword,
  isSameAsCurrentPassword,
} from '../api';
import { ROUTES } from '../routes';
import {
  checkPassword,
  checkPasswordConfirm,
  PASSWORD_MAX_LENGTH,
  type PasswordCheck,
} from '../utils/password';
import '../styles/AccountSettingPage.css';

/**
 * AccountSettingPage — 계정 설정(비밀번호 변경).
 * Figma: SWM / [My] 계정 설정-일반회원가입 (node 1365:16986)
 *
 * 마이페이지 "설정 > 계정 설정" 으로 들어온다. 디자인에 NavBar 가 없어 레이아웃 밖
 * 전체 화면이고, 바꾸고 나면 마이페이지로 돌아간다.
 *
 * ── 디자인과 다른 두 가지 ─────────────────────────────────────────────
 * 1. 맨 위 칸이 **이메일이 아니라 기존 비밀번호**다. 이메일을 바꾸는 API 가 없어
 *    읽기 전용 칸을 놓아 봐야 아무 데도 닿지 않고, 그 자리를 변경에 실제로 필요한
 *    값(현재 비밀번호)이 쓴다.
 * 2. CTA 가 "저장하기"가 아니라 **"변경하기"** 다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 🔐 **성공하면 이전에 발급된 access·refresh 토큰이 전부 무효화된다**(docs/account.md).
 * 이 화면은 그것을 따로 처리하지 않는다 — `changePassword` 가 응답으로 받은 새 토큰쌍을
 * 곧바로 TokenStorage(=인증 스토어)에 넣어 주므로 **이 기기의 로그인은 끊기지 않는다**.
 * 끊기는 것은 같은 계정으로 로그인해 둔 다른 기기·탭이고, 그건 의도된 동작이다.
 *
 * 비밀번호 규칙은 회원가입과 같은 `utils/password.ts` 를 그대로 쓴다. 이 검사를 통과한
 * 값은 서버도 반드시 통과하므로(그쪽이 더 느슨하다) 화면이 통과시킨 값이 400 으로
 * 되돌아오는 일은 없다.
 */

/** 실패 문구를 어느 칸 아래에 붙일지. `form` 은 어느 칸의 문제도 아닐 때(CTA 위)다. */
type ErrorField = 'current' | 'new' | 'form';

interface FormError {
  field: ErrorField;
  message: string;
}

/**
 * 변경 실패를 화면 문구로 옮긴다.
 *
 * 판정 순서가 `①새 비밀번호 형식 → ②현재 비밀번호 일치 → ③신·구 동일` 이라 한 번에
 * 하나만 온다. 어느 칸을 고쳐야 하는지가 사유마다 다르므로 칸까지 함께 정한다.
 *
 * 현재 비밀번호 불일치는 401 이 아니라 **400** 이다 — 오타 한 번에 401 인터셉터가
 * 로그아웃을 유발하지 않도록 백엔드가 일부러 나눈 코드다(docs/account.md).
 */
function describeChangeError(error: unknown): FormError {
  if (isInvalidCurrentPassword(error)) {
    return { field: 'current', message: (error as ApiError).message };
  }

  if (isSameAsCurrentPassword(error)) {
    return { field: 'new', message: (error as ApiError).message };
  }

  if (error instanceof ApiError) {
    // 길이·문자 구성 위반(400 Bean Validation)은 사유가 message 가 아니라 필드 맵에 담긴다.
    const fieldMessage = error.fieldErrors?.newPassword;
    return fieldMessage === undefined
      ? { field: 'form', message: error.message }
      : { field: 'new', message: fieldMessage };
  }

  return { field: 'form', message: '비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.' };
}

/** 안내문 한 줄. 통과·실패가 같은 자리에 같은 모양으로 뜨고 색만 갈린다. */
function Hint({ id, check }: { id: string; check: PasswordCheck }) {
  return (
    <p
      className={`account-setting-page__hint ${
        check.valid ? 'account-setting-page__hint--success' : 'account-setting-page__hint--error'
      }`}
      id={id}
    >
      <span className="account-setting-page__hint-icon" aria-hidden="true" />
      {check.message}
    </p>
  );
}

export default function AccountSettingPage() {
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newPasswordConfirm, setNewPasswordConfirm] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formError, setFormError] = useState<FormError | null>(null);

  /*
   * 판정은 아직 아무것도 치지 않은 칸에는 하지 않는다 — 화면에 들어오자마자 세 줄이
   * 빨개지면 "틀렸다"가 아니라 "아직 안 썼다"인데도 고칠 것을 찾게 된다.
   * 디자인의 Textfield 도 이 화면에서는 sub 를 끈 상태다.
   */
  const newPasswordCheck = newPassword.length > 0 ? checkPassword(newPassword) : null;
  const confirmCheck =
    newPasswordConfirm.length > 0 ? checkPasswordConfirm(newPassword, newPasswordConfirm) : null;

  /** 현재 비밀번호는 형식을 보지 않는다 — 맞는지 아닌지는 서버만 안다. 비어 있는 것만 막는다. */
  const canSubmit =
    currentPassword.length > 0 &&
    newPasswordCheck?.valid === true &&
    confirmCheck?.valid === true &&
    !isSubmitting;

  /**
   * 세 칸이 공유하는 입력 처리.
   * 무엇을 고치든 직전 실패 문구는 지운다 — 이미 답이 아닌 값에 대한 말이다.
   */
  const handleChange =
    (setter: (value: string) => void) => (event: ChangeEvent<HTMLInputElement>) => {
      setter(event.target.value);
      setFormError(null);
    };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSubmit) return;

    setIsSubmitting(true);
    setFormError(null);

    try {
      // 새 토큰쌍 저장은 API 계층이 이미 끝내 둔다(머리말 참고) — 여기서는 돌아가기만 하면 된다.
      await changePassword({ currentPassword, newPassword });
      navigate(ROUTES.my, { replace: true });
    } catch (cause: unknown) {
      setFormError(describeChangeError(cause));
      setIsSubmitting(false);
    }
  };

  const currentError = formError?.field === 'current' ? formError : null;
  const newError = formError?.field === 'new' ? formError : null;

  return (
    <div className="account-setting-page">
      <header className="account-setting-page__topbar">
        <button
          className="account-setting-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="account-setting-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="account-setting-page__topbar-title">계정 설정</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="account-setting-page__topbar-spacer" aria-hidden="true" />
      </header>

      <form className="account-setting-page__form" onSubmit={handleSubmit} noValidate>
        {/* 디자인의 "이메일" 자리 — 바꿀 방법이 없는 값 대신 변경에 필요한 값을 받는다 */}
        <div className="account-setting-page__group">
          <label className="account-setting-page__label" htmlFor="account-current-password">
            기존 비밀번호
          </label>
          <div className="account-setting-page__field">
            <input
              className={`account-setting-page__input${
                currentError ? ' account-setting-page__input--invalid' : ''
              }`}
              id="account-current-password"
              type="password"
              name="currentPassword"
              value={currentPassword}
              onChange={handleChange(setCurrentPassword)}
              placeholder="현재 비밀번호를 입력해주세요"
              autoComplete="current-password"
              aria-describedby={currentError ? 'account-current-password-hint' : undefined}
              aria-invalid={currentError !== null}
            />
            {/* 이 칸은 서버만 판정할 수 있어, 틀렸다는 답이 온 뒤에만 말한다 */}
            {currentError && (
              <Hint
                id="account-current-password-hint"
                check={{ valid: false, message: currentError.message }}
              />
            )}
          </div>
        </div>

        <div className="account-setting-page__group">
          <label className="account-setting-page__label" htmlFor="account-new-password">
            비밀번호
          </label>

          <div className="account-setting-page__field">
            {/*
              상한(12자)은 문구로 알리지 않고 maxLength 로 입력 자체를 막는다 —
              더 칠 수 없다는 것이 곧 안내라 문구를 겹쳐 띄울 이유가 없다(회원가입과 같다).
            */}
            <input
              className={`account-setting-page__input${
                (newPasswordCheck && !newPasswordCheck.valid) || newError
                  ? ' account-setting-page__input--invalid'
                  : ''
              }`}
              id="account-new-password"
              type="password"
              name="newPassword"
              value={newPassword}
              onChange={handleChange(setNewPassword)}
              placeholder="비밀번호를 입력해주세요"
              autoComplete="new-password"
              maxLength={PASSWORD_MAX_LENGTH}
              aria-describedby={
                newPasswordCheck || newError ? 'account-new-password-hint' : undefined
              }
              aria-invalid={
                (newPasswordCheck !== null && !newPasswordCheck.valid) || newError !== null
              }
            />
            {/* 서버가 되돌려준 사유가 있으면 그쪽이 먼저다 — 화면 판정보다 최신 소식이다 */}
            {(newError || newPasswordCheck) && (
              <Hint
                id="account-new-password-hint"
                check={
                  newError === null
                    ? (newPasswordCheck as PasswordCheck)
                    : { valid: false, message: newError.message }
                }
              />
            )}
          </div>

          <div className="account-setting-page__field">
            <input
              className={`account-setting-page__input${
                confirmCheck && !confirmCheck.valid ? ' account-setting-page__input--invalid' : ''
              }`}
              id="account-new-password-confirm"
              type="password"
              name="newPasswordConfirm"
              value={newPasswordConfirm}
              onChange={handleChange(setNewPasswordConfirm)}
              placeholder="비밀번호를 한번 더 입력해주세요"
              autoComplete="new-password"
              maxLength={PASSWORD_MAX_LENGTH}
              aria-label="새 비밀번호 확인"
              aria-describedby={confirmCheck ? 'account-new-password-confirm-hint' : undefined}
              aria-invalid={confirmCheck !== null && !confirmCheck.valid}
            />
            {/* 일치 여부는 비교할 값이 생긴 뒤에만 말한다 */}
            {confirmCheck && <Hint id="account-new-password-confirm-hint" check={confirmCheck} />}
          </div>
        </div>

        {formError?.field === 'form' && (
          <p className="account-setting-page__form-error" role="alert">
            {formError.message}
          </p>
        )}

        <button className="account-setting-page__submit" type="submit" disabled={!canSubmit}>
          {isSubmitting ? '변경 중...' : '변경하기'}
        </button>
      </form>
    </div>
  );
}
