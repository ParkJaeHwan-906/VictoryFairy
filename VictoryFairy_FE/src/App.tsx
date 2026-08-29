import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import CommunityPage from './pages/CommunityPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import TeamSelectPage from './pages/TeamSelectPage';
import MainPage from './pages/MainPage';
import MyPage from './pages/MyPage';
import GamePage from './pages/GamePage';
import PlayerSelectPage from './pages/PlayerSelectPage';
import CompletePage from './pages/CompletePage';
import InquiryPage from './pages/InquiryPage';
import ProfileEditPage from './pages/ProfileEditPage';
import AccountSettingPage from './pages/AccountSettingPage';
import CharacterCustomPage from './pages/CharacterCustomPage';
import QuizPage from './pages/QuizPage';
import QuizResultPage from './pages/QuizResultPage';
import ProtectedRoute from './components/ProtectedRoute';
import PublicOnlyRoute from './components/PublicOnlyRoute';
import { ROUTES } from './routes';

export default function App() {
  return (
    <Routes>
      {/*
        진입 지점. 로그인으로 보내지만 세션이 남아 있으면 아래 `PublicOnlyRoute` 가 곧바로
        메인으로 이어 보낸다 — 앱을 다시 켰을 때 로그인 화면을 거치지 않는 경로다.
        여기서 직접 판정하지 않는 이유는, 그러면 "세션이 있으면 메인" 규칙이 두 군데로
        갈라져 한쪽만 고치는 일이 생기기 때문이다.
      */}
      <Route path="/" element={<Navigate to={ROUTES.login} replace />} />
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
        여기부터는 로그인해야 들어올 수 있다. 위쪽 온보딩(구단 · 선수 선택 · 완료)은
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
        {/* 프로필 수정도 마찬가지로 NavBar 없는 전체 화면이다 */}
        <Route path={ROUTES.profileEdit} element={<ProfileEditPage />} />
        {/* 계정 설정(비밀번호 변경)도 같은 자리 — 마이페이지에서 들어와 뒤로가기로 돌아간다 */}
        <Route path={ROUTES.accountSetting} element={<AccountSettingPage />} />
        {/* 캐릭터 꾸미기도 NavBar 없는 전체 화면이다 — 홈의 옷 버튼으로 들어온다 */}
        <Route path={ROUTES.characterCustom} element={<CharacterCustomPage />} />
        {/*
          하단 NavBar 가 붙는 화면들.
          레이아웃에 NavBar 를 한 번만 두어, 이 안에서 화면을 옮겨 다녀도
          NavBar 가 그대로 남고 선택 표시가 이어서 움직인다.
        */}
        <Route element={<AppLayout />}>
          <Route path={ROUTES.main} element={<MainPage />} />
          <Route path={ROUTES.game} element={<GamePage />} />
          <Route path={ROUTES.community} element={<CommunityPage />} />
          <Route path={ROUTES.my} element={<MyPage />} />
        </Route>
      </Route>
      {/* 알 수 없는 경로는 로그인으로 되돌린다 */}
      <Route path="*" element={<Navigate to={ROUTES.login} replace />} />
    </Routes>
  );
}
