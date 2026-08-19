# VictoryFairy_APP

배포된 React 웹(`../VictoryFairy_FE`)을 WebView로 감싼 안드로이드·iOS 앱.

화면·라우팅·상태는 전부 웹이 들고 있다. 앱은 웹을 띄우고, 웹이 할 수 없는 것 — 로드
실패 시 재시도, 안드로이드 하드웨어 백 버튼, 응원 구단 경기 알림 — 만 맡는다. 그래서 **FE 변경은 앱을 다시
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

로컬 release 빌드는 **debug keystore로 서명된다**. RN 템플릿 기본값이다
(`android/app/build.gradle`의 `signingConfigs`).

- 테스트 기기 설치에는 문제없다
- Play 스토어에는 업로드할 수 없다
- 정식 keystore로 서명한 빌드와 서명이 달라, 나중에 덮어쓰기 설치가 안 된다 (기존 앱 삭제 후 설치)

## 스토어 빌드 (EAS)

배포용 빌드는 로컬 Gradle이 아니라 EAS Build로 만든다. keystore·iOS 인증서를
EAS가 만들어 보관하고, Mac 없이 iOS를 빌드할 수 있다.

```powershell
npm install
npx eas login
npx eas init                   # 최초 1회. app.json에 projectId를 심는다

npm run build:preview          # 내부 테스트용 APK
npm run build:android          # 스토어용 AAB
npm run build:ios              # 스토어용 IPA
```

프로필은 `eas.json`에 있다. 버전은 `appVersionSource: "remote"`라 `versionCode`·
`buildNumber`를 EAS가 올린다 — 손으로 건드리지 않는다. 표시용 버전만
`app.json`의 `version`에서 관리한다.

스토어 등록 절차·심사 요구사항은 [`docs/store-release.md`](docs/store-release.md).

## 구조

```
App.tsx              SafeAreaProvider · 상태바
index.ts             registerRootComponent
app.json             앱 이름·아이콘·번들 ID·스플래시·알림 플러그인
src/
  WebAppView.tsx     WebView · 로딩 · 오류 재시도 · 안드로이드 백 버튼
  webScreen.ts       백 버튼이 쓸 웹 화면 정보 (경로 · 히스토리 위치 · 시트 열림)
  config.ts          WEB_URL · API_USER_BASE_URL
  theme.ts           앱 셸 색·반경·타이포 (FE 디자인 토큰에서 가져옴)
  notifications/
    useGameReminders.ts  WebView 배선 — 언제 다시 확인하고 언제 물어볼지
    bridge.ts            WebView에 주입해 응원 구단·일정을 긁는 스크립트
    reminders.ts         권한 · 안드로이드 채널 · 알림 예약 동기화
    prompt.ts            권한 안내 시트를 미룬 시점 기록
    PermissionSheet.tsx  권한 안내 바텀시트 (FE 경기 상세 시트 디자인)
```

## 경기 알림

응원 구단 경기 시작 **30분 전**에 알림을 보낸다. 서버가 밀어주는 원격 푸시가 아니라
기기에 미리 예약해 두는 **로컬 알림**이다 — 경기 일정은 공개 API로 이미 알 수 있어
백엔드에 푸시 토큰 저장·발송 경로를 만들지 않고도 동작한다.

1. 웹이 뜨거나 앱이 다시 활성화되면 WebView에 스크립트를 주입한다 (`notifications/bridge.ts`).
2. 스크립트가 웹 localStorage의 액세스 토큰으로 `GET /users/me`(응원 구단)와 앞으로 7일치
   `GET /games`를 부르고, 응원 구단의 `SCHEDULED` 경기만 골라 앱으로 넘긴다.
   둘 다 백엔드 **user 모듈**(`/api`)이라 `API_USER_BASE_URL` 하나면 된다 — 채팅·퀴즈는
   `/rt`(game 모듈)에 있어서, 앱이 그쪽을 쓰게 되면 base를 하나 더 만들어야 한다.
3. 앱이 경기 시각 −30분에 알림을 예약한다. 이미 잡혀 있는 예약과 대조해 달라진 것만 고친다
   (`notifications/reminders.ts`).

**토큰은 네이티브로 꺼내오지 않는다.** 조회 자체를 WebView 안에서 끝낸다 — 만료 재발급
로직을 앱에 한 벌 더 두지 않아도 되고, 웹이 로그아웃했는데 앱이 든 사본만 남는 일도 없다.

조회에 실패하면(오프라인·토큰 만료) 예약을 건드리지 않는다. 실패를 "경기 없음"으로
취급하면 잠깐 끊긴 사이에 예약이 전부 지워진다.

권한은 **알릴 경기가 실제로 있을 때** 처음 묻는다. 시스템 대화상자는 한 번 거절당하면
다시 뜨지 않으므로, 그 앞에 이유를 설명하는 바텀시트를 한 겹 둔다(웹의 경기 상세 시트
디자인을 따른다). "다음에 할게요"면 2주 뒤에 다시 묻는다.

정확 알람 권한(`SCHEDULE_EXACT_ALARM`)은 요구하지 않는다 — Play 정책상 해명이 필요한
권한인데, 30분 전 안내에 초 단위 정확도가 필요하지는 않다. 대신 기기가 절전 상태면
몇 분 늦게 도착할 수 있다.

### 확인하는 법

네이티브 모듈이 늘었으므로 `npx expo prebuild --platform android --clean` 뒤 다시 빌드해야
한다. Expo Go는 알림 지원이 제한적이라 `npm run android`로 만든 개발 빌드에서 확인한다.

경기 30분 전을 기다릴 수 없으니 `src/notifications/reminders.ts`의 `REMINDER_LEAD_MS`를
임시로 크게(예: 8시간) 잡으면 다음 경기로 바로 확인할 수 있다.

## 안드로이드 백 버튼

누른 자리에 따라 셋으로 갈린다.

| 지금 화면 | 뒤로가기 |
| --- | --- |
| 바텀시트가 열려 있다 | 시트만 닫는다 (화면은 그대로) |
| NavBar가 있는 화면 (`/main` `/game` `/community` `/my`) | "한 번 더 누르면 종료돼요" → 한 번 더 누르면 종료 |
| 그 밖 (`/login` `/signup` 온보딩 `/quiz` …) | 이전 화면으로 |

**NavBar 화면에서 되감지 않는 이유**가 이 규칙의 핵심이다. 웹의 NavBar는 탭을 옮길
때마다 히스토리를 쌓기 때문에, 되감으면 사용자가 기억하지 못하는 순서로 탭 사이를
오간다("홈 → 경기 → 라운지 → 홈 → …"). 안드로이드에서 탭은 각자 뿌리이고 그 자리의
뒤로가기는 앱을 나가는 쪽에 가깝다.

되감기는 `webViewRef.goBack()`이 아니라 페이지 안에서 `history.back()`으로 한다.
SPA의 화면 이동은 문서 로드가 아니라 `pushState`라, WebView가 세는 문서 단위로 되감으면
몇 단계를 지나왔든 첫 문서로 돌아간다.

판단에 필요한 것(경로 · 히스토리 위치 · 시트 열림)은 웹이 바뀔 때마다 앱에 알려준다
(`src/webScreen.ts`) — 백 버튼은 눌린 그 자리에서 답을 내야 해서 그때 물어보면 늦다.
시트는 클래스가 아니라 `role="dialog"` + `aria-modal="true"`로 찾고, 닫을 때는 시트를
덮는 딤(=닫기 버튼)을 대신 누른다.

## 아이콘·스플래시

원본은 `assets/LOGO.svg` 하나다(Figma `SWM` / `[Splash] Basic` 의 LOGO, 280×280).
`assets/`의 PNG들은 전부 여기서 뽑은 것이라, 로고가 바뀌면 SVG를 갈아끼우고 다시 뽑는다.

| 파일 | 규격 | 로고를 얼마나 키웠나 |
| --- | --- | --- |
| `splash-icon.png` | 1120×1120, 투명 | 판 전체 (디자인의 280dp × 4배 밀도) |
| `icon.png` | 1024×1024, **알파 없음**(흰 바탕) | 950 — iOS 라운드 마스크 안쪽에 두려고 여백을 둔다 |
| `android-icon-foreground.png` | 1024×1024, 투명 | 660 — 적응형 아이콘 안전 영역(가운데 66/108) 안에 들어가야 한다 |
| `favicon.png` | 64×64, 투명 | 판 전체 |

배경은 전부 흰색이다 — 스플래시(`backgroundColor`)도, 적응형 아이콘 바탕
(`adaptiveIcon.backgroundColor`)도 `#FFFFFF`다.

### 안드로이드 스플래시만 로고가 작다

`imageWidth`가 플랫폼별로 다르다 — iOS 280, **안드로이드 192**.

안드로이드 12+ 는 스플래시를 시스템이 그린다. 로고는 288dp 판 한가운데에 놓이고
**지름 192dp 원 밖은 잘려 나간다.** 디자인대로 280dp를 주면 원 밖으로 나가 워드마크
양끝이 사라진다. 192dp면 가장 먼 픽셀까지 6dp가 남는다(잘리지 않는 것을 렌더된
`drawable-xxxhdpi/splashscreen_logo.png`에서 확인했다).

세로 위치도 시스템이 정한다 — 디자인은 로고를 화면 가운데보다 40px쯤 위에 두지만,
`expo-splash-screen`에는 그 여백을 줄 수 있는 값이 없어 가운데 정렬이다.

## 아직 안 된 것

- **알림 아이콘·테마 아이콘이 없다.** 둘 다 브랜드 로고를 그대로 줄여 쓸 수 없고
  **단색 실루엣**이어야 해서, 디자인에서 따로 받아야 한다.
  - 안드로이드 알림 아이콘(96×96, 흰색 실루엣 + 투명 배경) — 지금은 지정하지 않아
    상태 표시줄에서 앱 아이콘이 흰 덩어리로 뭉개진다.
    받으면 `app.json`의 `expo-notifications` 플러그인 `icon`에 건다.
  - 안드로이드 테마 아이콘(`adaptiveIcon.monochromeImage`) — 로고의 알파를 그대로 쓰면
    바깥 원판이 알파를 가득 채워 그냥 동그라미가 되고, 원판을 빼고 워드마크만 남기면
    글자 속이 메워져 읽히지 않는다. 그래서 참조를 지웠고, 지금은 안드로이드가
    일반 적응형 아이콘으로 폴백한다.
- **원격 푸시** — 경기 알림은 기기에 예약하는 로컬 알림이라 일정이 미리 정해진 것만
  알릴 수 있다. 경기 중 속보·퀴즈 오픈처럼 서버가 시점을 정하는 알림을 보내려면
  백엔드에 푸시 토큰 저장·발송 경로가 필요하다.
- **소셜 로그인** — 웹에 OAuth가 붙으면 카카오·네이버 도메인 리다이렉트를 WebView 안에서
  처리할지 시스템 브라우저로 뺄지 정해야 한다. 지금은 모든 링크를 WebView 내부에서 연다.
- **iOS** — `app.json`·`eas.json` 설정은 돼 있지만 빌드·실행한 적이 없다.
