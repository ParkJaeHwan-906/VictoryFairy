# VictoryFairy_APP

배포된 React 웹(`../VictoryFairy_FE`)을 WebView로 감싼 안드로이드·iOS 앱.

화면·라우팅·상태는 전부 웹이 들고 있다. 앱은 웹을 띄우고, 웹이 할 수 없는 것 — 로드
실패 시 재시도, 안드로이드 하드웨어 백 버튼 — 만 맡는다. 그래서 **FE 변경은 앱을 다시
설치하지 않아도 반영된다.** main에 머지되면 `deploy-fe.yml`이 S3에 올리고, `index.html`이
엣지 TTL 0이라 앱을 껐다 켜면 최신 화면이 뜬다.

반대로 `app.json`·`src/` 를 고치면 APK를 다시 만들어 설치해야 한다.

- Expo SDK 57 / React Native 0.86 / React 19 / TypeScript 6
- 번들 ID · 패키지명: `com.victoryfairy.app`

## 필요한 것

| | 버전 | 비고 |
|---|---|---|
| Node.js | 20 이상 | |
| **JDK** | **17** | 네이티브 빌드에 필수. 아래 참고 |
| Android SDK | platform 36, build-tools 36 | Android Studio로 설치 |

### JDK 17을 반드시 써야 한다

Android Studio 번들 JBR은 25이고, 그걸로 빌드하면 이렇게 실패한다:

```
Execution failed for task ':app:configureCMakeRelWithDebInfo[arm64-v8a]'.
> WARNING: A restricted method in java.lang.System has been called
```

JDK 24부터 JNI 관련 restricted method 호출이 경고를 내는데, AGP가 이 출력을 CMake
오류로 오인한다. `--enable-native-access=ALL-UNNAMED`를 줘도 같은 자리에서 죽는다.

시스템 `JAVA_HOME`을 바꾸는 대신 빌드할 때만 지정하는 걸 권한다 — 백엔드가 Java 21을
쓰고 있어(`../VictoryFairy_BE/build.gradle`) 그쪽 빌드에 영향이 갈 수 있다.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
```

로그에 함께 뜨는 `CXX5304: SDK XML version 4`는 SDK 패키지 메타데이터가 AGP보다
새로워서 나오는 경고로, 빌드를 막지 않는다.

## 개발

```bash
npm install
npm start        # Metro 개발 서버
```

기본값은 운영 도메인(`https://victoryfairy.com`)을 로드한다. 로컬 웹에 붙이려면
`.env.example`을 `.env`로 복사해 주소를 바꾼다. 실기기 기준으로 써야 하므로
`localhost`가 아니라 PC의 LAN IP를 넣는다.

```
EXPO_PUBLIC_WEB_URL=http://192.168.0.10:5173
```

## APK 빌드

`android/`는 gitignore 대상이고 `expo prebuild`가 생성한다. 처음이거나 `app.json`을
고쳤다면 먼저 재생성한다.

```powershell
npx expo prebuild --platform android --clean
```

이어서 release APK를 만든다.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
cd android
.\gradlew.bat assembleRelease -PreactNativeArchitectures=arm64-v8a
```

결과물: `android/app/build/outputs/apk/release/app-release.apk`

`-PreactNativeArchitectures`를 빼면 4개 아키텍처(armeabi-v7a, arm64-v8a, x86, x86_64)를
모두 만든다. 요즘 안드로이드 실기기는 전부 arm64라 테스트용으로는 하나면 충분하고
빌드 시간과 APK 크기가 크게 줄어든다. 스토어 배포용은 아키텍처를 모두 포함해야 한다.

### 서명

release 빌드는 **debug keystore로 서명된다**. RN 템플릿 기본값이다
(`android/app/build.gradle`의 `signingConfigs`).

- 테스트 기기 설치에는 문제없다
- Play 스토어에는 업로드할 수 없다
- 정식 keystore로 서명한 빌드와 서명이 달라, 나중에 덮어쓰기 설치가 안 된다 (기존 앱 삭제 후 설치)

## 구조

```
App.tsx              SafeAreaProvider · 상태바
index.ts             registerRootComponent
app.json             앱 이름·아이콘·번들 ID·스플래시
src/
  WebAppView.tsx     WebView · 로딩 · 오류 재시도 · 안드로이드 백 버튼
  config.ts          WEB_URL
  theme.ts           앱 셸 색상 (FE 디자인 토큰에서 가져옴)
```

## 아직 안 된 것

- **아이콘·스플래시 이미지가 Expo 기본 자산이다.** 브랜드 로고(1024×1024 PNG)를 받으면
  `assets/`의 `icon.png`, `android-icon-foreground.png`, `splash-icon.png`를 교체한다.
  배경색은 `app.json`에 브랜드 컬러(`#F04E23`)로 잡혀 있다.
- **소셜 로그인** — 웹에 OAuth가 붙으면 카카오·네이버 도메인 리다이렉트를 WebView 안에서
  처리할지 시스템 브라우저로 뺄지 정해야 한다. 지금은 모든 링크를 WebView 내부에서 연다.
- **iOS** — `app.json` 설정은 돼 있지만 빌드·테스트한 적이 없다. Mac 또는 EAS Build가 필요하다.
- **푸시 알림**
