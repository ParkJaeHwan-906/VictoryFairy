import { registerWebModule, NativeModule } from 'expo';

import type { InstagramShareResult } from './InstagramShare.types';

/**
 * 웹에는 스토리 공유 경로가 없다.
 *
 * 인스타에 스티커를 넘기는 건 앱 사이의 약속(URL 스킴 · 인텐트)이라 브라우저에서는
 * 흉내 낼 수 없다. `navigator.share`로는 OS 공유 시트까지가 끝이고 스티커 겹을
 * 지정할 수 없다. `expo start --web`에서 번들이 깨지지 않도록 없다고만 답한다.
 */
class InstagramShareModule extends NativeModule {
  async isAvailableAsync(): Promise<boolean> {
    return false;
  }

  async shareStickerAsync(): Promise<InstagramShareResult> {
    return 'instagram-missing';
  }
}

export default registerWebModule(InstagramShareModule, 'InstagramShareModule');
