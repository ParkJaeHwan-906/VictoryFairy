import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import googleIcon from '../assets/google.svg';
import kakaoIcon from '../assets/kakao.svg';
import naverIcon from '../assets/naver.svg';
import { ROUTES } from '../routes';
import '../styles/LoginPage.css';

/**
 * 소셜 로그인 제공자.
 * 아이콘은 프로젝트 자산(`src/assets`)을 그대로 사용한다.
 */
const SOCIAL_PROVIDERS = [
  { id: 'kakao', label: '카카오로 로그인하기', icon: kakaoIcon },
  { id: 'naver', label: '네이버로 로그인하기', icon: naverIcon },
  { id: 'google', label: '구글로 로그인하기', icon: googleIcon },
] as const;

/**
 * LoginPage — 로그인 메인 화면.
 * Figma: SWM / [Login] Main (node 289:36)
 *
 * 화면 구조와 입력 상태만 담당하는 프레젠테이션 컴포넌트다.
 * 실제 인증(POST /api/auth/login)과 토큰 저장·라우팅 연결은
 * api-agent(`src/api/authApi.ts`)·store-agent 쪽 결정이 필요해 아직 붙이지 않았다.
 */
export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  /** 디자인의 CTA 기본값은 Disable 상태 — 두 입력이 모두 채워져야 활성화한다. */
  const canSubmit = email.trim().length > 0 && password.length > 0;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    // TODO: api-agent - authApi.login({ email, password }) 연결,
    //       토큰 저장/리다이렉트는 store-agent 와 협의 후 처리
  };

  return (
    <main className="login-page">
      <section className="login-page__hero" aria-hidden="true">
        {/* 디자인에 실제 그래픽 없이 자리표시 텍스트만 존재한다 */}
        <p className="login-page__hero-placeholder">
          아무튼 뭔가
          <br />
          백그라운드 그래픽
        </p>
      </section>

      <section className="login-page__sheet">
        <h1 className="login-page__title">
          승리를 기다리는 모든 순간
          <br />더 재미있게!
        </h1>

        <form className="login-page__form" onSubmit={handleSubmit} noValidate>
          <div className="login-page__fields">
            <input
              className="login-page__field"
              type="email"
              name="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="이메일"
              aria-label="이메일"
              autoComplete="email"
            />
            <input
              className="login-page__field"
              type="password"
              name="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="비밀번호"
              aria-label="비밀번호"
              autoComplete="current-password"
            />
          </div>

          <button className="login-page__cta" type="submit" disabled={!canSubmit}>
            이메일로 로그인하기
          </button>
        </form>

        <div className="login-page__divider">
          <span className="login-page__divider-label">Sign up with</span>
        </div>

        <ul className="login-page__socials">
          {SOCIAL_PROVIDERS.map((provider) => (
            <li key={provider.id}>
              <button
                className={`login-page__social-button login-page__social-button--${provider.id}`}
                type="button"
                aria-label={provider.label}
              >
                {/* TODO: api-agent - 소셜 로그인 진입점 연결 */}
                <img className="login-page__social-icon" src={provider.icon} alt="" />
              </button>
            </li>
          ))}
        </ul>

        <p className="login-page__signup">
          아직 계정이 없으신가요?
          <Link className="login-page__signup-link" to={ROUTES.signup}>
            회원가입
          </Link>
        </p>
      </section>
    </main>
  );
}
