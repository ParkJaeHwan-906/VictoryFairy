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

  /** 설정 → 키보드 목록 화면을 연다. 켜는 것은 사용자가 직접 해야 한다. */
  openSettingsAsync(): Promise<void>;
}

/**
 * 키보드는 안드로이드에만 있다. iOS 는 키보드 확장이 별도 타깃이라 이 모듈이 없고,
 * 그때 `null` 이다 — 부르는 쪽이 "이 기기에는 키보드가 없다"로 처리한다.
 */
export default requireOptionalNativeModule<StickerKeyboardModule>('StickerKeyboard');
