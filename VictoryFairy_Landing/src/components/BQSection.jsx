import Reveal from './Reveal.jsx';
import { useCountUp } from '../hooks/useCountUp.js';

/* 기능 ② BQ — 앱의 퀴즈 결과 화면이 어두운 전광판이라, 이 섹션도
   통째로 어둡게 간다(흰↔검 교차 리듬). */
export default function BQSection() {
  const [ratingRef, rating] = useCountUp(1240, { comma: true });
  const [rateRef, rate] = useCountUp(80);

  return (
    <section className="feature feature--dark" id="bq">
      <div className="feature__inner feature__inner--rev">
        <div className="feature__copy">
          <Reveal as="p" className="eyebrow">기능 ②</Reveal>

          <Reveal as="h2" delay={60}>BQ — 야구 보는<br />안목을 숫자로</Reveal>

          <Reveal as="p" className="lead" delay={120}>
            타율 · OPS · WAR는 전부 <b>선수</b>를 평가하는 숫자입니다.
            보는 사람을 평가하는 숫자는 없었습니다.
          </Reveal>

          <Reveal as="ul" className="ticks ticks--dark" delay={180}>
            <li><b>맞힐수록 오릅니다.</b> 어려운 문제일수록 더 많은 점수를 얻습니다.</li>
            <li>쌓인 점수가 <b>‘근거 있는 야구 자랑’</b>의 근거가 됩니다.</li>
            <li>구단별 랭킹 필터와 친구 간 비교로 지표에 맥락을 붙입니다.</li>
          </Reveal>
        </div>

        {/* 전광판. 앱의 Ink Black 토큰을 그대로 쓴다 */}
        <Reveal className="board" delay={120}>
          <p className="board__caption">Baseball Quotient</p>
          <div className="board__panel">
            <div className="board__cell">
              <span className="board__tag">BQ 레이팅</span>
              <span className="board__digits" ref={ratingRef}>{rating}</span>
            </div>
            <div className="board__cell">
              <span className="board__tag">정답률</span>
              <span className="board__digits board__digits--sm">
                <span ref={rateRef}>{rate}</span><em>%</em>
              </span>
            </div>
          </div>
          <p className="board__foot">144 / 180 문제 정답</p>
        </Reveal>
      </div>
    </section>
  );
}
