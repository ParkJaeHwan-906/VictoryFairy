import { useEffect, useRef, useState } from 'react';

/** prefers-reduced-motion 을 한 번만 읽어 둔다(SSR 없음 전제). */
export const reduceMotion =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * 화면에 들어오면 true 가 되고 다시 false 로 돌아가지 않는다.
 * @param {IntersectionObserverInit} options
 * @returns {[React.RefObject, boolean]} [ref, 나타났는가]
 */
export function useInView(options) {
  const ref = useRef(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el || inView) return;

    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        setInView(true);
        io.unobserve(e.target);   // 한 번 나타나면 되돌리지 않는다
      }
    }, options ?? { rootMargin: '0px 0px -12% 0px', threshold: 0.08 });

    io.observe(el);
    return () => io.disconnect();
  }, [inView, options]);

  return [ref, inView];
}
