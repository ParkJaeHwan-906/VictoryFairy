import ExpoModulesCore
import UIKit

/// 키보드 확장의 NSExtensionPointIdentifier. 앱에 들어 있는 확장 중 키보드를 찾을 때 쓴다.
private let keyboardExtensionPoint = "com.apple.keyboard-service"

/**
 스토리 카메라로 바로 가는 주소. 공개 문서에 있는 주소가 아니라서, 받아 주지 않으면 첫 화면으로 연다.
 `instagram` 스킴은 `app.json` 의 `LSApplicationQueriesSchemes` 에 있어야 `canOpenURL` 이 참을 돌려준다.
 */
private let instagramStoryCameraURL = "instagram://story-camera"
private let instagramAppURL = "instagram://app"

struct StickerSourceRecord: Record {
  @Field var id: String = ""

  @Field var categoryId: String = ""

  @Field var categoryLabel: String = ""

  @Field var name: String = ""

  @Field var url: String = ""
}

/**
 앱(JS)에서 키보드를 다루는 창구(iOS). 안드로이드 `StickerKeyboardModule.kt` 와 같은 네 가지를 한다 —
 키보드가 쓸 스티커를 받아 두기, 키보드가 켜져 있는지 알려 주기, 설정 화면 열기, 인스타그램 열기.

 키보드 자체(`targets/sticker-keyboard/`)는 별도 확장 타깃이라 이 모듈과 코드로 이어지지 않는다.
 둘은 App Group 폴더에 놓인 파일로만 만난다(`StickerStore`).
 */
public class StickerKeyboardModule: Module {
  public func definition() -> ModuleDefinition {
    Name("StickerKeyboard")

    AsyncFunction("syncStickersAsync") { (sources: [StickerSourceRecord]) async throws -> Int in
      try await StickerStore.shared.sync(
        sources.map { StickerSource(id: $0.id, categoryId: $0.categoryId, categoryLabel: $0.categoryLabel, name: $0.name, url: $0.url) }
      )
    }

    AsyncFunction("getStatusAsync") { () -> [String: Any] in
      [
        "enabled": Self.isEnabled(),
        // iOS 는 지금 어떤 키보드를 쓰는지 다른 앱에 알려 주지 않는다.
        "selected": false,
        "stickerCount": StickerStore.count(),
      ]
    }

    /**
     인스타그램을 연다. 스티커는 넘기지 않는다 — 담은 스티커는 키보드에 있고, 사용자가
     스토리 편집기에서 키보드로 하나씩 복사해 붙인다.
     */
    AsyncFunction("openInstagramAsync") { (promise: Promise) in
      guard let storyCamera = URL(string: instagramStoryCameraURL),
            let app = URL(string: instagramAppURL),
            UIApplication.shared.canOpenURL(app)
      else {
        promise.resolve("instagram-missing")
        return
      }

      UIApplication.shared.open(storyCamera) { opened in
        if opened {
          promise.resolve("opened")
          return
        }
        UIApplication.shared.open(app) { opened in
          promise.resolve(opened ? "opened" : "instagram-missing")
        }
      }
    }
    .runOnQueue(.main)

    /**
     설정의 '승리요정' 화면을 연다. 키보드 확장이 있는 앱이면 그 안에 '키보드' 항목이 생기고,
     거기서 키보드 켜기와 '전체 접근 허용'을 한 번에 할 수 있다. 일반 → 키보드 화면으로 바로 가는
     공개 주소는 없다.
     */
    AsyncFunction("openSettingsAsync") { () in
      guard let url = URL(string: UIApplication.openSettingsURLString) else {
        return
      }
      UIApplication.shared.open(url)
    }
    .runOnQueue(.main)
  }

  /**
   설정 → 키보드에 추가해 두었는지.

   iOS 는 이를 묻는 공개 API 가 없다. 사용자가 추가한 키보드 목록이 앱의 `UserDefaults` 에
   `AppleKeyboards` 로 비쳐 보이는 것을 읽는다 — 널리 쓰이는 방법이지만 문서화된 값은 아니라,
   읽지 못하면 꺼진 것으로 답한다(웹이 설정으로 안내할 뿐 해가 없다).

   '전체 접근 허용'은 앱에서 알 수 없다. 꺼져 있으면 키보드가 스스로 켜는 곳을 안내한다.
   */
  private static func isEnabled() -> Bool {
    guard let keyboardId = keyboardBundleIdentifier(),
          let keyboards = UserDefaults.standard.object(forKey: "AppleKeyboards") as? [String]
    else {
      return false
    }
    return keyboards.contains(keyboardId)
  }

  /// 앱에 들어 있는 키보드 확장의 번들 ID. 확장이 빠진 빌드면 nil.
  private static func keyboardBundleIdentifier() -> String? {
    guard let plugIns = Bundle.main.builtInPlugInsURL,
          let bundles = try? FileManager.default.contentsOfDirectory(at: plugIns, includingPropertiesForKeys: nil)
    else {
      return nil
    }

    return bundles.lazy
      .filter { $0.pathExtension == "appex" }
      .compactMap { Bundle(url: $0) }
      .first { bundle in
        let point = (bundle.object(forInfoDictionaryKey: "NSExtension") as? [String: Any])?["NSExtensionPointIdentifier"]
        return point as? String == keyboardExtensionPoint
      }?
      .bundleIdentifier
  }
}
