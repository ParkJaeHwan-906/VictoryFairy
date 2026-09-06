export default function Footer() {
  return (
    <footer className="foot">
      <div className="foot__inner">
        <img className="foot__logo" src="/assets/brand/logo.svg" alt="" width="44" height="44" />
        <div className="foot__meta">
          <p className="foot__name">승리요정 <span>VICTORY FAIRY</span></p>
          <p className="foot__team">2026 AI·SW 마에스트로 제17기 · SK하이닉스 팀</p>
        </div>
        <p className="foot__copy">© 2026 VictoryFairy</p>
      </div>
      <p className="foot__disc">
        KBO 및 각 구단의 명칭과 로고는 해당 권리자에게 귀속됩니다.
      </p>
    </footer>
  );
}
