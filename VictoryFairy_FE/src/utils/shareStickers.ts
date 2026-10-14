/**
 * 스토리로 내보낼 수 있는 스티커 목록.
 * Figma: SWM / [Sticker] Character · Asset · Text · Number (컴포넌트 1000×1000)
 *
 * 그림은 Figma 컴포넌트를 **그대로 PNG 로 뽑아 둔 것**이다(`assets/stickers/<탭>/NN.png`,
 * 800×800 · 투명 배경). 파일 이름의 번호는 Figma 레이어 이름 앞 번호와 같고, 화면에 보이는
 * 순서도 그 번호 순서다 — 디자인에서 순서를 바꾸면 파일 이름만 바꿔 맞추면 된다.
 *
 * 이름과 설명 문구는 `docs/sticker-text.md` 가 원본이다. Figma 레이어 이름(`1. 야구공`)과
 * 화면 이름(`평범한 야구공`)이 다른 것이 있어 레이어 이름을 쓰지 않는다.
 *
 * ── 배경을 넘기지 않는다 ─────────────────────────────────────────────
 * 예전에는 스티커가 배경 그라데이션 색까지 들고 있었지만, 지금은 **스티커 한 장만** 넘긴다.
 * 사용자가 찍은 사진 위에 붙이는 것이 목적이라, 우리 색이 같이 넘어가면 사진을 덮는다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 탭. 화면 위 고르는 줄의 순서가 이 순서다. */
export type StickerCategoryId = 'character' | 'asset' | 'text' | 'number';

export interface ShareSticker {
  /** `<탭>-<번호>` — 예: `character-01`. 목록 안에서 겹치지 않는다. */
  id: string;
  /** 바텀시트 큰 제목이자 버튼의 접근성 이름. */
  name: string;
  /**
   * 바텀시트 설명. **줄 단위로 둔다** — 문구를 두 줄로 끊어 둔 자리가 있고, 한 문장으로
   * 넘기면 화면 폭에 따라 끊기는 자리가 달라진다.
   */
  description: readonly string[];
  /** 번들된 PNG 주소. 화면에 띄우는 것과 인스타로 넘기는 것이 같은 파일이다. */
  src: string;
}

export interface StickerCategory {
  id: StickerCategoryId;
  /** 고르는 줄의 탭 이름. */
  label: string;
  /** 바텀시트 맨 위 작은 제목. */
  sheetTitle: string;
  stickers: readonly ShareSticker[];
}

/*
 * 폴더마다 한 번에 읽어 둔다. 번호로 꺼내므로 파일이 빠지면 `pick()` 이 바로 알려 준다 —
 * 조용히 빈칸을 그리는 것보다 개발 중에 터지는 쪽이 찾기 쉽다.
 */
const IMAGES: Record<StickerCategoryId, Record<string, string>> = {
  character: import.meta.glob<string>('../assets/stickers/character/*.png', {
    eager: true,
    import: 'default',
  }),
  asset: import.meta.glob<string>('../assets/stickers/asset/*.png', {
    eager: true,
    import: 'default',
  }),
  text: import.meta.glob<string>('../assets/stickers/text/*.png', {
    eager: true,
    import: 'default',
  }),
  number: import.meta.glob<string>('../assets/stickers/number/*.png', {
    eager: true,
    import: 'default',
  }),
};

/** 이름 + 설명 줄들. 순서가 곧 파일 번호다(첫 항목이 `01.png`). */
type StickerCopy = readonly [name: string, ...description: string[]];

function pick(category: StickerCategoryId, copies: readonly StickerCopy[]): ShareSticker[] {
  return copies.map(([name, ...description], index) => {
    const number = String(index + 1).padStart(2, '0');
    const src = IMAGES[category][`../assets/stickers/${category}/${number}.png`];

    if (!src) {
      throw new Error(`스티커 그림이 없어요: assets/stickers/${category}/${number}.png`);
    }

    return { id: `${category}-${number}`, name, description, src };
  });
}

const CHARACTER_COPIES: readonly StickerCopy[] = [
  ['인사하는 승요', '승요가 반갑게 손을 흔들고 있어요.', '가볍게 인사를 건네고 싶은 순간에 활용해 보세요!'],
  ['신난 승요', '승요가 들뜬 마음을 온몸으로 표현하고 있어요.', '응원하거나 신나는 순간을 더 즐겁게 나타내 보세요!'],
  ['간절한 승요', '승요가 두 손을 모아 간절히 바라고 있어요.', '꼭 이루어졌으면 하는 마음을 담아보세요!'],
  ['긴장되는 승요', '승요가 두 손으로 얼굴을 가린 채 슬쩍 바라봐요.', '떨리거나 차마 눈 뜨고 보기 힘든 순간에 딱이에요.'],
  ['충격받은 승요', '예상하지 못한 상황에 승요가 깜짝 놀랐어요!', '말문이 막힐 만큼 놀라운 순간을 표현해 보세요.'],
  ['체념한 승요', '승요가 멍하니 앉아 모든 걸 내려놓았어요...', '더는 할 말이 없는 순간, 승요에게 마음을 맡겨보세요.'],
  ['달려가는 승요', '승요가 신나게 어딘가를 향해 달려가고 있어요.', '직관 가는 길이나 설레는 출발을 함께해요!'],
  ['집중하는 승요', '승요가 두 눈을 크게 뜨고 집중하고 있어요.', '놓치고 싶지 않은 순간에 함께 붙여보세요!'],
  ['행복한 승요', '승요가 온몸으로 행복을 만끽하고 있어요!', '기분 좋은 순간을 더욱 신나게 꾸며보세요.'],
  ['지친 승요', '승요가 힘이 빠져 터덜터덜 걷고 있어요.', '에너지를 모두 써버린 날의 마음을 대신 전해줘요.'],
];

const ASSET_COPIES: readonly StickerCopy[] = [
  ['평범한 야구공', '평범해 보여도 야구에 빠질 수 없는 야구공이에요.', '야구와 관련된 어떤 순간에도 톡 붙여보세요!'],
  ['야구 직관 티켓', '야구장에 다녀온 추억을 담는 티켓이에요.', '오늘의 직관을 특별하게 기록해 보세요!'],
  ['승요 캡모자', '승요가 매일 쓰고 다니는 시그니처 모자에요.', '친구나 선수 사진에 살포시 씌워볼까요?'],
  ['음료 컵', '열띤 응원에 빠질 수 없는 시원한 음료 한 잔!', '목마른 직관의 순간에 함께 붙여보세요.'],
  ['후라이드반 양념반', '야구장 먹거리의 대표 주자, 반반 치킨이에요.', '맛있는 직관 인증샷에 곁들이면 딱이에요!'],
  ['NEMO', '공을 네모 안에 넣어...!', '우리 팀의 스트라이크를 간절히 바랄 때 붙여보세요.'],
  ['승요 가방', '야구장에 갈 준비를 마친 승요의 가방이에요.', '설레는 직관길에 함께 챙겨가 볼까요?'],
  ['메가폰', '우리 팀을 향한 응원 소리를 키워줄 메가폰이에요.', '목청껏 응원하고 싶은 순간에 힘을 더해보세요!'],
  ['찢어진 스케치북', '하고 싶은 말을 마음껏 적을 수 있는 스케치북이에요.', '텍스트 스티커를 활용하거나 하고 싶은 말을 적어보세요!'],
  ['할 말 많은 말풍선', '오늘도 야구 보면서 할 말이 한가득 쌓였나요?', '사진 속 선수나 친구의 속마음을 대신 적어보세요!'],
];

const TEXT_COPIES: readonly StickerCopy[] = [
  ['PLAYBALL', '드디어 기다리던 야구가 시작됐어요!', '경기 시작 전, 설레는 마음을 붙여보세요.'],
  ['HOMERUN', '담장을 넘어가는 짜릿한 한 방!', '우리 팀 홈런이 터졌다면 소리 질러~'],
  ['CLUTCH', '중요한 순간에 등장한 우리 팀 해결사!', '역전타나 끝내기처럼 결정적인 활약을 기록해 보세요.'],
  ['WIN 전광판', '오늘의 야구는 승리로 마무리!', '기분 좋은 승리 인증샷에 빠질 수 없겠죠?'],
  ['LOSE 전광판', '오늘은 야구의 신이 우리 편이 아니었나봐요...', '아쉬운 패배도 야구의 일부, 다음엔 승리를 향해!'],
  ['MVP 전광판', '오늘 경기의 주인공은 바로 너야 너!', '최애 선수의 눈부신 활약을 자랑해 보세요.'],
  ['공을 네모 안에 넣어', '스트라이크존은 바로 여기라고...!', '볼넷이 걱정될 때 간절한 다음을 담아 붙여보세요.'],
  ['승리요정 등장', '오늘도 승리를 위해 승요가 출동했어요.', '직관 인증샷에 붙여 오늘의 승요가 되어보세요!'],
  ['내가 이러니 야구를 못 끊지', '끊으려다가도 어느새 또 보고 있는 야구의 굴레...', '속 터지는 경기 중이라면 이 스티커로 마음을 달래봐요.'],
];

/**
 * 숫자는 문구가 색과 글자만 바뀌는 같은 틀이라 만들어 쓴다(`docs/sticker-text.md` 의
 * Red 1 · Red Colon · Yellow 1 · Yellow Colon 네 줄이 원본). 순서는 Figma 와 같다 —
 * 색마다 1~9, 0, 콜론.
 */
function numberCopies(color: 'Red' | 'Yellow', colorName: string): StickerCopy[] {
  const digits = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map(
    (digit): StickerCopy => [
      `${color} ${digit}`,
      `${colorName}의 숫자 ${digit} 스티커에요.`,
      '다른 숫자와 조합하여 점수나 선수 번호를 표현해보세요!',
    ],
  );

  return [
    ...digits,
    [`${color} Colon`, `${colorName}의 콜론 스티커에요.`, '숫자 스티커와 조합하여 점수를 표현해보세요!'],
  ];
}

export const STICKER_CATEGORIES: readonly StickerCategory[] = [
  {
    id: 'character',
    label: '캐릭터',
    sheetTitle: '캐릭터 스티커',
    stickers: pick('character', CHARACTER_COPIES),
  },
  { id: 'asset', label: '에셋', sheetTitle: '에셋 스티커', stickers: pick('asset', ASSET_COPIES) },
  { id: 'text', label: '텍스트', sheetTitle: '텍스트 스티커', stickers: pick('text', TEXT_COPIES) },
  {
    id: 'number',
    label: '숫자',
    sheetTitle: '숫자 스티커',
    stickers: pick('number', [...numberCopies('Red', '붉은 색'), ...numberCopies('Yellow', '노란 색')]),
  },
];

/**
 * 인스타에 넘길 base64 를 만든다. 번들된 PNG 를 그대로 읽을 뿐 다시 그리지 않는다 —
 * 화면에 보이던 그림과 넘어간 그림이 다를 일이 없다.
 */
export async function loadStickerBase64(src: string): Promise<string> {
  const response = await fetch(src);

  if (!response.ok) {
    throw new Error('스티커를 불러오지 못했어요.');
  }

  const blob = await response.blob();

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('스티커를 불러오지 못했어요.'));
    reader.readAsDataURL(blob);
  });
}
