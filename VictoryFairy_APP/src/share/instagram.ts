import InstagramShare from '../../modules/instagram-share';
import type { InstagramShareResult } from '../../modules/instagram-share';
import { INSTAGRAM_APP_ID } from '../config';

/**
 * 인스타 스토리 공유를 앱 쪽 사정까지 포함해 감싼 층.
 *
 * 네이티브 모듈(`modules/instagram-share`)은 "인스타에 넘긴다"만 알고, 앱 ID가
 * 설정돼 있는지·실패를 어떻게 부를지는 여기서 정한다. 모듈을 다른 앱에 그대로
 * 옮겨도 되게 두려는 경계다.
 */

/**
 * 공유 시도의 결말. 웹에 그대로 전달되므로 `VictoryFairy_FE`가 이 값들을 안다.
 *
 * 어느 쪽이든 사용자에게 보여 줄 말이 다르다는 점이 이 구분의 이유다 — 설치 안내,
 * 그냥 실패, 그리고 우리 잘못(설정 누락)은 같은 문구로 묶을 수 없다.
 */
export type ShareOutcome =
  | InstagramShareResult
  /** 앱 ID가 빌드에 들어가지 않았다. 사용자가 할 수 있는 일이 없는, 우리 쪽 문제다. */
  | 'not-configured'
  /** 이미지가 깨졌거나 인스타를 띄우지 못했다. */
  | 'failed';

/**
 * 이 기기에서 공유 버튼을 그려도 되는지.
 *
 * 인스타가 깔려 있지 않은 기기에서 버튼을 눌렀을 때 아무 일도 일어나지 않는 것이
 * 제일 나쁜 결과라, 웹이 버튼을 그리기 전에 물어볼 수 있게 열어 둔다.
 */
export async function isInstagramShareAvailableAsync(): Promise<boolean> {
  if (!INSTAGRAM_APP_ID) {
    return false;
  }

  try {
    return await InstagramShare.isAvailableAsync();
  } catch {
    // 확인하다 실패했으면 없는 것으로 친다 — 있다고 답해 놓고 눌렀을 때 실패하는
    // 것보다, 처음부터 버튼이 없는 쪽이 덜 이상하다.
    return false;
  }
}

/** 웹이 넘긴 스티커 한 장. 색은 배경 그라데이션이고, 없으면 인스타 기본 배경이다. */
export interface StickerShareRequest {
  stickerBase64: string;
  backgroundTopColor?: string;
  backgroundBottomColor?: string;
}

/**
 * 스티커를 얹은 채 인스타 스토리 편집기를 연다.
 *
 * 던지지 않고 결말을 돌려준다 — 부르는 쪽(`useInstagramShare`)이 어차피 모든 결말을
 * 웹에 그대로 넘겨야 해서, 예외로 갈라 두면 양쪽에서 같은 분기를 두 번 쓰게 된다.
 */
export async function shareStickerAsync(request: StickerShareRequest): Promise<ShareOutcome> {
  if (!INSTAGRAM_APP_ID) {
    return 'not-configured';
  }

  try {
    return await InstagramShare.shareStickerAsync({
      appId: INSTAGRAM_APP_ID,
      stickerBase64: request.stickerBase64,
      backgroundTopColor: request.backgroundTopColor,
      backgroundBottomColor: request.backgroundBottomColor,
    });
  } catch {
    return 'failed';
  }
}
