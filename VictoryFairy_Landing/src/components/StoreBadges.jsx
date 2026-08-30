import { STORE_LINKS } from '../content.jsx';

/* 스토어 배지 — 공식 배지 에셋을 받기 전까지 쓰는 자체 제작본.
   히어로와 최종 CTA 두 곳에서 같은 마크업을 쓴다. */
export default function StoreBadges() {
  return (
    <>
      <a className="store" href={STORE_LINKS.appStore} aria-label="App Store 에서 다운로드">
        <svg className="store__icon" viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M17.05 12.54c-.02-2.2 1.79-3.25 1.87-3.3-1.02-1.49-2.6-1.7-3.16-1.72-1.35-.14-2.63.79-3.31.79-.68 0-1.73-.77-2.85-.75-1.47.02-2.82.85-3.58 2.16-1.52 2.64-.39 6.55 1.1 8.69.73 1.05 1.6 2.22 2.74 2.18 1.1-.04 1.51-.71 2.84-.71 1.33 0 1.7.71 2.86.69 1.18-.02 1.93-1.07 2.65-2.12.84-1.22 1.18-2.4 1.2-2.46-.03-.01-2.3-.88-2.32-3.49zM14.9 5.9c.6-.73 1.01-1.75.9-2.76-.87.04-1.92.58-2.54 1.31-.56.64-1.05 1.68-.92 2.67.97.08 1.96-.49 2.56-1.22z" />
        </svg>
        <span className="store__text"><small>Download on the</small><strong>App Store</strong></span>
      </a>

      <a className="store" href={STORE_LINKS.googlePlay} aria-label="Google Play 에서 다운로드">
        <svg className="store__icon" viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M3.6 2.3c-.3.3-.5.8-.5 1.4v16.6c0 .6.2 1.1.5 1.4l.1.1 9.3-9.3v-.2L3.7 2.2l-.1.1zm12.5 6.2L13.7 6.1 4.6 1.3c-.3-.2-.6-.2-.9-.1l12.4 7.3zM17.9 9.6l-1.8-1.1-2.7 2.7 2.7 2.7 1.8-1.1c.9-.5.9-1.7 0-2.2zM3.7 22.7c.3.1.6.1.9-.1l9.1-4.8 2.4-2.4L3.7 22.7z" />
        </svg>
        <span className="store__text"><small>GET IT ON</small><strong>Google Play</strong></span>
      </a>
    </>
  );
}
