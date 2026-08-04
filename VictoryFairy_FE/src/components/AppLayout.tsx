import { Outlet } from 'react-router-dom';
import NavBar from './NavBar';
import '../styles/AppLayout.css';

/**
 * AppLayout — 하단 NavBar 가 붙는 화면들의 공통 껍데기.
 *
 * NavBar 를 페이지마다 두면 라우트가 바뀔 때 껐다 켜지면서 선택 표시가 순간이동한다.
 * 레이아웃에 한 번만 두면 화면이 바뀌어도 NavBar 는 그대로 남아, 눌린 탭으로
 * 마름모가 미끄러지는 동작이 이어진다.
 *
 * 여기 감싸이지 않은 라우트(로그인 · 회원가입)에는 NavBar 가 나오지 않는다.
 */
export default function AppLayout() {
  return (
    <>
      <div className="app-layout">
        <Outlet />
      </div>

      <NavBar />
    </>
  );
}
