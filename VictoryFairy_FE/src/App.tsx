import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import { ROUTES } from './routes';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to={ROUTES.login} replace />} />
      <Route path={ROUTES.login} element={<LoginPage />} />
      <Route path={ROUTES.signup} element={<SignupPage />} />
      {/* 알 수 없는 경로는 로그인으로 되돌린다 */}
      <Route path="*" element={<Navigate to={ROUTES.login} replace />} />
    </Routes>
  );
}
