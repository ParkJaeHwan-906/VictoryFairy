import { toAssetUrl } from '../api';
import type { BqRankingEntry } from '../api';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
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
 *
 * ── 받는 값은 서버 응답 그대로다 ──────────────────────────────────────
 * `GET /rankings/bq/top`(홈) · `GET /rankings/bq` 의 앞 3건(라운지)이 그대로 들어온다.
 * 두 호출의 항목 모양이 같아 이 컴포넌트는 어느 쪽에서 왔는지 알 필요가 없다.
 *
 * 자리 배치용 CSS 는 `--rank1`·`--rank2`·`--rank3` 세 개뿐인데, 동점이면 서버가 같은
 * 등수를 보낸다(1·1·3). 그래서 **칸의 크기는 `rank` 값이 아니라 배열에서의 자리**로
 * 정한다 — `rank` 로 잡으면 1·1·3 일 때 두 칸이 같은 크기로 서고 3위 칸의 규칙이 없어
 * 배치가 통째로 무너진다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 노출 순서. 디자인은 2위 - 1위 - 3위 순으로 세운다(데이터는 순위 오름차순으로 받는다). */
const PODIUM_ORDER = [1, 0, 2] as const;

type RankingPodiumProps = {
  /** 1~3위. **순위 오름차순**으로 넘긴다 — 자리 배치는 이 컴포넌트가 바꾼다. */
  entries: readonly BqRankingEntry[];
};

export default function RankingPodium({ entries }: RankingPodiumProps) {
  return (
    <div className="ranking-podium">
      {PODIUM_ORDER.map((index) => {
        const entry = entries[index];
        // 3명이 다 차기 전(로딩·모집단 부족)에도 카드가 통째로 깨지지 않게 빈 자리는 건너뛴다.
        if (!entry) return null;

        return (
          <div
            className={`ranking-podium__item ranking-podium__item--rank${index + 1}`}
            /* 응답에 식별자가 없다(`BqRankingEntry` 주석 참고). 자리는 셋으로 고정이라 index 로 충분하다. */
            key={index}
          >
            {/* 순위를 알리는 것은 왕관 크기뿐이라 자리 표시가 아니라 뜻이 있는 그림이다 */}
            <span className="ranking-podium__crown" aria-hidden="true" />
            <div className="ranking-podium__profile">
              <img
                className="ranking-podium__avatar"
                src={toAssetUrl(entry.profileImgUrl) ?? profilePlaceholder}
                onError={fallbackToPlaceholder}
                alt=""
              />
              <div className="ranking-podium__label">
                {/*
                  랭킹 축인 BQ 다 — 상점에서 쓰는 포인트가 아니다. 종전에는 `1250p` 로
                  적었는데, 마이페이지·상점의 포인트 표기(`1250P`)와 대소문자 하나만
                  달라 같은 재화로 읽혔다. 단위를 아예 다른 글자로 갈랐다.
                */}
                <p className="ranking-podium__score">{entry.bqScore} BQ</p>
                <p className="ranking-podium__name">{entry.nickname}</p>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
