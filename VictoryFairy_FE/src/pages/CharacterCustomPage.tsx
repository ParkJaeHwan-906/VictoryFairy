import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  applyCharacterItemPurchase,
  applyCharacterItemToggle,
  canPurchaseCharacterItem,
  CHARACTER_ITEM_TYPE,
  findActiveCharacterItem,
  getCharacterItems,
  groupCharacterItemsByType,
  isCharacterItemAlreadyOwned,
  isCharacterItemNotFound,
  isCharacterItemNotOwned,
  isInsufficientPoint,
  purchaseCharacterItem,
  toAssetUrl,
  toggleCharacterItemActive,
} from '../api';
import type { CharacterItem, CharacterItemType } from '../api';
import CharacterAvatar from '../components/CharacterAvatar';
import ConfirmSheet from '../components/ConfirmSheet';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import '../styles/CharacterCustomPage.css';

/**
 * CharacterCustomPage — 캐릭터 꾸미기(상점 겸 착용).
 * Figma: SWM / [Custom] 캐릭터 꾸미기 — 머리(node 1468:17418) · 의상(1560:19311) ·
 * 아이템(1560:21220)
 *
 * 홈 히어로 왼쪽 아래 옷 버튼으로 들어온다. NavBar 가 없는 전체 화면이다.
 *
 * ── 한 화면이 상점과 옷장을 겸한다 ────────────────────────────────────
 * `GET /characters/items` 가 카탈로그 **전체**를 보유(`having`)·착용(`active`) 여부와
 * 함께 주므로 목록이 하나다. 그래서 카드의 상태도 셋뿐이다(디자인 Btn/Custom):
 *   ① `active`            → 주황 테두리 + 체크. 지금 입고 있는 것
 *   ② `having && !active` → 회색 테두리 + 열린 자물쇠. 샀지만 벗어 둔 것
 *   ③ `!having`           → 그림이 흐리고 닫힌 자물쇠 + 가격. 아직 안 산 것
 *
 * ── 두 응답을 나눠 쓴다 ──────────────────────────────────────────────
 * **카드**(그림·보유·체크)는 이 화면이 받은 카탈로그가, **미리보기 아바타**는
 * `GET /users/me` 가 그린다. 착용용 이미지는 프로필에만 있고 카탈로그의 `displayImg`
 * 는 진열용이라 좌표계가 다르기 때문이다(`CharacterAvatar` 주석 참고).
 * 그래서 착용을 바꿀 때마다 **카탈로그는 로컬에서 맞추고 프로필은 다시 받는다** —
 * 토글 응답에는 이미지가 실리지 않아 화면이 스스로 만들어 낼 수 없다.
 *
 * ── 사는 것과 입는 것은 다르다(그래서 두 번 부른다) ──────────────────
 * 서버는 구매한 아이템을 **꺼진 채로** 준다. 하지만 화면에서 잠긴 카드를 누르는 뜻은
 * "이걸 입고 싶다" 이므로, 구매가 성공하면 이어서 착용까지 보낸다.
 * 착용이 실패해도 구매는 유효하다 — 그때는 ②(샀지만 벗어 둔 것) 상태로 남고
 * 다시 눌러 입을 수 있다.
 *
 * ── ⚠️ 되돌릴 수 없는 소비다 ────────────────────────────────────────
 * 판매·환불·기간제가 없어 한 번 쓴 포인트는 돌아오지 않는다. 그래서 구매만
 * `ConfirmSheet` 로 한 번 더 묻는다(착용 토글은 공짜라 바로 보낸다).
 */

/* ------------------------------------------------------------------ *
 * 부위 → 탭 이름
 * ------------------------------------------------------------------ */

/**
 * 탭에 쓰는 이름. **서버의 `itemType` 과 글자가 다르다** — 디자인의 탭은
 * `머리 · 의상 · 아이템` 이고 서버 값은 `모자 · 의상 · 소품` 이다.
 *
 * 여기 없는 부위가 오면 서버 값을 그대로 쓴다 — 부위는 닫힌 집합이 아니라서
 * 이름을 못 찾는 일이 정상이고, 그때 탭이 사라지는 것보다 낯선 이름이라도 보이는 편이 낫다.
 */
const TAB_LABEL: Readonly<Record<string, string>> = {
  [CHARACTER_ITEM_TYPE.HEAD]: '머리',
  [CHARACTER_ITEM_TYPE.CLOTH]: '의상',
  [CHARACTER_ITEM_TYPE.ACCESSORY]: '아이템',
};

/**
 * 탭 순서. 서버 정렬은 `의상 → 모자 → 소품` 인데 디자인 탭은 `머리 → 의상 → 아이템`
 * 이라 여기서 다시 세운다. 이 목록에 없는 부위는 뒤에 받은 순서대로 붙인다.
 */
const TAB_ORDER: readonly CharacterItemType[] = [
  CHARACTER_ITEM_TYPE.HEAD,
  CHARACTER_ITEM_TYPE.CLOTH,
  CHARACTER_ITEM_TYPE.ACCESSORY,
];

function tabLabel(itemType: CharacterItemType): string {
  return TAB_LABEL[itemType] ?? itemType;
}

/** 실패를 화면 문구로 옮긴다. 판별 순서는 서버 판정 순서(존재 → 중복 → 잔액)와 같다. */
function describeError(error: unknown): string {
  if (isCharacterItemNotFound(error)) {
    return '없는 아이템이에요. 목록을 다시 불러올게요.';
  }

  if (isCharacterItemAlreadyOwned(error)) {
    return '이미 가지고 있는 아이템이에요.';
  }

  if (isInsufficientPoint(error)) {
    return '포인트가 부족해요.';
  }

  if (isCharacterItemNotOwned(error)) {
    return '아직 구매하지 않은 아이템이에요.';
  }

  return '잠시 후 다시 시도해주세요.';
}

/* ------------------------------------------------------------------ *
 * 카드 한 장
 * ------------------------------------------------------------------ */

type ItemCardProps = {
  item: CharacterItem;
  isPending: boolean;
  onSelect: (item: CharacterItem) => void;
};

function ItemCard({ item, isPending, onSelect }: ItemCardProps) {
  const imageUrl = toAssetUrl(item.displayImg);

  /*
   * 상태 셋 중 하나. `having: false` 면 `active` 도 언제나 false 라(서버 계약)
   * 세 갈래는 겹치지 않는다.
   */
  const state = item.active ? 'active' : item.having ? 'owned' : 'locked';

  return (
    <li className="character-custom-page__cell">
      <button
        className={`character-custom-page__card character-custom-page__card--${state}`}
        type="button"
        onClick={() => onSelect(item)}
        disabled={isPending}
        aria-pressed={item.active}
      >
        {/*
          진열용 그림(80×80). 아직 안 산 것은 디자인대로 흐리게 둔다 —
          잠금은 자물쇠가, "가질 수 있다"는 사실은 그림이 알린다.
        */}
        {imageUrl && (
          <img className="character-custom-page__thumb" src={imageUrl} alt="" aria-hidden="true" />
        )}

        <span className="character-custom-page__name">{item.name}</span>

        <span className="character-custom-page__mark">
          {state === 'active' && (
            <span className="character-custom-page__check" aria-hidden="true" />
          )}
          {state === 'owned' && <span className="character-custom-page__lock-open" aria-hidden="true" />}
          {state === 'locked' && (
            <>
              <span className="character-custom-page__lock-closed" aria-hidden="true" />
              <span className="character-custom-page__price">{item.price}P</span>
            </>
          )}
        </span>
      </button>
    </li>
  );
}

/* ------------------------------------------------------------------ *
 * 화면
 * ------------------------------------------------------------------ */

export default function CharacterCustomPage() {
  const navigate = useNavigate();

  // 미리보기 아바타와 잔액은 프로필이 갖는다. 채우는 일은 ProtectedRoute 가 이미 했다.
  const profile = useMyProfile();
  const fetchProfile = useAccountStore((state) => state.fetchProfile);
  /** 보유 포인트. 프로필이 아직 없으면 0 으로 본다 — 그 사이에는 구매를 막는 쪽이 안전하다. */
  const point = profile?.point ?? 0;

  const [items, setItems] = useState<CharacterItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  /** 지금 선택된 탭의 부위. 목록이 오기 전에는 정할 수 없어 `null` 로 둔다. */
  const [activeType, setActiveType] = useState<CharacterItemType | null>(null);
  /** 응답을 기다리는 아이템 PK. 연타와 교차 요청을 막는다. */
  const [pendingId, setPendingId] = useState<number | null>(null);
  /** 구매 확인을 기다리는 아이템. 확인 시트를 띄우는 조건이기도 하다. */
  const [purchaseTarget, setPurchaseTarget] = useState<CharacterItem | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    // 늦게 도착한 응답이 떠난 화면을 건드리지 않게 막는다(다른 화면들과 같은 방식).
    let alive = true;

    getCharacterItems()
      .then((list) => {
        if (alive) setItems(list);
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
   * 탭은 목록에 **실제로 실려 온 부위**로만 만든다 — 부위가 닫힌 집합이 아니라
   * 미리 세 개를 박아 두면 새 부위가 생기는 날 그 아이템들이 어느 탭에도 못 들어간다.
   * 순서만 디자인대로 다시 세우고(TAB_ORDER), 모르는 부위는 뒤에 붙인다.
   */
  const groups = useMemo(() => {
    const byType = groupCharacterItemsByType(items);
    const rank = (itemType: CharacterItemType) => {
      const index = TAB_ORDER.indexOf(itemType);
      return index === -1 ? TAB_ORDER.length : index;
    };

    // 안정 정렬이라 TAB_ORDER 에 없는 부위끼리는 서버가 준 순서가 남는다.
    return [...byType].sort((a, b) => rank(a.itemType) - rank(b.itemType));
  }, [items]);

  // 목록이 오면 첫 탭을 고른다. 사용자가 고른 뒤에는 건드리지 않는다.
  useEffect(() => {
    setActiveType((current) => current ?? groups[0]?.itemType ?? null);
  }, [groups]);

  const visibleItems = groups.find((group) => group.itemType === activeType)?.items ?? [];
  const wornInTab = activeType ? findActiveCharacterItem(items, activeType) : undefined;

  /**
   * 착용 토글. 성공하면 카탈로그는 로컬에서 맞추고 프로필은 다시 받는다.
   *
   * 로컬 반영에 `applyCharacterItemToggle` 을 쓰는 이유는 **같은 부위에서 대신 꺼진
   * 아이템이 응답에 실리지 않기** 때문이다 — 응답만 그대로 반영하면 한 부위에 체크가
   * 두 개 남는다.
   */
  const runToggle = (characterItemId: number): Promise<void> => {
    return toggleCharacterItemActive(characterItemId).then((result) => {
      setItems((current) =>
        applyCharacterItemToggle(current, result.characterItemId, result.active),
      );

      // 미리보기용 착용 이미지는 프로필에만 있다 — 재조회 말고는 갱신할 방법이 없다.
      return fetchProfile();
    });
  };

  /** 이미 산 아이템의 카드를 눌렀을 때. 켜져 있으면 벗고, 꺼져 있으면 입는다. */
  const handleToggle = (item: CharacterItem) => {
    if (pendingId !== null) return;

    setPendingId(item.id);
    setErrorMessage(null);

    runToggle(item.id)
      .catch((error: unknown) => {
        setErrorMessage(describeError(error));
      })
      .finally(() => {
        setPendingId(null);
      });
  };

  /**
   * "선택 안함" 카드. 이 부위에서 켜져 있는 것을 끈다.
   *
   * 서버에 "부위를 비워라" 는 요청은 없다 — 켜져 있는 그 아이템을 토글해 끄는 것뿐이다.
   * 아무것도 안 켜져 있으면 이미 원하는 상태라 아무 요청도 보내지 않는다.
   */
  const handleClear = () => {
    if (pendingId !== null || !wornInTab) return;

    handleToggle(wornInTab);
  };

  /** 구매 확인 시트에서 "구매하기" 를 눌렀을 때. 성공하면 이어서 입힌다. */
  const handlePurchase = () => {
    const target = purchaseTarget;
    if (!target || pendingId !== null) return;

    setPendingId(target.id);
    setErrorMessage(null);

    purchaseCharacterItem(target.id)
      .then((result) => {
        /*
         * 서버와 같이 "샀지만 안 입은" 상태로 먼저 맞춘다. 바로 아래에서 입히지만,
         * 착용이 실패해도 구매는 유효하므로 이 반영이 남아 있어야 한다.
         */
        setItems((current) => applyCharacterItemPurchase(current, result.characterItemId));

        // 차감 후 잔액이 응답에 실려 오지만 프로필 전체를 다시 받는 편이 단순하다
        // (어차피 미리보기 때문에 재조회가 필요하다).
        return runToggle(result.characterItemId);
      })
      .catch((error: unknown) => {
        setErrorMessage(describeError(error));
        // 판정이 어긋난 경우(목록이 낡음)까지 감안해 잔액·착용을 서버 값으로 되돌린다.
        void fetchProfile();
      })
      .finally(() => {
        setPendingId(null);
        setPurchaseTarget(null);
      });
  };

  /** 카드 하나를 눌렀을 때의 갈림길. 산 것은 바로 토글, 안 산 것은 확인부터. */
  const handleSelect = (item: CharacterItem) => {
    if (item.having) {
      handleToggle(item);
      return;
    }

    setErrorMessage(null);

    /*
     * 잔액이 모자라면 확인 시트를 열지 않는다 — 되돌릴 수 없는 소비를 묻는 자리인데
     * 눌러도 400 이 되는 버튼을 보여 줄 이유가 없다. 판정 자체는 서버가 갖고 있고
     * (경계는 "미만"만 거절이라 가격과 같으면 살 수 있다) 여기는 앞질러 막는 것뿐이라,
     * 어긋나면 서버 응답이 `describeError` 로 다시 잡는다.
     */
    if (!canPurchaseCharacterItem(item, point)) {
      setErrorMessage(`포인트가 부족해요. (${item.price}P 필요 · 보유 ${point}P)`);
      return;
    }

    setPurchaseTarget(item);
  };

  return (
    <div className="character-custom-page">
      <header className="character-custom-page__topbar">
        <button
          className="character-custom-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="character-custom-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="character-custom-page__topbar-title">캐릭터 꾸미기</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="character-custom-page__topbar-spacer" aria-hidden="true" />
      </header>

      {/* 미리보기. 착용을 바꾸면 프로필 재조회로 이 그림이 따라 바뀐다. */}
      <div className="character-custom-page__stage">
        <CharacterAvatar
          className="character-custom-page__avatar"
          imgUrl={profile?.characterImgUrl ?? null}
          items={profile?.characterItems ?? []}
        />
      </div>

      <nav className="character-custom-page__tabs" aria-label="꾸미기 부위">
        {groups.map((group) => (
          <button
            className={`character-custom-page__tab${
              group.itemType === activeType ? ' character-custom-page__tab--active' : ''
            }`}
            key={group.itemType}
            type="button"
            onClick={() => setActiveType(group.itemType)}
            aria-current={group.itemType === activeType}
          >
            {tabLabel(group.itemType)}
          </button>
        ))}
      </nav>

      {isLoading && <p className="character-custom-page__status">아이템을 불러오는 중입니다.</p>}

      {loadFailed && (
        <p className="character-custom-page__status character-custom-page__status--error">
          아이템을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      )}

      {/* 카탈로그가 비어 있어도 오류가 아니다 — 빈 배열 200 이다. */}
      {!isLoading && !loadFailed && groups.length === 0 && (
        <p className="character-custom-page__status">아직 꾸밀 수 있는 아이템이 없어요.</p>
      )}

      {errorMessage && (
        <p className="character-custom-page__status character-custom-page__status--error" role="alert">
          {errorMessage}
        </p>
      )}

      {!isLoading && !loadFailed && groups.length > 0 && (
        <ul className="character-custom-page__grid">
          {/*
            "선택 안함" — 이 부위를 비운다. 아무것도 안 입은 상태가 곧 선택된 상태라
            켜진 아이템이 없으면 이 카드에 체크가 붙는다.
          */}
          <li className="character-custom-page__cell">
            <button
              className={`character-custom-page__card character-custom-page__card--none${
                wornInTab ? '' : ' character-custom-page__card--active'
              }`}
              type="button"
              onClick={handleClear}
              disabled={pendingId !== null}
              aria-pressed={!wornInTab}
            >
              <span className="character-custom-page__none-graphic" aria-hidden="true" />
              <span className="character-custom-page__name">선택 안함</span>
              <span className="character-custom-page__mark">
                {!wornInTab && <span className="character-custom-page__check" aria-hidden="true" />}
              </span>
            </button>
          </li>

          {visibleItems.map((item) => (
            <ItemCard
              key={item.id}
              item={item}
              isPending={pendingId !== null}
              onSelect={handleSelect}
            />
          ))}
        </ul>
      )}

      {purchaseTarget && (
        <ConfirmSheet
          title={`${purchaseTarget.name}을(를) 구매할까요?`}
          description={[
            `${purchaseTarget.price}P 를 사용합니다. (보유 ${point}P)`,
            '한 번 구매한 아이템은 환불할 수 없어요.',
          ]}
          confirmLabel="구매하기"
          pendingLabel="구매 중"
          isPending={pendingId !== null}
          onConfirm={handlePurchase}
          onClose={() => setPurchaseTarget(null)}
        />
      )}
    </div>
  );
}
