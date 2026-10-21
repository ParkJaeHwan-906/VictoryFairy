package expo.modules.stickerkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

private const val INSTAGRAM_PACKAGE = "com.instagram.android"

private const val INSTAGRAM_STORY_CAMERA_URL = "instagram://story-camera"

class StickerSourceRecord : Record {
  @Field val id: String = ""

  @Field val categoryId: String = ""

  @Field val categoryLabel: String = ""

  @Field val name: String = ""

  @Field val url: String = ""
}

class StickerKeyboardException(message: String) : CodedException(message)

/**
 * 앱(JS)에서 키보드를 다루는 창구.
 *
 * 키보드 자체(`StickerKeyboardService`)는 시스템이 띄우고 JS 와 이어지지 않는다.
 * 여기서 하는 일은 넷뿐이다 — 키보드가 쓸 스티커를 받아 두기, 키보드가 켜져 있는지
 * 알려 주기, 키보드 설정 화면 열기, 인스타그램 열기.
 */
class StickerKeyboardModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("StickerKeyboard")

    // AsyncFunction 은 백그라운드 스레드에서 돈다 — 내려받기를 여기서 바로 해도 된다.
    AsyncFunction("syncStickersAsync") { sources: List<StickerSourceRecord> ->
      val context = requireContext()
      try {
        StickerStore.sync(
          context,
          sources.map { StickerSource(it.id, it.categoryId, it.categoryLabel, it.name, it.url) },
        )
      } catch (error: Exception) {
        throw StickerKeyboardException(error.message ?: "스티커를 저장하지 못했습니다.")
      }
    }

    AsyncFunction("getStatusAsync") {
      val context = requireContext()
      mapOf(
        "enabled" to isEnabled(context),
        "selected" to isSelected(context),
        "stickerCount" to StickerStore.count(context),
      )
    }

    /**
     * 인스타그램을 연다. 스티커는 넘기지 않는다 — 담은 스티커는 키보드에 있고, 사용자가
     * 스토리 글쓰기 칸에서 하나씩 넣는다.
     *
     * 스토리 카메라로 바로 가는 `instagram://story-camera` 를 먼저 시도한다. 공개 문서에
     * 있는 주소가 아니라서, 받아 주는 화면이 없으면 인스타 첫 화면으로 연다.
     */
    AsyncFunction("openInstagramAsync") {
      val activity = appContext.activityProvider?.currentActivity
        ?: throw StickerKeyboardException("인스타그램을 열 화면이 없습니다.")
      val packageManager = activity.packageManager

      val storyCamera = Intent(Intent.ACTION_VIEW, Uri.parse(INSTAGRAM_STORY_CAMERA_URL))
        .setPackage(INSTAGRAM_PACKAGE)
      @Suppress("DEPRECATION")
      val storyCameraHandler = packageManager.resolveActivity(storyCamera, 0)
      if (storyCameraHandler != null) {
        activity.startActivity(storyCamera)
        return@AsyncFunction "opened"
      }

      val launch = packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
        ?: return@AsyncFunction "instagram-missing"
      activity.startActivity(launch)
      "opened"
    }

    AsyncFunction("openSettingsAsync") {
      val context = appContext.activityProvider?.currentActivity ?: requireContext()
      val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
      if (context !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }

  private fun requireContext(): Context =
    appContext.reactContext ?: throw StickerKeyboardException("앱 컨텍스트가 없습니다.")

  /** 설정 → 키보드에서 켜 두었는지. 켜야 키보드 전환 목록에 나타난다. */
  private fun isEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
  }

  /** 지금 기본 키보드로 골라 둔 상태인지. 켜 두기만 하고 쓰지 않는 사용자가 대부분이다. */
  private fun isSelected(context: Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
      ?: return false
    return ComponentName.unflattenFromString(current)?.packageName == context.packageName
  }
}
