import { NativeModule, requireNativeModule } from 'expo';

import type { InstagramShareResult, InstagramStickerShareOptions } from './InstagramShare.types';

declare class InstagramShareModule extends NativeModule {
  /**
   * 이 기기에서 스토리 공유가 가능한지 — 사실상 인스타그램 설치 여부다.
   *
   * 공유 버튼을 그릴지 말지 정하는 데 쓴다. 없는 기기에서 버튼을 눌렀을 때
   * 아무 일도 일어나지 않는 것이 제일 나쁜 결과라서 미리 묻는다.
   */
  isAvailableAsync(): Promise<boolean>;

  /**
   * 스티커를 얹은 채 인스타 스토리 편집기를 연다.
   *
   * 이미지 전달 방식이 플랫폼마다 다르다(안드로이드는 파일 URI, iOS는 붙여넣기판)
   * — 그 차이를 여기서 덮고 JS에는 base64 한 장만 남긴다.
   */
  shareStickerAsync(options: InstagramStickerShareOptions): Promise<InstagramShareResult>;
}

export default requireNativeModule<InstagramShareModule>('InstagramShare');
