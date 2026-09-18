/**
 * 스토리로 내보낼 수 있는 스티커 목록.
 *
 * 지금은 **내 캐릭터** 하나뿐이지만 경기 결과 · 퀴즈 결과처럼 뒤따를 것이 있어,
 * 화면이 종류를 알지 못하게 만들어 둔다 — 공유 화면은 이 배열을 순회하고 고른
 * 항목의 `build()` 를 부를 뿐이라, 스티커를 늘릴 때 고치는 곳은 이 파일 하나다.
 *
 * ── 한 종류가 들고 있어야 하는 것 ─────────────────────────────────────
 * 그릴 겹(`layers`)과 판 크기뿐 아니라 **배경 그라데이션 색까지** 스티커가 정한다.
 * 뒤 색은 인스타가 칠하지만 무엇을 얹느냐에 따라 어울리는 색이 달라서, 스티커와
 * 떨어뜨려 두면 종류가 늘 때마다 화면이 색 분기를 들고 있게 된다.
 * ──────────────────────────────────────────────────────────────────────
 */
import { toAssetUrl } from '../api';
import type { MyProfile } from '../types/account';
import type { StickerCanvasSpec } from './stickerCanvas';

/**
 * 스티커 종류. 라우터 state 로 넘어오는 값이라 화면 밖에서도 쓰인다.
 * 늘어날 때 이 유니온에 더하면 `SHARE_STICKERS` 가 빠뜨린 종류를 타입이 잡아 준다.
 */
export type ShareStickerId = 'character';

/** 고른 스티커로 실제로 만들 한 장. */
export interface ShareStickerPlan {
  canvas: StickerCanvasSpec;
  /** 스티커 뒤를 채울 그라데이션. 인스타가 `#RRGGBB` 만 읽는다. */
  backgroundTopColor: string;
  backgroundBottomColor: string;
}

export interface ShareSticker {
  id: ShareStickerId;
  /** 고르는 자리에 보이는 이름. */
  label: string;
  /** 미리보기 아래 한 줄 설명. */
  description: string;
  /**
   * 지금 이 계정으로 만들 수 있으면 계획을, 만들 수 없으면 `null`.
   *
   * `null` 인 이유는 종류마다 다르지만(캐릭터 미지급 · 기록 없음 …) 화면이 할 말은
   * "아직 만들 수 없다" 하나라, 사유를 실어 보내지 않는다.
   */
  build: (profile: MyProfile) => ShareStickerPlan | null;
}

/**
 * 브랜드 그라데이션. `styles/tokens.css` 의 `--color-primary-400` · `--color-primary-500` 이다.
 *
 * 토큰을 CSS 밖에서 읽을 수는 없어 값을 한 벌 더 적는다 — 앱의 `theme.ts` 가 같은
 * 이유로 같은 일을 하고 있다. 토큰이 바뀌면 여기도 함께 고쳐야 색이 갈라지지 않는다.
 */
const BRAND_GRADIENT_TOP = '#ff7443';
const BRAND_GRADIENT_BOTTOM = '#f04e23';

/**
 * 내 캐릭터 스티커.
 *
 * 화면의 아바타(`CharacterAvatar`)와 같은 겹을 같은 순서로 쌓는다 — 본체 위에
 * 착용 아이템이고, `characterItems` 는 **부위 순으로 정렬돼 오므로 받은 대로** 그린다.
 * 부위별 z-index 를 여기서 정하지 않는 이유는 아바타와 같다(부위는 닫힌 집합이 아니다).
 *
 * 🚫 **발밑 그림자는 빼둔다.** 화면에서는 흰 바탕에 놓여 캐릭터를 지면에 붙여 주지만,
 * 스토리에서는 사용자가 찍은 사진 위에 얹히므로 회색 타원이 얼룩처럼 남는다.
 *
 * 판은 4:5(512×640)다. 인스타 문서의 권장값은 640×480 이지만 그건 가로 스티커 기준이고,
 * 세로로 긴 캐릭터를 거기 넣으면 좌우가 투명으로 비어 정작 캐릭터가 작게 잡힌다 —
 * 스티커의 크기는 **보이는 그림이 아니라 판**이 정하기 때문이다. 원본(160×200)의
 * 비율을 그대로 키워 여백 없이 채운다.
 */
const characterSticker: ShareSticker = {
  id: 'character',
  label: '내 캐릭터',
  description: '지금 입고 있는 아이템까지 그대로 담아요.',

  build: (profile) => {
    const baseUrl = toAssetUrl(profile.characterImgUrl);

    // 몸이 없으면 아이템만 뜬 그림이 된다 — 그럴 바에는 만들지 않는다.
    if (!baseUrl) {
      return null;
    }

    const layers = [{ src: baseUrl }];

    for (const item of profile.characterItems) {
      const layerUrl = toAssetUrl(item.imgUrl);

      // EP 가 비어 있는 겹은 건너뛴다. 아바타와 같은 처리다.
      if (layerUrl) {
        layers.push({ src: layerUrl });
      }
    }

    return {
      canvas: { width: 512, height: 640, layers },
      backgroundTopColor: BRAND_GRADIENT_TOP,
      backgroundBottomColor: BRAND_GRADIENT_BOTTOM,
    };
  },
};

/**
 * 고를 수 있는 스티커. **화면에 보이는 순서가 이 순서다.**
 *
 * 새 종류는 여기에 더하기만 하면 화면에 나타난다 — 공유 화면은 개수도 종류도
 * 알지 못하고, 하나뿐일 때는 고르는 줄을 그리지 않는다.
 */
export const SHARE_STICKERS: readonly ShareSticker[] = [characterSticker];

/** 기본으로 고를 스티커. 라우터 state 가 비었을 때 쓴다. */
export const DEFAULT_SHARE_STICKER_ID: ShareStickerId = characterSticker.id;

/**
 * id 로 스티커를 찾는다. 모르는 값(옛 history · 주소 직접 입력)이면 기본값으로 본다 —
 * 빈 화면을 띄우는 것보다 뭐라도 공유할 수 있는 쪽이 낫다.
 */
export function findShareSticker(id: unknown): ShareSticker {
  const found = SHARE_STICKERS.find((sticker) => sticker.id === id);

  return found ?? SHARE_STICKERS[0];
}
