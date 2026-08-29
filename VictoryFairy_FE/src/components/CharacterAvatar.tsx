import { toAssetUrl } from '../api';
import type { WornCharacterItem } from '../api';
import characterShadow from '../assets/character_shadow.svg';
import '../styles/CharacterAvatar.css';

/**
 * CharacterAvatar — 내 아바타 캐릭터를 착용 아이템까지 겹쳐 그린다.
 * Figma: SWM / Character Position (node 1560:25170 · 1560:25012), 160 x 200.
 *
 * 홈 메인과 캐릭터 꾸미기 두 화면이 같은 그림을 쓴다 — 배치만 다르고(홈은 오른쪽,
 * 꾸미기는 가운데) 안쪽 구성은 똑같아 하나로 둔다.
 *
 * ── 🖼️ 여기 쓰는 이미지는 `GET /users/me` 것뿐이다 ──────────────────
 * 겹쳐 그릴 수 있는 이미지는 **착용용**(`characterItems[].imgUrl`, `items/...`, 160×200)
 * 하나뿐이다. 상점 목록(`getCharacterItems()`)의 `displayImg` 는 진열용(`stores/...`,
 * 80×80 단독)이라 좌표계가 달라 여기에 넣으면 그림이 어긋난다.
 *
 * 그래서 착용을 바꾼 뒤 이 그림을 갱신하려면 **`fetchProfile()` 재조회가 필수**다 —
 * 토글 응답에는 이미지가 실리지 않아 화면이 스스로 만들어 낼 수 없다.
 *
 * ── 겹치는 순서 ──────────────────────────────────────────────────────
 * `characterItems` 는 **부위 순으로 정렬돼 오고 그 순서가 곧 겹치는 순서**라
 * 받은 대로 그린다(docs/account.md). 부위별 z-index 를 화면이 따로 정하지 않는다 —
 * 정해 두면 부위가 늘어나는 날(닫힌 집합이 아니다) 새 부위만 자리를 잃는다.
 */

type CharacterAvatarProps = {
  /**
   * 캐릭터 본체의 EP(`characterImgUrl`). 도메인은 이 컴포넌트가 붙인다.
   *
   * **`null` 이면 통째로 비운다** — 지급을 건너뛴 계정이 드물게 있고(서버가 다음 기동에
   * 채운다) 그때 몸 없이 그림자와 모자만 뜨면 고장으로 보인다. 자리는 그대로 차지해
   * 값이 채워질 때 옆 요소가 밀리지 않게 한다.
   */
  imgUrl: string | null;
  /** 지금 착용 중인 아이템(`characterItems`). 없으면 빈 배열이다. */
  items: readonly WornCharacterItem[];
  /** 바깥에서 자리를 잡을 때 쓰는 추가 클래스. 크기(160×200)는 이 컴포넌트가 갖는다. */
  className?: string;
};

export default function CharacterAvatar({ imgUrl, items, className }: CharacterAvatarProps) {
  const baseUrl = toAssetUrl(imgUrl);
  const rootClassName = className ? `character-avatar ${className}` : 'character-avatar';

  // 몸이 없으면 그림자도 아이템도 그리지 않는다 — 자리만 남긴다.
  if (!baseUrl) {
    return <div className={rootClassName} aria-hidden="true" />;
  }

  return (
    <div className={rootClassName} role="img" aria-label="내 캐릭터">
      {/* 발밑 타원. 캐릭터보다 뒤에 깔린다. */}
      <img className="character-avatar__shadow" src={characterShadow} alt="" aria-hidden="true" />

      <img className="character-avatar__layer" src={baseUrl} alt="" aria-hidden="true" />

      {items.map((item) => {
        const layerUrl = toAssetUrl(item.imgUrl);

        // EP 가 비어 있으면 그 겹만 건너뛴다 — 나머지는 그대로 그린다.
        if (!layerUrl) {
          return null;
        }

        /*
         * key 로 쓸 id 가 없다 — `characterItems` 는 `itemType`·`imgUrl` 두 키뿐이다.
         * 착용은 부위당 하나라 `itemType` 만으로도 이 배열 안에서는 유일하다.
         */
        return (
          <img
            className="character-avatar__layer"
            key={item.itemType}
            src={layerUrl}
            alt=""
            aria-hidden="true"
          />
        );
      })}
    </div>
  );
}
