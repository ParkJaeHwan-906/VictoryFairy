# web-support 모듈

> web-support 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱 생태계, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
`user`·`quiz` 두 배포 앱이 공유하는 web/security 인프라를 담는 `java-library` 모듈. **자체 실행 앱이 아니라 포트 없음, 컨테이너로 안 뜸.** JWT 발급/검증 부품 + 전역 예외 어드바이스 + 401 인증 엔트리포인트. 배포 앱은 여전히 `user`(8080)·`quiz`(8081) 2개뿐.

## 핵심 클래스 (`web-support/src/main/java/com/skhynix/websupport/`)
- `jwt.JwtTokenProvider` (HS256 생성/검증) — `createAccessToken(String uid)`/`createRefreshToken(String uid)`/`getUid(String): String`/`validateToken`/`isRefreshToken`
- `jwt.JwtAuthenticationFilter` — Bearer 파싱 후 `UserAccountRepository.findActiveIdByUid(uid)`(`:domain`)로 uid→id 해석, principal은 `Long id`. 생성자 `(JwtTokenProvider, UserAccountRepository)`
- `jwt.JwtProperties` — `jwt.secret` 등 설정 바인딩
- `jwt.JwtVerificationConfig` — 검증만 필요한 소비 앱이 `@Import`할 최소 설정(`JwtTokenProvider` 빈만 등록, 발급 로직인 `AuthService`는 안 따라옴)
- `error.GlobalExceptionHandler` (`@RestControllerAdvice`) — `BusinessException`→`ApiResponse.fail`, `MethodArgumentNotValidException`→400 + 필드별 메시지 맵
- `error.RestAuthenticationEntryPoint` — 미인증 401 + `ApiResponse` JSON 직접 직렬화. `ExceptionTranslationFilter` 단계(`DispatcherServlet` 밖)에서 호출돼 `GlobalExceptionHandler`(컨트롤러 단계)가 못 잡는 경로라 별도로 존재

## 엔드포인트
없음 — 라이브러리 모듈. 부트 플러그인 미적용.

## 의존
- 모듈: `api project(':common')`, `implementation project(':domain')`
- 라이브러리: Security, WebMVC, **data-jpa**(implementation), JJWT 0.12.6(api+impl+jackson)
- 소비 방향: `common` ← `domain` ← `web-support` ← `{user, quiz}`. 역방향 없음.

## 주의 / 컨벤션
- **data-jpa를 implementation으로 직접 선언한 이유**: `JwtAuthenticationFilter`가 `UserAccountRepository`(JpaRepository 하위) 타입을 컴파일 시 필요로 하는데, `domain`이 data-jpa를 `implementation`으로만 노출해 전이되지 않기 때문. `domain`의 스타터 노출 방식이 바뀌면 이 선언도 재점검할 것.
- **소비 앱은 필요한 설정을 `@Import`로 직접 배선해야 한다** — 자동 스캔에 기대지 말 것. `user`는 `com.skhynix` 전역 스캔이라 `@Import({JwtVerificationConfig.class, GlobalExceptionHandler.class})`(`UserApplication`)로, `quiz`는 `com.skhynix.quiz`로 좁게 스캔이라 `SecurityConfig`에서 동일하게 `@Import`한다. 둘 다 이 두 빈만 등록하고 `securityFilterChain` 자체(라우트 규칙)는 앱마다 직접 작성 — SecurityConfig는 web-support로 옮기지 않고 각 앱에 그대로 둔 의도적 결정(permitAll 규칙이 앱마다 다름).
- `JwtVerificationConfig`의 Javadoc은 "검증만 필요한 모듈은 이 설정만 import"라고 적혀 있지만, 실제로는 uid→id 해석에 `UserAccountRepository`(`:domain`+JPA)까지 필요하다 — 소비 앱이 이미 `:domain`+JPA를 갖추고 있어야 조립된다.
- `JwtAuthenticationFilter`/`RestAuthenticationEntryPoint` 생성자가 바뀌면 두 앱의 `SecurityConfig`(각각 `new`로 직접 생성)를 함께 고쳐야 한다 — 인터페이스가 아니라 구체 클래스를 직접 조립하는 커플링.
- Jackson 3 (Boot 4.1) 사용 중: `ObjectMapper`/`JsonNode`는 `tools.jackson.databind.*`. `com.fasterxml.jackson.databind.*`를 import하면 컴파일 깨짐 — 클래스패스의 `com.fasterxml` 2.x는 `jjwt-jackson`이 runtimeOnly로 끌고 온 것뿐이라 컴파일에 안 잡혀 더 헷갈림.
- 테스트: `web-support/src/test` 3개 클래스(`JwtTokenProviderTest` 8 · `JwtAuthenticationFilterTest` 5 · `RestAuthenticationEntryPointTest` 2, 총 15개), `user`에서 로직 무변경으로 이동. `RestAuthenticationEntryPointTest`는 스프링 컨텍스트 없이 직렬화만 단위 검증.
