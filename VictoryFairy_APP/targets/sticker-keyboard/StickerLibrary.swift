import Foundation

/// 앱이 받아 둔 스티커 한 장. 키보드는 이 파일만 보고 그린다.
struct StoredSticker {
  let id: String
  let name: String
  let file: URL
}

/// 키보드 위 탭 하나. 순서는 웹이 보낸 목록의 순서다.
struct StoredCategory {
  let id: String
  let label: String
  let stickers: [StoredSticker]
}

/**
 앱(`modules/sticker-keyboard/ios/StickerStore.swift`)이 App Group 폴더에 적어 둔 목록을 읽는다.

 키보드는 앱과 다른 프로세스라 앱의 메모리도, WebView 도 보지 못한다. 둘이 함께 볼 수 있는
 곳은 App Group 폴더뿐이고, 그것도 사용자가 '전체 접근 허용'을 켰을 때만 열린다.

 ⚠️ 폴더 구조와 목록 형식은 앱 쪽 `StickerStore.swift`(그리고 안드로이드 `StickerStore.kt`)와
 같다. 한쪽만 바꾸면 키보드가 빈 목록을 띄운다.
 */
enum StickerLibrary {
  private static let directory = "sticker-keyboard"
  private static let imageDirectory = "images"
  private static let manifestFile = "manifest.json"

  /// App Group 폴더 아래 키보드 폴더. 그룹 값이 없거나 열리지 않으면 nil.
  static var rootURL: URL? {
    guard let group = Bundle.main.object(forInfoDictionaryKey: "StickerKeyboardAppGroup") as? String,
          let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    else {
      return nil
    }
    return container.appendingPathComponent(directory, isDirectory: true)
  }

  /// 목록이 바뀌었는지 보는 값. 목록 파일이 없으면 nil.
  static func revision() -> Date? {
    guard let manifest = rootURL?.appendingPathComponent(manifestFile) else {
      return nil
    }
    return (try? FileManager.default.attributesOfItem(atPath: manifest.path))?[.modificationDate] as? Date
  }

  /// 키보드가 그릴 탭과 스티커. 목록이 없거나 깨졌으면 빈 목록이다.
  static func load() -> [StoredCategory] {
    guard let root = rootURL,
          let data = try? Data(contentsOf: root.appendingPathComponent(manifestFile)),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let entries = json["stickers"] as? [[String: Any]]
    else {
      return []
    }

    let images = root.appendingPathComponent(imageDirectory, isDirectory: true)
    var order: [String] = []
    var labels: [String: String] = [:]
    var buckets: [String: [StoredSticker]] = [:]

    for entry in entries {
      guard let fileName = entry["file"] as? String,
            let categoryId = entry["categoryId"] as? String
      else {
        continue
      }

      let file = images.appendingPathComponent(fileName)
      guard FileManager.default.fileExists(atPath: file.path) else {
        continue
      }

      // 탭 순서를 웹이 보낸 순서대로 지킨다.
      if buckets[categoryId] == nil {
        order.append(categoryId)
        labels[categoryId] = entry["categoryLabel"] as? String ?? ""
        buckets[categoryId] = []
      }
      buckets[categoryId]?.append(
        StoredSticker(id: entry["id"] as? String ?? "", name: entry["name"] as? String ?? "", file: file)
      )
    }

    return order.map { StoredCategory(id: $0, label: labels[$0] ?? "", stickers: buckets[$0] ?? []) }
  }
}
