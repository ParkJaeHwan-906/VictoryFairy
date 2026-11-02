import CryptoKit
import ExpoModulesCore
import Foundation

/// 웹이 보낸 목록의 한 줄. `url` 은 웹이 번들한 PNG 의 절대 주소다.
struct StickerSource {
  let id: String
  let categoryId: String
  let categoryLabel: String
  let name: String
  let url: String
}

final class StickerStoreError: GenericException<String>, @unchecked Sendable {
  override var reason: String {
    param
  }
}

/**
 앱과 키보드 확장이 함께 보는 스티커 보관소(iOS).

 ── 왜 App Group 인가 ─────────────────────────────────────────────────
 iOS 키보드는 앱과 **다른 프로세스**에서 돈다(안드로이드는 같은 프로세스). 그래서 앱의
 `Documents` 도 못 보고, 둘이 함께 쓸 수 있는 곳은 App Group 폴더뿐이다. 키보드는 사용자가
 '전체 접근 허용'을 켰을 때만 그 폴더를 읽을 수 있다.
 ──────────────────────────────────────────────────────────────────────

 순서와 형식은 안드로이드 `StickerStore.kt` 와 같다: 그림을 모두 받은 **뒤에** 목록을 바꾸고,
 받다가 하나라도 실패하면 목록은 그대로 두어 키보드가 이전 목록을 계속 쓰게 한다.

 ⚠️ 폴더 구조와 목록 형식은 키보드 쪽 `targets/sticker-keyboard/StickerLibrary.swift` 가 그대로
 읽는다. 한쪽만 바꾸면 키보드가 빈 목록을 띄운다.
 */
actor StickerStore {
  static let shared = StickerStore()

  private static let directory = "sticker-keyboard"
  private static let imageDirectory = "images"
  private static let manifestFile = "manifest.json"

  /// 스티커 한 장의 상한. 지금 그림은 100KB 안팎이라 넉넉하다 — 엉뚱한 응답을 거르는 값이다.
  private static let maxImageBytes = 2 * 1024 * 1024

  private static let timeout: TimeInterval = 10

  private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

  /**
   App Group 아래 키보드 폴더. 그룹 이름은 `app.json` 의 `ios.infoPlist.StickerKeyboardAppGroup` 으로
   들어온다 — 같은 값이 entitlements 에도 있어야 실제로 열린다.
   */
  static func rootURL() throws -> URL {
    guard let group = Bundle.main.object(forInfoDictionaryKey: "StickerKeyboardAppGroup") as? String,
          let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    else {
      throw StickerStoreError("App Group 폴더를 열 수 없습니다.")
    }
    return container.appendingPathComponent(directory, isDirectory: true)
  }

  /**
   웹이 보낸 목록대로 그림을 받아 두고 목록을 바꾼다. 저장된 스티커 수를 돌려준다.

   파일 이름은 주소의 해시다. 웹 빌드는 그림이 바뀌면 주소(해시가 붙은 파일 이름)도 바뀌므로,
   이미 있는 파일은 다시 받지 않아도 된다. actor 라 동기화가 겹치지 않는다.
   */
  func sync(_ sources: [StickerSource]) async throws -> Int {
    let root = try Self.rootURL()
    let images = root.appendingPathComponent(Self.imageDirectory, isDirectory: true)
    let fileManager = FileManager.default
    try fileManager.createDirectory(at: images, withIntermediateDirectories: true)

    var entries: [[String: String]] = []
    var keep = Set<String>()

    for source in sources {
      let fileName = "\(Self.sha256(source.url).prefix(32)).png"
      let file = images.appendingPathComponent(fileName)
      if !fileManager.fileExists(atPath: file.path) {
        try await Self.download(source.url, to: file)
      }
      keep.insert(fileName)

      entries.append([
        "id": source.id,
        "categoryId": source.categoryId,
        "categoryLabel": source.categoryLabel,
        "name": source.name,
        "file": fileName,
      ])
    }

    let manifest = try JSONSerialization.data(withJSONObject: ["stickers": entries])
    // .atomic 은 옆 파일에 다 쓴 뒤 바꿔 끼운다. 키보드가 반쯤 쓴 목록을 읽지 않는다.
    try manifest.write(to: root.appendingPathComponent(Self.manifestFile), options: .atomic)

    // 목록에서 빠진 그림을 치운다. 이미 붙여넣은 그림은 받은 앱이 자기 쪽으로 가져간다.
    for file in (try? fileManager.contentsOfDirectory(at: images, includingPropertiesForKeys: nil)) ?? []
    where !keep.contains(file.lastPathComponent) {
      try? fileManager.removeItem(at: file)
    }

    return sources.count
  }

  /// 키보드가 띄울 수 있는 스티커 수. 목록이 없거나 깨졌으면 0.
  nonisolated static func count() -> Int {
    guard let root = try? rootURL(),
          let data = try? Data(contentsOf: root.appendingPathComponent(manifestFile)),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let entries = json["stickers"] as? [[String: Any]]
    else {
      return 0
    }

    let images = root.appendingPathComponent(imageDirectory, isDirectory: true)
    return entries.filter { entry in
      guard let fileName = entry["file"] as? String else {
        return false
      }
      return FileManager.default.fileExists(atPath: images.appendingPathComponent(fileName).path)
    }.count
  }

  private static func download(_ address: String, to target: URL) async throws {
    guard let url = URL(string: address) else {
      throw StickerStoreError("주소가 올바르지 않습니다: \(address)")
    }

    var request = URLRequest(url: url)
    request.timeoutInterval = timeout
    let (data, response) = try await URLSession.shared.data(for: request)

    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard status == 200 else {
      throw StickerStoreError("스티커를 받지 못했습니다(\(status)): \(address)")
    }
    guard data.count <= maxImageBytes else {
      throw StickerStoreError("스티커가 너무 큽니다: \(address)")
    }
    // 키보드는 이 파일을 PNG 로 붙여넣기판에 놓는다. 다른 것이 섞이면 받는 앱이 깨진 그림을 띄운다.
    guard data.count >= pngSignature.count, Array(data.prefix(pngSignature.count)) == pngSignature else {
      throw StickerStoreError("PNG 가 아닙니다: \(address)")
    }

    try data.write(to: target, options: .atomic)
  }

  private static func sha256(_ value: String) -> String {
    SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
  }
}
