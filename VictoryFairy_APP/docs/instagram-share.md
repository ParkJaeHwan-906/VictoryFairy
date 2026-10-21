# 인스타그램 스토리 공유

승리요정에서 만든 그림(승패 기록·직관 인증 같은 것)을 사용자의 인스타 스토리에
**스티커로** 얹어 보내는 기능. 이 문서는 그게 어떤 원리로 동작하고, 왜 웹이 아니라
앱이, 왜 JS가 아니라 네이티브 코드가 필요한지를 적는다.

구현은 `modules/instagram-share/`(네이티브)와 `src/share/`(웹과의 배선)에 있다.

---

## 1. Sharing to Stories 라는 것

Meta가 문서로 공개한 **앱 사이의 약속**이다. HTTP API가 아니다 — 서버에 요청을
보내는 게 아니라, 내 앱이 인스타그램 앱을 열면서 이미지를 딸려 보내는 방식이다.
그래서 인증도, 토큰도, 레이트 리밋도 없다. 인스타 앱이 기기에 깔려 있어야만 동작한다.

인스타 스토리 편집기는 이 방식으로 받은 것을 두 겹으로 나눠 놓는다.

| 겹 | 사용자가 만질 수 있나 | 우리가 쓰는가 |
|---|---|---|
| **background** (이미지 또는 동영상) | ✗ 화면에 고정 | ✗ |
| **background 그라데이션** (색 두 개) | ✗ | ✓ 스티커 뒤를 채운다 |
| **sticker** (이미지) | ✓ 끌어서 이동·확대·회전 | ✓ 이것이 목적 |

> 문서 왈 — "You must send a background asset, a sticker asset, or both."

**우리는 스티커만 보낸다.** 배경 이미지까지 보내면 사용자가 찍어 둔 사진 대신 우리
이미지가 화면을 통째로 채운다. 사용자가 자기 스토리를 만들고 그 위에 우리 그림을
얹는 그림이 되어야 하므로, 배경은 색 두 개짜리 그라데이션에 맡기고 비워 둔다.

### 스티커 규격

- 권장 640×480, JPG 또는 PNG
- 투명 배경이 필요하므로 **PNG로 그린다** — 스티커가 사각형 사진처럼 보이면 안 된다
- 결과에 대해서는 아무것도 알 수 없다. 편집기가 열린 뒤 사용자가 실제로 게시했는지,
  취소했는지 인스타는 알려주지 않는다. 그래서 우리 코드의 `opened`는 "올렸다"가
  아니라 **"넘겼다"**까지다

---

## 2. 왜 웹이 아니라 앱인가

이 프로젝트의 기본 원칙은 "화면은 웹이 그린다"(`AGENTS.md`)이고, 기능을 붙일 곳은
대개 `VictoryFairy_FE`다. 그런데 이건 예외다.

브라우저에서 할 수 있는 공유는 **Web Share API**(`navigator.share`)가 전부인데,
이건 "이 파일을 아무 앱에나 넘겨라"까지만 표현할 수 있다. OS 공유 시트가 뜨고,
사용자가 인스타를 고르면 인스타는 그 파일을 **일반 게시물 또는 스토리 배경**으로
받는다. 스티커 겹을 지정할 방법이 Web Share API에는 없다.

스티커 겹을 지정하는 건 위에서 말한 "앱 사이의 약속"이고, 그 약속은 브라우저
샌드박스 밖에 있다. 그래서 분업이 이렇게 된다.

```
그림을 그린다           → 웹 (VictoryFairy_FE, canvas)
인스타에 넘긴다          → 앱 (이 저장소)
```

경기 알림과 같은 모양이다 — 웹이 아는 것과 앱만 할 수 있는 것을 잇는다.

---

## 3. 플랫폼별 원리

이미지를 넘기는 방법이 두 플랫폼에서 완전히 다르다. 네이티브 모듈이 감추는 것이
바로 이 차이고, JS에는 base64 문자열 한 장만 남는다.

### 안드로이드 — 인텐트 + FileProvider

```kotlin
Intent("com.instagram.share.ADD_TO_STORY").apply {
  type = "image/jpeg"
  putExtra("source_application", appId)
  putExtra("interactive_asset_uri", stickerUri)   // ← 스티커
  putExtra("top_background_color", "#...")
  putExtra("bottom_background_color", "#...")
  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
activity.grantUriPermission("com.instagram.android", stickerUri, FLAG_GRANT_READ_URI_PERMISSION)
activity.startActivity(intent)
```

여기에 걸려 넘어지기 쉬운 것이 네 가지 있다.

**(1) 이미지는 URI로만 건넌다.** 인텐트에 바이트 배열을 실을 수는 없다. 그래서
base64를 받아 캐시에 파일로 떨어뜨리고 그 URI를 넘긴다 — 한 번은 반드시 디스크를 거친다.

**(2) `file://`는 못 쓴다.** 안드로이드 7(API 24)부터 다른 앱에 `file://` URI를
넘기면 `FileUriExposedException`으로 앱이 죽는다. `FileProvider`로 감싼
`content://` URI여야 한다. 그래서 모듈이 자기 `AndroidManifest.xml`에 provider를
선언하고, `res/xml/instagram_share_file_paths.xml`로 **캐시 아래 딱 한 폴더만** 연다.

```xml
<provider
  android:name="androidx.core.content.FileProvider"
  android:authorities="${applicationId}.instagramshare.fileprovider"
  android:exported="false"
  android:grantUriPermissions="true">
```

`${applicationId}`를 쓰는 건 규약이 아니라 충돌 방지다 — 한 기기에 같은 authority를
쓰는 앱이 둘이면 나중에 깔리는 쪽이 **설치에 실패한다.**

**(3) `addFlags`만으로는 부족하다.** `FLAG_GRANT_READ_URI_PERMISSION`은 인텐트의
`data`에 걸리는 플래그다. 스티커는 `data`가 아니라 **extra**로 들어가므로,
`grantUriPermission()`을 직접 불러 인스타에게 따로 열어 줘야 한다. 이걸 빠뜨리면
인스타는 열리는데 스티커만 없다 — 오류도 나지 않아서 원인을 찾기 어렵다.

**(4) 안드로이드 11부터는 다른 앱이 안 보인다.** 패키지 가시성(package visibility)
제한 때문에, 선언 없이 `resolveActivity()`를 부르면 인스타가 깔려 있어도 `null`이
돌아온다. 모듈 매니페스트에 이렇게 선언해야 한다.

```xml
<queries><package android:name="com.instagram.android" /></queries>
```

> **`type = "image/jpeg"`인데 PNG를 보낸다?**
> 문서의 스티커 예제가 그렇게 한다. 이 자리는 스티커가 아니라 **배경 슬롯**의
> MIME 타입이고, 비워 두면 인스타가 인텐트를 거른다. 실제 스티커 형식은
> `interactive_asset_uri`가 가리키는 파일이 정한다.

### iOS — URL 스킴 + 붙여넣기판

iOS에는 인텐트가 없고 앱 사이로 데이터를 밀어 넣는 통로도 없다. 열 수 있는 건
URL뿐인데, URL에 수백 KB짜리 이미지를 실을 수는 없다. 그래서 Meta가 고른 방법이
**일반 붙여넣기판(`UIPasteboard.general`)** 이다.

```swift
UIPasteboard.general.setItems(
  [["com.instagram.sharedSticker.stickerImage": pngData]],
  options: [.expirationDate: Date().addingTimeInterval(60 * 5)]
)
UIApplication.shared.open(URL(string: "instagram-stories://share?source_application=\(appId)")!)
```

즉 **이미지 전달과 화면 전환이 별개의 두 동작**이다. 붙여넣기판에 먼저 놓고, 그
다음에 스킴을 연다. 순서가 바뀌면 인스타가 빈 붙여넣기판을 읽는다.

키는 이렇게 생겼다.

| 키 | 내용 |
|---|---|
| `com.instagram.sharedSticker.stickerImage` | 스티커 이미지 데이터 |
| `com.instagram.sharedSticker.backgroundImage` | 배경 이미지 (우리는 안 씀) |
| `com.instagram.sharedSticker.backgroundTopColor` | 그라데이션 위쪽 `#RRGGBB` |
| `com.instagram.sharedSticker.backgroundBottomColor` | 그라데이션 아래쪽 |

**만료 시간(5분)을 주는 이유**는 일반 붙여넣기판이 사용자의 복사·붙여넣기와 같은
공간이기 때문이다. 남겨 두면 사용자가 나중에 다른 앱에서 붙여넣기를 했을 때 우리
이미지가 튀어나온다. 인스타가 읽어 갈 만큼은 길고, 사용자가 놀라지는 않을 만큼 짧은
값이 문서의 5분이다. iOS 14부터는 이때 "○○이(가) 승리요정에서 붙여넣었습니다" 배너가
뜨는데, 이건 이 방식의 정상 동작이고 피할 수 없다.

**`canOpenURL`은 선언이 있어야 참을 돌려준다.** iOS 9부터 조회할 스킴을
`Info.plist`의 `LSApplicationQueriesSchemes`에 적어야 한다. 빠지면 인스타가 깔려
있어도 항상 거짓이라 "설치되지 않음"으로 오인한다. `android/`·`ios/`는 prebuild가
지우고 다시 만들므로 `app.json`에 넣는다.

```json
"ios": { "infoPlist": { "LSApplicationQueriesSchemes": ["instagram-stories"] } }
```

---

## 4. 리액트 네이티브(JS)만으로는 왜 안 되나

가장 자주 나오는 질문이라 따로 적는다. 결론부터 — **절반만 된다.**

| 해야 할 일 | JS API로 되나 |
|---|---|
| 인스타 앱 열기 | ✅ `Linking.openURL('instagram-stories://share?...')` |
| 설치 여부 확인 | ✅ `Linking.canOpenURL` (Info.plist 선언은 여전히 필요) |
| **iOS: 스티커 이미지 전달** | ❌ 방법이 없다 |
| **안드로이드: 스티커 이미지 전달** | ❌ 방법이 없다 |

**iOS가 막히는 이유.** 필요한 건 `UIPasteboard.setItems`에 **커스텀 타입 키**와
**만료 옵션**을 주는 것이다. RN의 `Clipboard`나 `expo-clipboard`가 다루는 건 평범한
텍스트와 이미지(표준 UTI) 하나뿐이라, `com.instagram.sharedSticker.stickerImage`
같은 키로 항목을 놓을 방법 자체가 노출돼 있지 않다.

**안드로이드가 막히는 이유.** `Linking.sendIntent(action, extras)`의 extras는
`string | number | boolean`만 받는다. 그런데 `interactive_asset_uri`는 **Uri
(Parcelable)** 로 들어가야 인스타가 읽는다 — 문자열로 넣으면 인스타 쪽
`getParcelableExtra`가 `null`을 돌려받는다. 게다가 `sendIntent`로는 MIME 타입도
플래그도 못 주고, `grantUriPermission()`을 부를 수단도 없다.
`expo-intent-launcher`도 extra를 Bundle 문자열로 넣는 건 마찬가지다.

그래서 **스티커 겹을 쓰는 한 네이티브 코드는 반드시 어딘가에 있어야 한다.**
선택지는 "직접 쓰느냐, 남의 것을 가져오느냐"이지 "네이티브를 쓰느냐 마느냐"가 아니다.

### 남의 것: `react-native-share`

`Share.shareSingle({ social: Share.Social.INSTAGRAM_STORIES, stickerImage, appId, ... })`
로 같은 일을 한다. 안에 들어 있는 건 결국 위와 똑같은 Kotlin·Swift 코드다.

이 프로젝트가 직접 쓰기로 한 이유:

- **`prebuild`가 필요한 건 어느 쪽이나 같다.** 둘 다 Expo Go에서는 못 돈다.
  "라이브러리를 쓰면 네이티브 빌드를 피할 수 있다"는 성립하지 않는다.
- 우리가 쓸 기능은 **스토리 스티커 하나**인데, `react-native-share`는 카카오톡·
  왓츠앱·텔레그램 등 수십 개 공유 대상을 함께 들고 온다. 그 목록에 맞춰
  `LSApplicationQueriesSchemes`와 `<queries>`가 통째로 따라 들어간다.
- 우리 모듈은 주석 포함 Kotlin 130줄 · Swift 95줄이고, 인스타가 규격을 바꾸면 그 두 파일만
  고치면 된다. 라이브러리였다면 업스트림 릴리스를 기다려야 한다.

반대로 **공유 대상이 카카오톡·트위터 등으로 늘어난다면** 그때는 갈아타는 게 맞다.
그 판단을 위해 모듈은 앱 사정(앱 ID 유무, 결말 이름)을 모르게 해 뒀다 —
그 층은 `src/share/instagram.ts`가 들고 있어서, 모듈만 들어내도 배선은 남는다.

---

## 5. 반드시 필요한 것: Meta 앱 ID

`developers.facebook.com`에서 앱을 만들면 받는 숫자 ID를 `source_application`으로
같이 보내야 한다. **2023년 1월부터 이 값이 없으면 인스타가 요청을 그냥 무시한다** —
오류도 나지 않고 편집기도 열리지 않아서, 모르고 보면 "아무 일도 안 일어난다".

- 비밀이 아니다. 요청의 출처 표시라 APK에 평문으로 들어가도 된다
  → `EXPO_PUBLIC_INSTAGRAM_APP_ID`
- **값을 두 곳에 둬야 한다.** `.env`는 gitignore 대상이라 EAS 빌드에 따라가지
  않는다 — 거기만 넣으면 로컬 빌드에서는 되고 스토어에 올라가는 빌드에서는 조용히
  실패한다. 웹 주소·API 주소와 같은 사정이라 같은 방법을 쓴다.

  | 어디 | 무엇을 위해 |
  |---|---|
  | `eas.json`의 `build.preview.env` · `build.production.env` | EAS가 만드는 빌드 |
  | `.env` (gitignore) | 로컬 `expo run:android` |
- **앱 심사는 필요 없다.** 심사가 필요한 건 스토리에 링크를 다는
  `contentURL`(스와이프업) 기능뿐이고, 스티커 공유는 대상이 아니다
- 값이 없으면 앱은 웹에 "공유 불가"로 답한다. 틀린 ID로 조용히 실패하느니
  버튼이 아예 안 보이는 쪽이 낫다는 판단이다(`src/share/instagram.ts`)

---

## 6. 이 저장소에서의 구조

```
modules/instagram-share/            로컬 Expo 모듈 (create-expo-module --local)
  android/src/main/
    AndroidManifest.xml             <queries> + FileProvider
    res/xml/…file_paths.xml         캐시 아래 한 폴더만 연다
    java/…/InstagramShareModule.kt  인텐트
  ios/InstagramShareModule.swift    붙여넣기판 + URL 스킴
  src/                              JS 쪽 타입·requireNativeModule

src/share/
  instagram.ts                      앱 ID 유무·실패 이름 붙이기
  bridge.ts                         웹 ↔ 앱 메시지 계약
  useInstagramShare.ts              WebView에 붙이는 배선
```

**모듈을 `modules/`에 둔 것**은 `AGENTS.md`의 "네이티브 디렉터리는 소유하지 않는다"를
지키기 위해서다. `android/`를 직접 고치면 다음 `prebuild --clean`에 사라지지만,
로컬 모듈은 소스이고 자동 링크 대상이라 재생성돼도 남는다. 매니페스트도 모듈이
자기 것을 들고 있다가 빌드 때 앱 매니페스트에 병합된다.

### 웹과의 계약

```
웹 → 앱   postMessage({ source: 'victoryfairy-app/instagram-share',
                        stickerBase64, backgroundTopColor?, backgroundBottomColor? })
앱 → 웹   window.__victoryFairyInstagramShare(outcome)
          'opened' | 'instagram-missing' | 'not-configured' | 'failed'
앱 → 웹   window.__victoryFairyInstagramShareAvailable   (로드마다 갱신되는 값)
```

- `stickerBase64`는 `data:image/png;base64,` 접두사가 붙어 있어도 된다 — 파서가
  떼 준다. 네이티브 디코더는 이 접두사를 모르고, 남겨 두면 "이미지를 읽을 수 없다"
  로만 돌아와 원인이 드러나지 않는다
- 색은 `#RRGGBB`만 통과한다. 어긋난 값은 **버리고 색 없이 보낸다** — 값 하나 때문에
  공유 전체가 조용히 죽는 것이 제일 나쁜 결과라서다
- 가용 여부를 값으로 놓아 두는 건 웹이 아무 때나 읽어야 하기 때문이다. 페이지가
  로드될 때마다 다시 놓으므로, 그 사이 인스타를 설치·삭제한 경우도 다음 로드에 따라온다

---

## 7. 확인과 디버깅

**에뮬레이터·시뮬레이터에서는 확인할 수 없다.** 인스타 앱이 깔려 있어야 하고,
Play 스토어 없는 에뮬레이터 이미지에는 설치가 안 된다. 실기기 + 개발 빌드가 필요하다.

```powershell
npx expo prebuild --platform android --clean
npx expo run:android --device
```

증상별로 원인이 거의 정해져 있다.

| 증상 | 거의 항상 이 원인 |
|---|---|
| 아무 일도 안 일어난다 | 앱 ID(`source_application`) 누락 — 인스타가 조용히 버린다 |
| "인스타그램이 없다"고 나오는데 깔려 있다 | 안드: `<queries>` 누락 / iOS: `LSApplicationQueriesSchemes` 누락 |
| 인스타는 열리는데 스티커가 없다 | 안드: `grantUriPermission()` 누락 / iOS: 붙여넣기판보다 스킴을 먼저 열었다 |
| `FileUriExposedException`으로 죽는다 | `file://`를 그대로 넘겼다 — FileProvider를 거쳐야 한다 |
| 스티커가 깨진 이미지로 뜬다 | base64에 `data:` 접두사가 남았다 |
| 스티커가 흰 사각형이다 | PNG가 아니거나 투명 배경이 아니다 |
| 설치가 실패한다(`INSTALL_FAILED_CONFLICTING_PROVIDER`) | FileProvider authority가 다른 앱과 겹쳤다 |

빌드 없이 확인할 수 있는 것도 있다. 매니페스트 병합 결과는 이렇게 본다.

```powershell
cd android
.\gradlew.bat :app:processDebugMainManifest
# app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

---

## 참고

- [Sharing to Stories](https://developers.facebook.com/docs/instagram-platform/sharing-to-stories) — Meta 공식 규격
- [Expo Module API](https://docs.expo.dev/modules/module-api/) — `Name` · `AsyncFunction` · `Record`
- [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [Package visibility (Android 11)](https://developer.android.com/training/package-visibility)
