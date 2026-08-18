# 스토어 출시

Google Play · App Store 등록 절차. 빌드와 서명은 EAS Build가 맡는다 —
Windows에서 iOS를 빌드해야 하고, keystore·인증서를 직접 보관하지 않아도 되기 때문이다.

## 먼저 막고 있는 것

코드보다 이쪽이 일정을 지배한다. 순서대로 걷어내야 한다.

### 1. 아이콘·스플래시가 아직 Expo 기본 이미지다

`assets/icon.png`는 파란 A 로고, `assets/splash-icon.png`는 회색 동심원 — 둘 다
Expo 템플릿 자산이다. 이대로 제출하면 두 스토어 모두 브랜딩 부재로 리젝된다.

교체 대상:

| 파일 | 규격 | 쓰이는 곳 |
|---|---|---|
| `assets/icon.png` | 1024×1024 PNG | iOS 앱 아이콘. **알파 채널 없이** |
| `assets/android-icon-foreground.png` | 1024×1024 PNG, 투명 배경 | 안드로이드 적응형 아이콘 전경 |
| `assets/android-icon-monochrome.png` | 1024×1024 PNG, 단색 실루엣 | 안드로이드 테마 아이콘 |
| `assets/splash-icon.png` | 1024×1024 PNG, 투명 배경 | 스플래시 중앙 로고 |

배경색은 `app.json`에 브랜드 컬러(`#F04E23`)로 이미 잡혀 있다.
적응형 아이콘은 바깥 33%가 잘리므로 로고를 가운데 66% 안에 넣어야 한다.

### 2. 웹뷰 래퍼는 최소 기능 심사에 걸린다

이 앱은 `https://victoryfairy.com`을 WebView로 띄우는 것이 전부다. 양쪽 스토어 모두
이걸 명시적으로 규제한다.

- **Apple App Store Review Guideline 4.2 (Minimum Functionality)** — 웹사이트를
  다시 포장한 앱은 거절 대상이다. Apple 쪽 리스크가 훨씬 크고, 실제로 웹뷰 단독
  앱은 초회 제출에서 리젝되는 일이 흔하다.
- **Google Play Spam and Minimum Functionality 정책** — 같은 취지지만 Play 쪽이
  상대적으로 통과가 쉽다.

통과 확률을 올리려면 앱만 할 수 있는 기능을 붙여야 한다. 비용 대비 효과 순:

1. **푸시 알림** (`expo-notifications`) — 경기 시작·퀴즈 오픈 알림. 야구 앱의
   성격상 가장 자연스럽고, 심사에서 "네이티브 기능"으로 인정받기 쉽다.
2. **딥링크** — `scheme: victoryfairy`가 이미 잡혀 있으니 웹 링크를 앱으로 여는
   경로를 연결한다.
3. **네이티브 공유** — 경기 결과·퀴즈 결과를 시스템 공유 시트로.

지금 상태로도 제출은 가능하다. 다만 Play를 먼저 통과시키고 App Store는 위 기능을
하나 이상 붙인 뒤 넣는 편이 리젝 왕복을 줄인다.

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
  - `android.blockedPermissions` — RN 템플릿이 자동으로 넣는 4개
    (`READ/WRITE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `VIBRATE`)를 제거한다.
    쓰지 않는 권한이 매니페스트에 있으면 데이터 안전 섹션에서 해명해야 하고,
    `SYSTEM_ALERT_WINDOW`는 특히 심사에서 눈에 띈다.
    FE에 이미지 업로드가 붙으면 `READ_MEDIA_IMAGES`가 필요해질 수 있다.
- **`package.json`** — `eas-cli`를 devDependency로 고정하고 빌드·제출 스크립트 추가.
- **`.gitignore`** — Play 서비스 계정 키를 제외.

`targetSdk`는 36이다(Expo SDK 57 기본값). Play가 2026년 8월 31일부터 신규 앱·업데이트에
API 36을 요구하는데, 이미 충족한다.

## 빌드와 제출

```powershell
npm install                    # eas-cli 설치
npx eas login
npx eas init                   # Expo 프로젝트 생성 · app.json에 projectId를 심는다
```

`eas init`은 `app.json`에 `extra.eas.projectId`와 `owner`를 추가한다. 커밋한다.

### 안드로이드

```powershell
npm run build:android          # AAB. keystore가 없으면 EAS가 만들어 보관한다
```

첫 빌드에서 keystore 생성 여부를 묻는다. EAS에 맡기면
`npx eas credentials`로 언제든 내려받을 수 있다.

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

1. 브랜드 아이콘·스플래시 교체 (블로커 1)
2. FE에 회원탈퇴 UI + 개인정보처리방침 링크 (블로커 3·4)
3. `eas init` → `npm run build:preview`로 내부 테스트 APK 배포, 실기기 확인
4. Play Console 등록 → 비공개 테스트 시작 (개인 계정이면 여기서 14일 시계가 돈다)
5. 그 2주 동안 푸시 알림을 붙인다 (블로커 2) — Play 프로덕션 승인과 App Store
   제출 준비가 같이 끝난다
6. iOS 빌드 → TestFlight 확인 → App Store 심사 제출
