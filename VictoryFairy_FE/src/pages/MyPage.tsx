import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getQuizSubmissions, logout, withdraw } from '../api';
import profilePlaceholder from '../assets/profile_img.svg';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES } from '../routes';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import { useAuthStore } from '../stores/useAuthStore';
import '../styles/MyPage.css';

/**
 * MyPage — 마이페이지 메인.
 * Figma: SWM / [My] 마이페이지 메인 (node 1186:9147)
 *
 * 위쪽 프로필·성적은 **전역 프로필 스토어**에서 읽는다(닉네임·응원 구단·포인트).
 * 목록 항목들은 아직 갈 곳이 없어 화살표만 그려 두고, 실제로 동작하는 것은
 * 맨 아래 로그아웃·회원 탈퇴 둘뿐이다.
 *
 * ── 평균 정답률만 전역이 아니다 ──────────────────────────────────────
 * `GET /users/me` 응답에는 정답률이 없다(키가 넷뿐 — `MyProfile` 주석 참고).
 * 대신 `GET /quizzes/submissions` 의 `summary.accuracy` 가 전체 기준 정답률이라
 * 이 화면에서 첫 페이지만 받아 요약만 쓴다. 목록은 쓰지 않으므로 한 번이면 된다.
 */

/** 앱 버전. 배포 파이프라인이 주입하기 전까지는 화면에 고정값으로 둔다. */
const APP_VERSION = 'v 1.0.0';

/** 아직 갈 곳이 정해지지 않은 목록 항목. 화살표만 그리고 눌러도 아무 일도 없다. */
const SETTING_ITEMS = ['계정 설정', '알림 설정', 'SNS 연동'] as const;
const CENTER_ITEMS = ['공지사항', '문의하기', '개인정보처리방침', '서비스 이용약관'] as const;

/**
 * 목록 한 줄.
 *
 * 아이콘은 디자인에서도 아직 자리(회색 사각형)만 잡혀 있어 그대로 옮겼다 —
 * 임의로 아이콘을 골라 넣으면 나중에 진짜 아이콘이 왔을 때 무엇을 바꿔야 하는지 흐려진다.
 */
function MenuRow({ label }: { label: string }) {
  return (
    <li>
      <button className="my-page__row" type="button">
        <span className="my-page__row-icon" aria-hidden="true" />
        <span className="my-page__row-label">{label}</span>
        <span className="my-page__row-arrow" aria-hidden="true" />
      </button>
    </li>
  );
}

export default function MyPage() {
  const navigate = useNavigate();
  const profile = useMyProfile();
  const clearAccount = useAccountStore((state) => state.clear);

  /** 정답률만 서버에서 따로 받는다(위 머리말 참고). 실패하면 그냥 비워 둔다. */
  const [accuracy, setAccuracy] = useState<number | null>(null);

  /**
   * 지금 진행 중인 계정 작업. 두 버튼을 동시에 누르는 것을 막고 문구도 이 값으로 바꾼다.
   */
  const [pending, setPending] = useState<'logout' | 'withdraw' | null>(null);
  /** 탈퇴는 되돌릴 수 없어 한 번 더 묻는다. */
  const [isConfirmingWithdraw, setIsConfirmingWithdraw] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;

    getQuizSubmissions(0)
      .then((history) => {
        if (alive) setAccuracy(history.summary.accuracy);
      })
      .catch(() => {
        /* 정답률은 이 화면의 곁가지다 — 못 받아도 나머지는 그대로 보여준다 */
      });

    return () => {
      alive = false;
    };
  }, []);

  /**
   * 로그아웃.
   *
   * 서버 호출이 실패해도 토큰은 비운다 — 이 기기에서 나가겠다는 뜻이 우선이고,
   * `POST /auth/logout` 은 멱등이라 서버에 남은 refresh 토큰은 만료로 정리된다.
   */
  const handleLogout = async () => {
    if (pending !== null) return;

    setPending('logout');
    setActionError(null);

    const { refreshToken, clear } = useAuthStore.getState();

    try {
      if (refreshToken !== null) await logout({ refreshToken });
    } catch {
      /* 서버에 못 알려도 이 기기에서는 나간다 */
    } finally {
      clear();
      clearAccount();
      navigate(ROUTES.login, { replace: true });
    }
  };

  /**
   * 회원 탈퇴.
   *
   * **유예도 취소도 없다**(`withdraw` 주석) — 그래서 로그아웃과 달리 실패하면 토큰을
   * 비우지 않고 그대로 알린다. 지우지 못했는데 로그아웃까지 되면 사용자는 탈퇴됐는지
   * 아닌지 알 방법이 없다.
   */
  const handleWithdraw = async () => {
    if (pending !== null) return;

    setPending('withdraw');
    setActionError(null);

    try {
      await withdraw();
      useAuthStore.getState().clear();
      clearAccount();
      navigate(ROUTES.login, { replace: true });
    } catch {
      setActionError('탈퇴하지 못했어요. 잠시 후 다시 시도해주세요.');
      setPending(null);
      setIsConfirmingWithdraw(false);
    }
  };

  const teamName = profile?.supportTeam
    ? (getTeamDisplay(profile.supportTeam.name)?.label ?? profile.supportTeam.name)
    : null;

  return (
    <main className="my-page">
      <header className="my-page__header">
        <div className="my-page__identity">
          <div className="my-page__avatar-box">
            {/* `GET /users/me` 에 사진 필드가 없어 자리표시 이미지를 쓴다 */}
            <img className="my-page__avatar" src={profilePlaceholder} alt="" />
            <button className="my-page__avatar-edit" type="button" aria-label="프로필 사진 바꾸기">
              <span className="my-page__avatar-edit-icon" aria-hidden="true" />
            </button>
          </div>

          <div className="my-page__profile">
            <p className="my-page__nickname">{profile?.nickname ?? '-'} 님</p>

            <button className="my-page__team" type="button">
              {teamName === null ? (
                <span className="my-page__team-empty">응원 구단을 골라주세요</span>
              ) : (
                <>
                  <span className="my-page__team-name">{teamName}</span>
                  <span className="my-page__team-suffix">응원중!</span>
                </>
              )}
              <span className="my-page__team-arrow" aria-hidden="true" />
            </button>
          </div>
        </div>

        <div className="my-page__summary">
          <div className="my-page__stats">
            <div className="my-page__stat">
              <p className="my-page__stat-label">평균 정답률</p>
              <p className="my-page__stat-value">
                {accuracy === null ? '-' : `${Math.round(accuracy * 100)}%`}
              </p>
            </div>

            <span className="my-page__stat-divider" aria-hidden="true" />

            <div className="my-page__stat">
              <p className="my-page__stat-label">내 포인트</p>
              <p className="my-page__stat-value">{profile?.point ?? 0}P</p>
            </div>
          </div>

          <button className="my-page__point-history" type="button">
            <span>포인트 사용 내역 보기</span>
            <span className="my-page__point-history-arrow" aria-hidden="true" />
          </button>
        </div>
      </header>

      <nav className="my-page__menu" aria-label="마이페이지 메뉴">
        <section className="my-page__section">
          <h2 className="my-page__section-title">설정</h2>
          <ul className="my-page__rows">
            {SETTING_ITEMS.map((label) => (
              <MenuRow key={label} label={label} />
            ))}
          </ul>
        </section>

        <span className="my-page__section-gap" aria-hidden="true" />

        <section className="my-page__section">
          <h2 className="my-page__section-title">센터</h2>
          <ul className="my-page__rows">
            {CENTER_ITEMS.map((label) => (
              <MenuRow key={label} label={label} />
            ))}
            {/* 버전은 눌러 갈 곳이 없어 화살표 대신 값이 붙는다 */}
            <li>
              <div className="my-page__row my-page__row--static">
                <span className="my-page__row-icon" aria-hidden="true" />
                <span className="my-page__row-label">버전정보</span>
                <span className="my-page__row-value">{APP_VERSION}</span>
              </div>
            </li>
          </ul>
        </section>
      </nav>

      {actionError !== null && (
        <p className="my-page__error" role="alert">
          {actionError}
        </p>
      )}

      <div className="my-page__account">
        {isConfirmingWithdraw ? (
          /* 탈퇴는 되돌릴 수 없어 같은 자리에서 한 번 더 묻는다 */
          <>
            <p className="my-page__confirm">탈퇴하면 되돌릴 수 없어요. 계속할까요?</p>
            <div className="my-page__account-actions">
              <button
                className="my-page__text-button my-page__text-button--danger"
                type="button"
                onClick={() => void handleWithdraw()}
                disabled={pending !== null}
              >
                {pending === 'withdraw' ? '탈퇴하는 중…' : '탈퇴하기'}
              </button>
              <span className="my-page__account-divider" aria-hidden="true" />
              <button
                className="my-page__text-button"
                type="button"
                onClick={() => setIsConfirmingWithdraw(false)}
                disabled={pending !== null}
              >
                취소
              </button>
            </div>
          </>
        ) : (
          <div className="my-page__account-actions">
            <button
              className="my-page__text-button"
              type="button"
              onClick={() => void handleLogout()}
              disabled={pending !== null}
            >
              {pending === 'logout' ? '로그아웃 중…' : '로그아웃'}
            </button>
            <span className="my-page__account-divider" aria-hidden="true" />
            <button
              className="my-page__text-button"
              type="button"
              onClick={() => setIsConfirmingWithdraw(true)}
              disabled={pending !== null}
            >
              회원 탈퇴
            </button>
          </div>
        )}
      </div>
    </main>
  );
}
