import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, getTokenStorage, login } from '../api';
// OAuth 연동 보류 — 소셜 로그인을 되살릴 때 아이콘 import 도 함께 푼다.
// import googleIcon from '../assets/google.svg';
// import kakaoIcon from '../assets/kakao.svg';
// import naverIcon from '../assets/naver.svg';
import { useAccountStore } from '../stores/useAccountStore';
import { ROUTES } from '../routes';
import '../styles/LoginPage.css';

/*
 * 소셜 로그인 제공자.
 * 아이콘은 프로젝트 자산(`src/assets`)을 그대로 사용한다.
 *
 * OAuth 연동이 잠정 대기라 목록째 주석 처리한다.
 *
 * const SOCIAL_PROVIDERS = [
 *   { id: 'kakao', label: '카카오로 로그인하기', icon: kakaoIcon },
 *   { id: 'naver', label: '네이버로 로그인하기', icon: naverIcon },
 *   { id: 'google', label: '구글로 로그인하기', icon: googleIcon },
 * ] as const;
 */

/** 입력창별 에러 문구. 해당 키가 있으면 그 입력 아래에 메시지를 노출한다. */
type FieldMessages = {
  email?: string;
  password?: string;
};

/**
 * 로그인 실패 응답을 입력창별 문구로 옮긴다.
 *
 * - 400 Bean Validation: 실패 본문의 `data` 가 "필드명 → 메시지" 맵이므로 각 입력에 그대로 붙인다.
 * - 401 INVALID_CREDENTIALS / 네트워크 오류: 서버가 어느 쪽이 틀렸는지 알려주지 않으므로
 *   디자인([Login] Main-PW error, node 404:742)대로 비밀번호 아래에 서버 메시지를 노출한다.
 */
function toFieldMessages(error: unknown): FieldMessages {
  if (error instanceof ApiError) {
    const fieldErrors = error.fieldErrors;

    if (fieldErrors && (fieldErrors.email || fieldErrors.password)) {
      return { email: fieldErrors.email, password: fieldErrors.password };
    }

    return { password: error.message };
  }

  return { password: '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.' };
}

/**
 * LoginPage — 로그인 메인 화면.
 * Figma: SWM / [Login] Main (node 289:36), 실패 상태는 [Login] Main-PW error (node 404:742)
 *
 * 이메일 로그인(POST /auth/login)을 연결한다. 실패 시 서버가 준 message 를
 * 해당 입력창 아래에 그대로 노출한다(문구를 프론트에서 새로 만들지 않는다).
 */
export default function LoginPage() {
  const navigate = useNavigate();
  /** 로그인 직후 프로필을 전역에 채운다 — 이후 화면들이 다시 받지 않아도 되게. */
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [messages, setMessages] = useState<FieldMessages>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  /** 디자인의 CTA 기본값은 Disable 상태 — 두 입력이 모두 채워져야 활성화한다. */
  const canSubmit = email.trim().length > 0 && password.length > 0 && !isSubmitting;

  /** 다시 입력하기 시작하면 직전 실패 문구를 걷어낸다. */
  const clearMessage = (field: keyof FieldMessages) => {
    setMessages((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (!canSubmit) {
      return;
    }

    setIsSubmitting(true);
    setMessages({});

    try {
      const tokens = await login({ email: email.trim(), password });

      // 이 페이지는 저장 위치를 알 필요가 없다. API 계층의 TokenStorage 시임에만 의존하고,
      // 실제 구현(zustand persist)은 부트스트랩에서 주입된다 — `src/stores/auth.ts` 참고.
      // 프로필 조회보다 **먼저** 저장해야 한다 — 그래야 users/me 에 Authorization 이 붙는다.
      getTokenStorage().setTokens(tokens);

      /*
       * 프로필을 전역 스토어에 올려 두고 넘어간다. 토큰과 달리 프로필은 persist 하지 않으므로
       * 로그인 시점에 한 번 채워야 NavBar·라운지 채팅 같은 화면이 각자 다시 받지 않는다.
       *
       * `fetchProfile` 은 실패해도 reject 하지 않는다(스토어가 `status` 로 알린다). 토큰은 이미
       * 유효하니 프로필을 못 받았다고 로그인 자체를 되돌리지는 않고, 그대로 메인으로 보낸다.
       */
      await fetchProfile();

      // replace — 뒤로가기로 로그인 화면에 돌아오지 않게 한다.
      navigate(ROUTES.main, { replace: true });
    } catch (error) {
      setMessages(toFieldMessages(error));
      // 실패했을 때만 버튼을 되살린다. 성공하면 화면이 바뀌므로 눌린 상태 그대로 둔다.
      setIsSubmitting(false);
    }
  };

  return (
    <main className="login-page">
      {/* 장식용 배경 일러스트. 그림은 CSS(.login-page__hero)에서 깐다. */}
      <section className="login-page__hero" aria-hidden="true" />

      <section className="login-page__sheet">
        <h1 className="login-page__title">
          승리를 기다리는 모든 순간
          <br />더 재미있게!
        </h1>

        <form
          className={`login-page__form${messages.password ? ' login-page__form--pw-error' : ''}`}
          onSubmit={handleSubmit}
          noValidate
        >
          <div className="login-page__fields">
            <div className="login-page__field-group">
              <input
                className={`login-page__field${messages.email ? ' login-page__field--error' : ''}`}
                type="email"
                name="email"
                value={email}
                onChange={(event) => {
                  setEmail(event.target.value);
                  clearMessage('email');
                }}
                placeholder="이메일"
                aria-label="이메일"
                autoComplete="email"
                aria-invalid={messages.email ? true : undefined}
                aria-describedby={messages.email ? 'login-email-error' : undefined}
              />
              {messages.email && (
                <p className="login-page__field-error" id="login-email-error" role="alert">
                  <span className="login-page__field-error-icon" aria-hidden="true" />
                  {messages.email}
                </p>
              )}
            </div>

            <div className="login-page__field-group">
              <input
                className={`login-page__field${messages.password ? ' login-page__field--error' : ''}`}
                type="password"
                name="password"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                  clearMessage('password');
                }}
                placeholder="비밀번호"
                aria-label="비밀번호"
                autoComplete="current-password"
                aria-invalid={messages.password ? true : undefined}
                aria-describedby={messages.password ? 'login-password-error' : undefined}
              />
              {messages.password && (
                <p className="login-page__field-error" id="login-password-error" role="alert">
                  <span className="login-page__field-error-icon" aria-hidden="true" />
                  {messages.password}
                </p>
              )}
            </div>
          </div>

          <button
            className="login-page__cta"
            type="submit"
            disabled={!canSubmit}
            aria-busy={isSubmitting}
          >
            이메일로 로그인하기
          </button>
        </form>

        {/*
          OAuth 연동이 잠정 대기라 소셜 로그인 버튼을 내린다.
          버튼만 지우면 "Sign up with" 구분선이 홀로 남으므로 구분선도 함께 가린다.
          TODO: api-agent - 소셜 로그인 진입점이 정해지면 되살린다.

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
                  <img className="login-page__social-icon" src={provider.icon} alt="" />
                </button>
              </li>
            ))}
          </ul>
        */}

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
