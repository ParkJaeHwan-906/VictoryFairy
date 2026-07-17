# user 모듈

> user 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
JWT 기반 인증/인가. signup·login·refresh·logout. **포트 8080.**

## 핵심 클래스 (`user/src/main/java/com/skhynix/...`)
- `AuthController` → `/api/auth/*`
- `AuthService` — 가입/로그인/재발급/로그아웃 로직
- `SecurityConfig` — stateless + JWT 필터 통합. `securityFilterChain(HttpSecurity, JwtTokenProvider, UserAccountRepository)`
- `JwtTokenProvider` (HS256 생성/검증) — `createAccessToken(String uid)`/`createRefreshToken(String uid)`/`getUid(String): String`. `JwtAuthenticationFilter` (Bearer 파싱, `UserAccountRepository` 주입), `JwtProperties`
- `GlobalExceptionHandler` (`@RestControllerAdvice`)
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
- **`JwtVerificationConfig`("검증만 필요한 모듈은 이 설정만 import" Javadoc)의 전제가 약해짐**: 이제 uid→id 해석에 `UserAccountRepository`(`:domain`+JPA)가 추가로 필요하다. `quiz`가 여기 걸려 있음 — 열린 항목
- **미인증 응답은 401이 아니라 403**(실측): `formLogin`/`httpBasic` 둘 다 disable, 커스텀 `AuthenticationEntryPoint` 없어 Spring Security 기본값(`Http403ForbiddenEntryPoint`) 적용. 이번 변경과 무관한 기존 특성이며 401 vs 403 정책은 미결
- **Refresh 토큰 1개 정책**: 발급 직전 `expireValidTokens()`로 기존 유효 토큰 만료(재사용 방지)
- 비번: 저장 `passwordEncoder.encode()`, 검증 `matches()`
- 설정: `jwt.secret`(32B+), access 3h(10800000ms) / refresh 14d(1209600000ms)
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=create`
- **`SignupRequest.password`에 `@Size`/`@Pattern`을 겹쳐 걸지 말 것**: `@ValidPassword` 단일 애노테이션만 사용. 동시 위반 시 `GlobalExceptionHandler`가 `Map<필드명,메시지>`에 `put`하는 구조라 위반이 2개면 순회 순서 비보장으로 응답 메시지가 비결정적이 된다. `/validate`와 signup 메시지가 항상 같아야 하는 이유도 동일(둘 다 `PasswordPolicy` 공유, 길이 위반 우선)
- `user/src/test`: 5개 클래스 49개 테스트(`AuthControllerPasswordValidateTest` 20 · `AuthControllerSignupTest` 9 · `PasswordPolicyViolationCountTest` 7 · `JwtTokenProviderTest` 8 · `JwtAuthenticationFilterTest` 5), 전부 통과. 컨트롤러 슬라이스: `UserApplication`이 `@EnableJpaRepositories(basePackages="com.skhynix")`를 들고 있어 `@WebMvcTest`만으로는 `entityManagerFactory` 부재로 컨텍스트 로딩 실패 → `@ContextConfiguration(classes = AuthController.class)`로 자동 병합을 끄고 `@Import({SecurityConfig, GlobalExceptionHandler})`로 필요한 빈만 명시하는 패턴 사용. **`SecurityFilterChain` 빈 구성에 `UserAccountRepository`도 필요해 `@MockitoBean`으로 같이 넣어야 함**(빠뜨리면 컨텍스트 로딩 실패)
