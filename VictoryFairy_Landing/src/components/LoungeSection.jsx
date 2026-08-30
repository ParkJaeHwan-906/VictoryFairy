import { useEffect, useState } from 'react';
import Reveal from './Reveal.jsx';
import { CHAT } from '../content.jsx';
import { reduceMotion, useInView } from '../hooks/useReveal.js';

const STEP = 700;   // 말풍선 하나가 올라오는 간격(ms)

/* 기능 ③ 라운지 — 화면에 들어오면 메시지가 아래에서 하나씩 올라온다. */
export default function LoungeSection() {
  const [listRef, played] = useInView({ threshold: 0.4 });
  const [shown, setShown] = useState(0);

  // 한 줄씩 붙이면 오래된 말풍선이 위로 밀려 나가 실제 대화처럼 흐른다
  useEffect(() => {
    if (!played) return;

    if (reduceMotion) { setShown(CHAT.length); return; }

    setShown(1);
    const id = setInterval(() => {
      setShown((n) => {
        if (n >= CHAT.length) { clearInterval(id); return n; }
        return n + 1;
      });
    }, STEP);
    return () => clearInterval(id);
  }, [played]);

  return (
    <section className="feature" id="lounge">
      <div className="feature__inner">
        <div className="feature__copy">
          <Reveal as="p" className="eyebrow">기능 ③</Reveal>

          <Reveal as="h2" delay={60}>같은 구단 팬만<br />모이는 실시간 라운지</Reveal>

          <Reveal as="p" className="lead" delay={120}>
            방 목록 · 입장 · 전송 등 모든 경로에서 <b>응원 구단이 일치해야</b> 접근이 열립니다.
            상대 팀 팬의 도발성 채팅이 구조적으로 차단됩니다.
          </Reveal>

          <Reveal as="ul" className="ticks" delay={180}>
            <li><b>자동 검열이 상시 동작합니다.</b> 패턴 검열과 LLM 검열 2단계로 욕설·광고를 걸러냅니다.</li>
            <li><b>신고 시 즉시 블라인드</b> 처리되고, 이후 조회에서 제외됩니다.</li>
          </Reveal>
        </div>

        <Reveal className="phone" delay={120}>
          <div className="phone__screen phone__screen--chat">
            <div className="chat" id="chat">
              <p className="chat__title">라운지 채팅</p>
              <ul className="chat__list" id="chatList" ref={listRef}>
                {CHAT.slice(0, shown).map((m, i) => (
                  <li key={i} className={m.me ? 'me' : undefined}>
                    {!m.me && <small>{m.name}</small>}
                    <p>{m.text}</p>
                  </li>
                ))}
              </ul>
              <div className="chat__input"><span>메시지를 입력해주세요</span></div>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
