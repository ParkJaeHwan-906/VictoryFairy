import { useEffect, useState } from 'react';
import Reveal from './Reveal.jsx';
import { ITEMS, TEAMS } from '../content.jsx';

/* 기능 ④ 캐릭터 — 이 페이지의 하이라이트. Figma 컴포넌트가 레이어 조립식이라
   구단을 고르면 유니폼·모자 레이어만 교체된다. */
export default function CharacterSection() {
  const [teamIdx, setTeamIdx] = useState(0);
  const [itemId, setItemId] = useState('item-balloon');
  const [swapping, setSwapping] = useState(false);

  const team = TEAMS[teamIdx];

  /* 새 SVG 가 뜨는 동안 이전 레이어가 남아 어긋나 보이는 걸 막는다.
     두 프레임 뒤면 브라우저가 새 이미지를 그린 상태다. */
  useEffect(() => {
    setSwapping(true);
    let inner = 0;
    const outer = requestAnimationFrame(() => {
      inner = requestAnimationFrame(() => setSwapping(false));
    });
    return () => { cancelAnimationFrame(outer); cancelAnimationFrame(inner); };
  }, [teamIdx, itemId]);

  return (
    <section className="character" id="character">
      <div className="character__head">
        <Reveal as="p" className="eyebrow">기능 ④</Reveal>

        <Reveal as="h2" delay={60}>내 구단 승요를 키웁니다</Reveal>

        <Reveal as="p" className="lead lead--center" delay={120}>
          퀴즈로 모은 포인트로 모자 · 상의 · 아이템을 사서 캐릭터를 꾸미고,
          그 결과가 <b>공유 카드</b>로 자동 생성됩니다.
        </Reveal>
      </div>

      <Reveal className="dressup" delay={120} style={{ '--team': team.color }}>
        <div className="dressup__stage">
          <img className="dressup__bg" src="/assets/brand/stadium.svg" alt="" aria-hidden="true" />
          <div className="dressup__shadow" aria-hidden="true" />

          {/* 네 레이어는 같은 160×200 좌표계라 겹치면 하나의 캐릭터가 된다 */}
          <div className={`dressup__char ${swapping ? 'is-swapping' : ''}`}>
            <img src="/assets/character/character-basic.svg" alt="승리요정 캐릭터 승요" />
            <img src={`/assets/character/${team.cap}.svg`} alt="" aria-hidden="true" />
            <img src={`/assets/character/uniform-${team.id}.svg`} alt="" aria-hidden="true" />
            <img
              src={itemId ? `/assets/character/${itemId}.svg` : undefined}
              alt=""
              aria-hidden="true"
              hidden={!itemId}
            />
          </div>
        </div>

        <div className="dressup__panel">
          <p className="dressup__label" style={{ color: team.color }}>{team.name}</p>

          <div className="teams" id="teams" role="radiogroup" aria-label="응원 구단 선택">
            {TEAMS.map((t, i) => (
              <button
                key={t.id}
                type="button"
                role="radio"
                aria-checked={i === teamIdx}
                aria-label={t.name}
                title={t.name}
                style={{ '--team': t.color }}
                onClick={() => setTeamIdx(i)}
              >
                <img src={`/assets/teams/${t.id}.png`} alt="" loading="lazy" />
              </button>
            ))}
          </div>

          <p className="dressup__sub">아이템</p>

          <div className="items" id="items" role="radiogroup" aria-label="아이템 선택">
            {ITEMS.map((it) => (
              <button
                key={it.label}
                type="button"
                role="radio"
                aria-checked={it.id === itemId}
                onClick={() => setItemId(it.id)}
              >
                {it.label}
              </button>
            ))}
          </div>
        </div>
      </Reveal>
    </section>
  );
}
