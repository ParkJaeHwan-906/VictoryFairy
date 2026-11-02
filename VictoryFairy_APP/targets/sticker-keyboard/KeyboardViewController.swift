import ImageIO
import UIKit
import UniformTypeIdentifiers

/// 한 줄에 놓는 스티커 수. 안드로이드 키보드와 같다.
private let gridColumns: CGFloat = 4

/// 썸네일로 줄여 읽을 때의 목표 한 변(pt). 원본은 800×800 이다.
private let thumbnailPoints: CGFloat = 72

/// 키보드 높이. 탭을 바꾸거나 목록이 비어도 출렁이지 않게 고정한다.
private let keyboardHeight: CGFloat = 300

/*
 색은 `VictoryFairy_FE/src/styles/tokens.css` 의 시맨틱 토큰에서 가져왔다(`src/theme.ts`,
 안드로이드 `StickerKeyboardService.kt` 와 같은 값). 키보드는 RN 밖이라 theme.ts 를 읽지 못한다.
 */
private extension UIColor {
  static let vfBackground = UIColor(hex: 0xF9F9FB) // --color-background-gray
  static let vfSurface = UIColor(hex: 0xFFFFFF) // --color-surface
  static let vfPrimary = UIColor(hex: 0xF04E23) // --color-primary-normal
  static let vfLabelNormal = UIColor(hex: 0x313140) // --color-label-normal
  static let vfLabelNeutral = UIColor(hex: 0x6A6A7D) // --color-label-neutral
  static let vfLine = UIColor(hex: 0xF1F1F5) // --color-line-light

  convenience init(hex: UInt32) {
    self.init(
      red: CGFloat((hex >> 16) & 0xFF) / 255,
      green: CGFloat((hex >> 8) & 0xFF) / 255,
      blue: CGFloat(hex & 0xFF) / 255,
      alpha: 1
    )
  }
}

/**
 승요 스티커 키보드(iOS).

 ── 하는 일 ────────────────────────────────────────────────────────────
 스티커를 누르면 그 PNG 를 **붙여넣기판에 복사**한다. 인스타 스토리 편집기는 붙여넣기판에
 그림이 있으면 '스티커 추가'를 띄우고, 글쓰기 칸에 붙여넣어도 따로 움직이는 스티커가 된다.

 안드로이드처럼 입력창에 바로 넣을 수는 없다 — iOS 키보드가 입력창에 보낼 수 있는 것은
 글자(`textDocumentProxy`)뿐이고 이미지 통로가 없다. 다른 스티커 키보드(Bitmoji 등)도 같은
 이유로 복사 → 붙여넣기를 쓴다.
 ──────────────────────────────────────────────────────────────────────

 '전체 접근 허용'이 꺼져 있으면 App Group 폴더도, 붙여넣기판도 열리지 않는다. 그때는 스티커
 대신 켜는 곳을 안내한다. 키보드에서 설정 화면을 직접 열 수는 없다.

 그림은 앱이 미리 받아 둔 파일만 쓴다. 키보드에서 내려받지 않는다 — 키보드가 뜨는 순간을
 네트워크에 묶지 않으려는 것이고, 메모리 상한(수십 MB)이 낮은 확장에서 할 일도 아니다.
 */
final class KeyboardViewController: UIInputViewController {
  private var categories: [StoredCategory] = []
  private var loadedRevision: Date?
  private var hasLoaded = false
  private var selectedCategoryIndex = 0

  private let tabScroll = UIScrollView()
  private let tabStack = UIStackView()
  private let statusLabel = UILabel()
  private let emptyLabel = UILabel()
  private let globeButton = UIButton(type: .system)
  private lazy var grid = UICollectionView(frame: .zero, collectionViewLayout: makeLayout())

  /// 썸네일 캐시. 확장은 메모리 상한이 낮아 원본을 들고 있지 않는다.
  private let thumbnails = NSCache<NSURL, UIImage>()

  private var stickers: [StoredSticker] {
    categories.indices.contains(selectedCategoryIndex) ? categories[selectedCategoryIndex].stickers : []
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    buildLayout()
  }

  override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)

    // 앱이 스티커를 다시 받아 목록이 바뀌었을 수 있다. 바뀐 경우에만 다시 읽는다.
    if hasFullAccess {
      let revision = StickerLibrary.revision()
      if !hasLoaded || revision != loadedRevision {
        hasLoaded = true
        loadedRevision = revision
        categories = StickerLibrary.load()
        selectedCategoryIndex = min(selectedCategoryIndex, max(categories.count - 1, 0))
        thumbnails.removeAllObjects()
      }
    }

    render()
    showStatus(hasFullAccess ? "누르면 복사돼요. 인스타에서 ‘스티커 추가’를 누르거나 붙여넣어 주세요." : nil)
  }

  override func viewWillLayoutSubviews() {
    super.viewWillLayoutSubviews()
    // 홈 버튼 없는 기기는 시스템이 키보드 아래에 지구본을 따로 그린다. 그때는 숨긴다.
    globeButton.isHidden = !needsInputModeSwitchKey
  }

  // ── 넣기 ─────────────────────────────────────────────────────────────

  // UIResponder 의 copy(_:) 와 헷갈리지 않게 이름을 따로 둔다.
  private func copySticker(_ sticker: StoredSticker) {
    guard let data = try? Data(contentsOf: sticker.file) else {
      showStatus("스티커를 열지 못했어요.")
      return
    }

    // UIImage 로 바꿔 놓으면 다시 인코딩된다. 받은 PNG 바이트를 그대로 놓아 투명 배경을 지킨다.
    UIPasteboard.general.setData(data, forPasteboardType: UTType.png.identifier)
    showStatus("\(sticker.name) 스티커를 복사했어요. 인스타에서 ‘스티커 추가’를 누르거나 붙여넣어 주세요.")
  }

  // ── 화면 ─────────────────────────────────────────────────────────────

  private func buildLayout() {
    guard let root = inputView else {
      return
    }
    root.backgroundColor = .vfBackground

    let height = root.heightAnchor.constraint(equalToConstant: keyboardHeight)
    // 시스템이 잠깐 다른 높이를 요구하는 순간(회전 등) 제약 충돌 경고가 나지 않게 한 단계 낮춘다.
    height.priority = .defaultHigh
    height.isActive = true

    tabStack.axis = .horizontal
    tabStack.spacing = 6
    tabStack.translatesAutoresizingMaskIntoConstraints = false
    tabScroll.showsHorizontalScrollIndicator = false
    tabScroll.addSubview(tabStack)
    NSLayoutConstraint.activate([
      tabStack.topAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.topAnchor),
      tabStack.bottomAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.bottomAnchor),
      tabStack.leadingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.leadingAnchor, constant: 8),
      tabStack.trailingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.trailingAnchor, constant: -8),
      tabStack.heightAnchor.constraint(equalTo: tabScroll.frameLayoutGuide.heightAnchor),
      tabScroll.heightAnchor.constraint(equalToConstant: 32),
    ])

    statusLabel.font = .systemFont(ofSize: 12)
    statusLabel.textColor = .vfLabelNeutral
    statusLabel.numberOfLines = 2

    grid.backgroundColor = .clear
    grid.dataSource = self
    grid.delegate = self
    grid.register(StickerCell.self, forCellWithReuseIdentifier: StickerCell.reuseIdentifier)

    emptyLabel.font = .systemFont(ofSize: 14)
    emptyLabel.textColor = .vfLabelNeutral
    emptyLabel.textAlignment = .center
    emptyLabel.numberOfLines = 0

    // 격자와 빈 안내는 같은 자리를 나눠 쓴다. 빈 안내는 가운데에만 걸어 둔다 — 위아래까지 붙이면
    // 숨겨져 있어도 제 글자 높이로 이 자리를 끌어당겨 격자가 한 줄로 찌그러진다.
    let body = UIView()
    for view in [grid, emptyLabel] {
      view.translatesAutoresizingMaskIntoConstraints = false
      body.addSubview(view)
    }
    NSLayoutConstraint.activate([
      grid.topAnchor.constraint(equalTo: body.topAnchor),
      grid.bottomAnchor.constraint(equalTo: body.bottomAnchor),
      grid.leadingAnchor.constraint(equalTo: body.leadingAnchor, constant: 8),
      grid.trailingAnchor.constraint(equalTo: body.trailingAnchor, constant: -8),
      emptyLabel.centerYAnchor.constraint(equalTo: body.centerYAnchor),
      emptyLabel.leadingAnchor.constraint(equalTo: body.leadingAnchor, constant: 24),
      emptyLabel.trailingAnchor.constraint(equalTo: body.trailingAnchor, constant: -24),
    ])

    let statusRow = UIStackView(arrangedSubviews: [statusLabel])
    statusRow.isLayoutMarginsRelativeArrangement = true
    statusRow.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)

    let bottomRow = buildBottomRow()

    // 남는 높이는 격자가 갖는다. 정해 두지 않으면 세로 스택이 아무 줄이나 늘려서
    // 지우기 키 줄이 키보드 절반을 차지한다.
    for view in [tabScroll, statusRow, bottomRow] {
      view.setContentHuggingPriority(.required, for: .vertical)
      view.setContentCompressionResistancePriority(.required, for: .vertical)
    }
    body.setContentHuggingPriority(.defaultLow - 1, for: .vertical)
    body.setContentCompressionResistancePriority(.defaultLow - 1, for: .vertical)

    let column = UIStackView(arrangedSubviews: [tabScroll, statusRow, body, bottomRow])
    column.axis = .vertical
    column.spacing = 4
    column.translatesAutoresizingMaskIntoConstraints = false
    root.addSubview(column)
    NSLayoutConstraint.activate([
      column.topAnchor.constraint(equalTo: root.topAnchor, constant: 8),
      column.bottomAnchor.constraint(equalTo: root.bottomAnchor),
      column.leadingAnchor.constraint(equalTo: root.leadingAnchor),
      column.trailingAnchor.constraint(equalTo: root.trailingAnchor),
    ])
  }

  private func render() {
    tabStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
    for (index, category) in categories.enumerated() {
      tabStack.addArrangedSubview(tabButton(category.label, index: index))
    }
    // 앱이 담은 스티커만 보내면 탭이 하나다. 고를 것이 없는 탭 줄은 자리만 차지하니 숨긴다.
    tabScroll.isHidden = categories.count <= 1

    if !hasFullAccess {
      emptyLabel.text = "설정 → 승리요정 → 키보드에서\n‘전체 접근 허용’을 켜야 스티커를 쓸 수 있어요."
    } else {
      emptyLabel.text = "승리요정 앱에서 스티커를 담고\n‘SNS에 올리기’를 누르면 여기에 나타나요."
    }

    let isEmpty = !hasFullAccess || categories.isEmpty
    grid.isHidden = isEmpty
    emptyLabel.isHidden = !isEmpty
    grid.reloadData()
    grid.setContentOffset(.zero, animated: false)
  }

  private func tabButton(_ title: String, index: Int) -> UIButton {
    let isActive = index == selectedCategoryIndex
    let button = UIButton(type: .custom)
    button.setTitle(title, for: .normal)
    button.titleLabel?.font = isActive ? UIFont.boldSystemFont(ofSize: 14) : UIFont.systemFont(ofSize: 14)
    button.setTitleColor(isActive ? UIColor.vfSurface : UIColor.vfLabelNeutral, for: .normal)
    button.backgroundColor = isActive ? UIColor.vfPrimary : UIColor.vfSurface
    button.layer.cornerRadius = 10
    button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 14, bottom: 6, right: 14)
    button.addAction(UIAction { [weak self] _ in
      self?.selectedCategoryIndex = index
      self?.render()
    }, for: .touchUpInside)
    return button
  }

  /// 다른 키보드로 돌아가는 키와 지우기 키. 글자 키보드를 대신하지 않으니 이 둘만 둔다.
  private func buildBottomRow() -> UIView {
    styleKey(globeButton, title: "🌐  키보드 전환")
    // 시스템 메서드에 이어야 길게 눌렀을 때 키보드 목록이 뜬다.
    globeButton.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)

    let deleteButton = UIButton(type: .system)
    styleKey(deleteButton, title: "⌫")
    deleteButton.addAction(UIAction { [weak self] _ in
      self?.textDocumentProxy.deleteBackward()
    }, for: .touchUpInside)

    let row = UIStackView(arrangedSubviews: [globeButton, UIView(), deleteButton])
    row.axis = .horizontal
    row.alignment = .center
    row.backgroundColor = .vfLine
    row.isLayoutMarginsRelativeArrangement = true
    row.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 8, bottom: 8, trailing: 8)
    return row
  }

  private func styleKey(_ button: UIButton, title: String) {
    button.setTitle(title, for: .normal)
    button.titleLabel?.font = .systemFont(ofSize: 14)
    button.setTitleColor(.vfLabelNormal, for: .normal)
    button.backgroundColor = .vfSurface
    button.layer.cornerRadius = 10
    button.contentEdgeInsets = UIEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)
    button.widthAnchor.constraint(greaterThanOrEqualToConstant: 56).isActive = true
  }

  private func showStatus(_ message: String?) {
    statusLabel.text = message
    statusLabel.isHidden = message == nil
  }

  private func makeLayout() -> UICollectionViewFlowLayout {
    let layout = UICollectionViewFlowLayout()
    layout.minimumInteritemSpacing = 4
    layout.minimumLineSpacing = 4
    layout.sectionInset = UIEdgeInsets(top: 0, left: 0, bottom: 8, right: 0)
    return layout
  }

  // ── 썸네일 ───────────────────────────────────────────────────────────

  /**
   원본을 그대로 띄우면 800×800 × 수십 장이라 확장의 메모리 상한에 금방 닿는다.
   ImageIO 로 줄여 읽어 캐시에 둔다. 읽는 사이 셀이 다른 스티커로 재활용됐으면 버린다.
   */
  fileprivate func bindThumbnail(_ cell: StickerCell, sticker: StoredSticker) {
    let key = sticker.file as NSURL
    cell.representedFile = sticker.file

    if let cached = thumbnails.object(forKey: key) {
      cell.imageView.image = cached
      return
    }

    cell.imageView.image = nil
    let file = sticker.file
    let maxPixels = thumbnailPoints * traitCollection.displayScale
    Task.detached(priority: .userInitiated) { [weak self, weak cell] in
      guard let image = Self.decodeThumbnail(file, maxPixels: maxPixels) else {
        return
      }
      await MainActor.run {
        self?.thumbnails.setObject(image, forKey: file as NSURL)
        if let cell, cell.representedFile == file {
          cell.imageView.image = image
        }
      }
    }
  }

  // 백그라운드 큐에서 부르므로 클래스의 메인 액터 격리에서 뺀다. 상태를 건드리지 않는 순수 함수다.
  nonisolated private static func decodeThumbnail(_ file: URL, maxPixels: CGFloat) -> UIImage? {
    guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else {
      return nil
    }
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceShouldCacheImmediately: true,
      kCGImageSourceThumbnailMaxPixelSize: maxPixels,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
      return nil
    }
    return UIImage(cgImage: image)
  }
}

extension KeyboardViewController: UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
    stickers.count
  }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(withReuseIdentifier: StickerCell.reuseIdentifier, for: indexPath)
    if let cell = cell as? StickerCell {
      let sticker = stickers[indexPath.item]
      cell.accessibilityLabel = sticker.name
      bindThumbnail(cell, sticker: sticker)
    }
    return cell
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    sizeForItemAt indexPath: IndexPath
  ) -> CGSize {
    let spacing = (collectionViewLayout as? UICollectionViewFlowLayout)?.minimumInteritemSpacing ?? 0
    let width = floor((collectionView.bounds.width - spacing * (gridColumns - 1)) / gridColumns)
    return CGSize(width: width, height: 76)
  }

  func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
    copySticker(stickers[indexPath.item])
  }
}

private final class StickerCell: UICollectionViewCell {
  static let reuseIdentifier = "StickerCell"

  let imageView = UIImageView()
  /// 비동기로 읽은 썸네일이 아직 이 셀의 것인지 확인하는 값.
  var representedFile: URL?

  override init(frame: CGRect) {
    super.init(frame: frame)
    isAccessibilityElement = true
    accessibilityTraits = .button
    contentView.backgroundColor = .vfSurface
    contentView.layer.cornerRadius = 10
    imageView.contentMode = .scaleAspectFit
    imageView.translatesAutoresizingMaskIntoConstraints = false
    contentView.addSubview(imageView)
    NSLayoutConstraint.activate([
      imageView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
      imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
      imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 6),
      imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -6),
    ])
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }
}
