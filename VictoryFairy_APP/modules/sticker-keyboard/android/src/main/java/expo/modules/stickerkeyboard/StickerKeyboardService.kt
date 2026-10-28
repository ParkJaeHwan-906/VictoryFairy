package expo.modules.stickerkeyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val PNG_MIME_TYPE = "image/png"

/** 한 줄에 놓는 스티커 수. 키보드 폭이 좁은 기기에서도 그림이 알아볼 만한 크기다. */
private const val GRID_COLUMNS = 4

/** 썸네일로 줄여 읽을 때의 목표 한 변(px). 원본은 800×800 이다. */
private const val THUMBNAIL_TARGET_PX = 200

/*
 * 색은 `VictoryFairy_FE/src/styles/tokens.css` 의 시맨틱 토큰에서 가져왔다(`src/theme.ts` 와
 * 같은 값). 키보드는 RN 밖이라 theme.ts 를 읽지 못해 여기 한 벌을 더 둔다.
 */
private val COLOR_BACKGROUND = Color.parseColor("#F9F9FB") // --color-background-gray
private val COLOR_SURFACE = Color.parseColor("#FFFFFF") // --color-surface
private val COLOR_PRIMARY = Color.parseColor("#F04E23") // --color-primary-normal
private val COLOR_LABEL_NORMAL = Color.parseColor("#313140") // --color-label-normal
private val COLOR_LABEL_NEUTRAL = Color.parseColor("#6A6A7D") // --color-label-neutral
private val COLOR_LINE = Color.parseColor("#F1F1F5") // --color-line-light

/**
 * 승요 스티커 키보드.
 *
 * ── 하는 일 ────────────────────────────────────────────────────────────
 * 스티커를 누르면 그 PNG 를 지금 입력창에 **이미지로** 넣는다(`commitContent`).
 * 인스타 스토리 글쓰기 칸은 이미지를 받아 따로 움직이는 스티커로 만든다 — Gboard
 * 스티커가 들어가는 것과 같은 경로다. 키보드가 닫히지 않으니 여러 장을 연달아 넣을 수 있다.
 *
 * 입력창이 이미지를 받지 않는다고 알리면(또는 넣기를 거절하면) 대신 **클립보드에 복사**하고
 * 붙여넣기를 안내한다. 이미지 붙여넣기만 받는 입력창이 있어서다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * "넣었어요"는 입력창이 요청을 받았다는 뜻까지다. 받은 앱이 실제로 스티커를 띄웠는지는
 * 키보드가 알 수 없다(받는 쪽이 그리기 전에 답할 수 있다).
 *
 * 그림은 앱이 미리 받아 둔 파일만 쓴다(`StickerStore`). 키보드에서 내려받지 않는다 —
 * 키보드가 뜨는 순간을 네트워크에 묶지 않으려는 것이다.
 */
class StickerKeyboardService : InputMethodService() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var decoder: ExecutorService? = null

  /** 썸네일 캐시. 크기는 바이트 기준이다 — 51장 × 200px 이면 8MB 남짓이다. */
  private val thumbnails = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
  }

  private var categories: List<StoredCategory> = emptyList()
  private var loadedRevision = -1L
  private var selectedCategoryIndex = 0

  /** 지금 입력창이 PNG 를 받는다고 알렸는지. 입력창이 바뀔 때마다 다시 본다. */
  private var acceptsPng = false

  private var tabRow: LinearLayout? = null
  private var statusText: TextView? = null
  private var grid: GridView? = null
  private var emptyText: TextView? = null
  private val adapter = StickerAdapter()

  override fun onCreate() {
    super.onCreate()
    decoder = Executors.newSingleThreadExecutor()
  }

  override fun onDestroy() {
    decoder?.shutdownNow()
    decoder = null
    super.onDestroy()
  }

  override fun onCreateInputView(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(COLOR_BACKGROUND)
    }

    val tabs = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(dp(8), dp(8), dp(8), dp(4))
    }
    tabRow = tabs
    root.addView(
      HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    statusText = TextView(this).apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setTextColor(COLOR_LABEL_NEUTRAL)
      setPadding(dp(16), dp(2), dp(16), dp(4))
    }
    root.addView(statusText)

    // 격자와 빈 안내는 같은 자리를 나눠 쓴다. 높이를 고정해 탭을 바꿔도 키보드 높이가 출렁이지 않게 한다.
    val body = android.widget.FrameLayout(this)
    grid = GridView(this).apply {
      numColumns = GRID_COLUMNS
      stretchMode = GridView.STRETCH_COLUMN_WIDTH
      verticalSpacing = dp(4)
      horizontalSpacing = dp(4)
      setPadding(dp(8), 0, dp(8), dp(8))
      clipToPadding = false
      adapter = this@StickerKeyboardService.adapter
      setOnItemClickListener { _, _, position, _ -> insert(this@StickerKeyboardService.adapter.getItem(position)) }
    }
    emptyText = TextView(this).apply {
      gravity = Gravity.CENTER
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTextColor(COLOR_LABEL_NEUTRAL)
      setPadding(dp(24), 0, dp(24), 0)
      text = "승리요정 앱에서 스티커를 담고\n‘SNS에 올리기’를 누르면 여기에 나타나요."
      visibility = View.GONE
    }
    body.addView(grid)
    body.addView(emptyText)
    root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

    root.addView(buildBottomRow())

    render()
    return root
  }

  // 가로 화면에서 시스템이 입력창을 키보드 위로 끌어올리는 전체 화면 모드를 끈다.
  // 그 모드에서는 받는 앱의 입력창 대신 시스템 입력칸이 앞에 서서 스티커를 넣을 자리가 가려진다.
  override fun onEvaluateFullscreenMode() = false

  override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)

    acceptsPng = info != null && EditorInfoCompat.getContentMimeTypes(info).any {
      ClipDescription.compareMimeTypes(PNG_MIME_TYPE, it)
    }

    // 앱이 스티커 화면을 다시 열어 목록이 바뀌었을 수 있다. 바뀐 경우에만 다시 읽는다.
    val revision = StickerStore.revision(this)
    if (revision != loadedRevision) {
      loadedRevision = revision
      categories = StickerStore.load(this)
      selectedCategoryIndex = selectedCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
      thumbnails.evictAll()
    }

    render()
    showStatus(defaultStatus())
  }

  private fun defaultStatus() =
    if (acceptsPng) "스티커를 누르면 바로 넣어요." else "누르면 복사돼요. 입력창을 길게 눌러 붙여넣어 주세요."

  // ── 넣기 ─────────────────────────────────────────────────────────────

  private fun insert(sticker: StoredSticker) {
    val uri = try {
      StickerStore.uriFor(this, sticker)
    } catch (error: IllegalArgumentException) {
      showStatus("스티커를 열지 못했어요.")
      return
    }

    val info = currentInputEditorInfo
    val connection = currentInputConnection

    if (acceptsPng && info != null && connection != null) {
      val content = InputContentInfoCompat(uri, ClipDescription(sticker.name, arrayOf(PNG_MIME_TYPE)), null)

      // 25 부터는 넣기 요청에 읽기 권한을 실어 보낼 수 있다. 그 전에는 받는 앱에 직접 열어 준다.
      var flags = 0
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        flags = flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
      } else {
        grantUriPermission(info.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

      if (InputConnectionCompat.commitContent(connection, info, content, flags, null)) {
        showStatus("${sticker.name} 스티커를 넣었어요.")
        return
      }
    }

    copyToClipboard(sticker.name, uri)
  }

  /**
   * 입력창이 이미지를 받지 않을 때의 대안 — 클립보드에 그림을 올린다.
   *
   * content:// 주소를 클립보드에 올리면, 붙여넣는 앱이 클립을 꺼낼 때 시스템이 읽기
   * 권한을 함께 열어 준다(provider 의 grantUriPermissions).
   */
  private fun copyToClipboard(label: String, uri: Uri) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newUri(contentResolver, label, uri))
    showStatus("복사했어요. 입력창을 길게 눌러 붙여넣어 주세요.")
  }

  // ── 화면 ─────────────────────────────────────────────────────────────

  private fun render() {
    val tabs = tabRow ?: return
    tabs.removeAllViews()

    categories.forEachIndexed { index, category ->
      tabs.addView(
        TextView(this).apply {
          text = category.label
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
          setPadding(dp(14), dp(6), dp(14), dp(6))
          val isActive = index == selectedCategoryIndex
          setTextColor(if (isActive) COLOR_SURFACE else COLOR_LABEL_NEUTRAL)
          typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
          background = pill(if (isActive) COLOR_PRIMARY else COLOR_SURFACE)
          setOnClickListener {
            selectedCategoryIndex = index
            render()
          }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          marginEnd = dp(6)
        },
      )
    }

    // 앱이 담은 스티커만 보내면 탭이 하나다. 고를 것이 없는 탭 줄은 자리만 차지하니 숨긴다.
    (tabs.parent as? View)?.visibility = if (categories.size > 1) View.VISIBLE else View.GONE

    val isEmpty = categories.isEmpty()
    grid?.visibility = if (isEmpty) View.GONE else View.VISIBLE
    emptyText?.visibility = if (isEmpty) View.VISIBLE else View.GONE
    adapter.stickers = categories.getOrNull(selectedCategoryIndex)?.stickers.orEmpty()
    grid?.setSelection(0)
  }

  /** 다른 키보드로 돌아가는 키와 지우기 키. 글자 키보드를 대신하지 않으니 이 둘만 둔다. */
  private fun buildBottomRow(): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), dp(4), dp(8), dp(8))
      setBackgroundColor(COLOR_LINE)
    }

    row.addView(
      keyButton("🌐  키보드 전환").apply {
        setOnClickListener { switchKeyboard() }
        // 길게 누르면 어떤 키보드로 갈지 고르게 한다.
        setOnLongClickListener {
          showInputMethodPicker()
          true
        }
      },
    )
    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    row.addView(
      keyButton("⌫").apply {
        setOnClickListener { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
      },
    )
    return row
  }

  private fun switchKeyboard() {
    // 바로 전에 쓰던 키보드로 돌아가는 것이 가장 자연스럽다. 갈 곳이 없으면 고르게 한다.
    val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()
    if (!switched) {
      showInputMethodPicker()
    }
  }

  private fun showInputMethodPicker() {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
  }

  private fun showStatus(message: String) {
    statusText?.text = message
  }

  private fun keyButton(label: String) = TextView(this).apply {
    text = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setTextColor(COLOR_LABEL_NORMAL)
    gravity = Gravity.CENTER
    minWidth = dp(56)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = pill(COLOR_SURFACE)
  }

  private fun pill(color: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(10).toFloat()
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

  // ── 썸네일 ───────────────────────────────────────────────────────────

  /**
   * 원본을 그대로 띄우면 800×800 × 51장이라 메모리가 금방 찬다. 줄여 읽어 캐시에 둔다.
   * 읽기는 백그라운드에서 하고, 그 사이 셀이 다른 스티커로 재활용됐으면 버린다.
   */
  private fun bindThumbnail(view: ImageView, sticker: StoredSticker) {
    val key = sticker.file.path
    view.tag = key

    val cached = thumbnails.get(key)
    if (cached != null) {
      view.setImageBitmap(cached)
      return
    }

    view.setImageDrawable(null)
    decoder?.execute {
      val bitmap = decodeThumbnail(sticker) ?: return@execute
      thumbnails.put(key, bitmap)
      mainHandler.post {
        if (view.tag == key) {
          view.setImageBitmap(bitmap)
        }
      }
    }
  }

  private fun decodeThumbnail(sticker: StoredSticker): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sticker.file.path, bounds)
    if (bounds.outWidth <= 0) {
      return null
    }

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= THUMBNAIL_TARGET_PX) {
      sampleSize *= 2
    }

    return BitmapFactory.decodeFile(sticker.file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
  }

  private inner class StickerAdapter : BaseAdapter() {
    var stickers: List<StoredSticker> = emptyList()
      set(value) {
        field = value
        notifyDataSetChanged()
      }

    override fun getCount() = stickers.size

    override fun getItem(position: Int) = stickers[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val view = (convertView as? ImageView) ?: ImageView(this@StickerKeyboardService).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = pill(COLOR_SURFACE)
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76))
      }
      val sticker = getItem(position)
      view.contentDescription = sticker.name
      bindThumbnail(view, sticker)
      return view
    }
  }
}
