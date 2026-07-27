import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import profileImage from '../assets/profile_img.svg';
import '../styles/SignupPage.css';

/**
 * SignupPage — 일반 회원가입(기본) 화면.
 * Figma: SWM / [Sign In] 일반 회원가입-기본 (node 296:1486)
 *
 * 화면 구조와 입력 상태만 담당하는 프레젠테이션 컴포넌트다.
 * 실제 검증·가입(`src/api/authApi.ts` 의 validatePassword / checkNicknameDuplicate /
 * sendEmailCode / signup)은 흐름 설계가 필요해 아직 붙이지 않았다.
 */
export default function SignupPage() {
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [nickname, setNickname] = useState('');

  /** 디자인의 CTA 기본값은 Disable 상태 — 네 입력이 모두 채워져야 활성화한다. */
  const canSubmit =
    email.trim().length > 0 &&
    password.length > 0 &&
    passwordConfirm.length > 0 &&
    nickname.trim().length > 0;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    // TODO: api-agent - authApi.signup(...) 연결. 이메일 인증 단계 포함 흐름 확정 필요
  };

  const handleDuplicateCheck = () => {
    // TODO: api-agent - authApi.checkNicknameDuplicate({ nickname }) 연결
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
        <button
          className="signup-page__avatar-edit"
          type="button"
          aria-label="프로필 사진 변경"
        >
          {/* TODO: react-agent - 이미지 업로드 진입점 연결 */}
          <span className="signup-page__avatar-edit-icon" aria-hidden="true" />
        </button>
      </div>

      <form className="signup-page__form" onSubmit={handleSubmit} noValidate>
        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-email">
            이메일
          </label>
          <div className="signup-page__field">
            <input
              className="signup-page__input"
              id="signup-email"
              type="email"
              name="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="victory@fairy.com"
              autoComplete="email"
            />
          </div>
        </div>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-password">
            비밀번호
          </label>
          <div className="signup-page__field">
            <input
              className="signup-page__input"
              id="signup-password"
              type="password"
              name="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="비밀번호를 입력해주세요"
              autoComplete="new-password"
              aria-describedby="signup-password-hint"
            />
            <p className="signup-page__hint" id="signup-password-hint">
              <span className="signup-page__hint-icon" aria-hidden="true" />
              8~12자 / 대문자, 특수문자 포함
            </p>
          </div>
          <div className="signup-page__field">
            <input
              className="signup-page__input"
              id="signup-password-confirm"
              type="password"
              name="passwordConfirm"
              value={passwordConfirm}
              onChange={(event) => setPasswordConfirm(event.target.value)}
              placeholder="비밀번호를 한번 더 입력해주세요"
              autoComplete="new-password"
              aria-label="비밀번호 확인"
            />
          </div>
        </div>

        <div className="signup-page__group">
          <label className="signup-page__label" htmlFor="signup-nickname">
            닉네임
          </label>
          <div className="signup-page__field">
            <div className="signup-page__nickname-row">
              <input
                className="signup-page__input signup-page__input--nickname"
                id="signup-nickname"
                type="text"
                name="nickname"
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                placeholder="김승요"
                autoComplete="nickname"
                aria-describedby="signup-nickname-hint"
              />
              <button
                className="signup-page__duplicate-check"
                type="button"
                onClick={handleDuplicateCheck}
                disabled={nickname.trim().length === 0}
              >
                중복확인
              </button>
            </div>
            <p className="signup-page__hint" id="signup-nickname-hint">
              <span className="signup-page__hint-icon" aria-hidden="true" />
              10자 이하 / 한글, 영문 사용 가능
            </p>
          </div>
        </div>

        <button className="signup-page__submit" type="submit" disabled={!canSubmit}>
          다음으로
        </button>
      </form>
    </div>
  );
}
