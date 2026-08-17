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
import QuizPage from './pages/QuizPage';
import QuizResultPage from './pages/QuizResultPage';
import ProtectedRoute from './components/ProtectedRoute';
import { ROUTES } from './routes';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to={ROUTES.login} replace />} />
      <Route path={ROUTES.login} element={<LoginPage />} />
      <Route path={ROUTES.signup} element={<SignupPage />} />
      <Route path={ROUTES.teamSelect} element={<TeamSelectPage />} />
      <Route path={ROUTES.playerSelect} element={<PlayerSelectPage />} />
      <Route path={ROUTES.complete} element={<CompletePage />} />
      {/*
        여기부터는 로그인해야 들어올 수 있다. 위쪽(로그인 · 회원가입 · 온보딩)은
        아직 토큰이 없거나 막 받은 단계라 열어 둔다.
      */}
      <Route element={<ProtectedRoute />}>
        {/* 퀴즈는 디자인에 NavBar 가 없어 레이아웃 밖 전체 화면이다 */}
        <Route path={ROUTES.quiz} element={<QuizPage />} />
        {/* 퀴즈 결과도 NavBar 없는 전체 화면이라 풀이 화면과 같은 자리에 둔다 */}
        <Route path={ROUTES.quizResult} element={<QuizResultPage />} />
        {/* 문의하기도 디자인에 NavBar 가 없다 — 마이페이지에서 들어와 뒤로가기로 돌아간다 */}
        <Route path={ROUTES.inquiry} element={<InquiryPage />} />
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
