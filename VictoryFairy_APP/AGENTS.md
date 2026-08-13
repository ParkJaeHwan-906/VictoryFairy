# VictoryFairy_APP

배포된 React 웹(`../VictoryFairy_FE`)을 WebView로 감싼 Expo 앱.

## Expo HAS CHANGED

Read the exact versioned docs at https://docs.expo.dev/versions/v57.0.0/ before writing any code.

## 이 프로젝트에서 지켜야 할 것

**화면은 웹이 그린다.** 앱이 직접 만드는 UI는 웹이 뜨기 전 로딩 표시와 로드 실패 시
재시도 화면뿐이다. 기능을 추가할 곳은 대개 `VictoryFairy_FE`이지 여기가 아니다.
여기에 화면을 만들면 웹과 앱 두 벌을 관리하게 된다.

**색은 `src/theme.ts`를 거친다.** 값은 `../VictoryFairy_FE/src/styles/tokens.css`의
시맨틱 토큰에서 가져온다. 웹과 앱의 경계가 눈에 띄지 않으려면 토큰이 바뀔 때 같이 고쳐야 한다.

**네이티브 디렉터리는 소유하지 않는다.** `android/`는 `expo prebuild`가 만들고 gitignore
대상이다. `android/gradle.properties`나 `AndroidManifest.xml`을 직접 고치면 다음
`prebuild --clean` 때 사라진다. 네이티브 설정이 필요하면 `app.json`의 config plugin
(`expo-build-properties` 등)으로 넣어 재생성돼도 남게 한다.

**환경변수는 `EXPO_PUBLIC_` 접두사 + 정적 참조.** `process.env.EXPO_PUBLIC_WEB_URL`
형태로만 인라인된다. 구조 분해나 대괄호 접근은 번들에 들어가지 않는다. 값이 평문으로
APK에 박히므로 비밀은 넣지 않는다.

## 빌드

**JDK 17을 써야 한다.** Android Studio 번들 JBR(25)로 빌드하면
`configureCMakeRelWithDebInfo` 단계에서 `WARNING: A restricted method in
java.lang.System has been called`로 실패한다. JDK 24부터 JNI 관련 restricted method
호출이 경고를 내는데 AGP가 이 출력을 CMake 오류로 오인한다. 자세한 절차는 `README.md` 참고.

같은 로그에 함께 뜨는 `CXX5304: SDK XML version 4`는 빌드를 막지 않는다.
