/**
 * 스티커 한 장을 캔버스에 그려 PNG 로 만든다.
 *
 * 인스타에 넘길 수 있는 것은 **이미지 한 장**뿐이라, 화면에서 여러 겹으로 그리던
 * 캐릭터를 한 장으로 합치는 자리가 필요하다. 미리보기도 이 함수가 만든 결과를 그대로
 * 띄운다 — DOM 으로 미리보기를 따로 그리면 "화면에서 보던 것과 올라간 것이 다르다"가
 * 생기고, 그 차이는 인스타로 넘어간 뒤에야 보인다.
 *
 * **배경은 그리지 않는다.** 뒤를 채우는 그라데이션은 인스타가 파라미터로 받아 자기
 * 편집기에서 칠하므로, 여기서 그리면 스티커가 사각형 판이 되어 사용자가 찍은 사진
 * 위에 얹는 그림이 아니게 된다. 그래서 결과는 **투명 배경 PNG** 다.
 */

/**
 * 한 겹.
 *
 * `frame` 을 비율(0~1)로 두는 것은 스티커 크기가 종류마다 다르기 때문이다 — 픽셀로
 * 적어 두면 판 크기를 바꿀 때 모든 겹의 좌표를 함께 고쳐야 한다.
 */
export interface StickerLayer {
  /** 이미지 주소(`toAssetUrl()` 을 거친 완성된 URL). 뒤에 오는 겹이 위에 쌓인다. */
  src: string;
  /** 판 안에서 이 겹이 차지할 칸. 생략하면 판 전체다. */
  frame?: { x: number; y: number; width: number; height: number };
}

/** 겹을 어떤 판에 그릴지. 종류마다 비율이 달라 크기를 스티커가 들고 있다. */
export interface StickerCanvasSpec {
  width: number;
  height: number;
  layers: readonly StickerLayer[];
}

/** 판 전체를 쓰는 기본 칸. */
const FULL_FRAME = { x: 0, y: 0, width: 1, height: 1 } as const;

/**
 * 이미지 한 장을 불러온다.
 *
 * ── `crossOrigin` 을 먼저 시도하고 실패하면 빼고 다시 부르는 이유 ──────
 * 캔버스는 **다른 오리진의 이미지를 그리면 오염(taint)되어** `toDataURL()` 이
 * SecurityError 로 막힌다. 막지 않으려면 `crossOrigin='anonymous'` 로 불러야 하는데,
 * 그러면 이번엔 서버가 CORS 헤더를 주지 않을 때 **이미지가 아예 로드되지 않는다.**
 *
 * 운영에서는 웹과 이미지 CDN 이 같은 오리진(`victoryfairy.com`)이라 어느 쪽이든
 * 문제가 없다. 갈리는 것은 로컬 개발(`localhost:5173` → CDN)뿐이라, 되는 쪽을
 * 먼저 시도하고 안 되면 물러선다. 물러선 경우 캔버스가 오염될 수 있고, 그때는
 * `toDataURL()` 이 던지는 것을 아래에서 잡아 사람이 읽을 수 있는 문구로 바꾼다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 너비·높이를 미리 넣어 두는 것은 SVG 때문이다(캐릭터 본체가 `characters/*.svg` 다).
 * 내재 크기가 없는 SVG 는 브라우저에 따라 0×0 으로 잡혀 `drawImage` 가 아무것도
 * 그리지 않는다 — 크기를 주면 그 크기로 래스터화된다.
 */
function loadImage(src: string, width: number, height: number): Promise<HTMLImageElement> {
  const attempt = (withCredentialsMode: boolean) =>
    new Promise<HTMLImageElement>((resolve, reject) => {
      const image = new Image();

      if (withCredentialsMode) {
        image.crossOrigin = 'anonymous';
      }

      image.width = Math.round(width);
      image.height = Math.round(height);
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`이미지를 불러오지 못했습니다: ${src}`));
      image.src = src;
    });

  return attempt(true).catch(() => attempt(false));
}

/**
 * `object-fit: contain` 과 같은 규칙으로 칸 안에 그릴 자리를 잡는다.
 *
 * 화면의 캐릭터(`CharacterAvatar.css`)가 `contain` 이라 여기서도 같아야 한다 —
 * 다르면 미리보기와 화면 속 캐릭터의 비율이 어긋난다.
 */
function containRect(
  imageWidth: number,
  imageHeight: number,
  boxX: number,
  boxY: number,
  boxWidth: number,
  boxHeight: number,
) {
  const scale = Math.min(boxWidth / imageWidth, boxHeight / imageHeight);
  const width = imageWidth * scale;
  const height = imageHeight * scale;

  return {
    x: boxX + (boxWidth - width) / 2,
    y: boxY + (boxHeight - height) / 2,
    width,
    height,
  };
}

/**
 * 겹들을 합쳐 PNG data URL 을 만든다.
 *
 * 한 겹이라도 불러오지 못하면 **통째로 실패시킨다.** 화면의 아바타는 못 그린 겹만
 * 건너뛰지만(나머지는 보여야 하니까), 이쪽은 사용자가 확인할 새도 없이 인스타로
 * 넘어가는 그림이라 모자 없는 캐릭터가 조용히 올라가는 쪽이 더 나쁘다.
 */
export async function renderStickerImage(spec: StickerCanvasSpec): Promise<string> {
  const canvas = document.createElement('canvas');
  canvas.width = spec.width;
  canvas.height = spec.height;

  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('스티커를 그릴 수 없습니다.');
  }

  for (const layer of spec.layers) {
    const frame = layer.frame ?? FULL_FRAME;
    const boxX = frame.x * spec.width;
    const boxY = frame.y * spec.height;
    const boxWidth = frame.width * spec.width;
    const boxHeight = frame.height * spec.height;

    const image = await loadImage(layer.src, boxWidth, boxHeight);
    // SVG 가 내재 크기를 갖지 못하면 0 이 온다 — 그때는 칸을 그대로 쓴다.
    const sourceWidth = image.naturalWidth || boxWidth;
    const sourceHeight = image.naturalHeight || boxHeight;

    const rect = containRect(sourceWidth, sourceHeight, boxX, boxY, boxWidth, boxHeight);
    context.drawImage(image, rect.x, rect.y, rect.width, rect.height);
  }

  try {
    return canvas.toDataURL('image/png');
  } catch {
    /*
     * 캔버스가 오염됐다 — 위에서 `crossOrigin` 없이 물러서 불러온 이미지가 있고
     * 그 오리진이 우리와 다르다는 뜻이다. 운영에서는 같은 오리진이라 일어나지 않고,
     * 로컬 개발에서 CDN 이미지를 쓸 때만 여기로 온다.
     */
    throw new Error('스티커를 만들 수 없습니다. 앱에서 다시 시도해 주세요.');
  }
}
