# user 모듈

> user 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
JWT 기반 인증/인가. signup·login·refresh·logout. **포트 8080.**

## 핵심 클래스 (`user/src/main/java/com/skhynix/...`)
- `AuthController` → `/api/auth/*`
- `AuthService` — 가입/로그인/재발급/로그아웃 로직
- `SecurityConfig` — stateless + JWT 필터 통합 + `RestAuthenticationEntryPoint` 등록(401). `securityFilterChain(HttpSecurity, JwtTokenProvider, UserAccountRepository, ObjectMapper)`
- `JwtTokenProvider` (HS256 생성/검증) — `createAccessToken(String uid)`/`createRefreshToken(String uid)`/`getUid(String): String`. `JwtAuthenticationFilter` (Bearer 파싱, `UserAccountRepository` 주입), `JwtProperties`
- `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `global.error.RestAuthenticationEntryPoint` — 미인증 요청 401 + `ApiResponse` JSON. `ExceptionTranslationFilter` 단계(`DispatcherServlet` 밖)에서 호출돼 `@RestControllerAdvice`가 못 잡는 경로라 직접 직렬화한다. `quiz`가 그대로 import해 공유(`JwtAuthenticationFilter`와 동일한 커플링 — 생성자 바뀌면 quiz도 같이 고칠 것)
- `auth.policy.PasswordPolicy` — 비밀번호 정책(길이 8~12, 영문+숫자+특수문자)의 **단일 출처**. `findViolation()`을 signup 검증과 `/password/validate`가 공유
- `auth.policy.ValidPassword`/`PasswordValidator` — `PasswordPolicy.findViolation()`에 위임하는 커스텀 제약
- DTO는 전부 **record**: `SignupRequest`, `LoginRequest`, `TokenRequest/Response`, `PasswordValidationRequest/Response` (`SignupResponse`는 `AuthController`에 import만 되어 있고 미사용 — signup은 `ResponseEntity<Boolean>` 반환)

## 엔드포인트
```
POST /api/auth/signup            SignupRequest → Boolean (201)
POST /api/auth/login              LoginRequest  → TokenResponse
POST /api/auth/refresh            TokenRequest  → TokenResponse
POST /api/auth/logout             TokenRequest  → 204
POST /api/auth/password/validate  PasswordValidationRequest → ApiResponse<PasswordValidationResponse> (항상 200)
```

## 의존
- 모듈: `:common`, `:domain`
- 라이브러리: Security, JPA, Validation, JJWT, BCrypt, MySQL, dotenv
- 엔티티는 **domain에 위치**: `User`, `UserAccount`, `UserRefreshToken` (user는 Repository만 주입)

## 주의 / 컨벤션
- 토큰 claim `type: access|refresh` → `isRefreshToken()`으로 구분
- **토큰 subject = `UserAccount.uid`(UUID v4). 내부 PK `id`는 claim 어디에도 싣지 않는다** — JWT payload는 서명만 되고 암호화는 안 돼 base64 디코드로 누구나 읽을 수 있으므로, id를 claim에 넣으면 uid로 감추는 의미가 사라진다. 이후에도 지켜야 할 정책.
- `JwtAuthenticationFilter`가 요청마다 `UserAccountRepository.findIdByUid(uid)`(`:domain`)로 uid→id를 해석해 **principal은 여전히 `Long` id**(내부 계약 불변). uid로 못 찾으면 `SecurityContext`를 비운 채 그대로 체인 통과(예외 없음). 요청당 조회 1회는 id를 숨기는 대가로 의도된 비용이며 캐시는 없음
- `JwtVerificationConfig`("검증만 필요한 모듈은 이 설정만 import" Javadoc)는 실제로는 uid→id 해석에 `UserAccountRepository`(`:domain`+JPA)까지 필요한데 Javadoc이 이를 안 적어 부정확할 뿐, `quiz`는 JWT 필터 도입 시점부터 이미 `:domain`+JPA를 갖추고 있어 새로 걸리는 모듈은 없음
- **미인증 응답은 401**(`RestAuthenticationEntryPoint` + `ApiResponse`, 실측 바디 `{"success":false,"data":null,"message":"인증이 필요합니다."}` = `ErrorCode.UNAUTHENTICATED`). `formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`)으로 떨어지는 함정이 있었음 — `exceptionHandling.authenticationEntryPoint`로 명시 등록해 401로 고정. 다시 밟기 쉬운 함정이니 기억할 것
- **엔트리포인트(필터 단계)와 `GlobalExceptionHandler`(컨트롤러 단계)는 다른 경로**: `POST /api/auth/login` 자격 오답도 401이지만 메시지가 다르다(`"이메일 또는 비밀번호가 올바르지 않습니다."`, `INVALID_CREDENTIALS`). 상태 코드만으로 두 경로가 구분되지 않는다 — 엔트리포인트는 `ExceptionTranslationFilter`에서 호출돼 `@RestControllerAdvice`에 안 잡힘
- `AccessDeniedHandler`는 의도적으로 미도입: 필터가 권한을 `Collections.emptyList()`로 넣어 authority 기반 403 경로가 없음
- Jackson 3 (Boot 4.1) 사용 중: `ObjectMapper`/`JsonNode`는 **`tools.jackson.databind.*`**. `com.fasterxml.jackson.databind.ObjectMapper`를 import하면 컴파일 깨짐 — 클래스패스의 `com.fasterxml` 2.x는 `jjwt-jackson`이 runtimeOnly로 끌고 온 것뿐이라 컴파일에 안 잡혀 더 헷갈림
- **Refresh 토큰 1개 정책**: 발급 직전 `expireValidTokens()`로 기존 유효 토큰 만료(재사용 방지)
- 비번: 저장 `passwordEncoder.encode()`, 검증 `matches()`
- 설정: `jwt.secret`(32B+), access 3h(10800000ms) / refresh 14d(1209600000ms)
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=create`
- **`SignupRequest.password`에 `@Size`/`@Pattern`을 겹쳐 걸지 말 것**: `@ValidPassword` 단일 애노테이션만 사용. 동시 위반 시 `GlobalExceptionHandler`가 `Map<필드명,메시지>`에 `put`하는 구조라 위반이 2개면 순회 순서 비보장으로 응답 메시지가 비결정적이 된다. `/validate`와 signup 메시지가 항상 같아야 하는 이유도 동일(둘 다 `PasswordPolicy` 공유, 길이 위반 우선)
- `user/src/test`: 7개 클래스 55개 테스트(`AuthControllerPasswordValidateTest` 20 · `AuthControllerSignupTest` 9 · `PasswordPolicyViolationCountTest` 7 · `JwtTokenProviderTest` 8 · `JwtAuthenticationFilterTest` 5 · `RestAuthenticationEntryPointTest` 2 · `AuthControllerAuthenticationEntryPointTest` 4), 전부 통과. 컨트롤러 슬라이스: `UserApplication`이 `@EnableJpaRepositories(basePackages="com.skhynix")`를 들고 있어 `@WebMvcTest`만으로는 `entityManagerFactory` 부재로 컨텍스트 로딩 실패 → `@ContextConfiguration(classes = AuthController.class)`로 자동 병합을 끄고 `@Import({SecurityConfig, GlobalExceptionHandler})`로 필요한 빈만 명시하는 패턴 사용. **`SecurityFilterChain` 빈 구성에 `UserAccountRepository`도 필요해 `@MockitoBean`으로 같이 넣어야 함**(빠뜨리면 컨텍스트 로딩 실패). `RestAuthenticationEntryPointTest`는 스프링 컨텍스트 없이 단위로 직렬화만 검증
