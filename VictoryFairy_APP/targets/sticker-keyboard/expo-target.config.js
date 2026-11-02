/**
 * 승요 스티커 키보드(iOS) — 앱과 별개로 도는 키보드 확장 타깃.
 *
 * `ios/`는 prebuild 가 만들고 gitignore 대상이라 타깃을 거기 둘 수 없다. 이 폴더가 소스이고,
 * `@bacons/apple-targets` 가 prebuild 때 Xcode 프로젝트에 타깃으로 이어 붙인다.
 * 이 파일이나 `app.json` 을 고치면 `npx expo prebuild -p ios --clean` 을 다시 돌려야 한다.
 *
 * App Group 은 앱이 받아 둔 스티커를 키보드가 읽는 통로다. 값은 `app.json` 의
 * `ios.entitlements` · `ios.infoPlist.StickerKeyboardAppGroup`, 이 폴더의 `Info.plist` 와 같아야 한다.
 *
 * @type {import('@bacons/apple-targets/app.plugin').ConfigFunction}
 */
module.exports = (config) => ({
  type: 'keyboard',
  // 앱 쪽 모듈 pod 이름(`StickerKeyboard`)과 겹치면 안 된다. 같으면 앱의 `import StickerKeyboard` 가
  // 이 확장의 Swift 모듈을 잡아 `StickerKeyboardModule` 을 찾지 못하고 빌드가 깨진다.
  name: 'StickerKeyboardExtension',
  displayName: '승요 스티커',
  bundleIdentifier: '.stickerkeyboard',
  // 앱(Expo SDK 57)과 같은 하한. 기본값(18.0)을 두면 그보다 낮은 기기에서는 키보드만 빠진다.
  deploymentTarget: '16.4',
  entitlements: {
    'com.apple.security.application-groups': config.ios.entitlements['com.apple.security.application-groups'],
  },
});
