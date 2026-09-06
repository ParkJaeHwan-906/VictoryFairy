import { useEffect, useRef, useState } from 'react';
import { useCountUp } from '../hooks/useCountUp.js';
import { PROBLEM_BARS } from '../content.jsx';

const STEP_COUNT = 3;

/* 문제 제기 — 스티키로 화면을 붙잡고 숫자를 하나씩 드러낸다.
   마지막 "0" 이 이 섹션의 펀치라인이라 혼자 남는다. */
export default function Problem() {
  const trackRef = useRef(null);
  const [step, setStep] = useState(0);
  const [countRef, countText] = useCountUp(1231, { suffix: '만' });

  /* 트랙 상단이 화면 상단을 지나간 만큼을 0~1 로 환산해 3등분한다. */
  useEffect(() => {
    let ticking = false;

    const paint = () => {
      ticking = false;
      const el = trackRef.current;
      if (!el) return;

      const r = el.getBoundingClientRect();
      const span = r.height - innerHeight;
      if (span <= 0) return;

      const p = Math.min(Math.max(-r.top / span, 0), 1);
      setStep(Math.min(Math.floor(p * STEP_COUNT), STEP_COUNT - 1));
    };

    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(paint);
    };

    addEventListener('scroll', onScroll, { passive: true });
    addEventListener('resize', onScroll);
    paint();

    return () => {
      removeEventListener('scroll', onScroll);
      removeEventListener('resize', onScroll);
    };
  }, []);

  const cls = (i) => `pstep ${step === i ? 'is-active' : ''}`;

  return (
    <section className="problem" id="problem">
      <div className="problem__track" ref={trackRef}>
        <div className="problem__pin">
          <p className="problem__eyebrow">PROBLEM</p>

          <div className="problem__stack">
            <div className={cls(0)} data-step="0">
              <p className="pstep__num"><span ref={countRef}>{countText}</span></p>
              <p className="pstep__label">2025 KBO 정규시즌 관중 — 전년 대비 +13%, 역대 최다</p>
            </div>

            <div className={cls(1)} data-step="1">
              <p className="pstep__lead">팬의 시간은 이미 야구에 가 있습니다</p>
              <ul className="bars">
                {PROBLEM_BARS.map((b) => (
                  <li key={b.name}>
                    <span className="bars__name">{b.name}</span>
                    <span className="bars__bar" style={{ '--w': `${b.value}%` }} />
                    <span className="bars__val">{b.value.toFixed(1)}%</span>
                  </li>
                ))}
              </ul>
              <p className="pstep__foot">전부 <b>보고 읽는</b> 행동입니다.</p>
            </div>

            <div className={cls(2)} data-step="2">
              <p className="pstep__num pstep__num--zero">0</p>
              <p className="pstep__label">
                팬이 <b>직접 참여하는</b> 행동은 조사 상위 항목 어디에도 없습니다.<br />
                <span className="muted">KBO 리그 팬 성향 조사 2025 · 만 15세 이상 4,000명</span>
              </p>
            </div>
          </div>
        </div>
      </div>

      <div className="problem__verdict">
        <p className="verdict">
          야구 팬에게는 <span className="accent">증명할 지표</span>도,<br />
          <span className="accent">참여할 콘텐츠</span>도,<br />
          <span className="accent">안전한 소통 공간</span>도 없습니다.
        </p>
        <p className="verdict__sub">
          참여할 수 있는 유일한 통로는 베팅이었습니다.<br />
          예측 콘텐츠를 쓰지 않는 이유의 <b>71%</b>가 사행성·금전 부담입니다.
          <span className="muted">자체 설문 · KBO 시청 경험자 142명 · 2026.7</span>
        </p>
      </div>
    </section>
  );
}
