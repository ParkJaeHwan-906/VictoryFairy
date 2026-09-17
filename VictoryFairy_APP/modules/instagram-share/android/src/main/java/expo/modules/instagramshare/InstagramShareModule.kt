package expo.modules.instagramshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File

/**
 * 인스타그램이 스토리 공유를 받는 액션.
 *
 * 공개된 API 가 아니라 인스타가 받아 주기로 한 인텐트 이름이다
 * (developers.facebook.com/docs/instagram-platform/sharing-to-stories).
 */
private const val ADD_TO_STORY_ACTION = "com.instagram.share.ADD_TO_STORY"

private const val INSTAGRAM_PACKAGE = "com.instagram.android"

/**
 * 인텐트에 붙이는 MIME 타입.
 *
 * 스티커는 투명 배경이 필요해 PNG 로 받는데도 문서의 스티커 예제가 이 값을 쓴다.
 * 이 자리는 스티커가 아니라 **배경 슬롯**의 타입이고, 비워 두면 인스타가 인텐트를
 * 거른다. 실제 스티커 형식은 `interactive_asset_uri` 가 가리키는 파일이 정한다.
 */
private const val BACKGROUND_MEDIA_TYPE = "image/jpeg"

/** 스티커 파일을 두는 캐시 하위 폴더. `res/xml/instagram_share_file_paths.xml` 과 같아야 한다. */
private const val SHARE_DIRECTORY = "instagram-share"

/** 캐시에 쓰는 스티커 파일 이름. 공유할 때마다 덮어쓴다. */
private const val STICKER_FILE_NAME = "sticker.png"

class StickerShareOptions : Record {
  @Field val appId: String = ""

  @Field val stickerBase64: String = ""

  @Field val backgroundTopColor: String? = null

  @Field val backgroundBottomColor: String? = null
}

class InstagramShareException(message: String) : CodedException(message)

class InstagramShareModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("InstagramShare")

    AsyncFunction("isAvailableAsync") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      isInstagramInstalled(context)
    }

    AsyncFunction("shareStickerAsync") { options: StickerShareOptions ->
      // 액티비티가 필요한 건 두 가지 때문이다 — 화면을 띄우는 것, 그리고 인스타에게
      // URI 권한을 주는 것. 앱이 백그라운드로 내려간 뒤 늦게 도착한 요청이면 없을 수 있다.
      val activity = appContext.activityProvider?.currentActivity
        ?: throw InstagramShareException("공유를 시작할 화면이 없습니다.")

      if (!isInstagramInstalled(activity)) {
        return@AsyncFunction "instagram-missing"
      }

      val stickerUri = writeSticker(activity, options.stickerBase64)

      val intent = Intent(ADD_TO_STORY_ACTION).apply {
        type = BACKGROUND_MEDIA_TYPE
        putExtra("source_application", options.appId)
        putExtra("interactive_asset_uri", stickerUri)
        options.backgroundTopColor?.let { putExtra("top_background_color", it) }
        options.backgroundBottomColor?.let { putExtra("bottom_background_color", it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

      // 플래그만으로는 부족하다. 플래그는 인텐트의 data 에 걸리는데 스티커는 extra 로
      // 들어가므로, extra 로 넘긴 URI 는 이렇게 직접 열어 줘야 인스타가 읽을 수 있다.
      activity.grantUriPermission(INSTAGRAM_PACKAGE, stickerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

      activity.startActivity(intent)
      "opened"
    }
  }

  /**
   * 인스타그램이 이 공유를 받을 수 있는 상태인지.
   *
   * 패키지 설치 여부가 아니라 **액션을 받아 줄 액티비티가 있는지**로 본다. 설치돼
   * 있어도 사용 중지된 경우가 있고, 그때 인텐트를 던지면 ActivityNotFoundException 이다.
   */
  private fun isInstagramInstalled(context: Context): Boolean {
    val probe = Intent(ADD_TO_STORY_ACTION).setType(BACKGROUND_MEDIA_TYPE)
    @Suppress("DEPRECATION")
    return context.packageManager.resolveActivity(probe, 0) != null
  }

  /**
   * base64 로 받은 스티커를 캐시에 파일로 떨어뜨리고 content:// URI 로 돌려준다.
   *
   * 앱 사이로 넘길 수 있는 건 URI 뿐이라 한 번은 디스크를 거쳐야 한다. 캐시에 두는
   * 것은 지워져도 그만인 값이기 때문이고, 매번 같은 이름에 덮어쓰는 것은 공유를
   * 반복해도 캐시가 불어나지 않게 하기 위해서다 — 인스타는 편집기를 열 때 이미지를
   * 자기 쪽으로 가져가므로 이전 파일을 남겨 둘 이유가 없다.
   */
  private fun writeSticker(context: Context, base64: String): Uri {
    val bytes = try {
      Base64.decode(base64, Base64.DEFAULT)
    } catch (error: IllegalArgumentException) {
      throw InstagramShareException("스티커 이미지를 읽을 수 없습니다.")
    }

    if (bytes.isEmpty()) {
      throw InstagramShareException("스티커 이미지가 비어 있습니다.")
    }

    val directory = File(context.cacheDir, SHARE_DIRECTORY)
    directory.mkdirs()
    val file = File(directory, STICKER_FILE_NAME)
    file.writeBytes(bytes)

    return FileProvider.getUriForFile(context, "${context.packageName}.instagramshare.fileprovider", file)
  }
}
