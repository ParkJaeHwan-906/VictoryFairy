import ExpoModulesCore
import UIKit

/**
 인스타그램이 스토리 공유를 받는 URL 스킴.

 공개된 API 가 아니라 인스타가 받아 주기로 한 주소다
 (developers.facebook.com/docs/instagram-platform/sharing-to-stories).
 */
private let instagramStoriesScheme = "instagram-stories"

/**
 스티커 겹으로 쓰이는 붙여넣기판 키.

 인스타는 이미지를 URL 로 받지 않고 **일반 붙여넣기판에서 꺼내 간다**. 스킴을 열면
 인스타가 이 키들을 읽는 구조라, 이미지 전달과 화면 전환이 별개의 두 동작이다.
 */
private let stickerImageKey = "com.instagram.sharedSticker.stickerImage"
private let backgroundTopColorKey = "com.instagram.sharedSticker.backgroundTopColor"
private let backgroundBottomColorKey = "com.instagram.sharedSticker.backgroundBottomColor"

/**
 붙여넣기판에 남겨 두는 시간.

 인스타가 읽어 갈 만큼은 길고, 사용자가 다른 앱에 붙여넣기 하다 우리 이미지를
 만나지는 않을 만큼은 짧아야 한다. 문서 예제가 쓰는 5분을 그대로 둔다.
 */
private let pasteboardLifetime: TimeInterval = 60 * 5

struct StickerShareOptions: Record {
  @Field var appId: String = ""

  @Field var stickerBase64: String = ""

  @Field var backgroundTopColor: String?

  @Field var backgroundBottomColor: String?
}

public class InstagramShareModule: Module {
  public func definition() -> ModuleDefinition {
    Name("InstagramShare")

    AsyncFunction("isAvailableAsync") { () -> Bool in
      guard let url = URL(string: "\(instagramStoriesScheme)://share") else {
        return false
      }
      // canOpenURL 은 Info.plist 의 LSApplicationQueriesSchemes 에 스킴이 선언돼
      // 있어야 참을 돌려준다. 선언이 빠지면 인스타가 깔려 있어도 항상 거짓이다.
      return UIApplication.shared.canOpenURL(url)
    }
    .runOnQueue(.main)

    AsyncFunction("shareStickerAsync") { (options: StickerShareOptions) -> String in
      guard let data = Data(base64Encoded: options.stickerBase64), !data.isEmpty else {
        throw InstagramShareException("스티커 이미지를 읽을 수 없습니다.")
      }

      // appId 는 스킴의 질의 문자열로 간다. 값에 특수문자가 들어갈 일은 없지만
      // 직접 이어 붙이면 주소가 깨질 수 있어 URLComponents 로 조립한다.
      var components = URLComponents()
      components.scheme = instagramStoriesScheme
      components.host = "share"
      components.queryItems = [URLQueryItem(name: "source_application", value: options.appId)]

      guard let url = components.url, UIApplication.shared.canOpenURL(url) else {
        return "instagram-missing"
      }

      var item: [String: Any] = [stickerImageKey: data]
      if let topColor = options.backgroundTopColor {
        item[backgroundTopColorKey] = topColor
      }
      if let bottomColor = options.backgroundBottomColor {
        item[backgroundBottomColorKey] = bottomColor
      }

      // 이미지를 먼저 놓고 스킴을 연다. 순서가 바뀌면 인스타가 빈 붙여넣기판을 읽는다.
      UIPasteboard.general.setItems(
        [item],
        options: [.expirationDate: Date().addingTimeInterval(pasteboardLifetime)]
      )

      UIApplication.shared.open(url)
      return "opened"
    }
    .runOnQueue(.main)
  }
}

final class InstagramShareException: GenericException<String> {
  override var reason: String {
    param
  }
}
