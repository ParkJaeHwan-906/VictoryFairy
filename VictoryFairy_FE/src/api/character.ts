import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { ApiError } from './errors';
import type { ApiResponse } from '../types/api';
import type {
  CharacterItem,
  CharacterItemActiveResult,
  CharacterItemPurchaseResult,
  CharacterItemRequest,
  CharacterItemType,
} from '../types/character';

/**
 * 캐릭터 꾸미기 API (user 모듈).
 *
 * 세 엔드포인트 전부 **인증이 필수**라 모두 `requiresAuth: true` 로 보낸다.
 * 대상 계정은 본문·경로가 아니라 access 토큰으로만 정해지므로 계정 식별자를 넘기지 않는다
 * (account 의 `/users/me`, support 와 같은 방식).
 *
 * 이 도메인만의 계약 셋:
 * - **상점과 인벤토리가 같은 목록이다.** 목록은 카탈로그 전체를 돌려주고 보유는 `having`
 *   으로만 구분된다. "안 산 것만" 걸러 주는 API 는 없으므로 필터는 화면 몫이다.
 * - **아이템은 영구적으로 한 개만 산다.** 재구매는 409 이고 판매·환불·기간제가 없다.
 * - **사는 것과 입는 것이 다르다.** 구매 직후는 `having: true, active: false` 라
 *   입히려면 `toggleCharacterItemActive()` 를 한 번 더 불러야 한다.
 * - **착용은 부위당 하나다.** 켜면 같은 부위의 켜져 있던 것이 자동으로 꺼지고,
 *   끄면 아무것도 대신 켜지지 않는다(서버가 기본 의상을 입혀 주지 않는다).
 *
 * ── 🖼️ 이미지가 두 벌이라 화면도 두 호출을 함께 쓴다 ────────────────
 * 이 모듈이 주는 `displayImg` 는 **상점 진열용**(`stores/...`, 80×80 단독)뿐이다.
 * 캐릭터에 겹쳐 그릴 **착용용**(`items/...`, 160×200)은 `getMyProfile()` 의
 * `characterItems[].imgUrl` 이 준다 — 경로 접두사부터 다른 별개 파일이라 바꿔 쓰면
 * 그림이 어긋난다. 두 값 모두 EP 라 화면에 쓰기 전 `toAssetUrl()` 을 거친다.
 *
 * 그래서 꾸미기 화면은 두 응답의 역할을 나눠 써야 한다:
 * - **카드 목록·보유·체크 상태** → 이 모듈(`having` · `active`)
 * - **아바타 미리보기 그림** → `getMyProfile()`(`characterImgUrl` + `characterItems`)
 *
 * 두 목록을 id 로 이을 수는 없다 — `characterItems[]` 는 `itemType`·`imgUrl` 2개 키뿐이라
 * **아이템 PK 가 없다.** 착용용 이미지를 이 모듈의 응답만으로 알아낼 방법도 없다.
 *
 * ── 🔁 응답이 알려주지 않는 것 ───────────────────────────────────────
 * 토글 응답에는 요청한 아이템의 결과 상태만 실리고 **같은 부위에서 대신 꺼진 아이템은
 * 실리지 않는다.** 목록을 다시 받지 않고 화면 상태만 맞추려면 이 파일의
 * `applyCharacterItemToggle()` 로 배타 규칙을 로컬에 그대로 재현한다.
 *
 * 착용 상태가 바뀌면 `GET /users/me` 의 `characterItems` 도 함께 바뀐다.
 * 토글 응답에는 이미지가 없으므로 **아바타 미리보기를 갱신하려면 `getMyProfile()` 재조회가
 * 필수다** — 이 모듈의 헬퍼로 맞출 수 있는 것은 카드의 체크 상태뿐이다.
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(support·quiz 와 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 도메인 에러 판별
 * ------------------------------------------------------------------ */

/**
 * 캐릭터 도메인 실패 메시지.
 * 백엔드 ErrorCode 이름은 응답에 오지 않아 상태 코드 + message 문자열로만 구분된다.
 * (문구가 바뀌면 이 상수도 함께 갱신해야 한다.)
 */
export const CHARACTER_ERROR_MESSAGE = {
  /** 404 CHARACTER_ITEM_NOT_FOUND — 카탈로그에 없는 id. */
  ITEM_NOT_FOUND: '존재하지 않는 아이템입니다.',
  /** 409 CHARACTER_ITEM_ALREADY_OWNED — 이미 산 아이템의 재구매. */
  ITEM_ALREADY_OWNED: '이미 보유한 아이템입니다.',
  /** 400 INSUFFICIENT_POINT — 잔액이 가격 **미만**(같으면 성공한다). */
  INSUFFICIENT_POINT: '보유 포인트가 부족합니다.',
  /** 404 CHARACTER_ITEM_NOT_OWNED — 카탈로그엔 있지만 사지 않은 아이템의 토글. */
  ITEM_NOT_OWNED: '보유하지 않은 아이템입니다.',
} as const;

function isCharacterError(error: unknown, status: number, message: string): boolean {
  return error instanceof ApiError && error.status === status && error.message === message;
}

/**
 * 404 — 카탈로그에 없는 `characterItemId`(구매 · 토글 공통).
 * 목록이 낡았다는 신호라 재조회가 맞다. "안 산 아이템"과는 다른 상황이다.
 */
export function isCharacterItemNotFound(error: unknown): boolean {
  return isCharacterError(error, 404, CHARACTER_ERROR_MESSAGE.ITEM_NOT_FOUND);
}

/**
 * 409 — 이미 보유한 아이템의 재구매.
 *
 * 잔액 부족보다 **앞서 판정된다** — 포인트가 0 인 계정이 이미 가진 아이템을 다시 사도
 * 400 이 아니라 이쪽이다. 실패지만 사용자 입장에선 "이미 가진 것"이므로
 * 오류 문구보다 착용 토글로 유도하는 편이 낫다.
 */
export function isCharacterItemAlreadyOwned(error: unknown): boolean {
  return isCharacterError(error, 409, CHARACTER_ERROR_MESSAGE.ITEM_ALREADY_OWNED);
}

/** 400 — 포인트 부족. 잔액이 가격과 정확히 같으면 성공하므로 "미만"만 여기 걸린다. */
export function isInsufficientPoint(error: unknown): boolean {
  return isCharacterError(error, 400, CHARACTER_ERROR_MESSAGE.INSUFFICIENT_POINT);
}

/**
 * 404 — 보유하지 않은 아이템의 착용 토글.
 *
 * `isCharacterItemNotFound` 와 상태 코드가 같고 문구만 다르다 — **의도된 구분**이다.
 * 이쪽은 카탈로그에 존재하니 "구매하기"로 유도하면 되고, 저쪽은 목록이 낡았다는
 * 뜻이라 재조회가 맞다. 하나로 뭉치면 화면이 무엇을 띄울지 판단할 근거를 잃는다.
 */
export function isCharacterItemNotOwned(error: unknown): boolean {
  return isCharacterError(error, 404, CHARACTER_ERROR_MESSAGE.ITEM_NOT_OWNED);
}

/* ------------------------------------------------------------------ *
 * 엔드포인트 — 세 개 전부 성공 시 ApiResponse 래핑(200)
 * ------------------------------------------------------------------ */

/**
 * GET /characters/items — 아이템 목록 조회(상점 + 인벤토리). 성공 시 ApiResponse 래핑(200).
 *
 * 요청 파라미터가 0개이고, 대상 계정은 access 토큰으로만 정해진다.
 * **카탈로그 전체**가 보유·착용 여부와 함께 오므로 상점 화면과 인벤토리 화면이
 * 같은 응답을 나눠 쓴다 — 서버에 "보유분만" 을 요청할 방법은 없다.
 *
 * 순서는 **부위 → id** 로 고정이라 그대로 렌더하면 부위별로 묶여 나온다
 * (`groupCharacterItemsByType()` 이 이 순서를 보존한다).
 * 카탈로그가 비어 있어도 404 가 아니라 빈 배열이다.
 *
 * 에러: 401 UNAUTHENTICATED.
 */
export function getCharacterItems(): Promise<CharacterItem[]> {
  return userClient
    .get<ApiResponse<CharacterItem[]>>('/characters/items', { requiresAuth: true })
    .then(unwrap);
}

/**
 * POST /characters/items/purchase — 아이템 구매. 성공 시 ApiResponse 래핑(200).
 *
 * **한 번에 한 개만** 살 수 있고, 성공하면 포인트가 즉시 차감된다.
 * 응답의 `remainingPoint` 가 차감 후 잔액이라 `getMyProfile()` 재조회 없이
 * 화면·스토어의 포인트를 갱신하면 된다.
 *
 * ⚠️ **구매한 아이템은 착용되지 않는다.** 응답 직후 상태는 `having: true, active: false`
 * 라 입히려면 `toggleCharacterItemActive()` 를 한 번 더 보내야 한다.
 * 로컬 목록을 직접 맞출 거라면 `applyCharacterItemPurchase()` 를 쓴다.
 *
 * 🔢 **판정 순서가 고정돼 있다 — ①존재(404) → ②중복 보유(409) → ③잔액(400).**
 * 여러 사유가 동시에 성립해도 앞선 것이 응답을 결정하므로, 화면이 세 판별을
 * 이 순서로 확인해야 실제 원인과 어긋나지 않는다.
 *
 * @param characterItemId 구매할 아이템 PK(`getCharacterItems()` 의 `id`).
 * 에러: 404 존재하지 않음 · 409 이미 보유 · 400 포인트 부족 · 400 본문 누락 · 401 미인증.
 */
export function purchaseCharacterItem(
  characterItemId: number,
): Promise<CharacterItemPurchaseResult> {
  const body: CharacterItemRequest = { characterItemId };

  return userClient
    .post<ApiResponse<CharacterItemPurchaseResult>>('/characters/items/purchase', body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * PUT /characters/items/active — 착용 on/off 토글. 성공 시 ApiResponse 래핑(200).
 *
 * 보유한 아이템의 착용 상태를 **뒤집는다**(지정이 아니라 반전이다).
 * 요청 본문은 구매와 같고, 값은 인벤토리 행 id 가 아니라 **아이템 PK** 다.
 *
 * 동작 규칙:
 * | 요청 시점 상태 | 결과 | 같은 부위의 다른 아이템 |
 * | --- | --- | --- |
 * | 꺼짐 · 같은 부위에 켜진 것 없음 | 켜짐 | 변화 없음 |
 * | 꺼짐 · 같은 부위에 켜진 것 있음 | 켜짐 | **자동으로 꺼짐** |
 * | 켜짐 | 꺼짐 | **아무것도 켜지지 않음** |
 *
 * 🔁 **PUT 이지만 멱등이 아니다.** 같은 요청을 두 번 보내면 켜졌다 꺼진다 —
 * 재시도·중복 클릭을 그냥 흘려보내면 사용자가 의도한 것과 반대 상태로 끝난다.
 * 요청을 세지 말고 응답의 `active` 로 결과 상태를 확인한다.
 *
 * **끄기는 끄기만 한다** — 서버가 기본 의상을 대신 입혀 주지 않는다.
 * **다른 부위는 건드리지 않는다** — 배타 조건의 단위는 계정이 아니라 (계정, 부위)다.
 *
 * 착용 해제는 삭제가 아니라 `active` 만 끄는 것이라 보유 사실은 그대로 남는다.
 *
 * @param characterItemId 토글할 아이템 PK(`getCharacterItems()` 의 `id`).
 * 에러: 404 미보유(카탈로그엔 있음) · 400 본문 누락 · 401 미인증.
 */
export function toggleCharacterItemActive(
  characterItemId: number,
): Promise<CharacterItemActiveResult> {
  const body: CharacterItemRequest = { characterItemId };

  return userClient
    .put<ApiResponse<CharacterItemActiveResult>>('/characters/items/active', body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/* ------------------------------------------------------------------ *
 * 목록 헬퍼 — 응답이 알려주지 않는 규칙을 로컬에 재현한다
 * ------------------------------------------------------------------ */

/**
 * 목록을 부위별로 묶는다. 응답 정렬이 **부위 → id** 라 그 순서를 그대로 보존한다.
 *
 * 부위를 하드코딩하지 않고 실제로 실려 온 값으로만 그룹을 만든다 —
 * `itemType` 은 닫힌 집합이 아니라 서버에서 늘어날 수 있기 때문이다.
 */
export function groupCharacterItemsByType(
  items: CharacterItem[],
): Array<{ itemType: CharacterItemType; items: CharacterItem[] }> {
  const groups: Array<{ itemType: CharacterItemType; items: CharacterItem[] }> = [];

  for (const item of items) {
    const group = groups.find((entry) => entry.itemType === item.itemType);

    if (group) {
      group.items.push(item);
    } else {
      groups.push({ itemType: item.itemType, items: [item] });
    }
  }

  return groups;
}

/**
 * 해당 부위에서 지금 켜져 있는 아이템. 아무것도 안 켜져 있으면 `undefined`
 * — 서버가 기본값을 대신 켜 주지 않으므로 **정상 상태**다(끄기 직후가 그렇다).
 */
export function findActiveCharacterItem(
  items: CharacterItem[],
  itemType: CharacterItemType,
): CharacterItem | undefined {
  return items.find((item) => item.itemType === itemType && item.active);
}

/** 이 아이템을 지금 살 수 있는가. 잔액 경계는 "미만"만 거절이라 같으면 살 수 있다. */
export function canPurchaseCharacterItem(item: CharacterItem, point: number): boolean {
  return !item.having && point >= item.price;
}

/**
 * 구매 성공을 로컬 목록에 반영한다(목록 재조회를 아끼는 용도).
 *
 * **켜 주지 않는다** — 서버와 같이 `having: true, active: false` 로만 만든다.
 * 여기서 `active: true` 로 두면 화면은 입은 것처럼 보이는데 서버는 안 입은 상태라
 * 다음 조회에서 되돌아간다.
 */
export function applyCharacterItemPurchase(
  items: CharacterItem[],
  characterItemId: number,
): CharacterItem[] {
  return items.map((item) =>
    item.id === characterItemId ? { ...item, having: true, active: false } : item,
  );
}

/**
 * 착용 토글 결과를 로컬 목록에 반영한다 — **같은 부위의 배타 규칙까지** 재현한다.
 *
 * 응답에는 요청한 아이템의 결과 상태(`active`)만 실리고 대신 꺼진 아이템은 실리지 않아,
 * 응답만 그대로 반영하면 같은 부위에 켜진 아이템이 둘로 보인다.
 *
 * 규칙은 서버와 같다 — 켤 때만 같은 부위의 다른 아이템을 끄고, 끌 때는 아무것도
 * 대신 켜지 않는다. 다른 부위는 손대지 않는다.
 *
 * @param active 토글 **응답의 `active`** 를 그대로 넘긴다(요청 전 상태를 뒤집지 말 것 —
 *               토글은 멱등이 아니라 화면의 추측이 서버와 어긋날 수 있다).
 */
export function applyCharacterItemToggle(
  items: CharacterItem[],
  characterItemId: number,
  active: boolean,
): CharacterItem[] {
  const target = items.find((item) => item.id === characterItemId);

  if (!target) {
    return items;
  }

  return items.map((item) => {
    if (item.id === characterItemId) {
      return { ...item, active };
    }

    // 켠 경우에만 같은 부위의 다른 아이템이 꺼진다.
    if (active && item.itemType === target.itemType && item.active) {
      return { ...item, active: false };
    }

    return item;
  });
}
