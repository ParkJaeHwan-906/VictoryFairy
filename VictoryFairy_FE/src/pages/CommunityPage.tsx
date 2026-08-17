import { useState } from 'react';
import profilePlaceholder from '../assets/profile_img.svg';
import LoungeChatSheet from '../components/LoungeChatSheet';
import RankingPodium from '../components/RankingPodium';
import { LIST_RANKING, MY_RANKING, PODIUM_RANKING } from '../data/communityRanking';
import type { RankingEntry } from '../types/community';
import '../styles/CommunityPage.css';

/** 목록 한 줄. 내 순위 줄은 배경·글자색만 다르고 구조는 같다. */
function RankingRow({ entry, isMine = false }: { entry: RankingEntry; isMine?: boolean }) {
  return (
    <li className={`community-page__rank-row${isMine ? ' community-page__rank-row--mine' : ''}`}>
      <span className="community-page__rank-number">{entry.rank}</span>
      <div className="community-page__rank-content">
        <div className="community-page__rank-user">
          <img
            className="community-page__rank-avatar"
            src={entry.avatarUrl ?? profilePlaceholder}
            alt=""
          />
          <span className="community-page__rank-name">{entry.nickname}</span>
        </div>
        <span className="community-page__rank-point">{entry.point}p</span>
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
 * 랭킹·채팅 모두 아직 더미 데이터를 그린다(API 연결 예정).
 */
export default function CommunityPage() {
  const [isChatOpen, setIsChatOpen] = useState(false);

  return (
    <main className="community-page">
      <header className="community-page__header">
        <h1 className="community-page__title">
          퀴즈를 풀고
          <br />
          승리요정 랭킹에 도전해보세요!
        </h1>

        {/* 홈 메인과 같은 시상대라 컴포넌트를 함께 쓴다(`RankingPodium`) */}
        <RankingPodium entries={PODIUM_RANKING} />
      </header>

      <ol className="community-page__ranking">
        {LIST_RANKING.map((entry) => (
          <RankingRow entry={entry} key={entry.id} />
        ))}
        <RankingRow entry={MY_RANKING} isMine />
      </ol>

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
