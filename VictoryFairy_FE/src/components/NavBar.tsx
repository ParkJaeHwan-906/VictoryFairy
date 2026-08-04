import { useEffect, useId, useRef, type CSSProperties } from 'react';
import { Link, useLocation } from 'react-router-dom';
import ballIcon from '../assets/ball.svg';
import ballActiveIcon from '../assets/ball_f.svg';
import chatIcon from '../assets/chat.svg';
import chatActiveIcon from '../assets/chat_f.svg';
import homeIcon from '../assets/home.svg';
import homeActiveIcon from '../assets/home_f.svg';
import profileIcon from '../assets/profile.svg';
import profileActiveIcon from '../assets/profile_f.svg';
import { ROUTES } from '../routes';
import '../styles/NavBar.css';

type NavBarItem = {
  key: string;
  label: string;
  to: string;
  icon: string;
  activeIcon: string;
};

/** 탭 순서는 디자인 그대로다(홈 · 경기 · 라운지 · 마이). */
const NAV_ITEMS: NavBarItem[] = [
  { key: 'home', label: '홈', to: ROUTES.main, icon: homeIcon, activeIcon: homeActiveIcon },
  { key: 'game', label: '경기', to: ROUTES.game, icon: ballIcon, activeIcon: ballActiveIcon },
  {key: 'lounge', label: '라운지', to: ROUTES.community, icon: chatIcon, activeIcon: chatActiveIcon },
  { key: 'my', label: '마이', to: ROUTES.my, icon: profileIcon, activeIcon: profileActiveIcon },
];

/**
 * 흰 바에서 파여 나갈 자리.
 * 세로는 바 위끝을 0 으로 두고, 가로는 파인 자리 **한가운데를 0** 으로 잡았다.
 * 그래서 이 도형 하나를 translateX 로 밀기만 하면 파인 자국이 탭 사이를 미끄러진다.
 *
 * 좌표는 Figma 의 Union(node 553:3595) 을 옮긴 것이다.
 *   - Union 은 바 위끝보다 11px 아래에서 시작하므로 y 에 11 을 더했다.
 *   - Union 의 x 0~80 은 한가운데가 40 이므로 x 에서 40 을 뺐다.
 *   - 양옆 이음새(Figma 의 radius 10 모서리)는 반지름 11 짜리 호로 이었다.
 *     Union 이 y=11 에서 시작하니 그 높이에서 만나야 곡선이 끊기지 않는다.
 */
const NOTCH_PATH = [
  'M-51 0',
  'A11 11 0 0 1 -40 11',
  'C-39.734 14.558 -38.259 18.05 -35.539 20.77',
  'L-10.77 45.539',
  'C-4.822 51.487 4.822 51.487 10.77 45.539',
  'L35.539 20.77',
  'C38.259 18.05 39.734 14.558 40 11',
  'A11 11 0 0 1 51 0',
  'Z',
].join('');

/**
 * 선택된 탭의 주황 마름모(68 x 68).
 * Figma Rectangle 71(node 553:3602) 의 좌표 그대로다. 모서리가 단순 원호가 아니라
 * Figma 의 코너 스무딩이 들어간 곡선이라 border-radius 로는 대신할 수 없다.
 */
const DIAMOND_PATH =
  'M3.79172 43.154C-1.26391 38.0984 -1.26391 29.9016 3.79172 24.846L24.846 3.79172C29.9016 -1.26391 38.0984 -1.26391 43.154 3.79172L64.2083 24.846C69.2639 29.9016 69.2639 38.0984 64.2083 43.154L43.154 64.2083C38.0984 69.2639 29.9016 69.2639 24.846 64.2083L3.79172 43.154Z';

/** 한 칸. 아이콘·글자는 늘 회색으로 그리고, 선택 표시는 따로 떠다니는 마름모가 맡는다. */
function NavBarItemLink({ item, isActive }: { item: NavBarItem; isActive: boolean }) {
  return (
    <Link
      className={`nav-bar__link${isActive ? ' nav-bar__link--active' : ''}`}
      to={item.to}
      aria-current={isActive ? 'page' : undefined}
    >
      <span
        className="nav-bar__icon"
        style={{ '--nav-bar-icon': `url("${item.icon}")` } as CSSProperties}
        aria-hidden="true"
      />
      <span className="nav-bar__label">{item.label}</span>
    </Link>
  );
}

/**
 * NavBar — 화면 하단 고정 내비게이션.
 * Figma: SWM / [Home] 홈 메인 (node 443:4359) 안의 Bar/Bottom (node 554:4503), 기준 프레임 402 x 874
 *
 * 탭을 누르면 주황 마름모가 그 자리로 미끄러지고, 흰 바탕도 같은 속도로 파이며 따라간다.
 * 마름모와 파인 자국은 각각 도형 하나뿐이고 위치가 `--nav-bar-active-index` 로만 정해지므로,
 * 그 값을 CSS 로 보간하면 둘이 어긋날 일 없이 같이 움직인다(NavBar.css 의 `@property` 참고).
 *
 * 화면이 바뀌어도 이 움직임이 이어지려면 컴포넌트가 살아 있어야 하므로,
 * 페이지마다 두지 않고 AppLayout 에 한 번만 둔다. 아래 여백도 그쪽에서 잡는다.
 */
export default function NavBar() {
  const { pathname } = useLocation();
  /* useId 값에는 CSS·URL 에서 다루기 껄끄러운 문자가 섞여 있어 걸러 쓴다. */
  const maskId = `nav-bar-notch-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  /* 목록에 없는 경로면 파인 자국 없이 평평한 바로 그린다. */
  const activeIndex = NAV_ITEMS.findIndex((item) => item.to === pathname);
  const hasActive = activeIndex >= 0;
  const slideIndex = Math.max(activeIndex, 0);
  const pillItem = NAV_ITEMS[slideIndex];

  /*
   * 몇 칸을 건너가는지에 따라 걸리는 시간을 달리한다(NavBar.css 의 slide-duration).
   * 거리를 재려면 이번 렌더가 아직 직전 자리를 알고 있어야 하므로,
   * 기준값은 화면에 반영된 뒤에 갱신한다.
   */
  const previousIndexRef = useRef(slideIndex);
  const slideDistance = Math.max(Math.abs(slideIndex - previousIndexRef.current), 1);

  useEffect(() => {
    previousIndexRef.current = slideIndex;
  }, [slideIndex]);

  return (
    <nav
      className={`nav-bar${hasActive ? '' : ' nav-bar--flat'}`}
      style={
        {
          '--nav-bar-active-index': String(slideIndex),
          '--nav-bar-slide-distance': String(slideDistance),
        } as CSSProperties
      }
      aria-label="주요 화면"
    >
      {/*
        흰 바탕. 파인 자리는 mask 로 도려낸다.
        도형을 좌표로 들고 있어야 위치를 이어서 바꿀 수 있어 파일 대신 인라인으로 그린다.
      */}
      <svg className="nav-bar__bar" height="60" aria-hidden="true" focusable="false">
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="100%" height="60">
          <rect width="100%" height="60" fill="#fff" />
          <path className="nav-bar__notch" d={NOTCH_PATH} fill="#000" />
        </mask>
        <rect width="100%" height="60" mask={`url(#${maskId})`} />
      </svg>

      <ul className="nav-bar__list">
        {NAV_ITEMS.map((item, index) => (
          <li className="nav-bar__item" key={item.key}>
            <NavBarItemLink item={item} isActive={index === activeIndex} />
          </li>
        ))}
      </ul>

      {/*
        선택 표시. 탭마다 두지 않고 하나만 두고 옮긴다.
        읽을 내용은 위 목록에 이미 있으므로 보조기기에서는 숨긴다.
      */}
      <div className="nav-bar__pill" aria-hidden="true">
        <svg className="nav-bar__diamond" viewBox="0 0 68 68" focusable="false">
          <path d={DIAMOND_PATH} />
        </svg>
        <span
          className="nav-bar__pill-icon"
          style={{ '--nav-bar-icon': `url("${pillItem.activeIcon}")` } as CSSProperties}
        />
        <span className="nav-bar__pill-label">{pillItem.label}</span>
      </div>

      {/* 홈 인디케이터 영역. 값이 0 인 브라우저에서는 높이도 0 이다. */}
      <div className="nav-bar__safe-area" aria-hidden="true" />
    </nav>
  );
}
