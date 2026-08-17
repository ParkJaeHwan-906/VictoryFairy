import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getSupportGameList } from '../api';
import type { Game } from '../api';
import clothingStore from '../assets/clothing_store.svg';
import GameDetailSheet from '../components/GameDetailSheet';
import MatchCard from '../components/MatchCard';
import RankingPodium from '../components/RankingPodium';
import { PODIUM_RANKING } from '../data/communityRanking';
import { ROUTES } from '../routes';
import { useMyProfile } from '../stores/useAccountStore';
import { getTodayInSeoul } from '../utils/date';
import '../styles/MainPage.css';

/**
 * MainPage — 홈 메인.
 * Figma: SWM / [Home] 홈 메인(full) (node 1443:15451)
 *
 * 위에서부터 인사말·캐릭터 자리, 승요 카드 배너, 오늘의 경기, 승리요정 랭킹 순이다.
 *
 * ── 아직 없는 것 두 가지 ────────────────────────────────────────────
 * **승리요정 꾸미기 · 나만의 승요 카드 만들기**는 갈 곳이 정해지지 않아 **버튼만** 두었다
 * (마이페이지의 자리표시 메뉴와 같은 취급 — 눌러도 아무 일도 하지 않는다).
 * 헤더 오른쪽 캐릭터도 에셋이 없어 디자인의 회색 자리(`캐릭터`)를 그대로 옮겼다.
 *
 * **랭킹은 아직 더미다**(`communityRanking`). 라운지와 같은 자료를 그리므로 API 가 붙으면
 * 두 화면이 같은 응답을 나눠 쓰게 된다.
 */
export default function MainPage() {
  // 프로필은 새로고침하면 비어 있는 상태로 시작한다(persist 하지 않는다). 채우는 일은
  // 보호 라우트(`ProtectedRoute`)가 맡는다 — 홈만 거치는 게 아니라 어느 화면으로 바로
  // 들어와도 채워져야 해서 여기서 부르지 않는다.
  const profile = useMyProfile();

  // 서버가 판정하는 "오늘"과 같은 기준(Asia/Seoul). 화면이 살아 있는 동안 고정이다.
  const [today] = useState(getTodayInSeoul);
  const [games, setGames] = useState<Game[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [selectedGame, setSelectedGame] = useState<Game | null>(null);

  /*
   * 내 응원 구단 경기만 받는다 — 거르는 일은 서버가 한다(`GET /games/support`).
   *
   * 프로필이 아직 안 왔더라도 그냥 부른다. 대상 계정은 본문이 아니라 토큰으로 정해지므로
   * 프로필을 기다릴 이유가 없고, 기다리면 두 응답의 도착 순서에 따라 조회가 늦어진다.
   * 프로필은 아래에서 **빈 결과의 문구를 고를 때만** 쓴다.
   */
  useEffect(() => {
    // 늦게 도착한 응답이 떠난 화면을 건드리지 않게 막는다(GamePage 와 같은 방식).
    let alive = true;

    getSupportGameList(today)
      .then((list) => {
        if (alive) setGames(list);
      })
      .catch(() => {
        if (alive) setLoadFailed(true);
      })
      .finally(() => {
        if (alive) setIsLoading(false);
      });

    return () => {
      alive = false;
    };
  }, [today]);

  /**
   * 응원 구단을 고른 적이 없는지 여부.
   *
   * ⚠️ **빈 배열만으로는 알 수 없다** — `GET /games/support` 는 "오늘 경기가 없다"와
   * "응원 구단이 없다"를 똑같은 `[]` 200 으로 돌려준다(docs/game.md). 그래서 프로필의
   * `supportTeam` 으로 가른다. 프로필이 아직 안 왔으면 단정하지 않는다(`null`).
   */
  const hasSupportTeam = profile ? profile.supportTeam !== null : null;

  return (
    <main className="main-page">
      <header className="main-page__hero">
        <div className="main-page__hero-body">
          <h1 className="main-page__greeting">
            {/* 프로필이 아직 없으면 이름 줄만 비운다 — 잠깐 뒤 채워진다. */}
            {profile && `${profile.nickname}님,`}
            <br />
            만나서 반가워요!
          </h1>

          {/* 갈 곳이 정해지지 않았다 — 눌러도 아무 일도 하지 않는다 */}
          <button className="main-page__decorate" type="button">
            <img className="main-page__decorate-icon" src={clothingStore} alt="" />
            <span>승리요정 꾸미기</span>
          </button>
        </div>

        {/*
          캐릭터 에셋이 아직 없다. 디자인에서도 흰 상자에 "캐릭터"라고만 적혀 있어
          그대로 옮긴다 — 임의의 그림을 넣으면 진짜가 왔을 때 무엇을 바꿔야 하는지 흐려진다.
        */}
        <div className="main-page__character" aria-hidden="true">
          캐릭터
        </div>
      </header>

      {/* 갈 곳이 정해지지 않았다 — 눌러도 아무 일도 하지 않는다 */}
      <button className="main-page__promo" type="button">
        {/* 디자인에도 "임시 그래픽"으로 잡혀 있는 두 장의 카드다 */}
        <span className="main-page__promo-graphic" aria-hidden="true" />
        <span className="main-page__promo-text">
          <span className="main-page__promo-title">나만의 승요 카드 만들기</span>
          <span className="main-page__promo-desc">
            야구 경기 직관, 생방송 시청의 순간을 담아보세요
          </span>
        </span>
        <span className="main-page__promo-arrow" aria-hidden="true" />
      </button>

      <section className="main-page__section">
        <div className="main-page__section-head">
          <h2 className="main-page__section-title">오늘의 경기</h2>
          <Link className="main-page__more" to={ROUTES.game}>
            <span>전체 보기</span>
            <span className="main-page__more-arrow" aria-hidden="true" />
          </Link>
        </div>

        {isLoading && <p className="main-page__status">경기를 불러오는 중입니다.</p>}

        {loadFailed && (
          <p className="main-page__status main-page__status--error">
            경기를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {/* 경기가 없는 날은 빈 배열(200)이 온다 — 오류가 아니다. */}
        {!isLoading && !loadFailed && games.length === 0 && (
          <p className="main-page__status">
            {hasSupportTeam === false ? (
              // 구단이 없으면 이 목록은 영원히 비어 있다 — 고르러 갈 길을 남긴다.
              <Link className="main-page__status-link" to={ROUTES.teamSelect}>
                응원 구단을 골라주세요
              </Link>
            ) : (
              '오늘은 응원 구단의 경기가 없어요.'
            )}
          </p>
        )}

        {!isLoading && !loadFailed && games.length > 0 && (
          <ol className="main-page__game-list">
            {games.map((game) => (
              <MatchCard key={game.gameId} game={game} onSelect={setSelectedGame} />
            ))}
          </ol>
        )}
      </section>

      <section className="main-page__section">
        <div className="main-page__section-head">
          <h2 className="main-page__section-title">승리요정 랭킹</h2>
          <Link className="main-page__more" to={ROUTES.community}>
            <span>전체 보기</span>
            <span className="main-page__more-arrow" aria-hidden="true" />
          </Link>
        </div>

        <RankingPodium entries={PODIUM_RANKING} />
      </section>

      {selectedGame && (
        <GameDetailSheet game={selectedGame} onClose={() => setSelectedGame(null)} />
      )}
    </main>
  );
}
