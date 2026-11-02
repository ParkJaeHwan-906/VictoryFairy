package expo.modules.stickerkeyboard

import androidx.core.content.FileProvider

/**
 * 키보드 전용 FileProvider.
 *
 * 하는 일은 FileProvider 그대로다. 클래스를 따로 두는 건 이름 때문이다 — 인스타 공유
 * 모듈이 이미 `androidx.core.content.FileProvider` 로 provider 를 선언해 두었고, 같은
 * 이름을 한 번 더 쓰면 매니페스트 병합이 실패한다.
 */
class StickerKeyboardFileProvider : FileProvider()
