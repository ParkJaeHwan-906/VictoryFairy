import type { ShareOutcome, StickerShareRequest } from './instagram';

/**
 * 웹이 그린 스티커를 네이티브로 넘기는 다리.
 *
 * ── 왜 이것만 앱이 하나 ────────────────────────────────────────────────
 * 인스타 스토리에 스티커를 얹는 건 앱과 앱 사이의 약속(안드로이드 인텐트 · iOS URL
 * 스킴)이라 브라우저에서는 흉내 낼 수 없다. `navigator.share`로는 OS 공유 시트까지가
 * 끝이고 스티커 겹을 지정할 수 없다. 그래서 **그림은 웹이 그리고, 넘기는 것만 앱이**
 * 한다 — 알림과 같은 분업이다(`src/notifications/bridge.ts`).
 * ──────────────────────────────────────────────────────────────────────
 *
 * 이미지는 base64 문자열로 `postMessage`를 타고 온다. 640×480 PNG 한 장이면 수백 KB
 * 수준이라 이 통로로 충분하다. 더 큰 그림이 필요해지면 그때는 문자열이 아니라 웹이
 * 서버에 올린 URL을 넘기는 쪽을 봐야 한다.
 */

/** 웹이 보낸 다른 메시지와 섞이지 않도록 붙이는 표식. */
const MESSAGE_SOURCE = 'victoryfairy-app/instagram-share';

/**
 * 결과를 돌려줄 때 부르는, 웹이 심어 둔 전역 함수의 이름.
 *
 * 앱이 인스타로 넘어간 뒤의 일은 알 수 없으니 이 값은 "올렸다"가 아니라 "넘겼다"까지다.
 * 그래도 알려줘야 하는 이유는 실패한 경우다 — 인스타가 없거나 이미지가 깨졌을 때
 * 알려주지 않으면 웹은 버튼을 누른 채로 영원히 기다린다.
 */
const RESULT_CALLBACK = '__victoryFairyInstagramShare';

/**
 * 공유가 가능한 기기인지 웹이 읽어 갈 전역 이름.
 *
 * 앱 안인지는 웹이 UA(`VictoryFairyApp`)로 알 수 있지만, 인스타가 깔려 있는지는
 * 앱만 안다. 눌러도 아무 일이 없는 버튼을 그리지 않게 하려고 미리 놓아 둔다.
 */
const AVAILABILITY_FLAG = '__victoryFairyInstagramShareAvailable';

/**
 * 색으로 받아들일 형태 — `#RRGGBB`.
 *
 * 인스타가 어떤 표기까지 읽는지는 문서에 없다. 넓게 받아 두면 값 하나가 어긋났을 때
 * 공유 전체가 조용히 실패하고 원인을 찾기 어려워지므로, 확실한 형태만 통과시키고
 * 나머지는 색 없이(인스타 기본 배경으로) 보낸다.
 */
const HEX_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/;

/** `data:image/png;base64,` 접두사. 웹이 `canvas.toDataURL()` 결과를 그대로 보낼 때 붙어 온다. */
const DATA_URL_PREFIX_PATTERN = /^data:image\/[a-z+]+;base64,/;

/**
 * `onMessage`로 올라온 문자열이 공유 요청인지 보고, 맞으면 그 내용을 돌려준다.
 *
 * 표식이 없거나 형태가 어긋나면 `null` — 화면 보고이거나 알림 쪽 메시지다.
 */
export function parseShareRequest(raw: string): StickerShareRequest | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }

  const message = parsed as Record<string, unknown>;
  if (message.source !== MESSAGE_SOURCE || typeof message.stickerBase64 !== 'string') {
    return null;
  }

  // 접두사가 붙어 있으면 떼고 본문만 넘긴다. 네이티브의 base64 디코더는 이 접두사를
  // 모르고, 남겨 두면 "이미지를 읽을 수 없다"로만 돌아와 원인이 드러나지 않는다.
  const stickerBase64 = message.stickerBase64.replace(DATA_URL_PREFIX_PATTERN, '');
  if (!stickerBase64) {
    return null;
  }

  return {
    stickerBase64,
    backgroundTopColor: asHexColor(message.backgroundTopColor),
    backgroundBottomColor: asHexColor(message.backgroundBottomColor),
  };
}

function asHexColor(value: unknown): string | undefined {
  return typeof value === 'string' && HEX_COLOR_PATTERN.test(value) ? value : undefined;
}

/**
 * 결과를 웹에 돌려주는 스크립트.
 *
 * 전역이 없으면(공유 화면을 떠난 뒤 · 다른 화면) 아무 일도 하지 않는다. 알림 권한을
 * 돌려줄 때와 같은 방식이다 — 값을 웹의 저장소에 밀어 넣지 않고 화면에만 건넨다.
 */
export function shareResultScript(outcome: ShareOutcome): string {
  return `
(function () {
  if (typeof window.${RESULT_CALLBACK} === 'function') {
    window.${RESULT_CALLBACK}(${JSON.stringify(outcome)});
  }
})();
true;
`;
}

/**
 * 공유 가능 여부를 웹에 놓아 두는 스크립트.
 *
 * 함수가 아니라 값으로 두는 건 웹이 아무 때나 읽어야 하기 때문이다. 페이지가 로드될
 * 때마다 다시 놓으므로, 그 사이 인스타를 설치하거나 지운 경우도 다음 로드에 따라온다.
 */
export function shareAvailabilityScript(isAvailable: boolean): string {
  return `
(function () {
  window.${AVAILABILITY_FLAG} = ${isAvailable ? 'true' : 'false'};
})();
true;
`;
}
