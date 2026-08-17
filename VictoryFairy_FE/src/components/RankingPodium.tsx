import profilePlaceholder from '../assets/profile_img.svg';
import type { RankingEntry } from '../types/community';
import '../styles/RankingPodium.css';

/**
 * RankingPodium — 승리요정 랭킹 상위 3명이 서는 주황 카드.
 * Figma: [Lounge] 라운지 메인 (1090:8107) · [Home] 홈 메인(full) (1443:15451)
 *
 * 라운지와 홈이 **같은 그림**을 쓴다. 홈에도 같은 카드가 들어오면서 마크업과 90 줄쯤 되는
 * 스타일이 두 벌이 될 상황이라 여기로 모았다 — 두 화면이 나란히 보이는 자리라 한쪽만
 * 손보면 곧바로 어긋나 보인다.
 *
 * 순위를 알리는 것은 **크기뿐**이다(1위만 왕관·사진·이름이 한 단계 크다). 등수 숫자를
 * 따로 쓰지 않으므로 세 칸의 크기 차이가 곧 정보다.
 */

/** 노출 순서. 디자인은 2위 - 1위 - 3위 순으로 세운다(데이터는 순위 오름차순으로 받는다). */
const PODIUM_ORDER = [1, 0, 2] as const;

type RankingPodiumProps = {
  /** 1~3위. **순위 오름차순**으로 넘긴다 — 자리 배치는 이 컴포넌트가 바꾼다. */
  entries: readonly RankingEntry[];
};

export default function RankingPodium({ entries }: RankingPodiumProps) {
  return (
    <div className="ranking-podium">
      {PODIUM_ORDER.map((index) => {
        const entry = entries[index];
        // 3명이 다 차기 전(로딩·부족)에도 카드가 통째로 깨지지 않게 빈 자리는 건너뛴다.
        if (!entry) return null;

        return (
          <div
            className={`ranking-podium__item ranking-podium__item--rank${entry.rank}`}
            key={entry.id}
          >
            {/* 순위를 알리는 것은 왕관 크기뿐이라 자리 표시가 아니라 뜻이 있는 그림이다 */}
            <span className="ranking-podium__crown" aria-hidden="true" />
            <div className="ranking-podium__profile">
              <img
                className="ranking-podium__avatar"
                src={entry.avatarUrl ?? profilePlaceholder}
                alt=""
              />
              <div className="ranking-podium__label">
                <p className="ranking-podium__point">{entry.point}p</p>
                <p className="ranking-podium__name">{entry.nickname}</p>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
