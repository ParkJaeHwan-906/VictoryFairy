import Reveal from './Reveal.jsx';
import StoreBadges from './StoreBadges.jsx';
import { RELEASE_NOTE, SHOTS } from '../content.jsx';

/* 최종 CTA — 당근 엔딩 패턴. 헤드라인 하나와 배지 둘. */
export default function Download() {
  return (
    <section className="download" id="download">
      <img className="download__mark" src="/assets/brand/wordmark.svg" alt="승리요정" />

      <Reveal as="h2" className="download__title">이번 가을,<br />당신의 안목을 증명하세요</Reveal>

      <Reveal className="hero__cta" delay={80}><StoreBadges /></Reveal>

      <p className="download__note">{RELEASE_NOTE}</p>

      {/* 앱 화면 미리보기 */}
      <div className="shots">
        {SHOTS.map((s, i) => (
          <Reveal as="figure" key={s.src} className="shots__item" delay={i * 60}>
            <img src={`/assets/screens/${s.src}.png`} alt={s.alt} loading="lazy" />
            <figcaption>{s.caption}</figcaption>
          </Reveal>
        ))}
      </div>
    </section>
  );
}
