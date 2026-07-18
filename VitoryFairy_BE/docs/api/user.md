# user API 명세

> 코드 기준 자동 작성. 포트 **8080**(`user/src/main/resources/application.yaml`의 `server.port: 8080`), `server.servlet.context-path` 미설정이므로 base URL은 `http://localhost:8080`.
> 최종 갱신: 2026-07-18 (nickname/duplicate 추가)
> 대상 컨트롤러: `user/src/main/java/com/skhynix/user/auth/controller/AuthController.java` (`@RequestMapping("/api/auth")`), `user/src/main/java/com/skhynix/user/account/controller/UserAccountController.java` (`@RequestMapping("/api/users")`) — user 모듈의 컨트롤러 2개.
> 인증: JWT Bearer (`Authorization: Bearer <accessToken>`). `SecurityConfig`에서 `/api/auth/**` 전체가 `permitAll()`이라 `AuthController`의 7개 엔드포인트는 인증 불필요. **`/api/users/me`(회원탈퇴)는 `anyRequest().authenticated()`에 걸리는 이 모듈의 첫 인증 필요 엔드포인트다** — 과거 이 문서에 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 없다"고 적혀 있었다면 그건 이 엔드포인트가 생기기 전 사실이었다. 미인증 시 **401**(`RestAuthenticationEntryPoint`) — 자세한 내용은 아래 "인증 방식" 절 참고.

## 공통 사항

### 응답 포맷 — 주의: 엔드포인트별로 다름
`AuthController`의 signup/login/refresh/logout 4개와 `UserAccountController`의 회원탈퇴(`DELETE /api/users/me`)는 `ApiResponse<T>`(`:common`)를 쓰지 않고 **`ResponseEntity<T>`를 직접 반환**한다. 즉 이 5개의 **성공 응답 본문은 `ApiResponse`로 감싸이지 않는다**(탈퇴는 아예 본문이 없다).

**단, `POST /api/auth/password/validate`, `POST /api/auth/nickname/validate`, `POST /api/auth/nickname/duplicate`만 예외**로 성공 응답도 각각 `ApiResponse<PasswordValidationResponse>`/`ApiResponse<NicknameValidationResponse>`/`ApiResponse<NicknameValidationResponse>`로 감싼다(`ResponseEntity<ApiResponse<T>>` 직접 반환, `ApiResponse.ok(result)` 사용). 컨트롤러 안에서도 응답 포맷이 갈리므로 엔드포인트마다 확인할 것.

반면 **에러 응답은 `GlobalExceptionHandler`(`user/src/main/java/com/skhynix/user/global/error/GlobalExceptionHandler.java`)가 `ApiResponse`로 감싸서 반환**한다. 즉 이 모듈은 "성공은 (validate 제외) raw, 실패는 ApiResponse"인 비대칭 구조다.

- 비즈니스 예외(`BusinessException`) → `ApiResponse<Void>` = `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`.
- Bean Validation 실패(`MethodArgumentNotValidException`) → `ApiResponse<Map<String,String>>` = `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 상태코드 `400 Bad Request`. `data`에는 실패한 필드별 검증 메시지가 담긴다(모든 필드가 아니라 **위반한 필드만**).

### 인증 방식
JWT HS256. `JwtTokenProvider`가 access(3h, 10800000ms)/refresh(14d, 1209600000ms) 토큰을 발급하며 claim `type: access|refresh`로 구분한다. `AuthController`의 7개 엔드포인트는 `SecurityConfig`에서 permitAll이라 Authorization 헤더가 필요 없다(로그인/재발급 전 단계이므로 당연함). **`DELETE /api/users/me`(회원탈퇴)만 `Authorization: Bearer <accessToken>`이 필수**다.

**토큰 payload 구조** (`login`/`refresh`가 발급하는 accessToken/refreshToken 공통 — JWT는 서명만 되고 암호화는 안 되므로 base64 디코드만으로 누구나 읽을 수 있음, 실제 발급 payload 예시):
```json
{"jti":"72c6e5fa-0e33-4537-9d50-72ae3bd9a3c8","sub":"36f050ef-321a-413e-8f87-998b2031ec69","type":"access","iat":1784272512,"exp":1784283312}
```

| claim | 의미 |
|---|---|
| `sub` | **`UserAccount.uid`(UUID v4)**. `JwtTokenProvider.createToken()`이 subject로 uid만 싣는다 — 내부 PK `id`는 어떤 claim에도 담기지 않는다(순차 PK 열거 방지가 목적) |
| `type` | `access` \| `refresh`. `isRefreshToken()`이 이 claim으로 판정 |
| `jti` | 토큰(발급 건)마다 랜덤 생성되는 UUID. **계정/사용자 식별자가 아니다** — `sub`와 혼동하지 말 것 |
| `iat` / `exp` | 발급/만료 시각(epoch seconds) |

`JwtAuthenticationFilter`는 요청마다 `sub`(uid)를 `UserAccountRepository.findActiveIdByUid()`로 **활성(`exit_at IS NULL`) 계정의** 내부 `id`로 변환해 그 `id`를 principal로 사용한다(uid에 해당하는 활성 계정이 없으면 — 존재하지 않거나 **탈퇴한 계정이면** — 인증 없이 그대로 통과). **이 문서의 어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다** — `POST /api/auth/signup`도 여전히 `Boolean`만 반환한다(아래 참고). `uid`는 오직 발급된 토큰의 `sub` 안에만 존재하며, 클라이언트가 이를 응답 body나 URL에서 직접 얻을 방법은 현재 없다.

**`/api/users/me`(회원탈퇴)가 이 모듈에서 실제로 인증이 걸리는 첫 엔드포인트다.** `SecurityConfig`에 `anyRequest().authenticated()` 규칙이 있고 `/api/auth/**`만 permitAll이라, 그 밖의 경로인 `/api/users/**`가 이 규칙에 실제로 걸린다. 아래 401 정책은 다른 모듈(`quiz` 등)에도 그대로 재사용된다.

**미인증 요청 → 401**(403 아님). `RestAuthenticationEntryPoint`(`user/src/main/java/com/skhynix/user/global/error/RestAuthenticationEntryPoint.java`)가 `ExceptionTranslationFilter` 단계에서 직접 `ApiResponse` JSON을 직렬화해 401로 응답한다(`SecurityConfig`가 `exceptionHandling().authenticationEntryPoint(...)`로 명시 등록). 실측 원문(토큰 없음/`Bearer garbage`로 요청, user:8080·quiz:8081 양쪽 확인):
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```
`formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`, 403)으로 떨어지는 함정이 있었는데, 이번에 `RestAuthenticationEntryPoint`를 명시 등록해 401로 고정했다.

**401이 2종류이며 상태 코드만으론 구분되지 않는다 — 메시지로만 구분된다:**

| 상황 | 상태 | message | ErrorCode |
|---|---|---|---|
| 토큰 없음/무효/`sub`(uid)에 해당하는 **활성** 계정 없음 — 미가입이거나 **탈퇴한 계정** (필터·엔트리포인트 단계, `ExceptionTranslationFilter`) | 401 | `"인증이 필요합니다."` | `UNAUTHENTICATED` |
| `POST /api/auth/login` 자격 오답 또는 **해당 이메일 계정이 탈퇴함**(비밀번호 정답 여부 무관, 미가입 이메일과 응답 완전히 동일) (컨트롤러 단계, `GlobalExceptionHandler`) | 401 | `"이메일 또는 비밀번호가 올바르지 않습니다."` | `INVALID_CREDENTIALS` |
| `POST /api/auth/refresh` 서명/만료 무효 또는 access 토큰 오용 | 401 | `"유효하지 않은 리프레시 토큰입니다."` | `INVALID_REFRESH_TOKEN` |
| `POST /api/auth/refresh` DB에 없거나 이미 만료된 refresh 토큰, 또는 **탈퇴한 계정의 refresh 토큰**(탈퇴가 유효 토큰을 모두 만료시키므로 보통 이 경로로 먼저 걸리지만, 탈퇴와 로그인이 동시에 일어나 방금 발급된 토큰이 살아남는 경우를 대비해 `AuthService.reissue()`가 계정 상태로 한 번 더 판정) | 401 | `"만료되었거나 이미 무효화된 리프레시 토큰입니다."` | `EXPIRED_REFRESH_TOKEN` |

이 4개 모두 401이지만 발생 경로는 둘로 나뉜다: `UNAUTHENTICATED`는 `RestAuthenticationEntryPoint`가 필터 단계(`DispatcherServlet` 바깥)에서 직접 직렬화하고, 나머지 3개는 컨트롤러가 던진 `BusinessException`을 `GlobalExceptionHandler`가 잡아 변환한다. 클라이언트 입장에서 이 구분이 중요한 이유: `UNAUTHENTICATED`는 "로그인하거나(토큰이 아예 없거나 계정이 사라짐) `/api/auth/refresh`로 access 토큰을 새로 받으라"는 신호이고, `INVALID_CREDENTIALS`/`INVALID_REFRESH_TOKEN`/`EXPIRED_REFRESH_TOKEN`은 각각 로그인 폼 재입력, refresh 자체의 재로그인 유도로 이어져야 한다는 뜻이다. 401 자체가 (403과 달리) "인증을 다시 하라"는 신호라는 점은 4개 공통이지만, 정확히 무엇을 다시 해야 하는지는 `message`로만 판별 가능하다.

`AccessDeniedHandler`는 의도적으로 미도입 — `JwtAuthenticationFilter`가 인증된 principal의 권한을 항상 `Collections.emptyList()`로 채워 authority 기반 403이 발생할 경로 자체가 없다. **즉 이 API 전체에서 403은 나오지 않는다.**

**참고: `UNAUTHENTICATED`는 아주 드물게 컨트롤러 단계에서도 발생할 수 있다.** `UserAccountService.withdraw()`는 필터가 이미 활성 계정으로 확인한 `id`를 다시 `findById()`로 조회하는데, 그 사이(같은 요청 처리 중) 계정이 사라졌다면 `BusinessException(UNAUTHENTICATED)`를 던진다. 메시지·상태는 엔트리포인트가 내는 것과 동일하게 `"인증이 필요합니다."` / 401이지만, 응답은 `GlobalExceptionHandler`가 만든다는 점만 다르다(코드 주석상 방어적 분기이며 정상 경로에서는 도달하지 않는다).

---

## POST /api/auth/password/validate
비밀번호 정책 **사전 검사**. 프론트가 비밀번호 입력창에 문자가 들어올 때마다(타이핑마다) 실시간으로 호출하는 용도로, DB 조회 없이 순수하게 정책만 판정한다.

**인증** 불필요

**왜 GET이 아니라 POST인가**: 비밀번호가 URL·쿼리스트링·서버 접근 로그에 평문으로 남는 것을 막기 위해 요청 본문(body)에 실어 보내는 POST를 쓴다.

**계약: 이 엔드포인트는 정책 위반이어도 항상 HTTP 200을 반환한다.** "정책 위반"은 검사가 정상적으로 수행된 하나의 결과일 뿐 요청 자체의 오류가 아니므로 400이 아니다(`PasswordValidationRequest`에는 `@Valid`/Bean Validation 애노테이션을 의도적으로 붙이지 않는다). 프론트는 이 엔드포인트를 4xx/5xx catch 대상이 아니라 `data.valid`/`data.message`로만 판정하면 된다.

**요청** `PasswordValidationRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| password | String | 없음(검증 애노테이션 미부착) | 검사할 비밀번호(평문). `null`/`""`도 허용 — 길이 위반으로 판정됨 |

**응답 200 OK** `ApiResponse<PasswordValidationResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` (엔드포인트 자체는 항상 정상 처리) |
| data.valid | boolean | 정책 만족 여부 |
| data.message | String | 위반 시 위반 규칙 메시지 1개(길이 위반이 구성 위반보다 우선), 통과 시 `"사용 가능한 비밀번호입니다."` |
| message | null | 최상위 `message`는 사용되지 않음(`ApiResponse.ok()`는 항상 `message: null`) |

**중요한 계약: `/signup`과 메시지가 100% 동일하다.** 두 엔드포인트 모두 `PasswordPolicy.findViolation()` 하나만 판정 로직으로 공유하므로(`PasswordPolicy`가 단일 출처), 이 엔드포인트가 특정 비밀번호에 대해 `valid:false` + 메시지 X를 반환하면 같은 비밀번호로 `/signup`을 호출했을 때도 반드시 400 + 동일 메시지 X가 난다. 프론트는 이 엔드포인트의 판정 결과를 신뢰하고 가입 폼의 최종 검증으로 재사용해도 된다.

**실패**: 없음. 항상 200이다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/password/validate \
  -H 'Content-Type: application/json' \
  -d '{"password":"Passw0rd!"}'
```
응답(정책 만족):
```json
{"success":true,"data":{"valid":true,"message":"사용 가능한 비밀번호입니다."},"message":null}
```
응답(길이 위반 — `"abc"`처럼 길이와 구성을 동시에 위반해도 길이 메시지만 반환):
```json
{"success":true,"data":{"valid":false,"message":"비밀번호는 8~12자여야 합니다."},"message":null}
```
응답(구성 위반 — 길이만 만족, 문자 종류 미충족):
```json
{"success":true,"data":{"valid":false,"message":"비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다."},"message":null}
```

---

## POST /api/auth/nickname/validate
닉네임 사전 검사(정책 → 중복 **2단 파이프라인**). 회원가입 전 프론트가 닉네임 사용 가능 여부를 미리 확인하는 용도.

**인증** 불필요

**`/password/validate`와의 차이**: 비밀번호 사전 검사는 순수 정책 판정만(DB 미조회)이지만, 이 엔드포인트는 **1단계 정책 판정 후 2단계로 DB 중복 조회까지 수행**한다(`AuthController.validateNickname()` → `AuthService.validateNickname()`). 정책을 위반하면 그 시점에 즉시 반환하고 **중복(DB) 검사는 수행하지 않는다** — 우선순위는 길이 → 문자 구성 → 중복.

**계약: 이 엔드포인트는 정책 위반이든 중복이든 항상 HTTP 200을 반환한다.** "위반"·"중복" 모두 검사가 정상적으로 수행된 하나의 결과일 뿐 요청 자체의 오류가 아니므로 400/409가 아니다(`NicknameValidationRequest`에는 `@Valid`/Bean Validation 애노테이션을 의도적으로 붙이지 않는다). **주의: signup(`POST /api/auth/signup`)의 닉네임 중복은 이와 달리 409(`DUPLICATE_NICKNAME`)를 반환한다** — 사전검사와 실제 가입은 상태 코드 계약이 다르다(의도된 설계). 프론트는 이 엔드포인트를 4xx/5xx catch 대상이 아니라 `data.valid`/`data.message`로만 판정하면 된다.

**요청** `NicknameValidationRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| nickname | String | 없음(검증 애노테이션 미부착) | 검사할 닉네임. `null`/`""`도 허용 — 길이 위반으로 판정됨 |

**응답 200 OK** `ApiResponse<NicknameValidationResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` (엔드포인트 자체는 항상 정상 처리) |
| data.valid | boolean | 정책·중복 검사를 모두 통과했는지 여부 |
| data.message | String | 위반/중복 시 메시지 1개(길이 위반 > 문자 구성 위반 > 중복 순으로 우선), 통과 시 `"사용 가능한 닉네임입니다."` |
| message | null | 최상위 `message`는 사용되지 않음(`ApiResponse.ok()`는 항상 `message: null`) |

**판정 규칙** (`com.skhynix.user.auth.policy.NicknamePolicy` — 단일 출처, signup의 `@ValidNickname`과 정책 단계 판정 함수를 그대로 공유)

| 단계 | 규칙 | 위반 메시지 |
|---|---|---|
| 1. 길이 | 1~10자 (포함). `null`/`""`도 길이 위반으로 처리 | `닉네임은 1~10자여야 합니다.` |
| 2. 문자 구성 | 한글 완성형(가–힣)·호환 자모 낱자(ㄱ–ㅎ, ㅏ–ㅣ)·영문·숫자만 허용(정규식 `[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+`, 전체 매치). 공백·특수문자·이모지는 전부 거부 | `닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.` |
| 3. 중복 (DB 조회) | `userAccountRepository.existsByNickname()` true. `exit_at`을 거르지 않으므로 **탈퇴한 계정이 점유한 닉네임도 중복으로 판정**(signup과 동일한 쿼리 재사용) | `이미 사용 중인 닉네임입니다.`(`ErrorCode.DUPLICATE_NICKNAME`의 메시지 문구를 그대로 재사용하되 상태 코드는 200) |

- 세 단계를 동시에 위반할 수 있는 경우(예: 정책도 어기고 DB에도 있는 문자열)에도 메시지는 **항상 1개만** 응답하며, **정책(길이→문자) 위반이 중복보다 우선**한다 — 정책 위반이면 DB 조회 자체를 하지 않는다.
- signup의 `@ValidNickname`(1단계, 정책만)과 이 엔드포인트의 1단계는 `NicknamePolicy.findViolation()`을 문자 그대로 공유하므로, 이 엔드포인트가 특정 닉네임에 대해 정책 위반 메시지를 반환하면 같은 닉네임으로 `/signup`을 호출했을 때도 반드시 400 + 동일한 정책 메시지가 난다(단, 중복은 이 엔드포인트가 200, signup은 409로 상태 코드가 다르다는 점 주의).

**실패**: 없음. 항상 200이다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/nickname/validate \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"길동이"}'
```
응답(통과):
```json
{"success":true,"data":{"valid":true,"message":"사용 가능한 닉네임입니다."},"message":null}
```
응답(길이 위반 — 11자 이상 또는 빈 문자열):
```json
{"success":true,"data":{"valid":false,"message":"닉네임은 1~10자여야 합니다."},"message":null}
```
응답(문자 구성 위반 — 길이는 만족하나 특수문자/공백/이모지 포함):
```json
{"success":true,"data":{"valid":false,"message":"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."},"message":null}
```
응답(중복 — 정책은 통과했으나 이미 사용 중이거나 탈퇴 계정이 점유):
```json
{"success":true,"data":{"valid":false,"message":"이미 사용 중인 닉네임입니다."},"message":null}
```

---

## POST /api/auth/nickname/duplicate
닉네임 중복 **단독** 검사(정책 미검사, DB 중복만). `AuthController.checkNicknameDuplicate()` → `AuthService.checkNicknameDuplicate()`가 담당. 프론트가 정책 검사를 이미 통과시킨 화면에서 "중복 확인" 버튼처럼 중복만 다시 확인하는 용도.

**인증** 불필요

**`/nickname/validate`와의 차이(핵심)**: `/nickname/validate`는 정책(길이→문자 구성) → 중복 **2단계**를 모두 수행하지만, 이 엔드포인트는 **`AuthService.checkNicknameDuplicate()`가 `isNicknameDuplicated()`(= `existsByNickname`) 딱 하나만** 호출한다 — 정책은 아예 판정하지 않는다. 그 결과 **정책 위반이지만 미점유인 닉네임(예: `"hi!"`)에도 이 엔드포인트는 `valid:true`를 반환할 수 있다.** 이때 `valid:true`("사용 가능한 닉네임입니다.")는 **"DB에 중복이 없다"는 뜻일 뿐 가입 가능을 보장하지 않는다** — 같은 닉네임으로 실제 `/signup`을 호출하면 `@ValidNickname`(정책)에 걸려 400이 날 수 있다. 정책까지 포함해 판정하려면 `POST /api/auth/nickname/validate`를 쓸 것.

**계약: 이 엔드포인트는 중복이어도 항상 HTTP 200을 반환한다.** "중복"도 검사가 정상 수행된 결과일 뿐 요청 자체의 오류가 아니므로 409가 아니다(`NicknameValidationRequest`에 `@Valid`/Bean Validation 애노테이션을 붙이지 않는다 — 임의 문자열 허용). **주의: signup(`POST /api/auth/signup`)의 닉네임 중복은 이와 달리 여전히 409(`DUPLICATE_NICKNAME`)를 반환한다** — 사전검사와 실제 가입은 상태 코드 계약이 다르다(`/nickname/validate`와 동일한 설계).

**요청** `NicknameValidationRequest` (`/nickname/validate`와 동일한 DTO를 재사용)

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| nickname | String | 없음(검증 애노테이션 미부착) | 중복 여부만 검사할 닉네임. `null`/`""`도 허용 |

**응답 200 OK** `ApiResponse<NicknameValidationResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` (엔드포인트 자체는 항상 정상 처리) |
| data.valid | boolean | DB 중복 여부만 반영(정책과 무관). 미중복이면 `true`, 중복이면 `false` |
| data.message | String | 중복 시 `"이미 사용 중인 닉네임입니다."`(`ErrorCode.DUPLICATE_NICKNAME`의 메시지 문구를 그대로 재사용하되 상태 코드는 200), 통과 시 `"사용 가능한 닉네임입니다."` |
| message | null | 최상위 `message`는 사용되지 않음(`ApiResponse.ok()`는 항상 `message: null`) |

**판정 규칙**: `userAccountRepository.existsByNickname()` 단 하나(`AuthService.isNicknameDuplicated()` 재사용 — signup·`/nickname/validate`의 3단계와 동일한 쿼리). `exit_at`을 거르지 않으므로 **탈퇴한 계정이 점유한 닉네임도 중복으로 판정**한다(signup·`/nickname/validate`와 동일한 동작).

**실패**: 없음. 항상 200이다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/nickname/duplicate \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"길동이"}'
```
응답(미중복 — 정책은 검사하지 않으므로 `"hi!"`처럼 정책 위반 문자열도 미점유이면 `valid:true`가 나온다):
```json
{"success":true,"data":{"valid":true,"message":"사용 가능한 닉네임입니다."},"message":null}
```
응답(중복 — 이미 사용 중이거나 탈퇴 계정이 점유):
```json
{"success":true,"data":{"valid":false,"message":"이미 사용 중인 닉네임입니다."},"message":null}
```

---

## POST /api/auth/signup
회원가입. `User`(개인정보)와 `UserAccount`(로그인 계정)를 함께 생성한다.

**인증** 불필요

**요청** `SignupRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| name | String | `@NotBlank` `@Size(max=30)` | 이름 |
| tel | String | `@NotBlank` `@Pattern(regexp="\\d{10,11}")` (메시지: "전화번호는 숫자 10~11자리여야 합니다.") | 전화번호(숫자만) |
| email | String | `@NotBlank` `@Email` `@Size(max=100)` | 이메일 |
| gender | Gender (`MALE`\|`FEMALE`) | `@NotNull` | 성별. **DB에는 ORDINAL로 저장**(MALE=0, FEMALE=1) |
| nickname | String | `@ValidNickname` (아래 "닉네임 정책" 참고) | 닉네임 |
| password | String | `@ValidPassword` (아래 "비밀번호 정책" 참고) | 비밀번호(평문, 서버에서 BCrypt로 인코딩 후 저장) |

**응답 201 Created** `Boolean` (raw, `ApiResponse` 미사용)
```json
true
```
참고: `AuthService.signup()`은 생성된 `userAccountId`(Long)를 반환하지만 컨트롤러는 이를 쓰지 않고 항상 `true`만 응답한다. `SignupResponse` DTO(`userAccountId` 필드)는 `AuthController`에 import만 되어 있고 실제로 응답에 쓰이지 않는다(미사용 DTO).

**닉네임 정책** (`com.skhynix.user.auth.policy.NicknamePolicy` — 단일 출처, 위 `POST /api/auth/nickname/validate` 절의 정책 단계와 완전히 동일한 규칙·메시지를 공유. **변경 이력: 과거 `@Size(max=100)`만으로 느슨했던 제약이 아래 정책으로 강화됨**)

| 규칙 | 내용 | 위반 메시지 |
|---|---|---|
| 길이 | 1~10자 (포함) | `닉네임은 1~10자여야 합니다.` |
| 문자 구성 | 한글 완성형(가–힣)·호환 자모 낱자(ㄱ–ㅎ, ㅏ–ㅣ)·영문·숫자만 허용. 공백·특수문자·이모지는 전부 거부 | `닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.` |

- 두 규칙을 동시에 위반해도 위반 메시지는 **항상 1개만** 응답한다. **길이 위반이 문자 구성 위반보다 우선**한다.
- `nickname`이 `null`이거나 `""`인 경우도 `NicknamePolicy.findViolation()`이 예외 없이 처리하며, **길이 위반 메시지**로 응답한다(`@NotBlank`를 걸지 않으므로 "공백일 수 없습니다" 류의 메시지는 나오지 않는다).
- `SignupRequest.nickname`에는 `@ValidNickname` 단일 애노테이션만 붙어 있다. `@NotBlank`·`@Size`·`@Pattern`을 겹쳐 걸면 동시 위반 시 `GlobalExceptionHandler`가 `Map`에 `put`하는 순서가 비결정적이라 응답 메시지가 호출마다 달라지는 문제(`password`가 이미 겪은 문제)가 있어 의도적으로 배제했다.
- **이 정책은 signup(Bean Validation, 400)과 `POST /api/auth/nickname/validate`(2단 파이프라인 1단계, 200)가 `NicknamePolicy.findViolation()`을 문자 그대로 공유**하므로, 사전 검사가 특정 닉네임에 대해 정책 위반 메시지를 반환하면 같은 닉네임으로 signup을 호출했을 때도 반드시 400 + 동일 메시지가 난다. **단, 중복은 상태 코드가 다르다**(사전 검사 200 vs signup 409) — 아래 "실패" 표와 `POST /api/auth/nickname/validate` 절 참고.

**비밀번호 정책** (`com.skhynix.user.auth.policy.PasswordPolicy` — 단일 출처, 위 `POST /api/auth/password/validate` 절과 완전히 동일한 규칙·메시지를 공유)

| 규칙 | 내용 | 위반 메시지 |
|---|---|---|
| 길이 | 8~12자 (포함) | `비밀번호는 8~12자여야 합니다.` |
| 구성 | 영문(대소문자 무관) 1자 이상 + 숫자 1자 이상 + 특수문자(`!@#$%^&*()_+=-[]{};:'",.<>/?\|`~`) 1자 이상 각각 포함. 공백은 특수문자로 인정되지 않음 | `비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다.` |

- 두 규칙을 동시에 위반해도(예: `"abc"`) 위반 메시지는 **항상 1개만** 응답한다. **길이 위반이 구성 위반보다 우선**한다.
- `password`가 `null`이거나 `""`인 경우도 `PasswordPolicy.findViolation()`이 예외 없이 처리하며, **길이 위반 메시지**로 응답한다(`@NotBlank`를 걸지 않으므로 "공백일 수 없습니다" 류의 메시지는 나오지 않는다).
- `SignupRequest.password`에는 `@ValidPassword` 단일 애노테이션만 붙어 있다. `@NotBlank`·`@Size`·`@Pattern`을 겹쳐 걸면 동시 위반 시 `GlobalExceptionHandler`가 `Map`에 `put`하는 순서가 비결정적이라 응답 메시지가 호출마다 달라지는 문제가 있어(과거 이슈) 의도적으로 배제했다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `name`/`tel`/`email`/`gender`/`nickname`/`password` 제약 위반. 응답 형태는 `{"success":false,"data":{"password":"<메시지>"},"message":"입력값이 올바르지 않습니다."}`처럼 위반 필드만 `data`에 담김 |
| 409 | DUPLICATE_EMAIL | `userRepository.existsByEmail()` true |
| 409 | DUPLICATE_TEL | `userRepository.existsByTel()` true |
| 409 | DUPLICATE_NICKNAME | `userAccountRepository.existsByNickname()` true |

중복 체크 순서는 email → tel → nickname이며, 여러 항목이 동시에 중복이어도 가장 먼저 걸린 하나만 응답한다.

**탈퇴한 계정이 점유한 email/tel/nickname으로는 재가입할 수 없다.** `existsByEmail`/`existsByTel`/`existsByNickname`이 탈퇴 여부를 구분하지 않으므로(탈퇴해도 `users`/`users_account` 행이 삭제되지 않는 soft delete), 탈퇴한 계정의 이메일·전화번호·닉네임 그대로 가입을 시도하면 각각 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` 409로 막힌다. `users.email`/`users.tel`에 DB unique 제약이 걸려 있어 앱 로직만으로 재가입을 열 수 없다(스키마 재설계가 필요한 별개 결정 — `docs/requirements/user/withdraw.md`의 "결정 근거 1" 참고).

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "홍길동",
    "tel": "01012345678",
    "email": "user@example.com",
    "gender": "MALE",
    "nickname": "gildong",
    "password": "Passw0rd!"
  }'
```

실패 예시(닉네임 중복, 409):
```json
{ "success": false, "data": null, "message": "이미 사용 중인 닉네임입니다." }
```

실패 예시(비밀번호 정책 위반, 400 — 길이 위반이 구성 위반보다 우선하므로 `"abc"`처럼 둘 다 위반해도 길이 메시지만 응답):
```json
{ "success": false, "data": { "password": "비밀번호는 8~12자여야 합니다." }, "message": "입력값이 올바르지 않습니다." }
```

실패 예시(닉네임 정책 위반, 400 — 11자 이상이거나 특수문자/공백/이모지 포함):
```json
{ "success": false, "data": { "nickname": "닉네임은 1~10자여야 합니다." }, "message": "입력값이 올바르지 않습니다." }
```

---

## POST /api/auth/login
이메일/비밀번호로 로그인하고 access/refresh 토큰 쌍을 발급받는다.

**인증** 불필요

**요청** `LoginRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| email | String | `@NotBlank` `@Email` | 계정 이메일 |
| password | String | `@NotBlank` | 비밀번호(평문) |

**응답 200 OK** `TokenResponse` (raw, `ApiResponse` 미사용)

| 필드 | 타입 | 설명 |
|---|---|---|
| accessToken | String | 유효 3h |
| refreshToken | String | 유효 14d. 발급 직전 해당 계정의 기존 유효 refresh 토큰을 모두 만료시킨다(계정당 유효 refresh 토큰 1개 정책) |

```json
{ "accessToken": "eyJ...", "refreshToken": "eyJ..." }
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | email/password 형식 위반 |
| 401 | INVALID_CREDENTIALS | 이메일에 해당하는 **활성** `UserAccount`가 없거나(`findByUser_EmailAndExitAtIsNull` 실패 — 미가입이거나 **탈퇴한 계정**), 비밀번호가 `passwordEncoder.matches()`로 불일치 |

이메일 미존재와 비밀번호 불일치를 동일한 `INVALID_CREDENTIALS`로 응답해 계정 존재 여부를 노출하지 않는다. **탈퇴한 계정의 이메일로 로그인을 시도하면(비밀번호가 정확해도) 조회 자체가 활성 계정만 대상으로 하므로 비밀번호 검사조차 하지 않고 곧바로 같은 401을 반환한다** — 미가입 이메일로 로그인했을 때와 응답이 완전히 동일해 그 이메일의 가입 이력(탈퇴 여부 포함)을 노출하지 않는다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password123"}'
```

---

## POST /api/auth/refresh
refresh 토큰으로 access/refresh 토큰 쌍을 재발급한다(refresh 토큰도 함께 갱신됨 — rotate).

**인증** 불필요 (Authorization 헤더가 아니라 **요청 본문**에 refresh 토큰을 실어 보낸다)

**요청** `TokenRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| refreshToken | String | `@NotBlank` | 재발급에 사용할 refresh 토큰 |

**응답 200 OK** `TokenResponse` (raw, `ApiResponse` 미사용) — 필드는 로그인 응답과 동일(신규 access/refresh 토큰 쌍).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `refreshToken` 공백 |
| 401 | INVALID_REFRESH_TOKEN | `tokenProvider.validateToken()` 실패(서명/만료 무효) 또는 `isRefreshToken()`이 false(즉 access 토큰을 넣은 경우) |
| 401 | EXPIRED_REFRESH_TOKEN | DB에 저장된 토큰 레코드가 없음(`findByRefreshToken` 실패 — 이미 사용/무효화됨) 또는 `expiredAt`이 현재 시각 이전, 또는 **토큰은 유효하지만 그 계정이 탈퇴함**(`account.isWithdrawn()`) |

재발급 시에도 계정당 유효 refresh 토큰 1개 정책이 적용되어, 재발급 직전 해당 계정의 기존 유효 토큰이 모두 만료 처리된다(전달받은 토큰 자신 포함). **탈퇴는 탈퇴 즉시 해당 계정의 유효 refresh 토큰을 전부 만료시키므로** 보통은 위 "만료된 토큰" 경로로 먼저 걸리지만, 탈퇴와 로그인이 정확히 동시에 일어나 만료 처리 직후 새 토큰이 발급되는 극히 드문 경우를 대비해 `AuthService.reissue()`가 계정의 `isWithdrawn()`도 별도로 확인한다. 두 경우 모두 같은 `EXPIRED_REFRESH_TOKEN`으로 응답해 계정 상태를 노출하지 않는다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"eyJ..."}'
```

---

## POST /api/auth/logout
전달받은 refresh 토큰을 DB에서 삭제해 무효화한다.

**인증** 불필요 (본문에 refresh 토큰 전달)

**요청** `TokenRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| refreshToken | String | `@NotBlank` | 무효화할 refresh 토큰 |

**응답 204 No Content** — 본문 없음.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `refreshToken` 공백 |

`AuthService.logout()`은 `findByRefreshToken().ifPresent(...delete)` 형태로, **DB에 없는 토큰(이미 로그아웃했거나 존재하지 않는 토큰)을 넘겨도 예외 없이 204를 반환**한다(멱등). access 토큰 유효성이나 JWT 서명 검증도 하지 않는다 — 단순 문자열로 조회/삭제만 시도한다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"eyJ..."}'
```

---

## DELETE /api/users/me
회원 탈퇴(soft delete). 대상 컨트롤러는 `AuthController`가 아니라 `UserAccountController`(`/api/users`)다.

**인증 필요** — `Authorization: Bearer <accessToken>`. **이 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫(유일한) 엔드포인트**다. `/api/auth/**`는 전부 permitAll이라 탈퇴를 그쪽에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()` 규칙에 자연히 포함됨).

**왜 경로에 대상 식별자가 없는가**: 탈퇴 대상 계정은 URL이 아니라 access 토큰에서만 정해진다. `JwtAuthenticationFilter`가 토큰 `sub`(uid)를 활성 계정의 내부 `id`로 해석해 `@AuthenticationPrincipal Long userAccountId`로 주입하고, 컨트롤러는 이 `id`만으로 `UserAccountService.withdraw()`를 호출한다. `uid`는 여전히 응답 body나 URL 어디에도 노출되지 않는다.

**요청**: 없음. 본문 없음(비밀번호 재확인 절차 없음).

**응답 204 No Content** (`ResponseEntity<Void>`, raw — `ApiResponse` 미사용) — 본문 없음.

내부 동작(`UserAccountService.withdraw()`, 한 트랜잭션):
1. `UserAccount.withdraw(now)` — `exit_at`에 서버 현재 시각을 기록한다. **탈퇴는 즉시 완료이며 유예 기간·취소가 없다.** 이미 `exit_at`이 설정된 계정(이미 탈퇴한 계정)에 다시 호출해도 엔티티가 아무것도 하지 않고 최초 탈퇴 시각을 그대로 보존한다(멱등이 아니라 "덮어쓰지 않음" — 애초에 이미 탈퇴한 계정은 principal로 들어올 수 없어 재요청 자체가 아래 실패 표의 401로 막힌다).
2. `UserRefreshTokenRepository.expireValidTokens(account, now)` — 해당 계정의 유효한 refresh 토큰을 모두 만료 처리한다.

탈퇴 전에 발급받은 **access 토큰은 폐기되지 않는다**(stateless라 서버가 할 수 없음). 대신 이후의 모든 인증 필요 요청에서 `JwtAuthenticationFilter`가 `findActiveIdByUid()`로 매번 활성 여부를 다시 조회하므로, 탈퇴 순간부터 그 access 토큰은 남은 유효 기간(최대 3h)과 무관하게 즉시 인증되지 않는다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/**이미 탈퇴한 계정의 access 토큰**(필터가 활성 계정을 못 찾아 `SecurityContext`가 비고, `anyRequest().authenticated()`에 걸려 엔트리포인트가 401 응답) |

(그 밖의 실패 없음 — 이 엔드포인트는 요청 본문이 없어 검증 실패가 발생할 수 없고, 서비스 내부의 `findById()` 방어적 분기가 던지는 `UNAUTHENTICATED`도 위와 같은 코드·메시지다.)

탈퇴 후 외부에 드러나는 부수 효과(다른 엔드포인트에서 관찰됨 — 자세한 조건은 각 절 참고):

| 이후 호출 | 결과 |
|---|---|
| 같은 access 토큰으로 `DELETE /api/users/me` 재호출 | 401 `UNAUTHENTICATED` |
| 그 계정의 refresh 토큰으로 `POST /api/auth/refresh` | 401 `EXPIRED_REFRESH_TOKEN` |
| 그 계정의 이메일 + 정확한 비밀번호로 `POST /api/auth/login` | 401 `INVALID_CREDENTIALS` (미가입 이메일과 응답 동일) |
| 그 계정의 email/tel/nickname으로 `POST /api/auth/signup` | 409 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` (영구 재가입 불가) |

**예시**
```bash
curl -i -X DELETE http://localhost:8080/api/users/me \
  -H 'Authorization: Bearer eyJ...'
```
성공: `204 No Content`, 본문 없음.

미인증 예시:
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## 확인 필요 / 코드 미확인
- `SignupResponse` DTO는 코드상 정의되어 있으나 `AuthController.signup()`에서 실제로 사용되지 않는 죽은 코드로 확인됨(import만 존재).
- `uid`를 응답 body/URL에 노출하는 엔드포인트는 아직 없음(현재는 토큰 `sub` claim 안에만 존재). `DELETE /api/users/me`도 `uid`가 아니라 access 토큰으로만 대상을 식별하며 응답에 아무것도 담지 않는다. 향후 `uid`를 응답에 싣는 변경이 생기면 이 문서를 다시 갱신해야 함.
- (과거 기록, 정정됨) 이전 버전 문서에는 미인증 응답이 "401이 아니라 403"이라고 적혀 있었다 — `formLogin`/`httpBasic`을 disable하면 커스텀 엔트리포인트가 없는 한 Spring Security 기본값(`Http403ForbiddenEntryPoint`)으로 떨어지기 때문에 나온 실측이었다. 이후 `RestAuthenticationEntryPoint`가 도입되며 401로 고정됐다(위 "인증 방식" 절 참고). 과거 그 문서 기준 코드를 그대로 쓰고 있는 클라이언트가 있다면 401/403 처리 로직을 다시 확인할 것.
- (과거 기록, 정정됨) 이전 버전 문서에는 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 현재 없다"고 적혀 있었다 — `DELETE /api/users/me` 추가로 더 이상 사실이 아니다.
- **탈퇴 취소(복구) API·하드 딜리트·개인정보 파기 배치는 코드에 없다**(`docs/requirements/user/withdraw.md`가 범위 제외로 명시). `exit_at`은 표식만 남기고 행을 삭제하지 않는다.
- `AccessDeniedHandler`가 미도입이라 이 엔드포인트를 포함해 이 API 전체에서 403은 발생하지 않는다(위 "인증 방식" 절과 동일).
