import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import profileImage from '../assets/profile_img.svg';
import '../styles/SignupPage.css';
import {
  ApiError,
  checkNicknameDuplicate,
  getTokenStorage,
  login,
  sendEmailCode,
  signup,
} from '../api';
import type { Gender, NicknameValidationResponse } from '../api';
import EmailVerifySheet from '../components/EmailVerifySheet';
import { ROUTES } from '../routes';
import { checkPassword, checkPasswordConfirm, PASSWORD_MAX_LENGTH } from '../utils/password';
import { checkName, checkTel, toTelDigits, TEL_MAX_LENGTH } from '../utils/profile';

/** 성별 선택지. `SignupRequest.gender` 가 받는 두 값이 전부다. */
const GENDER_OPTIONS: ReadonlyArray<{ value: Gender; label: string }> = [
  { value: 'MALE', label: '남성' },
  { value: 'FEMALE', label: '여성' },
];

/**
 * 가입·로그인 실패를 CTA 위에 띄울 한 줄로 옮긴다.
 *
 * 문구는 가능한 한 서버가 준 것을 그대로 쓴다 — 409 는 무엇이 중복인지(이메일/전화번호/닉네임),
 * 400 은 어느 필드가 왜 틀렸는지를 이미 담고 있어 프론트에서 새로 지어내면 되레 흐려진다.
 * Bean Validation(400) 은 필드별 맵으로 오므로 첫 항목을 대표로 보여준다.
 *
 * `afterSignup` 이면 가입은 끝나고 로그인만 실패한 것이라, 실패 원인보다
 * "계정은 만들어졌다"는 사실을 먼저 알려야 다음 행동을 고를 수 있다.
 */
function toSubmitMessage(error: unknown, afterSignup: boolean): string {
  if (afterSignup) {
    return '가입은 완료됐지만 자동 로그인에 실패했어요. 로그인 화면에서 다시 시도해 주세요.';
  }

  if (error instanceof ApiError) {
    const firstFieldMessage = error.fieldErrors ? Object.values(error.fieldErrors)[0] : undefined;
    return firstFieldMessage ?? error.message;
  }

  return error instanceof Error
    ? error.message
    : '회원가입에 실패했어요. 잠시 후 다시 시도해 주세요.';
}

/**
 * SignupPage — 일반 회원가입(기본) 화면.
 * Figma: SWM / [Sign In] 일반 회원가입-기본 (node 296:1486)
 *
 * "인증 요청"이 성공하면 인증번호 입력 바텀시트가 올라오고(`EmailVerifySheet`),
 * 시트에서 인증에 성공해야 이메일 항목이 통과한다. 모든 항목이 통과해야 CTA 가 열리고,
 * 누르면 가입(`signup`) → 로그인(`login`) → 구단 선택(`ROUTES.teamSelect`) 순으로 이어진다.
 *
 * 이름·전화번호·성별은 Figma 에 칸이 없지만 `SignupRequest` 의 필수값이라 함께 받는다.
 * 없이 보내면 가입이 400 으로 떨어져 화면이 성립하지 않는다 — 스타일은 기존 입력 규격을
 * 그대로 따르게 두어 디자인과 어긋나지 않게 했다.
 */
export default function SignupPage() {
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [nickname, setNickname] = useState('');
  const [name, setName] = useState('');
  /** 숫자만 담는다 — 화면에서 하이픈을 걷어내고(`toTelDigits`) 그대로 전송한다. */
  const [tel, setTel] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);

  const [isSendingCode, setIsSendingCode] = useState(false);
  const [isVerifySheetOpen, setIsVerifySheetOpen] = useState(false);
  const [isCheckingNickname, setIsCheckingNickname] = useState(false);
  /**
   * 중복확인 결과와 **그때 검사한 닉네임**. 이메일 인증과 같은 이유로 닉네임까지 들고 있어야
   * 검사 뒤 값을 고쳤을 때 지난 결과가 남지 않는다.
   */
  const [nicknameCheck, setNicknameCheck] = useState<
    (NicknameValidationResponse & { nickname: string }) | null
  >(null);
  /** 인증을 마친 주소. 인증 후 이메일을 고치면 이 값과 어긋나 인증이 자동으로 풀린다. */
  const [verifiedEmail, setVerifiedEmail] = useState<string | null>(null);
  /** 코드 전송 실패 사유(중복 이메일·재요청 쿨다운 등). 서버 문구를 그대로 보여준다. */
  const [emailError, setEmailError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  /** 가입·로그인 실패 사유. CTA 바로 위에 띄운다 — 어느 칸의 문제인지 서버가 알려주지 않는 경우가 많다. */
  const [submitError, setSubmitError] = useState<string | null>(null);
  /**
   * 가입은 됐지만 이어지는 로그인이 실패한 상태. 이 폼으로 다시 시도해봐야 409(중복)뿐이라
   * CTA 를 다시 누르게 두지 않고 로그인 화면으로 보내는 버튼으로 바꾼다.
   */
  const [needsManualLogin, setNeedsManualLogin] = useState(false);

  const trimmedEmail = email.trim();
  /**
   * 인증 완료 판정은 `verifiedEmail` 존재 여부가 아니라 **현재 입력값과의 일치**로 한다.
   * 인증 후 주소를 고치고 그대로 제출하면 인증하지 않은 주소로 가입되기 때문이다.
   */
  const isEmailVerified = verifiedEmail !== null && verifiedEmail === trimmedEmail;

  /**
   * 비밀번호 입력 규칙 판정. 아직 아무것도 입력하지 않았으면 판정하지 않고(null)
   * 기본 안내문("8~12자 / 대문자, 특수문자 포함")을 그대로 둔다 — 화면에 들어오자마자
   * 빨간 문구가 떠 있으면 아직 하지도 않은 일을 틀렸다고 하는 셈이다.
   */
  const passwordCheck = password.length > 0 ? checkPassword(password) : null;

  /**
   * 확인 칸 일치 판정. 위 칸이 비어 있으면 판정하지 않는다 — 아직 비교할 대상이 없는데
   * "일치하지 않습니다"를 띄우면 확인 칸이 틀린 것처럼 보인다.
   */
  const passwordConfirmCheck =
    password.length > 0 && passwordConfirm.length > 0
      ? checkPasswordConfirm(password, passwordConfirm)
      : null;

  const trimmedNickname = nickname.trim();
  /** 지금 입력값을 검사한 결과만 유효하다. 값을 고치면 결과가 자동으로 떨어져 나간다. */
  const nicknameResult =
    nicknameCheck && nicknameCheck.nickname === trimmedNickname ? nicknameCheck : null;

  const trimmedName = name.trim();
  /** 이름·전화번호도 입력 전에는 판정하지 않는다 — 비밀번호 칸과 같은 이유다. */
  const nameCheck = name.length > 0 ? checkName(name) : null;
  const telCheck = tel.length > 0 ? checkTel(tel) : null;

  /** 디자인의 CTA 기본값은 Disable 상태 — 모든 항목의 검증이 끝나야 활성화한다. */
  const canSubmit =
    isEmailVerified &&
    passwordCheck?.valid === true &&
    passwordConfirmCheck?.valid === true &&
    nicknameResult?.valid === true &&
    nameCheck?.valid === true &&
    telCheck?.valid === true &&
    gender !== null &&
    !isSubmitting;

  /**
   * 가입 → 로그인 → 구단 선택.
   *
   * `POST /auth/signup` 은 토큰을 주지 않으므로 방금 친 자격으로 곧바로 로그인해 토큰을
   * 받아온다. 저장은 API 계층의 `TokenStorage` 시임에 맡긴다 — 이 화면은 토큰이 어디에
   * 담기는지 알 필요가 없다(실제 구현 주입은 `src/stores/useAuthStore.ts`).
   *
   * 가입은 됐는데 로그인이 실패하는 경우를 따로 가른다. 계정은 이미 만들어졌으니
   * 같은 폼으로 다시 가입시키면 409(중복)만 돌아온다 — 로그인 화면으로 보내는 것이 맞다.
   */
  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (!canSubmit) return;

    setIsSubmitting(true);
    setSubmitError(null);

    let isSignedUp = false;

    try {
      // 성공은 201 + raw true. false 가 오면 가입되지 않은 것으로 본다.
      const created = await signup({
        name: trimmedName,
        tel,
        email: trimmedEmail,
        gender,
        nickname: trimmedNickname,
        password,
      });

      if (!created) {
        throw new Error('회원가입에 실패했어요. 잠시 후 다시 시도해 주세요.');
      }

      isSignedUp = true;

      const tokens = await login({ email: trimmedEmail, password });
      getTokenStorage().setTokens(tokens);

      // 뒤로 가기로 가입 폼에 돌아오면 이미 만들어진 계정을 또 만들려 하게 된다 — 이력을 갈아끼운다.
      navigate(ROUTES.teamSelect, { replace: true });
    } catch (cause: unknown) {
      setNeedsManualLogin(isSignedUp);
      setSubmitError(toSubmitMessage(cause, isSignedUp));
      setIsSubmitting(false);
      return;
    }

    // 성공 경로에서는 상태를 되돌리지 않는다 — 이동이 끝날 때까지 CTA 를 잠가 둔다.
  };

  const handleSendEmailCode = () => {
    if (trimmedEmail.length === 0 || isSendingCode) return;

    setIsSendingCode(true);
    setEmailError(null);

    sendEmailCode({ email: trimmedEmail })
      // 전송이 성공한 뒤에만 시트를 연다 — 중복 이메일(409)·쿨다운(429)이면 열 이유가 없다.
      .then(() => setIsVerifySheetOpen(true))
      .catch((cause: unknown) => {
        setEmailError(
          cause instanceof Error
            ? cause.message
            : '인증번호를 보내지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      })
      .finally(() => setIsSendingCode(false));
  };

  /** 시트에서 인증에 성공했을 때. 어느 주소를 인증했는지까지 남겨야 이후 수정을 감지할 수 있다. */
  const handleEmailVerified = () => {
    setVerifiedEmail(trimmedEmail);
    setIsVerifySheetOpen(false);
    setEmailError(null);
  };

  /**
   * 닉네임 중복확인. `POST /auth/nickname/duplicate` 는 중복이어도 200 이라
   * 실패가 아니라 `valid: false` 로 온다 — catch 로 가는 건 네트워크·서버 오류뿐이다.
   *
   * 이 엔드포인트는 **중복만** 본다(정책 미검사). 그래서 `valid: true` 는 "아직 아무도 안 쓴다"
   * 까지만 보장하고 가입 가능을 보장하지는 않는다 — 길이·문자 위반은 가입 시점에 걸린다.
   */
  const handleNicknameDuplicateCheck = () => {
    if (trimmedNickname.length === 0 || isCheckingNickname) return;

    setIsCheckingNickname(true);

    checkNicknameDuplicate({ nickname: trimmedNickname })
      // 문구는 서버가 준 것을 그대로 쓴다. 중복·통과 판정과 문구의 출처를 하나로 둔다.
      .then((result) => setNicknameCheck({ ...result, nickname: trimmedNickname }))
      .catch((cause: unknown) => {
        setNicknameCheck({
          valid: false,
          message:
            cause instanceof Error
              ? cause.message
              : '닉네임을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
          nickname: trimmedNickname,
        });
      })
      .finally(() => setIsCheckingNickname(false));
  };

  return (
    <div className="signup-page">
      <header className="signup-page__topbar">
        <button
          className="signup-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="signup-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="signup-page__topbar-title">회원가입</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="signup-page__topbar-spacer" aria-hidden="true" />
      </header>

      <div className="signup-page__profile">
        <img className="signup-page__avatar" src={profileImage} alt="" />
        {/* <button
          className="signup-page__avatar-edit"
          type="button"
          aria-label="프로필 사진 변경"
        > */}
          {/* TODO: react-agent - 이미지 업로드 진입점 연결 */}
          {/* <span className="signup-page__avatar-edit-icon" aria-hidden="true" />
        </button> */}
      </div>

      <form className="signup-page__form" onSubmit={handleSubmit} noValidate>
        {/*
          이름·전화번호·성별은 Figma 에 칸이 없지만 `SignupRequest` 의 필수값이라 함께 받는다.
          확인 왕복(인증 요청·중복확인)이 없는 항목들이라 맨 앞에 둔다 —
          위에서부터 그냥 채워 내려오다가 이메일에서 한 번, 닉네임에서 한 번만 멈추게 된다.
        */}
        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-name">
            이름
          </label>
          <div className="signup-page__field">
            <input
              className={`signup-page__input${
                nameCheck && !nameCheck.valid ? ' signup-page__input--invalid' : ''
              }`}
              id="signup-name"
              type="text"
              name="name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="김승요"
              autoComplete="name"
              aria-invalid={nameCheck !== null && !nameCheck.valid}
              aria-describedby={nameCheck && !nameCheck.valid ? 'signup-name-hint' : undefined}
            />
            {/* 통과 문구까지 띄우면 이름 칸에서 확인할 것이 없는데도 줄이 하나 늘어난다 */}
            {nameCheck && !nameCheck.valid && (
              <p className="signup-page__hint signup-page__hint--error" id="signup-name-hint">
                <span className="signup-page__hint-icon" aria-hidden="true" />
                {nameCheck.message}
              </p>
            )}
          </div>
        </div>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-tel">
            전화번호
          </label>
          <div className="signup-page__field">
            {/*
              inputMode="numeric" 으로 모바일에서 숫자 키패드를 띄우되 type 은 text 로 둔다 —
              type="number" 는 앞자리 0(010…)과 스크롤 증감 때문에 전화번호에 맞지 않는다.
              하이픈을 쳐도 입력을 막지 않고 toTelDigits 로 걷어낸다.
            */}
            <input
              className={`signup-page__input${
                telCheck && !telCheck.valid ? ' signup-page__input--invalid' : ''
              }`}
              id="signup-tel"
              type="text"
              name="tel"
              value={tel}
              onChange={(event) => setTel(toTelDigits(event.target.value))}
              placeholder="01012345678"
              autoComplete="tel-national"
              inputMode="numeric"
              maxLength={TEL_MAX_LENGTH}
              aria-describedby="signup-tel-hint"
              aria-invalid={telCheck !== null && !telCheck.valid}
            />
            <p
              className={[
                'signup-page__hint',
                telCheck && !telCheck.valid ? 'signup-page__hint--error' : '',
                telCheck?.valid ? 'signup-page__hint--success' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              id="signup-tel-hint"
            >
              <span className="signup-page__hint-icon" aria-hidden="true" />
              {telCheck ? telCheck.message : '숫자만 입력해주세요 (- 없이)'}
            </p>
          </div>
        </div>

        {/*
          라디오 그룹이라 label/htmlFor 대신 fieldset/legend 로 묶는다 —
          선택지가 여럿이라 가리킬 입력이 하나로 정해지지 않는다.
        */}
        <fieldset className="signup-page__group signup-page__fieldset">
          <legend className="signup-page__label">성별</legend>
          <div className="signup-page__gender">
            {GENDER_OPTIONS.map((option) => (
              <label
                className={`signup-page__gender-option${
                  gender === option.value ? ' signup-page__gender-option--selected' : ''
                }`}
                key={option.value}
              >
                <input
                  className="signup-page__gender-input"
                  type="radio"
                  name="gender"
                  value={option.value}
                  checked={gender === option.value}
                  onChange={() => setGender(option.value)}
                />
                {option.label}
              </label>
            ))}
          </div>
        </fieldset>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-email">
            이메일
          </label>
          <div className="signup-page__field">
            <div className="signup-page__input-row">
              <input
                className="signup-page__input signup-page__input--with-action"
                id="signup-email"
                type="email"
                name="email"
                value={email}
                onChange={(event) => {
                  setEmail(event.target.value);
                  setEmailError(null);
                }}
                placeholder="victory@fairy.com"
                autoComplete="email"
                aria-describedby={emailError ? 'signup-email-error' : undefined}
                aria-invalid={emailError !== null}
              />
              {/*
                인증이 끝난 주소면 다시 요청할 이유가 없어 버튼을 잠근다.
                주소를 고치면 isEmailVerified 가 풀리면서 자동으로 다시 열린다.
              */}
              <button
                className="signup-page__input-action"
                type="button"
                onClick={handleSendEmailCode}
                disabled={trimmedEmail.length === 0 || isSendingCode || isEmailVerified}
              >
                {isEmailVerified ? '인증 완료' : isSendingCode ? '전송 중...' : '인증 요청'}
              </button>
            </div>

            {emailError && (
              <p
                className="signup-page__hint signup-page__hint--error"
                id="signup-email-error"
                role="alert"
              >
                <span className="signup-page__hint-icon" aria-hidden="true" />
                {emailError}
              </p>
            )}

            {isEmailVerified && (
              <p className="signup-page__hint signup-page__hint--success">
                <span className="signup-page__hint-icon" aria-hidden="true" />
                인증이 완료되었어요
              </p>
            )}
          </div>
        </div>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-password">
            비밀번호
          </label>
          <div className="signup-page__field">
            {/*
              상한(12자)은 문구로 알리지 않고 maxLength 로 입력 자체를 막는다 —
              더 칠 수 없다는 것이 곧 안내라 문구를 겹쳐 띄울 이유가 없다.
            */}
            <input
              className="signup-page__input"
              id="signup-password"
              type="password"
              name="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="비밀번호를 입력해주세요"
              autoComplete="new-password"
              maxLength={PASSWORD_MAX_LENGTH}
              aria-describedby="signup-password-hint"
              aria-invalid={passwordCheck !== null && !passwordCheck.valid}
            />
            {/*
              안내문 한 줄이 세 가지로 쓰인다 — 입력 전에는 기본 안내, 입력 중에는 위반 사유,
              통과하면 사용 가능 표시. 자리가 그대로라 문구가 바뀔 때 레이아웃이 흔들리지 않는다.
            */}
            <p
              className={[
                'signup-page__hint',
                passwordCheck && !passwordCheck.valid ? 'signup-page__hint--error' : '',
                passwordCheck?.valid ? 'signup-page__hint--success' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              id="signup-password-hint"
            >
              <span className="signup-page__hint-icon" aria-hidden="true" />
              {passwordCheck ? passwordCheck.message : '8~12자 / 대문자, 숫자, 특수문자 포함'}
            </p>
          </div>
          <div className="signup-page__field">
            <input
              className={`signup-page__input${
                passwordConfirmCheck && !passwordConfirmCheck.valid
                  ? ' signup-page__input--invalid'
                  : ''
              }`}
              id="signup-password-confirm"
              type="password"
              name="passwordConfirm"
              value={passwordConfirm}
              onChange={(event) => setPasswordConfirm(event.target.value)}
              placeholder="비밀번호를 한번 더 입력해주세요"
              autoComplete="new-password"
              maxLength={PASSWORD_MAX_LENGTH}
              aria-label="비밀번호 확인"
              aria-describedby={passwordConfirmCheck ? 'signup-password-confirm-hint' : undefined}
              aria-invalid={passwordConfirmCheck !== null && !passwordConfirmCheck.valid}
            />
            {/* 일치 여부는 비교할 값이 생긴 뒤에만 말한다 — 그전에는 안내문 자리를 비워 둔다. */}
            {passwordConfirmCheck && (
              <p
                className={`signup-page__hint ${
                  passwordConfirmCheck.valid
                    ? 'signup-page__hint--success'
                    : 'signup-page__hint--error'
                }`}
                id="signup-password-confirm-hint"
              >
                <span className="signup-page__hint-icon" aria-hidden="true" />
                {passwordConfirmCheck.message}
              </p>
            )}
          </div>
        </div>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-nickname">
            닉네임
          </label>
          <div className="signup-page__field">
            <div className="signup-page__input-row">
              <input
                className={`signup-page__input signup-page__input--with-action${
                  nicknameResult && !nicknameResult.valid ? ' signup-page__input--invalid' : ''
                }`}
                id="signup-nickname"
                type="text"
                name="nickname"
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                placeholder="김승요"
                autoComplete="nickname"
                aria-describedby="signup-nickname-hint"
                aria-invalid={nicknameResult !== null && !nicknameResult.valid}
              />
              {/* 이미 확인을 마친 값이면 같은 요청을 또 보낼 이유가 없어 잠근다. */}
              <button
                className="signup-page__input-action"
                type="button"
                onClick={handleNicknameDuplicateCheck}
                disabled={
                  trimmedNickname.length === 0 || isCheckingNickname || nicknameResult?.valid
                }
              >
                {isCheckingNickname ? '확인 중...' : '중복확인'}
              </button>
            </div>
            {/* 확인 전에는 기본 안내, 확인 뒤에는 서버가 준 문구(중복 사유·사용 가능)를 그대로 쓴다. */}
            <p
              className={[
                'signup-page__hint',
                nicknameResult && !nicknameResult.valid ? 'signup-page__hint--error' : '',
                nicknameResult?.valid ? 'signup-page__hint--success' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              id="signup-nickname-hint"
            >
              <span className="signup-page__hint-icon" aria-hidden="true" />
              {nicknameResult ? nicknameResult.message : '10자 이하 / 한글, 영문 사용 가능'}
            </p>
          </div>
        </div>

        {submitError && (
          <p className="signup-page__hint signup-page__hint--error" role="alert">
            <span className="signup-page__hint-icon" aria-hidden="true" />
            {submitError}
          </p>
        )}

        {/*
          가입까지 끝난 뒤라면 같은 폼을 다시 제출해봐야 중복 오류뿐이다 —
          할 수 있는 유일한 다음 동작(로그인)으로 CTA 를 바꿔 끝을 막지 않는다.
        */}
        {needsManualLogin ? (
          <button
            className="signup-page__submit"
            type="button"
            onClick={() => navigate(ROUTES.login, { replace: true })}
          >
            로그인하러 가기
          </button>
        ) : (
          <button
            className="signup-page__submit"
            type="submit"
            disabled={!canSubmit}
            aria-busy={isSubmitting}
          >
            {isSubmitting ? '가입 중...' : '다음으로'}
          </button>
        )}
      </form>

      {isVerifySheetOpen && (
        <EmailVerifySheet
          email={trimmedEmail}
          onVerified={handleEmailVerified}
          onClose={() => setIsVerifySheetOpen(false)}
        />
      )}
    </div>
  );
}
