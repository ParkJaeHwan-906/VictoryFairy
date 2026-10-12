import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import CommunityPage from './pages/CommunityPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import TeamSelectPage from './pages/TeamSelectPage';
import MainPage from './pages/MainPage';
import MyPage from './pages/MyPage';
import GamePage from './pages/GamePage';
import GameDetailPage from './pages/GameDetailPage';
import PlayerSelectPage from './pages/PlayerSelectPage';
import CompletePage from './pages/CompletePage';
import InquiryPage from './pages/InquiryPage';
import NoticePage from './pages/NoticePage';
import NoticeDetailPage from './pages/NoticeDetailPage';
import ProfileEditPage from './pages/ProfileEditPage';
import AccountSettingPage from './pages/AccountSettingPage';
import NotificationSettingPage from './pages/NotificationSettingPage';
import CharacterCustomPage from './pages/CharacterCustomPage';
import StorySharePage from './pages/StorySharePage';
import QuizPage from './pages/QuizPage';
import QuizResultPage from './pages/QuizResultPage';
import ProtectedRoute from './components/ProtectedRoute';
import PublicOnlyRoute from './components/PublicOnlyRoute';
import { useProfileBootstrap } from './hooks/useProfileBootstrap';
import { ROUTES } from './routes';

export default function App() {
  // 로그인 상태면 프로필을 채운다 — 공개 화면(홈·경기)도 지나야 해서 라우트가 아니라 여기서 부른다.
  useProfileBootstrap();

  return (
    <Routes>
      {/*
        진입 지점. 로그인 여부와 상관없이 홈으로 보낸다 — 홈·경기는 로그인 없이 둘러볼 수
        있어야 한다(App Store 심사 5.1.1(v): 계정과 무관한 기능에 가입을 요구하지 말 것).
        로그인은 계정 기능에 처음 들어가려 할 때 `ProtectedRoute` 가 요구한다.
      */}
      <Route path="/" element={<Navigate to={ROUTES.main} replace />} />
      {/*
        로그인 · 회원가입은 세션이 없는 사람만 들어오는 자리다. 토큰이 남아 있는데도
        폼을 마주하지 않도록 `PublicOnlyRoute` 가 메인으로 돌려보낸다.
      */}
      <Route element={<PublicOnlyRoute />}>
        <Route path={ROUTES.login} element={<LoginPage />} />
        <Route path={ROUTES.signup} element={<SignupPage />} />
      </Route>
      <Route path={ROUTES.teamSelect} element={<TeamSelectPage />} />
      <Route path={ROUTES.playerSelect} element={<PlayerSelectPage />} />
      <Route path={ROUTES.complete} element={<CompletePage />} />
      {/*
        하단 NavBar 가 붙는 화면들.
        레이아웃에 NavBar 를 한 번만 두어, 이 안에서 화면을 옮겨 다녀도
        NavBar 가 그대로 남고 선택 표시가 이어서 움직인다.

        홈 · 경기는 **공개**다(로그인 없이 둘러보기). 라운지 · 마이는 계정 기능이라 안쪽에서
        `ProtectedRoute` 로 다시 막는다 — 탭을 누르면 로그인으로 갔다가 그 탭으로 돌아온다.
      */}
      <Route element={<AppLayout />}>
        <Route path={ROUTES.main} element={<MainPage />} />
        <Route path={ROUTES.game} element={<GamePage />} />
        <Route element={<ProtectedRoute />}>
          <Route path={ROUTES.community} element={<CommunityPage />} />
          <Route path={ROUTES.my} element={<MyPage />} />
        </Route>
      </Route>
      {/*
        경기 상세도 공개다 — 아래에 경기 채팅이 붙어 NavBar 없는 전체 화면이다.
        채팅(과 퀴즈)만 계정 기능이라 비로그인이면 화면 안에서 로그인 안내로 바꾼다.
      */}
      <Route path={ROUTES.gameDetail} element={<GameDetailPage />} />
      {/*
        여기부터는 로그인해야 들어올 수 있는 계정 기능이다. 위쪽 온보딩(구단 · 선수 선택 · 완료)은
        회원가입으로 토큰을 막 받은 채 지나는 구간이라 어느 쪽으로도 막지 않는다 —
        `PublicOnlyRoute` 를 씌우면 가입 직후 흐름이 통째로 끊긴다.
      */}
      <Route element={<ProtectedRoute />}>
        {/* 퀴즈는 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다 */}
        <Route path={ROUTES.quiz} element={<QuizPage />} />
        {/* 퀴즈 결과도 NavBar 없는 전체 화면이라 풀이 화면과 같은 자리에 둔다 */}
        <Route path={ROUTES.quizResult} element={<QuizResultPage />} />
        {/* 문의하기도 디자인에 NavBar 가 없다 — 마이페이지에서 들어와 뒤로가기로 돌아간다 */}
        <Route path={ROUTES.inquiry} element={<InquiryPage />} />
        {/* 공지사항도 마이페이지에서 들어오는 NavBar 없는 화면이다 — 목록과 글 한 건 */}
        <Route path={ROUTES.notice} element={<NoticePage />} />
        <Route path={ROUTES.noticeDetail} element={<NoticeDetailPage />} />
        {/* 프로필 수정도 마찬가지로 NavBar 없는 전체 화면이다 */}
        <Route path={ROUTES.profileEdit} element={<ProfileEditPage />} />
        {/* 계정 설정(비밀번호 변경)도 같은 자리 — 마이페이지에서 들어와 뒤로가기로 돌아간다 */}
        <Route path={ROUTES.accountSetting} element={<AccountSettingPage />} />
        {/* 알림 설정도 같은 자리 — 앱이 읽어 갈 푸시 수신 여부를 여기서 끄고 켠다 */}
        <Route path={ROUTES.notificationSetting} element={<NotificationSettingPage />} />
        {/* 캐릭터 꾸미기도 NavBar 없는 전체 화면이다 — 홈의 옷 버튼으로 들어온다 */}
        <Route path={ROUTES.characterCustom} element={<CharacterCustomPage />} />
        {/*
          스토리 공유도 마이페이지에서 들어오는 NavBar 없는 화면이다 — 스티커를 만드는
          것까지가 웹이고, 인스타로 건네는 것은 앱이 한다(utils/instagramShare.ts).
        */}
        <Route path={ROUTES.storyShare} element={<StorySharePage />} />
      </Route>
      {/* 알 수 없는 경로는 홈으로 되돌린다 — 보호 화면이면 거기서 다시 로그인으로 보낸다 */}
      <Route path="*" element={<Navigate to={ROUTES.main} replace />} />
    </Routes>
  );
}
