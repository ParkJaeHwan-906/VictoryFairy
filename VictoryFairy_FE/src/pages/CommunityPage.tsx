import { useState } from 'react';
import profilePlaceholder from '../assets/profile_img.svg';
import LoungeChatSheet from '../components/LoungeChatSheet';
import { LIST_RANKING, MY_RANKING, PODIUM_RANKING } from '../data/communityRanking';
import type { RankingEntry } from '../types/community';
import '../styles/CommunityPage.css';

/**
 * 시상대 노출 순서. 디자인은 2위 - 1위 - 3위 순으로 세운다.
 * 데이터는 순위 오름차순이므로 인덱스로 자리를 바꿔 준다.
 */
const PODIUM_ORDER = [1, 0, 2] as const;

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
 * Figma: SWM / [Lounge] 라운지 메인 (node 724:20029), 기준 프레임 402 x 874
 *
 * 오른쪽 아래 채팅 버튼을 누르면 라운지 채팅 바텀시트가 올라온다.
 * 랭킹·채팅 모두 아직 더미 데이터를 그린다(API 연결 예정).
 *
 * 디자인 하단의 NavBar 는 요청에 따라 구현하지 않았다.
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

        <div className="community-page__podium">
          {PODIUM_ORDER.map((index) => {
            const entry = PODIUM_RANKING[index];

            return (
              <div
                className={`community-page__podium-item community-page__podium-item--rank${entry.rank}`}
                key={entry.id}
              >
                <div className="community-page__podium-profile">
                  <div className="community-page__podium-label">
                    <p className="community-page__podium-point">{entry.point}p</p>
                    <p className="community-page__podium-name">{entry.nickname}</p>
                  </div>
                  <img
                    className="community-page__podium-avatar"
                    src={entry.avatarUrl ?? profilePlaceholder}
                    alt=""
                  />
                </div>
                <div className="community-page__podium-bar" aria-hidden="true" />
              </div>
            );
          })}
        </div>
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
