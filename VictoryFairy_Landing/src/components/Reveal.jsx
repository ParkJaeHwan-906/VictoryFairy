import { useInView } from '../hooks/useReveal.js';

/**
 * 스크롤 리빌 래퍼. .reveal → 화면에 들어오면 .is-in 이 붙는다(styles.css).
 * delay 는 --d 커스텀 프로퍼티로 넘어가 stagger 를 만든다.
 *
 * @param {{as?: any, delay?: number, className?: string, style?: object}} props
 */
export default function Reveal({
  as: Tag = 'div',
  delay = 0,
  className = '',
  style,
  children,
  ...rest
}) {
  const [ref, inView] = useInView();

  return (
    <Tag
      ref={ref}
      className={`reveal ${inView ? 'is-in' : ''} ${className}`.trim()}
      style={delay ? { '--d': `${delay}ms`, ...style } : style}
      {...rest}
    >
      {children}
    </Tag>
  );
}
