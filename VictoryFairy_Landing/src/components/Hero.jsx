import Reveal from './Reveal.jsx';
import StoreBadges from './StoreBadges.jsx';
import { RELEASE_NOTE } from '../content.jsx';

/* 히어로 — 배경(stadium-band.svg)은 캐릭터가 빠진 순수 벡터라, 그 위에
   레이어드 승요를 얹는다. 세 장은 같은 좌표에 겹쳐 하나의 캐릭터가 된다. */
export default function Hero() {
  return (
    <section className="hero">
      <div className="hero__body">
        <Reveal as="p" className="hero__eyebrow">KBO 참여형 콘텐츠</Reveal>

        <Reveal as="h1" className="hero__title" delay={60}>
          베팅 없이,<br />
          <span className="accent">야구 지식과 안목</span>만으로<br />
          겨룹니다
        </Reveal>

        <Reveal as="p" className="hero__lead" delay={120}>
          매일 AI가 만드는 KBO 퀴즈를 풀고, 쌓인 점수가 <b>BQ 레이팅</b>이 됩니다.<br />
          데드타임에도 비경기일에도, 팬이 할 수 있는 일이 생깁니다.
        </Reveal>

        <Reveal className="hero__cta" delay={180}><StoreBadges /></Reveal>

        <Reveal as="p" className="hero__note" delay={240}>{RELEASE_NOTE}</Reveal>
      </div>

      {/* stadium-band.svg 는 stadium.svg 에서 홈플레이트(원본 y≥181)를 잘라낸 판이다.
          히어로는 캐릭터를 가운데 세우는데 플레이트는 원본에서 오른쪽에 있어 어긋난다. */}
      <div className="hero__stage" aria-hidden="true">
        <img className="hero__bg" src="/assets/brand/stadium-band.svg" alt="" />
        <div className="hero__shadow" />
        <div className="hero__char">
          <img src="/assets/character/character-basic.svg" alt="" />
          <img src="/assets/character/cap-red.svg" alt="" />
          <img src="/assets/character/uniform-basic.svg" alt="" />
        </div>
      </div>
    </section>
  );
}
