package expo.modules.stickerkeyboard

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 앱이 내려받아 둔 스티커 한 장. 키보드는 이 파일만 보고 그린다. */
data class StoredSticker(
  val id: String,
  val name: String,
  val file: File,
)

/** 키보드 위 탭 하나. 순서는 웹이 보낸 목록의 순서다. */
data class StoredCategory(
  val id: String,
  val label: String,
  val stickers: List<StoredSticker>,
)

/** 웹이 보낸 목록의 한 줄. `url` 은 웹이 번들한 PNG 의 절대 주소다. */
data class StickerSource(
  val id: String,
  val categoryId: String,
  val categoryLabel: String,
  val name: String,
  val url: String,
)

class StickerStoreException(message: String) : IOException(message)

/**
 * 앱(WebView 쪽)과 키보드가 함께 보는 스티커 보관소.
 *
 * ── 왜 파일로 두나 ─────────────────────────────────────────────────────
 * 키보드는 앱 프로세스에서 돌지만 WebView 없이 뜬다. 그래서 웹이 들고 있는 스티커
 * 목록을 그 자리에서 물어볼 수 없고, 앱이 미리 내려받아 둔 것을 읽어야 한다.
 * 그림의 원본은 계속 웹(`VictoryFairy_FE/src/utils/shareStickers.ts`)이다 — 웹이 스티커
 * 화면을 열 때마다 목록을 보내고, 여기서는 받은 것을 그대로 옮겨 적기만 한다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 순서가 중요하다: 그림을 모두 받아 둔 **뒤에** 목록을 바꾼다. 키보드가 목록을 읽는
 * 순간에 그림이 아직 없을 일이 없게 하려는 것이다. 받다가 하나라도 실패하면 목록은
 * 그대로 두어, 키보드는 이전 목록을 계속 쓴다.
 */
object StickerStore {
  private const val DIRECTORY = "sticker-keyboard"

  /** `res/xml/sticker_keyboard_file_paths.xml` 이 여는 폴더와 같아야 한다. */
  private const val IMAGE_DIRECTORY = "images"

  private const val MANIFEST_FILE = "manifest.json"

  /** 스티커 한 장의 상한. 지금 그림은 100KB 안팎이라 넉넉하다 — 엉뚱한 응답을 거르는 값이다. */
  private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

  private const val TIMEOUT_MS = 10_000

  private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
  )

  /** 동기화가 겹치면 같은 파일을 두 번 쓰게 되므로 한 번에 하나만 돈다. */
  private val lock = Any()

  fun fileProviderAuthority(context: Context): String =
    "${context.packageName}.stickerkeyboard.fileprovider"

  fun uriFor(context: Context, sticker: StoredSticker): Uri =
    FileProvider.getUriForFile(context, fileProviderAuthority(context), sticker.file)

  /** 목록이 바뀌었는지 키보드가 확인하는 값. 목록 파일이 없으면 0. */
  fun revision(context: Context): Long = manifestFile(context).lastModified()

  /**
   * 웹이 보낸 목록대로 그림을 받아 두고 목록을 바꾼다. 저장된 스티커 수를 돌려준다.
   *
   * 파일 이름은 주소의 해시다. 웹 빌드는 그림이 바뀌면 주소(해시가 붙은 파일 이름)도
   * 바뀌므로, 이미 있는 파일은 다시 받지 않아도 된다 — 스티커 화면을 열 때마다 이 함수가
   * 불려도 처음 한 번만 실제로 내려받는다.
   */
  fun sync(context: Context, sources: List<StickerSource>): Int = synchronized(lock) {
    val imageDirectory = imageDirectory(context)
    imageDirectory.mkdirs()

    val entries = JSONArray()
    val keep = HashSet<String>()

    for (source in sources) {
      val fileName = "${sha256(source.url).take(32)}.png"
      val file = File(imageDirectory, fileName)
      if (!file.exists()) {
        download(source.url, file)
      }
      keep += fileName

      entries.put(
        JSONObject()
          .put("id", source.id)
          .put("categoryId", source.categoryId)
          .put("categoryLabel", source.categoryLabel)
          .put("name", source.name)
          .put("file", fileName),
      )
    }

    writeAtomically(manifestFile(context), JSONObject().put("stickers", entries).toString().toByteArray())

    // 목록에서 빠진 그림을 치운다. 이미 입력창에 넘긴 그림은 받은 앱이 자기 쪽으로
    // 복사해 가므로, 목록에서 빠진 뒤까지 남겨 둘 이유가 없다.
    imageDirectory.listFiles()?.forEach { file ->
      if (file.name !in keep) {
        file.delete()
      }
    }

    sources.size
  }

  /** 키보드가 그릴 탭과 스티커. 목록이 없거나 깨졌으면 빈 목록이다. */
  fun load(context: Context): List<StoredCategory> {
    val manifest = manifestFile(context)
    if (!manifest.exists()) {
      return emptyList()
    }

    val stickers = try {
      JSONObject(manifest.readText()).getJSONArray("stickers")
    } catch (error: Exception) {
      return emptyList()
    }

    val imageDirectory = imageDirectory(context)
    // 탭 순서를 웹이 보낸 순서대로 지키려고 LinkedHashMap 에 모은다.
    val categories = LinkedHashMap<String, Pair<String, MutableList<StoredSticker>>>()

    for (index in 0 until stickers.length()) {
      val entry = stickers.optJSONObject(index) ?: continue
      val file = File(imageDirectory, entry.optString("file"))
      if (!file.exists()) {
        continue
      }

      val categoryId = entry.optString("categoryId")
      val bucket = categories.getOrPut(categoryId) {
        entry.optString("categoryLabel") to mutableListOf()
      }
      bucket.second += StoredSticker(
        id = entry.optString("id"),
        name = entry.optString("name"),
        file = file,
      )
    }

    return categories.map { (id, value) -> StoredCategory(id, value.first, value.second) }
  }

  fun count(context: Context): Int = load(context).sumOf { it.stickers.size }

  private fun rootDirectory(context: Context) = File(context.filesDir, DIRECTORY)

  private fun imageDirectory(context: Context) = File(rootDirectory(context), IMAGE_DIRECTORY)

  private fun manifestFile(context: Context) = File(rootDirectory(context), MANIFEST_FILE)

  private fun download(url: String, target: File) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS

      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        throw StickerStoreException("스티커를 받지 못했습니다(${connection.responseCode}): $url")
      }

      val bytes = connection.inputStream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          if (output.size() > MAX_IMAGE_BYTES) {
            throw StickerStoreException("스티커가 너무 큽니다: $url")
          }
        }
        output.toByteArray()
      }

      // 키보드는 이 파일을 image/png 라고 소개해 넘긴다. 다른 것이 섞이면 받는 앱이
      // 깨진 그림을 띄우므로 여기서 거른다.
      if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
        throw StickerStoreException("PNG 가 아닙니다: $url")
      }

      writeAtomically(target, bytes)
    } finally {
      connection.disconnect()
    }
  }

  /** 옆 파일에 다 쓴 뒤 이름을 바꾼다. 키보드가 반쯤 쓴 파일을 읽지 않게 하려는 것이다. */
  private fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(target)) {
      temp.delete()
      throw StickerStoreException("파일을 저장하지 못했습니다: ${target.name}")
    }
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray())
      .joinToString("") { "%02x".format(it) }
}
