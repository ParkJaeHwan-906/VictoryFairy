import Reveal from './Reveal.jsx';
import QuizDeck from './QuizDeck.jsx';

/* 기능 ① 매일 퀴즈 — 실제로 밀어볼 수 있는 카드. */
export default function QuizSection() {
  return (
    <section className="feature" id="quiz">
      <div className="feature__inner">
        <div className="feature__copy">
          <Reveal as="p" className="eyebrow">기능 ①</Reveal>

          <Reveal as="h2" delay={60}>매일 자동 생성되는<br />야구 퀴즈</Reveal>

          <Reveal as="p" className="lead" delay={120}>
            그날 열리는 경기와 최신 선수·기록을 담아 <b>매일 새 문제</b>가 올라옵니다.
            어제 푼 문제를 오늘 또 만날 일이 없습니다.
          </Reveal>

          <Reveal as="ul" className="ticks" delay={180}>
            <li><b>쇼츠처럼 넘기며 풉니다.</b> 탭 한 번으로 즉답하고 바로 채점됩니다.</li>
            <li><b>맞히면 난이도에 비례해 포인트</b>가 쌓입니다. EASY 30P ~ EXPERT 120P.</li>
            <li><b>예측형은 정산 가능한 항목만.</b> 승패·득점·점수차·투수 승패/세이브/홀드 —
              경기 기록으로 결과가 확실히 판정되는 것만 나와 분쟁이 없습니다.</li>
          </Reveal>

          <Reveal as="p" className="hint" delay={240}>↔ 카드를 좌우로 밀어보세요</Reveal>
        </div>

        <Reveal className="phone" delay={120}>
          <div className="phone__screen"><QuizDeck /></div>
        </Reveal>
      </div>
    </section>
  );
}
