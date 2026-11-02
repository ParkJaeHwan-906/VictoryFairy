import { NativeModule, requireOptionalNativeModule } from 'expo';

import type { StickerKeyboardSource, StickerKeyboardStatus } from './StickerKeyboard.types';

declare class StickerKeyboardModule extends NativeModule {
  /**
   * 목록대로 그림을 내려받아 두고 키보드 목록을 바꾼다. 저장한 스티커 수를 돌려준다.
   *
   * 이미 받은 그림은 다시 받지 않는다. 하나라도 받지 못하면 던지고, 그때 키보드는
   * 이전 목록을 그대로 쓴다.
   */
  syncStickersAsync(stickers: StickerKeyboardSource[]): Promise<number>;

  getStatusAsync(): Promise<StickerKeyboardStatus>;

  /**
   * 인스타그램을 연다(가능하면 스토리 카메라로). 스티커는 넘기지 않는다 — 키보드에 있다.
   * `opened` 는 "넘어갔다"까지다.
   */
  openInstagramAsync(): Promise<'opened' | 'instagram-missing'>;

  /**
   * 키보드를 켜는 설정 화면을 연다. 켜는 것은 사용자가 직접 해야 한다.
   * 안드로이드는 설정 → 키보드 목록, iOS 는 설정 → 승리요정(그 안의 '키보드'에서 '전체 접근 허용'까지).
   */
  openSettingsAsync(): Promise<void>;
}

/**
 * 안드로이드와 iOS 에 있다(iOS 키보드 자체는 `targets/sticker-keyboard/` 의 확장 타깃이다).
 * 이 모듈이 들어가기 전 빌드나 웹에서는 `null` 이다 — 부르는 쪽이 "이 기기에는 키보드가 없다"로 처리한다.
 */
export default requireOptionalNativeModule<StickerKeyboardModule>('StickerKeyboard');
