import { useEffect, useRef, useState } from 'react';
import { QUIZZES } from '../content.jsx';

const THRESHOLD = 72;   // 이 이상 밀면 카드가 날아간다
const PIPS = 20;        // 여론 게이지 눈금

/** 한 장의 퀴즈 카드. 맨 위(depth 0)일 때만 드래그 핸들러가 붙는다. */
function Card({ data, no, depth, anim, handlers }) {
  const top = depth === 0;

  const style =
    !top || anim.mode === 'idle' ? undefined
    : anim.mode === 'drag'
      ? { transform: `translate(${anim.dx}px, ${anim.dy * 0.25}px) rotate(${anim.dx * 0.045}deg)` }
    : anim.mode === 'fly'
      ? { transform: `translate(${anim.dir * 560}px, 40px) rotate(${anim.dir * 22}deg)`, opacity: 0 }
      : { transform: '' };   // settle — 제자리로 돌아간다

  const lean = top && anim.mode === 'drag'
    ? (anim.dx < -18 ? ' lean-o' : anim.dx > 18 ? ' lean-x' : '')
    : '';
  const settle = top && (anim.mode === 'settle' || anim.mode === 'fly') ? ' qzcard--settle' : '';

  return (
    <article
      className={`qzcard${settle}${lean}`}
      data-depth={depth}
      style={style}
      {...(top ? handlers : null)}
    >
      <div className="qzcard__meta"><span>{data.date}</span><span>{data.inning}</span></div>
      <p className="qzcard__match">{data.match}</p>
      <span className="qzcard__no">Q{no}.</span>
      <p className="qzcard__q">{data.q}</p>
      <div className="qzcard__ox">
        <div className="ox__o"><div className="ox__mark" /><span>가능하다</span></div>
        <div className="ox__x"><div className="ox__mark" /><span>불가능하다</span></div>
      </div>
      <div className="qzcard__rail"><i>←</i><i>→</i></div>
      <p className="qzcard__count">{no} / 10</p>
    </article>
  );
}

/** 다 푼 뒤 잠깐 보이는 완료 카드. */
function DoneCard() {
  return (
    <article className="qzcard" style={{ cursor: 'default' }}>
      <div className="qzcard__ox" style={{ margin: 'auto 0' }}>
        <div className="ox__o" style={{ gridColumn: '1/-1' }}>
          <div className="ox__mark" style={{ borderColor: 'var(--green-500)' }} />
          <span style={{ fontSize: 14 }}>오늘의 퀴즈 완료!</span>
        </div>
      </div>
      <p className="qzcard__count">앱에서는 매일 새 문제가 열립니다</p>
    </article>
  );
}

/* 퀴즈 카드 더미 — 실제 포인터 드래그로 넘어간다. */
export default function QuizDeck() {
  const [answered, setAnswered] = useState(0);
  const [oPct, setOPct] = useState(65);
  const [anim, setAnim] = useState({ mode: 'idle', dx: 0, dy: 0, dir: 0 });
  const gesture = useRef({ active: false, startX: 0, startY: 0, dx: 0, pid: null });
  const timer = useRef(0);

  const remaining = QUIZZES.slice(answered);
  const done = remaining.length === 0;

  // 덱을 다 비우면 잠시 뒤 처음부터 다시 돈다
  useEffect(() => {
    if (!done) return;
    timer.current = setTimeout(() => { setAnswered(0); setOPct(65); }, 2600);
    return () => clearTimeout(timer.current);
  }, [done]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const release = (e) => {
    const g = gesture.current;
    if (g.pid !== null && e.currentTarget.hasPointerCapture?.(g.pid)) {
      e.currentTarget.releasePointerCapture(g.pid);
    }
    g.active = false;
    g.pid = null;
  };

  const handlers = {
    onPointerDown: (e) => {
      const g = gesture.current;
      g.active = true;
      g.pid = e.pointerId;
      g.startX = e.clientX;
      g.startY = e.clientY;
      g.dx = 0;
      e.currentTarget.setPointerCapture(e.pointerId);
      setAnim({ mode: 'drag', dx: 0, dy: 0, dir: 0 });
    },

    onPointerMove: (e) => {
      const g = gesture.current;
      if (!g.active) return;

      const dx = e.clientX - g.startX;
      const dy = e.clientY - g.startY;

      // 세로로 크게 움직이면 페이지 스크롤 의도로 보고 손을 뗀다
      if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 24) {
        release(e);
        setAnim({ mode: 'settle', dx: 0, dy: 0, dir: 0 });
        return;
      }

      g.dx = dx;
      setAnim({ mode: 'drag', dx, dy, dir: 0 });
    },

    onPointerUp: (e) => {
      const g = gesture.current;
      if (!g.active) return;

      const dx = g.dx;
      release(e);

      if (Math.abs(dx) < THRESHOLD) {
        setAnim({ mode: 'settle', dx: 0, dy: 0, dir: 0 });
        return;
      }

      const dir = dx > 0 ? 1 : -1;
      setAnim({ mode: 'fly', dx: 0, dy: 0, dir });

      // 고른 쪽으로 여론이 조금 기우는 연출
      setOPct((p) => Math.min(92, Math.max(8, p + (dir < 0 ? 6 : -6))));

      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        setAnswered((a) => a + 1);
        setAnim({ mode: 'idle', dx: 0, dy: 0, dir: 0 });
      }, 320);
    },

    onPointerCancel: (e) => {
      if (!gesture.current.active) return;
      release(e);
      setAnim({ mode: 'settle', dx: 0, dy: 0, dir: 0 });
    },
  };

  const on = Math.round((oPct / 100) * PIPS);

  return (
    <div className="qz" id="qz">
      <div className="qz__top">경기 퀴즈</div>

      {/* 뒤 카드가 먼저 그려져야 앞 카드가 위에 온다 */}
      <div className="qz__deck" id="qzDeck">
        {done ? <DoneCard /> : [...remaining.slice(0, 3)].reverse().map((data, i, arr) => {
          const depth = arr.length - 1 - i;
          return (
            <Card
              key={`${answered}-${depth}`}
              data={data}
              no={answered + depth + 1}
              depth={depth}
              anim={anim}
              handlers={handlers}
            />
          );
        })}
      </div>

      <p className="qz__hint">카드를 좌우로 밀어 정답을 선택하세요!</p>

      <div className="qz__gauge">
        <div className="qz__gaugeHead">
          <span className="o">○ <b id="qzO">{oPct}%</b></span>
          <span className="x"><b id="qzX">{100 - oPct}%</b> ✕</span>
        </div>
        <div className="qz__pips" id="qzPips">
          {Array.from({ length: PIPS }, (_, i) => <i key={i} className={i < on ? 'on' : undefined} />)}
        </div>
      </div>
    </div>
  );
}
