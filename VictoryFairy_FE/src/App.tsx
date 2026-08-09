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
      {/* 알 수 없는 경로는 로그인으로 되돌린다 */}
      <Route path="*" element={<Navigate to={ROUTES.login} replace />} />
    </Routes>
  );
}
