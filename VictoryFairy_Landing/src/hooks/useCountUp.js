import { useEffect, useRef, useState } from 'react';
import { reduceMotion, useInView } from './useReveal.js';

const DUR = 1400;

/**
 * 화면에 들어오면 0에서 target 까지 세어 올린 문자열을 돌려준다.
 * @param {number} target
 * @param {{comma?: boolean, suffix?: string}} opts
 * @returns {[React.RefObject, string]} [ref, 표시할 텍스트]
 */
export function useCountUp(target, { comma = false, suffix = '' } = {}) {
  const [ref, inView] = useInView({ threshold: 0.6 });
  const [value, setValue] = useState(0);
  const raf = useRef(0);

  useEffect(() => {
    if (!inView) return;
    if (reduceMotion) { setValue(target); return; }

    let t0 = null;
    const tick = (t) => {
      if (t0 === null) t0 = t;
      const p = Math.min((t - t0) / DUR, 1);
      // easeOutExpo — 빠르게 올라가다 부드럽게 멈춘다
      const e = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
      setValue(Math.round(target * e));
      if (p < 1) raf.current = requestAnimationFrame(tick);
    };
    raf.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf.current);
  }, [inView, target]);

  const text = (comma ? value.toLocaleString('ko-KR') : String(value)) + suffix;
  return [ref, text];
}
