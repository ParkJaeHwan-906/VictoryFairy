# user 모듈

> user 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
JWT 기반 인증/인가 + 회원 탈퇴 + 이메일 인증(가입 선행조건). signup·login·refresh·logout·withdraw·email 인증. **포트 8080.**

## 핵심 클래스 (`user/src/main/java/com/skhynix/...`)
- `AuthController` → `/api/auth/*`
- `AuthService` — 가입/로그인/재발급/로그아웃 로직
- `account.controller.UserAccountController` → `/api/users/*` · `account.service.UserAccountService` — 탈퇴(soft delete) 전용. `AuthService`에 얹지 않은 이유: 탈퇴는 토큰을 **만들지 않고 지우기만** 해서 `AuthService` 협력자 5개 중 `PasswordEncoder`/`JwtTokenProvider`/`UserRepository` 3개가 불필요
- `SecurityConfig` — stateless + JWT 필터 통합 + `RestAuthenticationEntryPoint` 등록(401). `securityFilterChain(HttpSecurity, JwtTokenProvider, UserAccountRepository, ObjectMapper)`
- `JwtTokenProvider` (HS256 생성/검증) — `createAccessToken(String uid)`/`createRefreshToken(String uid)`/`getUid(String): String`. `JwtAuthenticationFilter` (Bearer 파싱, `UserAccountRepository` 주입), `JwtProperties`
- `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `global.error.RestAuthenticationEntryPoint` — 미인증 요청 401 + `ApiResponse` JSON. `ExceptionTranslationFilter` 단계(`DispatcherServlet` 밖)에서 호출돼 `@RestControllerAdvice`가 못 잡는 경로라 직접 직렬화한다. `quiz`가 그대로 import해 공유(`JwtAuthenticationFilter`와 동일한 커플링 — 생성자 바뀌면 quiz도 같이 고칠 것)
- `auth.policy.PasswordPolicy` — 비밀번호 정책(길이 8~12, 영문+숫자+특수문자)의 **단일 출처**. `findViolation()`을 signup 검증과 `/password/validate`가 공유
- `auth.policy.ValidPassword`/`PasswordValidator` — `PasswordPolicy.findViolation()`에 위임하는 커스텀 제약
- `auth.policy.NicknamePolicy` — 닉네임 정책(허용 문자 화이트리스트 `[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]`, 길이 1~10, 위반 우선순위 길이→문자)의 **단일 출처**. `PasswordPolicy`와 동일 구조. `findViolation()`을 signup 검증과 `/nickname/validate`가 공유(중복은 여기서 판정하지 않음)
- `auth.policy.ValidNickname`/`NicknameValidator` — `ValidPassword`/`PasswordValidator`를 그대로 미러링해 `NicknamePolicy.findViolation()`에 위임하는 커스텀 제약
- `auth.service.EmailVerificationService` — 이메일 소유 검증 정책 판정(발송 쿨다운/시도 5회 한도/1회용 소비). 실제 저장은 `EmailVerificationStore`(포트)에, 발송은 `EmailSender`(포트)에 위임
- `auth.store.EmailVerificationStore`(포트) / `RedisEmailVerificationStore`(Redis 구현) — `StringRedisTemplate` 기반으로 전 메서드 완성 구현(런타임 검증 통과)
- `auth.email.EmailSender`(포트) — `SmtpEmailSender`(`@Profile("prod")`, `JavaMailSender` 실발송) / `LogEmailSender`(`@Profile("!prod")`, 로그로만 대체 — dev/test 실메일 미발송)
- DTO는 전부 **record**: `SignupRequest`, `LoginRequest`, `TokenRequest/Response`, `PasswordValidationRequest/Response`, `NicknameValidationRequest/Response`, `EmailSendCodeRequest`, `EmailVerifyRequest` (`SignupResponse`는 `AuthController`에 import만 되어 있고 미사용 — signup은 `ResponseEntity<Boolean>` 반환)

## 엔드포인트
```
POST /api/auth/signup            SignupRequest → Boolean (201, 이메일 인증완료 선행조건)
POST /api/auth/login              LoginRequest  → TokenResponse
POST /api/auth/refresh            TokenRequest  → TokenResponse
POST /api/auth/logout             TokenRequest  → 204
POST /api/auth/password/validate  PasswordValidationRequest → ApiResponse<PasswordValidationResponse> (항상 200)
POST /api/auth/nickname/validate  NicknameValidationRequest → ApiResponse<NicknameValidationResponse> (항상 200)
POST /api/auth/nickname/duplicate NicknameValidationRequest → ApiResponse<NicknameValidationResponse> (항상 200, 중복만 검사)
POST /api/auth/email/send-code    EmailSendCodeRequest → 200 (가입된 이메일 409, 쿨다운 60초 내 재요청 429)
POST /api/auth/email/verify       EmailVerifyRequest   → 200 (불일치/만료·무효/시도초과 400)
DELETE /api/users/me              (본문 없음, access 필수) → 204
```
`/api/users/me`는 `/api/auth/**` 밖의 첫 엔드포인트이자 `anyRequest().authenticated()`에 실제로 걸리는 첫 경로다(`SecurityConfig` 수정 불필요 — 기존 규칙에 그대로 걸림). `/api/auth/**`는 전부 `permitAll`이라 탈퇴를 그쪽에 두면 인증이 걸리지 않는다.

## 의존
- 모듈: `:common`, `:domain`
- 라이브러리: Security, JPA, Validation, JJWT, BCrypt, MySQL, dotenv, Redis(`spring-boot-starter-data-redis`, 이메일 인증 상태 저장), Mail(`spring-boot-starter-mail`, prod SMTP 발송)
- 엔티티는 **domain에 위치**: `User`, `UserAccount`, `UserRefreshToken` (user는 Repository만 주입)

## 주의 / 컨벤션
- 토큰 claim `type: access|refresh` → `isRefreshToken()`으로 구분
- **토큰 subject = `UserAccount.uid`(UUID v4). 내부 PK `id`는 claim 어디에도 싣지 않는다** — JWT payload는 서명만 되고 암호화는 안 돼 base64 디코드로 누구나 읽을 수 있으므로, id를 claim에 넣으면 uid로 감추는 의미가 사라진다. 이후에도 지켜야 할 정책.
- `JwtAuthenticationFilter`가 요청마다 `UserAccountRepository.findActiveIdByUid(uid)`(`:domain`, `exit_at is null` 조건 포함)로 uid→id를 해석해 **principal은 여전히 `Long` id**(내부 계약 불변). uid로 못 찾으면(미존재 또는 탈퇴) `SecurityContext`를 비운 채 그대로 체인 통과(예외 없음) → `anyRequest().authenticated()`가 401로 떨어뜨림. 요청당 조회 1회는 id를 숨기는 대가로 의도된 비용이며 캐시는 없음
- **탈퇴 계정 차단 지점은 3곳**: 필터(`findActiveIdByUid`) · `AuthService.login`(`findByUser_EmailAndExitAtIsNull`) · `AuthService.reissue`(`account.isWithdrawn()`). `login`·`refresh`는 `permitAll`이라 필터를 안 타서 각자 판정이 필요하다. `reissue`의 탈퇴 검사는 이중 방어가 아니라 **레이스 방어**다 — 탈퇴가 refresh 토큰을 전부 만료시켜 정상 경로에선 만료 검사에 먼저 걸리지만, READ_COMMITTED에서 login 트랜잭션이 탈퇴 커밋 전 계정을 읽고 `expireValidTokens` 이후에 새 토큰을 INSERT하면 탈퇴 계정에 유효 토큰이 남을 수 있다. `account`는 `issueTokens`가 어차피 초기화해 추가 조회는 0회
- `login`의 탈퇴 판정은 코드가 아니라 쿼리 조건(`findByUser_EmailAndExitAtIsNull`)이 하는 일이다 — 탈퇴 계정이 "못 찾음"으로 흡수돼 bcrypt 검사조차 안 타므로 미가입 이메일과 **응답이 완전히 동일**(전용 "탈퇴했습니다" 메시지를 두면 가입 이력이 노출되므로 의도적)
- `JwtVerificationConfig`("검증만 필요한 모듈은 이 설정만 import" Javadoc)는 실제로는 uid→id 해석에 `UserAccountRepository`(`:domain`+JPA)까지 필요한데 Javadoc이 이를 안 적어 부정확할 뿐, `quiz`는 JWT 필터 도입 시점부터 이미 `:domain`+JPA를 갖추고 있어 새로 걸리는 모듈은 없음
- **미인증 응답은 401**(`RestAuthenticationEntryPoint` + `ApiResponse`, 실측 바디 `{"success":false,"data":null,"message":"인증이 필요합니다."}` = `ErrorCode.UNAUTHENTICATED`). `formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`)으로 떨어지는 함정이 있었음 — `exceptionHandling.authenticationEntryPoint`로 명시 등록해 401로 고정. 다시 밟기 쉬운 함정이니 기억할 것
- **엔트리포인트(필터 단계)와 `GlobalExceptionHandler`(컨트롤러 단계)는 다른 경로**: `POST /api/auth/login` 자격 오답도 401이지만 메시지가 다르다(`"이메일 또는 비밀번호가 올바르지 않습니다."`, `INVALID_CREDENTIALS`). 상태 코드만으로 두 경로가 구분되지 않는다 — 엔트리포인트는 `ExceptionTranslationFilter`에서 호출돼 `@RestControllerAdvice`에 안 잡힘
- `AccessDeniedHandler`는 의도적으로 미도입: 필터가 권한을 `Collections.emptyList()`로 넣어 authority 기반 403 경로가 없음
- Jackson 3 (Boot 4.1) 사용 중: `ObjectMapper`/`JsonNode`는 **`tools.jackson.databind.*`**. `com.fasterxml.jackson.databind.ObjectMapper`를 import하면 컴파일 깨짐 — 클래스패스의 `com.fasterxml` 2.x는 `jjwt-jackson`이 runtimeOnly로 끌고 온 것뿐이라 컴파일에 안 잡혀 더 헷갈림
- **Refresh 토큰 1개 정책**: 발급 직전 `expireValidTokens()`로 기존 유효 토큰 만료(재사용 방지). 탈퇴도 같은 메서드로 `exit_at`과 같은 시각에 유효 토큰을 전부 만료시킨다(`UserAccountService.withdraw`)
- **재가입 불가**(탈퇴한 email·tel·nickname 영구 점유)는 새 정책이 아니라 `existsBy*`가 탈퇴를 구분하지 않는 현행 동작. 스키마 제약 근거는 `docs/requirements/user/withdraw.md`("결정 근거 1") 참고 — 다시 조사하지 말 것
- **signup 검사 순서**: 형식(`@Valid`) → **이메일 인증완료 여부**(`EmailVerificationService.isEmailVerified`, 미인증·만료 모두 `EMAIL_NOT_VERIFIED` 400) → 중복(`existsByEmail`, `DUPLICATE_EMAIL` 409). 가입 성공 시 인증 상태를 `consumeVerified`로 1회용 소비 — 재가입하려면 이메일 재인증 필요
- Redis 키 규약(접두사 `email:verify:`): `code:{email}`(6자리, TTL 5분) · `attempts:{email}`(카운터, code와 동일 TTL 5분) · `cooldown:{email}`(존재 여부만, TTL 60초) · `verified:{email}`(존재 여부만, TTL 30분, signup이 조회). 오답은 5회까지 `INVALID_VERIFICATION_CODE`로 응답(카운터만 증가), 직전 누적 시도가 5 이상인 상태에서 재시도(=6번째)하면 정답 여부와 무관하게 `VERIFICATION_ATTEMPTS_EXCEEDED`로 차단하고 코드 무효화 → 재발송 요구
- 신규 `ErrorCode`(`:common`) 5종: `INVALID_VERIFICATION_CODE`/`EXPIRED_VERIFICATION_CODE`/`VERIFICATION_ATTEMPTS_EXCEEDED`/`EMAIL_NOT_VERIFIED`는 400, `EMAIL_SEND_COOLDOWN`은 **이 모듈 첫 429** 응답
- `send-code`가 이미 가입된 이메일에 409(`DUPLICATE_EMAIL`)를 주는 것은 위 "탈퇴 계정 비노출"류 원칙의 **의도적 예외**다 — 계정 존재를 비노출하기보다 중복가입 사전차단을 우선하기로 한 결정(근거: `docs/requirements/user/email-verification.md`)
- **알려진 한계**: 동시 `DELETE /api/users/me` 2건이 각각 필터를 통과하면 둘 다 `exitAt=null`을 읽어 last-write-wins로 `exit_at`이 밀리초 단위로 밀릴 수 있음(비관적 락/조건부 UPDATE 미도입, 영향 경미 — 계정은 탈퇴되고 토큰도 만료됨). 순차 호출은 실측 PASS
- 비번: 저장 `passwordEncoder.encode()`, 검증 `matches()`
- 설정: `jwt.secret`(32B+), access 3h(10800000ms) / refresh 14d(1209600000ms)
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=create`
- **`SignupRequest.password`/`nickname`에 `@Size`/`@Pattern`(nickname은 `@NotBlank`도)을 겹쳐 걸지 말 것**: 각각 `@ValidPassword`/`@ValidNickname` 단일 애노테이션만 사용. 동시 위반 시 `GlobalExceptionHandler`가 `Map<필드명,메시지>`에 `put`하는 구조라 위반이 2개면 순회 순서 비보장으로 응답 메시지가 비결정적이 된다. `/validate`류와 signup 메시지가 항상 같아야 하는 이유도 동일(각각 `PasswordPolicy`/`NicknamePolicy` 공유, 길이 위반 우선)
- **닉네임 사전 검사는 정책→중복 2단 파이프라인**(비밀번호 사전 검사와 다름): `AuthService.validateNickname()`이 `findNicknamePolicyViolation()`(순수, DB 미조회) → `isNicknameDuplicated()`(`existsByNickname` 위임) 순서로 호출하고, 정책 위반이면 중복 조회를 생략한다. **validate의 중복은 200, signup의 중복은 409**(둘 다 같은 `existsByNickname`을 공유하지만 상태 코드는 의도적으로 다름 — signup만 실제 가입 실패라 409). 탈퇴 닉네임도 `existsByNickname`이 걸러내지 않아 두 경로 모두 점유로 판정(재가입 불가 정책과 일치)
- **`checkNicknameDuplicate()`은 중복만 보는 단독 검사**(`/nickname/duplicate`): 정책(`findNicknamePolicyViolation`) 없이 `isNicknameDuplicated()`만 호출 — `validateNickname()`의 정책→중복 2단과 달리 1단이다. 정책 위반이지만 미점유인 닉네임(예: `"hi!"`)에도 `valid:true`가 나올 수 있음(의도된 동작, "사용 가능"=중복 아님일 뿐 가입 가능 보장 아님). 프론트에서 정책 검사를 이미 통과시킨 뒤 "중복 확인" 버튼 용도로 분리
- `user/src/test`: 19개 클래스 143개 테스트(`AuthControllerPasswordValidateTest` 20 · `AuthControllerSignupTest` 9 · `PasswordPolicyViolationCountTest` 7 · `JwtTokenProviderTest` 8 · `JwtAuthenticationFilterTest` 5 · `RestAuthenticationEntryPointTest` 2 · `AuthControllerAuthenticationEntryPointTest` 4 · `AuthServiceTest` 10 · `UserAccountControllerTest` 5 · `UserAccountServiceTest` 3 · `UserAccountWithdrawTest` 3 · `NicknamePolicyTest` 19 · `NicknamePolicyViolationCountTest` 8 · `AuthServiceValidateNicknameTest` 9 · `NicknamePolicyCrossCheckTest` 9 · `AuthControllerNicknameValidateTest` 7 · `AuthControllerSignupNicknameTest` 8 · `AuthServiceCheckNicknameDuplicateTest` 4 · `AuthControllerNicknameDuplicateTest` 3), 전부 통과. 컨트롤러 슬라이스: `UserApplication`이 `@EnableJpaRepositories(basePackages="com.skhynix")`를 들고 있어 `@WebMvcTest`만으로는 `entityManagerFactory` 부재로 컨텍스트 로딩 실패 → `@ContextConfiguration(classes = AuthController.class)`로 자동 병합을 끄고 `@Import({SecurityConfig, GlobalExceptionHandler})`로 필요한 빈만 명시하는 패턴 사용. **`SecurityFilterChain` 빈 구성에 `UserAccountRepository`도 필요해 `@MockitoBean`으로 같이 넣어야 함**(빠뜨리면 컨텍스트 로딩 실패). `RestAuthenticationEntryPointTest`는 스프링 컨텍스트 없이 단위로 직렬화만 검증. `AuthServiceTest`는 이 모듈 첫 `AuthService` 유닛 테스트
- **로컬 실측 함정**: `GRADLE_USER_HOME`이 한글 경로를 포함하면 Gradle 테스트 워커가 죽는다 — ASCII 전용 임시 홈(`-Dgradle.user.home=<ascii-path>` 또는 `GRADLE_USER_HOME` 재설정)으로 우회해야 로컬에서 테스트가 돈다. 코드 문제 아님, 재조사하지 말 것
