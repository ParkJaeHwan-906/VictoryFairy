# 스토어 출시

Google Play · App Store 등록 절차. 빌드와 서명은 EAS Build가 맡는다 —
Windows에서 iOS를 빌드해야 하고, keystore·인증서를 직접 보관하지 않아도 되기 때문이다.

## 먼저 막고 있는 것

코드보다 이쪽이 일정을 지배한다. 순서대로 걷어내야 한다.

### 1. ~~아이콘·스플래시가 아직 Expo 기본 이미지다~~ → 걷어냈다

브랜드 로고(`assets/LOGO.svg`)로 전부 교체했다. 앱 아이콘·적응형 아이콘 전경·스플래시·
파비콘이 모두 이 SVG 하나에서 나오고, 배경은 흰색이다. 규격과 크기 규칙은
[`README.md`](../README.md)의 "아이콘·스플래시" 참고.

스플래시는 Figma `SWM` / `[Splash] Basic`(node `1461:16493`) 그대로 — 흰 바탕에
로고 280dp 하나다. 안드로이드 12+ 는 시스템이 스플래시를 그리면서 지름 192dp 원 밖을
잘라내므로 그쪽만 `imageWidth`를 192로 낮췄다.

**남은 것은 단색 실루엣 두 개다.** 로고를 줄여서 만들 수 없어 디자인에서 받아야 한다.

| 파일 | 규격 | 쓰이는 곳 | 없으면 |
|---|---|---|---|
| (미정) | 96×96 PNG, 흰색 실루엣 + 투명 배경 | 안드로이드 알림 아이콘 | 상태 표시줄에서 앱 아이콘이 흰 덩어리로 뭉개진다 |
| (미정) | 1024×1024 PNG, 단색 실루엣 | 안드로이드 테마 아이콘(`monochromeImage`) | 테마 아이콘을 켠 런처에서 일반 아이콘으로 폴백한다 |

둘 다 **제출을 막지는 않는다.** 스토어 심사 항목이 아니라 마감의 문제다.

### 2. 웹뷰 래퍼는 최소 기능 심사에 걸린다

이 앱은 `https://victoryfairy.com`을 WebView로 띄우는 것이 전부다. 양쪽 스토어 모두
이걸 명시적으로 규제한다.

- **Apple App Store Review Guideline 4.2 (Minimum Functionality)** — 웹사이트를
  다시 포장한 앱은 거절 대상이다. Apple 쪽 리스크가 훨씬 크고, 실제로 웹뷰 단독
  앱은 초회 제출에서 리젝되는 일이 흔하다.
- **Google Play Spam and Minimum Functionality 정책** — 같은 취지지만 Play 쪽이
  상대적으로 통과가 쉽다.

통과 확률을 올리려면 앱만 할 수 있는 기능을 붙여야 한다. 비용 대비 효과 순:

1. ~~**푸시 알림**~~ — **붙였다.** 응원 구단 경기 시작 30분 전 알림
   (`expo-notifications`, `src/notifications/`). 동작과 설계는 `README.md`의 "경기 알림" 참고.
   원격 푸시가 아니라 기기에 예약하는 로컬 알림이지만, 알림 권한을 요청하고 알림을
   보내는 **네이티브 기능**이라는 점은 같다. 심사 메모에는 "응원 구단 경기 시작 30분 전
   알림"이라고 용도를 적고, 테스트 계정에는 응원 구단이 설정돼 있어야 한다
   (구단이 없으면 알림 권한 요청 자체가 뜨지 않는다).
2. **딥링크** — `scheme: victoryfairy`가 이미 잡혀 있으니 웹 링크를 앱으로 여는
   경로를 연결한다. 알림을 누르면 경기 목록으로 이동하는 처리는 이미 들어가 있다.
3. **네이티브 공유** — 경기 결과·퀴즈 결과를 시스템 공유 시트로.

알림이 붙었으니 Apple 4.2 리스크는 눈에 띄게 줄었다. 남은 리젝 사유는 대부분
아래 3·4번(개인정보처리방침·회원탈퇴)이다.

### 3. 개인정보처리방침이 앱에 연결돼 있지 않다

방침 문서는 노션에 있다:
<https://fate-almanac-c79.notion.site/3bbad13a96fe8033a87ac32602615758>

스토어 콘솔에 URL을 넣는 것과 별개로, **앱 안에서도 접근 가능해야 한다**
(회원가입이 있고 이메일을 수집하므로). 화면은 웹이 그리므로 이 작업은
`VictoryFairy_FE` 쪽이다 — 마이페이지나 회원가입 화면에 링크를 건다.

노션 공개 페이지를 그대로 쓰면 심사에서 문제되지는 않지만, 자체 도메인
(`victoryfairy.com/privacy`)으로 두는 편이 안전하다. 노션 사이트가 내려가면
방침이 접근 불가가 되고, 그건 정책 위반이다.

### 4. 회원탈퇴 화면이 없다

`VictoryFairy_FE`에 `DELETE /users/me`(`src/api/account.ts`의 `withdraw`)는 있는데
`MyPage.tsx`에서 부르는 곳이 없다.

**Apple Guideline 5.1.1(v)** — 계정 생성이 가능한 앱은 앱 안에서 계정 삭제도
가능해야 한다. 이건 예외 없는 필수 조건이고, 안 되어 있으면 확정 리젝이다.
Play도 계정 삭제 경로를 데이터 안전 섹션에서 요구한다.

API가 이미 있으니 FE에 UI만 붙이면 된다.

### 5. Play 신규 개인 계정이면 비공개 테스트 14일이 필요하다

2023년 11월 13일 이후에 만든 **개인** 개발자 계정은 프로덕션 출시 전에
테스터 12명이 14일 연속 옵트인된 비공개 테스트를 마쳐야 한다.
조직(사업자) 계정과 그 이전에 만든 개인 계정은 해당 없다.

즉 개인 계정으로 새로 시작하면 **출시까지 최소 2주가 더 걸린다.** 팀 계정이
이미 있는지, 사업자 등록으로 조직 계정을 만들 수 있는지 먼저 확인하는 게 좋다.

## 계정 준비

| | 비용 | 비고 |
|---|---|---|
| Google Play Console | $25 (1회) | 승인까지 며칠 걸릴 수 있다 |
| Apple Developer Program | $99/년 | 조직 가입은 D-U-N-S 번호가 필요하다 |
| Expo 계정 | 무료 | EAS Build 무료 티어는 빌드 대기열이 길다 |

## 이 저장소에서 끝난 설정

- **`eas.json`** — `preview`(내부 배포용 APK), `production`(스토어용 AAB) 프로필.
  버전은 `appVersionSource: "remote"`로 EAS가 관리한다. `versionCode`·`buildNumber`를
  손으로 올리지 않아도 되고, 브랜치 간 충돌도 생기지 않는다. 사람이 읽는
  버전(`1.0.0`)만 `app.json`의 `version`에서 관리한다.
- **`app.json`**
  - `ios.config.usesNonExemptEncryption: false` — HTTPS만 쓰므로 수출 규정 대상이
    아니다. 넣어 두면 제출할 때마다 뜨는 암호화 문항을 건너뛴다.
  - `android.blockedPermissions` — RN 템플릿이 자동으로 넣는 것 중 쓰지 않는 3개
    (`READ/WRITE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`)를 제거한다.
    쓰지 않는 권한이 매니페스트에 있으면 데이터 안전 섹션에서 해명해야 하고,
    `SYSTEM_ALERT_WINDOW`는 특히 심사에서 눈에 띈다.
    `VIBRATE`는 경기 알림이 붙으면서 실제로 쓰게 되어 목록에서 뺐다.
    FE에 이미지 업로드가 붙으면 `READ_MEDIA_IMAGES`가 필요해질 수 있다.
  - **알림 관련 권한** — `expo-notifications`가 `POST_NOTIFICATIONS`(안드로이드 13+)와
    `RECEIVE_BOOT_COMPLETED`(재부팅 후에도 예약이 살아남게)를 넣는다. 정확 알람
    (`SCHEDULE_EXACT_ALARM`)은 **일부러 넣지 않았다** — Play가 알람·캘린더가 본업인
    앱에만 허용하는 권한이라 해명 대상이 되는데, 30분 전 안내에는 필요 없다.

    `expo-notifications`는 여기에 딸려 오는 것들도 함께 넣는다. FCM 쪽
    (`WAKE_LOCK`·`ACCESS_NETWORK_STATE`·`c2dm.permission.RECEIVE`)과 배지 라이브러리
    (ShortcutBadger)가 제조사 런처마다 하나씩 선언하는 20여 개
    (`com.sec.android.provider.badge.*`, `READ_APP_BADGE` 등)다. 배지는 쓰지 않지만
    (`shouldSetBadge: false`) 이 목록은 **막지 않았다** — Play의 민감·제한 권한이
    아니고 데이터 안전 양식에도 나오지 않는데, 20여 줄을 `blockedPermissions`에
    적어 두면 나중에 배지를 켤 때 조용히 실패하는 함정만 남는다. 심사에서 지적받으면
    그때 목록에 넣으면 된다(전부 `blockedPermissions`로 제거 가능하다).
- **`package.json`** — 빌드·제출 스크립트는 `npx eas-cli ...`로 부른다.
  **`eas-cli`를 의존성에 두지 않는다.** `npx expo-doctor`가 지적하는 항목이기도 하고
  ("EAS CLI should not be installed in your project"), 실제로 EAS 빌드에 부담이 된다 —
  빌더는 `npm ci --include=dev`로 devDependency까지 설치하므로 `eas-cli`의 의존성
  트리(약 200개, 그중 `dtrace-provider`는 node-gyp로 네이티브를 빌드한다)가 빌드
  머신에서 매번 설치된다. 앱이 쓰지도 않는 것이다. 버전은 `eas.json`의
  `cli.version`(`>= 21.0.0`)이 묶어 준다.

  명령을 `npx eas`가 아니라 **`npx eas-cli`**로 써야 한다. 패키지 이름이 `eas-cli`라
  `npx eas`는 npm에 있는 다른 `eas` 패키지를 받아 온다.
- **`.gitignore`** — Play 서비스 계정 키를 제외.

`targetSdk`는 36이다(Expo SDK 57 기본값). Play가 2026년 8월 31일부터 신규 앱·업데이트에
API 36을 요구하는데, 이미 충족한다.

## 빌드와 제출

```powershell
npm install
npx eas-cli login
npx eas-cli init                   # Expo 프로젝트 생성 · app.json에 projectId를 심는다
```

`eas init`은 `app.json`에 `extra.eas.projectId`와 `owner`를 추가한다. 커밋한다.

### 안드로이드

```powershell
npm run build:android          # AAB. keystore가 없으면 EAS가 만들어 보관한다
```

첫 빌드에서 keystore 생성 여부를 묻는다. EAS에 맡기면
`npx eas-cli credentials`로 언제든 내려받을 수 있다.

**첫 릴리스는 Play Console에 손으로 올려야 한다.** Play Developer API는 앱이
콘솔에 이미 존재해야 동작하므로, 1회차는 빌드 결과 AAB를 내려받아 직접 업로드한다.
그 다음부터 `npm run submit:android`가 쓸 수 있게 된다 — 이때
[서비스 계정 키](https://docs.expo.dev/submit/android/)를
`google-play-service-account.json`으로 저장소 루트에 둔다(gitignore 대상).

### iOS

```powershell
npm run build:ios              # 인증서·프로비저닝 프로파일을 EAS가 만든다
npm run submit:ios             # TestFlight 업로드
```

Apple 계정 로그인을 묻는다. App Store Connect에 앱이 없으면 EAS가 만들어 준다.
TestFlight에 올라간 뒤 실기기에서 먼저 확인하고 심사에 제출한다 —
**이 앱은 iOS에서 한 번도 빌드·실행된 적이 없다.**

#### iOS 번들 ID만 `com.victoryfairy.mobile`인 이유

App ID는 Apple 전체 개발자 계정을 통틀어 유일해야 하는데 `com.victoryfairy.app`이
이미 다른 팀에 등록돼 있었다. 회수 경로가 없어서(도메인 소유권도 근거가 되지 않는다)
iOS 쪽만 다른 문자열을 쓴다. 안드로이드 `package`는 Play에 올라간 뒤로는 바꾸면
다른 앱이 되므로 `com.victoryfairy.app` 그대로 둔다. 두 값이 같을 필요는 없다.

#### `eas.json`에 iOS 항목이 없는 이유

없어도 된다. `production`은 프로필 최상단의 `distribution: "store"`만으로 App Store용
IPA가 나오고, 인증서·프로비저닝 프로파일은 `credentialsSource: "remote"`(기본값)로
EAS가 만들어 보관한다. `autoIncrement`도 안드로이드의 `versionCode`와 iOS의
`buildNumber`를 함께 올린다. 실제로 `npx eas-cli config --platform ios --profile production`이
해석해 주는 값이 그렇다.

다만 두 가지는 알고 있어야 한다.

- **`preview`를 iOS로 돌리면 ad hoc 빌드가 된다.** `distribution: "internal"`이라
  설치할 기기의 UDID를 미리 등록해야 한다(`npx eas-cli device:create`). 등록하지 않으면
  빌드는 되지만 어느 기기에도 설치되지 않는다. Mac 없이 iOS를 테스트하는 현실적인
  경로는 **TestFlight**(= `production` 빌드 → `submit`)이므로, 이 프로필은 안드로이드
  전용으로 두고 있다(`npm run build:preview`도 `--platform android`다).
- **`submit.production`에 `ios`가 없다.** 그래서 `npm run submit:ios`는 Apple ID·앱을
  대화형으로 묻는다. 값이 정해지면 아래를 채워 두면 물어보지 않는다.

  ```json
  "submit": {
    "production": {
      "ios": {
        "appleId": "<Apple 계정 이메일>",
        "ascAppId": "<App Store Connect 앱 ID(숫자)>",
        "appleTeamId": "<팀 ID>"
      }
    }
  }
  ```

#### 빌드할 때 뜨는 "Expo Go" 경고

`production` 프로필로 빌드하면 EAS CLI가 이렇게 경고한다.

> ⚠️ Detected that your app uses Expo Go for development, this is not recommended
> when building production apps.

**빌드 결과물에는 Expo Go와 관련된 것이 아무것도 들어가지 않는다.** 이 경고는
"개발을 Expo Go로 하는 것 같다"는 **추측**이고, 판단 기준은 셋뿐이다
(`eas-cli/build/project/discourageExpoGoForProdAsync.js`).

1. `production` 프로필로 빌드하는가
2. `expo-dev-client`가 설치돼 있지 않은가
3. `android/`·`ios/` 네이티브 디렉터리가 저장소에 없는가

이 저장소는 2·3을 그대로 만족한다 — `android/`를 gitignore하고 `prebuild`로
생성하는 것이 **의도한 구조**이기 때문이다(`AGENTS.md`). 실제 개발은
`npm run android`(`expo run:android`)로 네이티브 빌드를 만들어서 하므로,
경고가 말하는 위험(Expo Go에서는 `app.json` 설정과 네이티브 모듈이 다르게 동작한다)은
이미 피하고 있다.

셋 중 하나를 고르면 된다.

| | 방법 | 대가 |
|---|---|---|
| 그냥 둔다 | 경고는 빌드를 막지 않는다 | 빌드할 때마다 뜬다 |
| 숨긴다 | `EAS_BUILD_NO_EXPO_GO_WARNING=true` | 나중에 진짜 문제일 때도 안 뜬다 |
| 없앤다 | `npx expo install expo-dev-client` | 네이티브 모듈이 하나 늘고 재빌드가 필요하다. 대신 `npm start`가 Expo Go 대신 개발 빌드를 붙잡고, 개발 메뉴가 생긴다 |

## 스토어 등록 정보

콘솔에서 채워야 하는 것들. 두 스토어 공통으로 준비할 자료부터.

### 공통

- 앱 이름: 승리요정
- 짧은 설명 / 부제
- 자세한 설명
- 스크린샷 — 실기기 화면. 로그인·메인·경기·퀴즈·마이페이지 정도
- 개인정보처리방침 URL (위 노션 링크 또는 자체 도메인)
- 지원 이메일

### Google Play

| 항목 | 규격 |
|---|---|
| 앱 아이콘 | 512×512 PNG |
| 그래픽 이미지 | 1024×500 PNG/JPG |
| 휴대전화 스크린샷 | 최소 2장 (권장 4~8장) |
| 짧은 설명 | 80자 |
| 자세한 설명 | 4000자 |

추가로 **데이터 안전(Data safety) 양식**을 채워야 한다. 이 앱이 수집하는 것:
이메일·비밀번호(계정), 닉네임, 응원 구단·선수, 커뮤니티 채팅 내용.
전송 중 암호화 여부와 삭제 요청 경로를 함께 신고한다.
콘텐츠 등급 설문, 광고 포함 여부, 타겟 고객층도 같은 자리에서 받는다.

### App Store

| 항목 | 규격 |
|---|---|
| 앱 아이콘 | 1024×1024 PNG, 알파 없음 |
| 스크린샷 | iPhone 6.9인치 필수 (아이패드는 `supportsTablet: false`라 불필요) |
| 부제 | 30자 |
| 프로모션 텍스트 | 170자 |
| 키워드 | 100자, 쉼표 구분 |

추가로 **App Privacy(영양 성분표)**를 채우고, 심사용 **테스트 계정**을 제공해야 한다
(로그인 없이는 대부분의 화면을 볼 수 없으므로 필수다).
스크린샷 규격은 Apple이 자주 바꾸므로 제출 직전 App Store Connect에서 확인한다.

## 권장 순서

1. ~~브랜드 아이콘·스플래시 교체~~ (블로커 1 — 끝났다. 단색 실루엣 두 개만 남았고 제출을 막지는 않는다)
2. FE에 회원탈퇴 UI + 개인정보처리방침 링크 (블로커 3·4)
3. `eas init` → `npm run build:preview`로 내부 테스트 APK 배포, 실기기 확인
   (경기 알림은 이 빌드부터 동작한다)
4. Play Console 등록 → 비공개 테스트 시작 (개인 계정이면 여기서 14일 시계가 돈다)
5. iOS 빌드 → TestFlight 확인 → App Store 심사 제출
