import { useEffect, useState } from 'react';
import { NAV_LINKS } from '../content.jsx';

/* 헤더 — Apple 서브내비 패턴. 스크롤과 무관하게 항상 붙어 있고,
   오른쪽 캡슐(앱 다운로드)이 페이지 어디서든 한 번에 닿는 목표다. */
export default function Nav() {
  const [stuck, setStuck] = useState(false);

  useEffect(() => {
    const onScroll = () => setStuck(window.scrollY > 8);
    onScroll();
    addEventListener('scroll', onScroll, { passive: true });
    return () => removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header className={`nav ${stuck ? 'is-stuck' : ''}`} id="nav">
      <a className="nav__brand" href="#top" aria-label="승리요정 홈">
        <img className="nav__emblem" src="/assets/brand/logo.svg" alt="" width="36" height="36" />
        <img className="nav__wordmark" src="/assets/brand/wordmark.svg" alt="승리요정" />
      </a>

      <nav className="nav__links" aria-label="섹션 바로가기">
        {NAV_LINKS.map((l) => <a key={l.href} href={l.href}>{l.label}</a>)}
      </nav>

      <a className="btn btn--primary btn--sm" href="#download">앱 다운로드</a>
    </header>
  );
}
