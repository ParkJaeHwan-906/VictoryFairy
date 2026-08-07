import { useEffect, useState, type FormEvent } from 'react';
import { sendEmailCode, verifyEmailCode } from '../api';
import '../styles/EmailVerifySheet.css';

/** 서버가 발급하는 인증번호 자릿수(`EmailVerifyRequest.code` 는 6자리 숫자 문자열이다). */
const CODE_LENGTH = 6;

type EmailVerifySheetProps = {
  /** 인증번호를 받은 이메일. 시트 안내문과 재요청·확인 요청에 그대로 쓴다. */
  email: string;
  /** 인증 성공 시. 시트를 닫는 것은 페이지가 한다(성공 상태를 페이지가 들고 있어야 하므로). */
  onVerified: () => void;
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/**
 * EmailVerifySheet — 회원가입 이메일 인증번호 입력 바텀시트.
 *
 * `sendEmailCode` 가 성공한 뒤에만 열린다. 코드를 다시 받는 것(재요청)은 이 시트 안에서
 * 처리한다 — 이메일을 이미 알고 있어 페이지를 거칠 이유가 없고, 재요청 쿨다운(429)도
 * 여기서 바로 안내하는 편이 자연스럽다.
 *
 * 구조(딤 · 핸들 · rise 애니메이션 · Esc 닫기 · 배경 스크롤 잠금)는 PlayerSearchSheet 와
 * 같게 맞췄다. 높이만 다르다 — 내용이 짧고 고정이라 GameDetailSheet 처럼 내용에 맡긴다.
 */
export default function EmailVerifySheet({ email, onVerified, onClose }: EmailVerifySheetProps) {
  const [code, setCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isResending, setIsResending] = useState(false);
  /** 실패 사유. 서버 문구(만료·불일치·시도 횟수 초과)를 그대로 보여준다. */
  const [error, setError] = useState<string | null>(null);
  /** 재요청 성공 안내. 다음 입력·제출에서 지운다. */
  const [notice, setNotice] = useState<string | null>(null);

  /* Esc 로 닫기 — 시트가 떠 있는 동안에만 듣는다. */
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  /* 시트가 떠 있는 동안 뒤 페이지가 같이 스크롤되지 않게 막는다. */
  useEffect(() => {
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);

  /** 숫자만 남기고 자릿수를 넘기지 않는다 — 붙여넣기로 들어오는 공백·하이픈도 여기서 걸러진다. */
  const handleChange = (value: string) => {
    setCode(value.replace(/\D/g, '').slice(0, CODE_LENGTH));
    setError(null);
    setNotice(null);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (code.length < CODE_LENGTH || isSubmitting) return;

    setIsSubmitting(true);
    setError(null);
    setNotice(null);

    verifyEmailCode({ email, code })
      .then(onVerified)
      .catch((cause: unknown) => {
        // 만료·불일치·시도 횟수 초과 모두 400 이라 상태 코드로는 갈리지 않는다.
        // 어느 쪽이든 사용자가 할 일은 "다시 입력하거나 다시 받기"로 같아 문구만 그대로 보여준다.
        setError(cause instanceof Error ? cause.message : '인증에 실패했어요. 다시 시도해 주세요.');
        setIsSubmitting(false);
      });
  };

  const handleResend = () => {
    if (isResending) return;

    setIsResending(true);
    setError(null);
    setNotice(null);

    sendEmailCode({ email })
      .then(() => {
        // 새 코드가 발급되면 이전 코드는 무효다 — 입력칸을 비워 헷갈리지 않게 한다.
        setCode('');
        setNotice('인증번호를 다시 보냈어요');
      })
      .catch((cause: unknown) => {
        // 재요청 쿨다운(429)이 여기로 온다. 남은 시간 안내는 서버 문구에 담겨 있다.
        setError(
          cause instanceof Error ? cause.message : '인증번호를 보내지 못했어요. 잠시 후 다시 시도해 주세요.',
        );
      })
      .finally(() => setIsResending(false));
  };

  return (
    <div className="email-sheet">
      {/* 딤. 클릭하면 닫히지만 읽어 줄 내용은 없다. */}
      <button
        className="email-sheet__dim"
        type="button"
        onClick={onClose}
        aria-label="이메일 인증 닫기"
      />

      <section
        className="email-sheet__sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="email-sheet-title"
      >
        <button className="email-sheet__handle" type="button" onClick={onClose}>
          <span className="email-sheet__handle-bar" aria-hidden="true" />
          <span className="email-sheet__sr-only">이메일 인증 닫기</span>
        </button>

        <h2 className="email-sheet__title" id="email-sheet-title">
          인증번호를 입력해주세요
        </h2>
        <p className="email-sheet__guide">
          <strong className="email-sheet__email">{email}</strong> 으로
          <br />
          {CODE_LENGTH}자리 인증번호를 보냈어요
        </p>

        <form className="email-sheet__form" onSubmit={handleSubmit} noValidate>
          <label className="email-sheet__sr-only" htmlFor="email-verify-code">
            인증번호 {CODE_LENGTH}자리
          </label>
          <input
            className="email-sheet__input"
            id="email-verify-code"
            /*
             * type="number" 는 앞자리 0 이 사라지고 스피너가 붙어 인증번호에 맞지 않는다.
             * text + inputMode 로 모바일 숫자 키패드만 띄운다.
             */
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={CODE_LENGTH}
            value={code}
            onChange={(event) => handleChange(event.target.value)}
            placeholder="000000"
            aria-describedby={error ? 'email-verify-error' : undefined}
            aria-invalid={error !== null}
            // 인증번호를 넣으려고 여는 시트라 열자마자 입력에 커서를 둔다.
            autoFocus
          />

          {error && (
            <p className="email-sheet__message email-sheet__message--error" id="email-verify-error">
              {error}
            </p>
          )}
          {!error && notice && <p className="email-sheet__message">{notice}</p>}

          <button
            className="email-sheet__submit"
            type="submit"
            disabled={code.length < CODE_LENGTH || isSubmitting}
          >
            {isSubmitting ? '확인 중...' : '인증 확인'}
          </button>
        </form>

        <button
          className="email-sheet__resend"
          type="button"
          onClick={handleResend}
          disabled={isResending}
        >
          {isResending ? '보내는 중...' : '인증번호 다시 받기'}
        </button>
      </section>
    </div>
  );
}
