import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { logout, toAssetUrl, withdraw } from '../api';
import profilePlaceholder from '../assets/profile_img.svg';
import ConfirmSheet from '../components/ConfirmSheet';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES, type TeamSelectState } from '../routes';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import { useAuthStore } from '../stores/useAuthStore';
import '../styles/MyPage.css';

/**
 * MyPage — 마이페이지 메인.
 * Figma: SWM / [My] 마이페이지 메인 (node 1186:9147)
 *
 * 위쪽 프로필·성적은 **전역 프로필 스토어**에서 읽는다(닉네임·응원 구단·포인트).
 * 목록 항목들은 대부분 아직 갈 곳이 없어 화살표만 그려 두었다. 지금 동작하는 것은
 * 문의하기(`InquiryPage`) · 약관 두 건(Notion 문서로 나가는 링크), 그리고 맨 아래
 * 로그아웃·회원 탈퇴뿐이다.
 *
 * ── ⚠️ 평균 정답률을 줄 API 가 없다(2026-08-13) ─────────────────────
 * `GET /users/me` 응답에는 정답률이 없고(키가 넷뿐 — `MyProfile` 주석 참고), 이 화면이
 * 대신 쓰던 `GET /quizzes/submissions` 의 `summary.accuracy` 는 **계정 누적이 아니라
 * 경기 한 건 기준으로 바뀌었다**(`gameId` 필수). 마이페이지에는 지목할 경기가 없어
 * 그 값을 쓸 수 없고, 대체 경로는 아직 없다(docs/quiz.md — 후속 과제).
 *
 * 그래서 자리는 디자인대로 남기고 값만 비워 둔다. 임의의 경기 하나를 골라 채우면
 * "평균"이라는 이름과 다른 수가 나오므로 지어내지 않는다.
 * 계정 단위 통계 API 가 생기면 여기서 그 값을 읽어 채우면 된다.
 */

/** 앱 버전. 배포 파이프라인이 주입하기 전까지는 화면에 고정값으로 둔다. */
const APP_VERSION = 'v 1.0.0';

/**
 * 선호(구단 · 선수) 수정 흐름으로 들어간다는 표식.
 * 렌더마다 새 객체를 만들지 않도록 밖에 둔다 — 라우터 state 는 값만 실어 보내면 된다.
 */
const SUPPORT_EDIT_STATE: TeamSelectState = { mode: 'edit' };

/**
 * 줄 앞에 붙는 아이콘. 값은 **그림 이름이 아니라 쓰임새 이름**이다 —
 * 실제 어떤 SVG 를 오려 쓰는지는 `MyPage.css` 의 `--row-icon` 한 줄만 보면 되고,
 * 나중에 그림이 바뀌어도 이쪽은 손대지 않는다.
 */
type RowIcon =
  'account' | 'notification' | 'sns' | 'notice' | 'inquiry' | 'privacy' | 'terms' | 'version';

/** 목록 한 줄의 재료. `to`(앱 안 이동)와 `href`(바깥 문서)는 둘 중 하나만 쓰거나 둘 다 없다. */
type MenuItem = { label: string; icon: RowIcon; to?: string; href?: string };

/**
 * 설정 묶음. 계정 설정만 갈 곳이 있고(비밀번호 변경 — `AccountSettingPage`),
 * 나머지 둘은 아직 화면이 없어 화살표만 그리고 눌러도 아무 일도 없다.
 */
const SETTING_ITEMS: readonly MenuItem[] = [
  { label: '계정 설정', icon: 'account', to: ROUTES.accountSetting },
  { label: '알림 설정', icon: 'notification' },
  { label: 'SNS 연동', icon: 'sns' },
];

/**
 * 센터 묶음.
 *
 * 약관 두 건은 **앱 안에 화면을 두지 않고 Notion 문서로 내보낸다** — 법무 문구는 배포 없이
 * 고쳐야 하는 글이라, 문서 쪽에서 고치면 앱은 그대로 최신을 가리킨다.
 *
 * 주소는 반드시 **웹에 게시된 `*.notion.site` 쪽**이어야 한다. 작업용 `app.notion.com/p/…`
 * 주소는 워크스페이스 멤버만 열 수 있어, 로그인하지 않은 사용자에게는 빈 화면이 된다.
 *
 * 문의하기는 앱 안 화면(`InquiryPage`)이라 `to` 로 간다. 남은 항목은 아직 갈 곳이 없다.
 */
const CENTER_ITEMS: readonly MenuItem[] = [
  { label: '공지사항', icon: 'notice', to: ROUTES.notice },
  { label: '문의하기', icon: 'inquiry', to: ROUTES.inquiry },
  {
    label: '개인정보처리방침',
    icon: 'privacy',
    href: 'https://fate-almanac-c79.notion.site/3bead13a96fe8057b5b7c5abb0c3762c',
  },
  {
    label: '서비스 이용약관',
    icon: 'terms',
    href: 'https://fate-almanac-c79.notion.site/3bead13a96fe80c8b3f0c6445dae13e3',
  },
];

/**
 * 확인 시트에 넣을 문구. 둘 다 되돌리기 어려운 동작이라 **무엇이 남고 무엇이 사라지는지**를
 * 먼저 말한다. 줄은 디자인이 끊어 둔 그대로다(`ConfirmSheet` 의 `description` 주석 참고).
 */
const ACCOUNT_CONFIRMS = {
  logout: {
    title: '로그아웃 하시겠어요?',
    description: ['다시 로그인하면 언제든', '승리요정을 이어서 이용할 수 있어요.'],
    confirmLabel: '로그아웃',
    pendingLabel: '로그아웃 중…',
  },
  withdraw: {
    title: '회원 탈퇴 하시겠어요?',
    description: [
      '탈퇴하면 승리요정의 이용 정보가 삭제되며,',
      '동일한 계정으로 30일간 다시 가입할 수 없어요.',
    ],
    confirmLabel: '회원 탈퇴',
    pendingLabel: '탈퇴하는 중…',
  },
} as const;

/** 지금 묻고 있는 것 — 곧 확인 시트의 정체다. */
type AccountAction = keyof typeof ACCOUNT_CONFIRMS;

/**
 * 목록 한 줄.
 *
 * 갈 곳이 있는 줄은 **버튼이 아니라 링크로 그린다** — 눌러서 어딘가로 가는 줄은 길게 눌러
 * 주소를 복사하거나 새 탭으로 여는 것이 브라우저에서 당연히 되어야 한다. `to`(앱 안)와
 * `href`(바깥 문서)를 나누는 이유는 앱 안 이동에서 새로고침이 일어나면 안 되기 때문이다.
 * 둘 다 없는 줄은 종전대로 아무 일도 하지 않는 버튼이다.
 */
function MenuRow({ label, icon, to, href }: MenuItem) {
  const inner = (
    <>
      <span className={`my-page__row-icon my-page__row-icon--${icon}`} aria-hidden="true" />
      <span className="my-page__row-label">{label}</span>
      <span className="my-page__row-arrow" aria-hidden="true" />
    </>
  );

  if (to !== undefined) {
    return (
      <li>
        <Link className="my-page__row" to={to}>
          {inner}
        </Link>
      </li>
    );
  }

  if (href !== undefined) {
    return (
      <li>
        {/* 앱을 떠나는 링크라 새 탭으로 연다 — 보던 자리를 잃지 않는다. */}
        <a className="my-page__row" href={href} target="_blank" rel="noreferrer">
          {inner}
        </a>
      </li>
    );
  }

  return (
    <li>
      <button className="my-page__row" type="button">
        {inner}
      </button>
    </li>
  );
}

export default function MyPage() {
  const navigate = useNavigate();
  const profile = useMyProfile();
  const clearAccount = useAccountStore((state) => state.clear);

  /**
   * 지금 진행 중인 계정 작업. 두 버튼을 동시에 누르는 것을 막고, 확인 시트의 실행 버튼도
   * 이 값이 있는 동안 잠근다.
   */
  const [pending, setPending] = useState<AccountAction | null>(null);
  /**
   * 지금 떠 있는 확인 시트. 로그아웃도 탈퇴도 누르는 즉시 실행하지 않고 한 번 더 묻는다 —
   * 둘 다 화면 맨 아래에 나란히 붙어 있어 잘못 누르기 쉽다.
   */
  const [confirming, setConfirming] = useState<AccountAction | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

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
      /* 시트를 닫아야 그 아래 화면의 오류 문구가 보인다 */
      setConfirming(null);
    }
  };

  /** 확인 시트의 실행 버튼. 어느 것을 묻고 있었는지에 따라 갈린다. */
  const handleConfirm = () => {
    if (confirming === 'logout') void handleLogout();
    if (confirming === 'withdraw') void handleWithdraw();
  };

  const teamName = profile?.supportTeam
    ? (getTeamDisplay(profile.supportTeam.name)?.label ?? profile.supportTeam.name)
    : null;

  return (
    <main className="my-page">
      <header className="my-page__header">
        <div className="my-page__identity">
          <div className="my-page__avatar-box">
            {/* 서버는 EP 만 준다 — 도메인을 붙이고, 사진이 없으면 자리표시 이미지를 쓴다 */}
            <img
              className="my-page__avatar"
              src={toAssetUrl(profile?.profileImgUrl) ?? profilePlaceholder}
              alt=""
            />
            {/*
              연필 배지는 사진이 아니라 **프로필 수정 화면**으로 가는 문이다 —
              닉네임도 사진도 그쪽에서 바꾼다(`ProfileEditPage`). 버튼이 아니라 링크로
              그리는 이유는 문의하기 줄과 같다 — 눌러서 어딘가로 가는 것은 링크여야 한다.
            */}
            <Link className="my-page__avatar-edit" to={ROUTES.profileEdit} aria-label="프로필 수정">
              <span className="my-page__avatar-edit-icon" aria-hidden="true" />
            </Link>
          </div>

          <div className="my-page__profile">
            <p className="my-page__nickname">{profile?.nickname ?? '-'} 님</p>

            {/*
              응원 구단 줄은 **선호(구단 · 선수) 수정으로 가는 문**이다.
              온보딩이 쓰던 화면을 그대로 다시 쓰고, `mode: 'edit'` 만 넘겨
              "저장된 선호를 채운 채로 시작해 마이페이지로 돌아오라"고 알린다
              (`TeamSelectPage` · `PlayerSelectPage` 머리말 참고).
            */}
            <Link className="my-page__team" to={ROUTES.teamSelect} state={SUPPORT_EDIT_STATE}>
              {teamName === null ? (
                <span className="my-page__team-empty">응원 구단을 골라주세요</span>
              ) : (
                <>
                  <span className="my-page__team-name">{teamName}</span>
                  <span className="my-page__team-suffix">응원중!</span>
                </>
              )}
              <span className="my-page__team-arrow" aria-hidden="true" />
            </Link>
          </div>
        </div>

        <div className="my-page__summary">
          <div className="my-page__stats">
            <div className="my-page__stat">
              <p className="my-page__stat-label">평균 정답률</p>
              {/* 계정 단위 정답률을 주는 API 가 사라졌다 — 위 머리말 참고 */}
              <p className="my-page__stat-value">-</p>
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
            {SETTING_ITEMS.map((item) => (
              <MenuRow key={item.label} {...item} />
            ))}
          </ul>
        </section>

        <span className="my-page__section-gap" aria-hidden="true" />

        <section className="my-page__section">
          <h2 className="my-page__section-title">센터</h2>
          <ul className="my-page__rows">
            {CENTER_ITEMS.map((item) => (
              <MenuRow key={item.label} {...item} />
            ))}
            {/* 버전은 눌러 갈 곳이 없어 화살표 대신 값이 붙는다 */}
            <li>
              <div className="my-page__row my-page__row--static">
                <span className="my-page__row-icon my-page__row-icon--version" aria-hidden="true" />
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
        <div className="my-page__account-actions">
          <button
            className="my-page__text-button"
            type="button"
            onClick={() => setConfirming('logout')}
            disabled={pending !== null}
          >
            로그아웃
          </button>
          <span className="my-page__account-divider" aria-hidden="true" />
          <button
            className="my-page__text-button"
            type="button"
            onClick={() => setConfirming('withdraw')}
            disabled={pending !== null}
          >
            회원 탈퇴
          </button>
        </div>
      </div>

      {confirming !== null && (
        <ConfirmSheet
          {...ACCOUNT_CONFIRMS[confirming]}
          isPending={pending !== null}
          onConfirm={handleConfirm}
          onClose={() => setConfirming(null)}
        />
      )}
    </main>
  );
}
