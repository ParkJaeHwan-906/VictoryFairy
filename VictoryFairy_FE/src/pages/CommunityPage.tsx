import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getBqRanking, getMyBqRanking, toAssetUrl } from '../api';
import type { BqRankingEntry } from '../api';
import LoungeChatSheet from '../components/LoungeChatSheet';
import RankingPodium from '../components/RankingPodium';
import { ROUTES } from '../routes';
import { useMyProfile } from '../stores/useAccountStore';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
import '../styles/CommunityPage.css';

/** 시상대에 서는 인원. 앞 세 건이 시상대로 가고 나머지가 목록이 된다. */
const PODIUM_SIZE = 3;

/** 목록 한 줄. 내 순위 줄은 배경·글자색만 다르고 구조는 같다. */
function RankingRow({ entry, isMine = false }: { entry: BqRankingEntry; isMine?: boolean }) {
  return (
    <li className={`community-page__rank-row${isMine ? ' community-page__rank-row--mine' : ''}`}>
      <span className="community-page__rank-number">{entry.rank}</span>
      <div className="community-page__rank-content">
        <div className="community-page__rank-user">
          <img
            className="community-page__rank-avatar"
            src={toAssetUrl(entry.profileImgUrl) ?? profilePlaceholder}
            onError={fallbackToPlaceholder}
            alt=""
          />
          <span className="community-page__rank-name">{entry.nickname}</span>
        </div>
        {/* 시상대와 같은 이유로 `p` 가 아니라 `BQ` 다(`RankingPodium` 주석 참고) */}
        <span className="community-page__rank-score">{entry.bqScore} BQ</span>
      </div>
    </li>
  );
}

/**
 * CommunityPage — 라운지 메인(승리요정 랭킹).
 * Figma: SWM / [Lounge] 라운지 메인 (수정) (node 1090:8107), 기준 프레임 402 x 874
 *
 * 상위 3명은 주황 그라데이션 카드 위에 왕관과 함께 서고, 4위 아래로는 목록이 이어진다.
 * 오른쪽 아래 채팅 버튼을 누르면 라운지 채팅 바텀시트가 올라온다.
 *
 * ── 두 번 부르고, 세 번 부르지 않는다 ─────────────────────────────────
 * 시상대와 목록은 `GET /rankings/bq`(TOP 10) **한 응답을 나눠 쓴다.** TOP 3 전용 경로가
 * 따로 있지만 이 화면에서는 부르지 않는다 — 두 경로는 각각 별개 스냅샷이라 그 사이
 * 누군가 BQ 를 적립하면 시상대와 목록이 서로 어긋난 순위를 보여 줄 수 있다.
 *
 * "내 순위"만 `GET /rankings/bq/me` 로 따로 받는다. TOP 10 안에 내가 있어도 응답에
 * `isMe` 같은 표식이 없어 목록만으로는 어느 줄이 나인지 가릴 수 없기 때문이다.
 * 그래서 10위 안이면 **같은 사람이 목록과 맨 아래에 두 번 나온다** — 디자인이 내 순위를
 * 늘 맨 아래 고정 줄로 두는 형태라 의도된 중복이다.
 *
 * 두 호출은 성패를 함께한다(`Promise.all`). 한 화면 한 덩어리의 정보라, 목록만 그려 두고
 * 내 줄을 조용히 빠뜨리면 "내가 순위 밖"이라는 뜻으로 잘못 읽힌다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityPage() {
  const [isChatOpen, setIsChatOpen] = useState(false);

  // 프로필은 보호 라우트(`ProtectedRoute`)가 채운다 — 빈 결과의 문구를 고를 때만 쓴다.
  const profile = useMyProfile();

  const [ranking, setRanking] = useState<BqRankingEntry[]>([]);
  const [myRanking, setMyRanking] = useState<BqRankingEntry | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    // 늦게 도착한 응답이 떠난 화면을 건드리지 않게 막는다(MainPage·GamePage 와 같은 방식).
    let alive = true;

    Promise.all([getBqRanking(), getMyBqRanking()])
      .then(([list, mine]) => {
        if (!alive) return;
        setRanking(list);
        setMyRanking(mine);
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
  }, []);

  /*
   * 응원 구단을 고른 적이 없는지 여부.
   *
   * ⚠️ **빈 배열만으로는 알 수 없다** — 구단이 없어도, 구단은 있는데 모집단이 비어도
   * 똑같은 `[]` 200 이 온다(docs/ranking.md). 그래서 프로필의 `supportTeam` 으로 가른다.
   * 프로필이 아직 안 왔으면 단정하지 않는다(`null`).
   */
  const hasSupportTeam = profile ? profile.supportTeam !== null : null;

  const podium = ranking.slice(0, PODIUM_SIZE);
  const rest = ranking.slice(PODIUM_SIZE);
  const isEmpty = !isLoading && !loadFailed && ranking.length === 0;

  return (
    <main className="community-page">
      <header className="community-page__header">
        <h1 className="community-page__title">
          퀴즈를 풀고
          <br />
          승리요정 랭킹에 도전해보세요!
        </h1>

        {/*
          홈 메인과 같은 시상대라 컴포넌트를 함께 쓴다(`RankingPodium`).
          비어 있을 때 그리지 않는 이유는 카드가 좌우로 꽉 찬 주황 바탕이라, 안이 비면
          내용 없는 주황 띠만 남기 때문이다 — 불러오는 중과 실패에도 같은 모습이 된다.
        */}
        {podium.length > 0 && <RankingPodium entries={podium} />}
      </header>

      {isLoading && <p className="community-page__status">랭킹을 불러오는 중입니다.</p>}

      {loadFailed && (
        <p className="community-page__status community-page__status--error">
          랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      )}

      {/* 모집단이 비어 있는 것은 오류가 아니다 — 정상 200 의 빈 배열이다. */}
      {isEmpty && (
        <p className="community-page__status">
          {hasSupportTeam === false ? (
            // 구단이 없으면 이 순위는 영원히 비어 있다 — 고르러 갈 길을 남긴다.
            <Link className="community-page__status-link" to={ROUTES.teamSelect}>
              응원 구단을 골라주세요
            </Link>
          ) : (
            '아직 순위에 오른 사람이 없어요.'
          )}
        </p>
      )}

      {!isLoading && !loadFailed && ranking.length > 0 && (
        <ol className="community-page__ranking">
          {rest.map((entry, index) => (
            // 응답에 식별자가 없다 — 닉네임도 순위도 겹칠 수 있어 index 로 잡는다.
            <RankingRow entry={entry} key={index} />
          ))}
          {/* 구단이 없으면 `null` 이다. 그때는 강조할 내 줄도 없다. */}
          {myRanking && <RankingRow entry={myRanking} isMine />}
        </ol>
      )}

      <button
        className="community-page__chat-fab"
        type="button"
        onClick={() => setIsChatOpen(true)}
        aria-label="라운지 채팅 열기"
        aria-expanded={isChatOpen}
      >
        <span className="community-page__chat-fab-icon" aria-hidden="true" />
      </button>

      {isChatOpen && <LoungeChatSheet onClose={() => setIsChatOpen(false)} />}
    </main>
  );
}
