# user API 명세

> 코드 기준 자동 작성. 포트 **8080**(`user/src/main/resources/application.yaml`의 `server.port: 8080`). `server.servlet.context-path: /api/member`가 설정돼 있어(같은 파일) 컨트롤러의 `@RequestMapping`은 접두사 없는 자원 경로만 갖고, 실제 외부 경로에는 `/api/member`가 항상 붙는다. base URL 자체(`http://localhost:8080`)는 context-path와 무관.
> 최종 갱신: 2026-08-03 (`GET /api/member/players`에 이름 부분 일치 검색 파라미터 `name` 추가 — `teamId`와 AND 결합, 빈 값·공백은 미지정과 동일)
> 대상 컨트롤러: `user/src/main/java/com/skhynix/user/auth/controller/AuthController.java` (`@RequestMapping("/auth")`), `user/src/main/java/com/skhynix/user/account/controller/UserAccountController.java` (`@RequestMapping("/users")`), `user/src/main/java/com/skhynix/user/team/controller/TeamController.java` (`@RequestMapping("/teams")`), `user/src/main/java/com/skhynix/user/player/controller/PlayerController.java` (`@RequestMapping("/players")`), `user/src/main/java/com/skhynix/user/support/controller/SupportController.java` (`@RequestMapping("/support")`), `user/src/main/java/com/skhynix/user/game/controller/GameController.java` (`@RequestMapping("/games")`) — user 모듈의 컨트롤러 6개.
> 인증: JWT Bearer (`Authorization: Bearer <accessToken>`). `SecurityConfig`에서 `/api/member/auth/**` 전체가 `permitAll()`이라 `AuthController`의 9개 엔드포인트는 인증 불필요. **`/api/member/users/me`(회원탈퇴)는 `anyRequest().authenticated()`에 걸리는 이 모듈의 첫 인증 필요 엔드포인트다** — 과거 이 문서에 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 없다"고 적혀 있었다면 그건 이 엔드포인트가 생기기 전 사실이었다. 미인증 시 **401**(`RestAuthenticationEntryPoint`) — 자세한 내용은 아래 "인증 방식" 절 참고. **`GET /api/member/teams`는 `/api/member/auth/**` 밖에서 처음으로 `permitAll`이 된 경로**이며 GET으로만 좁혀 열려 있다(비-GET은 401 — 아래 해당 절 참고). **`GET /api/member/players`·`GET /api/member/games`도 같은 성격의 참조 데이터라 같은 방식(GET 한정 `permitAll`)으로 열려 있다.**

## 공통 사항

### 응답 포맷 — 주의: 엔드포인트별로 다름
`AuthController`의 signup/login/refresh/logout 4개와 `UserAccountController`의 회원탈퇴(`DELETE /api/member/users/me`)는 `ApiResponse<T>`(`:common`)를 쓰지 않고 **`ResponseEntity<T>`를 직접 반환**한다. 즉 이 5개의 **성공 응답 본문은 `ApiResponse`로 감싸이지 않는다**(탈퇴는 아예 본문이 없다).

**단, `POST /api/member/auth/password/validate`·`POST /api/member/auth/nickname/validate`·`POST /api/member/auth/nickname/duplicate`·`POST /api/member/auth/email/send-code`·`POST /api/member/auth/email/verify` 5개는 예외**로 성공 응답도 `ApiResponse<T>`로 감싼다(`ResponseEntity<ApiResponse<T>>` 직접 반환). `password/validate`는 `ApiResponse<PasswordValidationResponse>`, `nickname/validate`·`nickname/duplicate`는 `ApiResponse<NicknameValidationResponse>`를 각각 `ApiResponse.ok(result)`로 반환하고, `email/send-code`·`email/verify`는 본문이 없는 `ApiResponse<Void>`(`ApiResponse.<Void>ok(null)` → `{"success":true,"data":null,"message":null}`)를 200으로 반환한다. 컨트롤러 안에서도 응답 포맷이 갈리므로 엔드포인트마다 확인할 것.

반면 **에러 응답은 `GlobalExceptionHandler`(`user/src/main/java/com/skhynix/user/global/error/GlobalExceptionHandler.java`)가 `ApiResponse`로 감싸서 반환**한다. 즉 이 모듈은 "성공은 (validate·email/* 제외) raw, 실패는 ApiResponse"인 비대칭 구조다.

- 비즈니스 예외(`BusinessException`) → `ApiResponse<Void>` = `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`. **`POST /api/member/auth/email/send-code`의 쿨다운 실패(`EMAIL_SEND_COOLDOWN`)가 이 모듈 문서상 첫 `429 Too Many Requests` 응답이다** — 그 전까지는 400/401/409/204만 존재했다.
- Bean Validation 실패(`MethodArgumentNotValidException`) → `ApiResponse<Map<String,String>>` = `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 상태코드 `400 Bad Request`. `data`에는 실패한 필드별 검증 메시지가 담긴다(모든 필드가 아니라 **위반한 필드만**).

### 인증 방식
JWT HS256. `JwtTokenProvider`가 access(3h, 10800000ms)/refresh(14d, 1209600000ms) 토큰을 발급하며 claim `type: access|refresh`로 구분한다. `AuthController`의 9개 엔드포인트(signup/login/refresh/logout/password validate/nickname validate/nickname duplicate/email send-code/email verify)는 `SecurityConfig`에서 permitAll이라 Authorization 헤더가 필요 없다(로그인/재발급/가입 전 단계이므로 당연함). **`DELETE /api/member/users/me`(회원탈퇴)만 `Authorization: Bearer <accessToken>`이 필수**다.

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

`JwtAuthenticationFilter`는 요청마다 `sub`(uid)를 `UserAccountRepository.findActiveIdByUid()`로 **활성(`exit_at IS NULL`) 계정의** 내부 `id`로 변환해 그 `id`를 principal로 사용한다(uid에 해당하는 활성 계정이 없으면 — 존재하지 않거나 **탈퇴한 계정이면** — 인증 없이 그대로 통과). **이 문서의 어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다** — `POST /api/member/auth/signup`도 여전히 `Boolean`만 반환한다(아래 참고). `uid`는 오직 발급된 토큰의 `sub` 안에만 존재하며, 클라이언트가 이를 응답 body나 URL에서 직접 얻을 방법은 현재 없다.

**`/api/member/users/me`(회원탈퇴)가 이 모듈에서 실제로 인증이 걸리는 첫 엔드포인트다.** `SecurityConfig`에 `anyRequest().authenticated()` 규칙이 있고 `/api/member/auth/**`만 permitAll이라, 그 밖의 경로인 `/api/member/users/**`가 이 규칙에 실제로 걸린다. 아래 401 정책은 다른 모듈(`quiz` 등)에도 그대로 재사용된다.

**미인증 요청 → 401**(403 아님). `RestAuthenticationEntryPoint`(`user/src/main/java/com/skhynix/user/global/error/RestAuthenticationEntryPoint.java`)가 `ExceptionTranslationFilter` 단계에서 직접 `ApiResponse` JSON을 직렬화해 401로 응답한다(`SecurityConfig`가 `exceptionHandling().authenticationEntryPoint(...)`로 명시 등록). 실측 원문(토큰 없음/`Bearer garbage`로 요청, user:8080·quiz:8081 양쪽 확인):
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```
`formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`, 403)으로 떨어지는 함정이 있었는데, 이번에 `RestAuthenticationEntryPoint`를 명시 등록해 401로 고정했다.

**401이 2종류이며 상태 코드만으론 구분되지 않는다 — 메시지로만 구분된다:**

| 상황 | 상태 | message | ErrorCode |
|---|---|---|---|
| 토큰 없음/무효/`sub`(uid)에 해당하는 **활성** 계정 없음 — 미가입이거나 **탈퇴한 계정** (필터·엔트리포인트 단계, `ExceptionTranslationFilter`) | 401 | `"인증이 필요합니다."` | `UNAUTHENTICATED` |
| `POST /api/member/auth/login` 자격 오답 또는 **해당 이메일 계정이 탈퇴함**(비밀번호 정답 여부 무관, 미가입 이메일과 응답 완전히 동일) (컨트롤러 단계, `GlobalExceptionHandler`) | 401 | `"이메일 또는 비밀번호가 올바르지 않습니다."` | `INVALID_CREDENTIALS` |
| `POST /api/member/auth/refresh` 서명/만료 무효 또는 access 토큰 오용 | 401 | `"유효하지 않은 리프레시 토큰입니다."` | `INVALID_REFRESH_TOKEN` |
| `POST /api/member/auth/refresh` DB에 없거나 이미 만료된 refresh 토큰, 또는 **탈퇴한 계정의 refresh 토큰**(탈퇴가 유효 토큰을 모두 만료시키므로 보통 이 경로로 먼저 걸리지만, 탈퇴와 로그인이 동시에 일어나 방금 발급된 토큰이 살아남는 경우를 대비해 `AuthService.reissue()`가 계정 상태로 한 번 더 판정) | 401 | `"만료되었거나 이미 무효화된 리프레시 토큰입니다."` | `EXPIRED_REFRESH_TOKEN` |

이 4개 모두 401이지만 발생 경로는 둘로 나뉜다: `UNAUTHENTICATED`는 `RestAuthenticationEntryPoint`가 필터 단계(`DispatcherServlet` 바깥)에서 직접 직렬화하고, 나머지 3개는 컨트롤러가 던진 `BusinessException`을 `GlobalExceptionHandler`가 잡아 변환한다. 클라이언트 입장에서 이 구분이 중요한 이유: `UNAUTHENTICATED`는 "로그인하거나(토큰이 아예 없거나 계정이 사라짐) `/api/member/auth/refresh`로 access 토큰을 새로 받으라"는 신호이고, `INVALID_CREDENTIALS`/`INVALID_REFRESH_TOKEN`/`EXPIRED_REFRESH_TOKEN`은 각각 로그인 폼 재입력, refresh 자체의 재로그인 유도로 이어져야 한다는 뜻이다. 401 자체가 (403과 달리) "인증을 다시 하라"는 신호라는 점은 4개 공통이지만, 정확히 무엇을 다시 해야 하는지는 `message`로만 판별 가능하다.

`AccessDeniedHandler`는 의도적으로 미도입 — `JwtAuthenticationFilter`가 인증된 principal의 권한을 항상 `Collections.emptyList()`로 채워 authority 기반 403이 발생할 경로 자체가 없다. **즉 이 API 전체에서 403은 나오지 않는다.**

**참고: `UNAUTHENTICATED`는 아주 드물게 컨트롤러 단계에서도 발생할 수 있다.** `UserAccountService.withdraw()`는 필터가 이미 활성 계정으로 확인한 `id`를 다시 `findById()`로 조회하는데, 그 사이(같은 요청 처리 중) 계정이 사라졌다면 `BusinessException(UNAUTHENTICATED)`를 던진다. 메시지·상태는 엔트리포인트가 내는 것과 동일하게 `"인증이 필요합니다."` / 401이지만, 응답은 `GlobalExceptionHandler`가 만든다는 점만 다르다(코드 주석상 방어적 분기이며 정상 경로에서는 도달하지 않는다).

---

## POST /api/member/auth/password/validate
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
curl -i -X POST http://localhost:8080/api/member/auth/password/validate \
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

## POST /api/member/auth/nickname/validate
닉네임 사전 검사(정책 → 중복 **2단 파이프라인**). 회원가입 전 프론트가 닉네임 사용 가능 여부를 미리 확인하는 용도.

**인증** 불필요

**`/password/validate`와의 차이**: 비밀번호 사전 검사는 순수 정책 판정만(DB 미조회)이지만, 이 엔드포인트는 **1단계 정책 판정 후 2단계로 DB 중복 조회까지 수행**한다(`AuthController.validateNickname()` → `AuthService.validateNickname()`). 정책을 위반하면 그 시점에 즉시 반환하고 **중복(DB) 검사는 수행하지 않는다** — 우선순위는 길이 → 문자 구성 → 중복.

**계약: 이 엔드포인트는 정책 위반이든 중복이든 항상 HTTP 200을 반환한다.** "위반"·"중복" 모두 검사가 정상적으로 수행된 하나의 결과일 뿐 요청 자체의 오류가 아니므로 400/409가 아니다(`NicknameValidationRequest`에는 `@Valid`/Bean Validation 애노테이션을 의도적으로 붙이지 않는다). **주의: signup(`POST /api/member/auth/signup`)의 닉네임 중복은 이와 달리 409(`DUPLICATE_NICKNAME`)를 반환한다** — 사전검사와 실제 가입은 상태 코드 계약이 다르다(의도된 설계). 프론트는 이 엔드포인트를 4xx/5xx catch 대상이 아니라 `data.valid`/`data.message`로만 판정하면 된다.

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
curl -i -X POST http://localhost:8080/api/member/auth/nickname/validate \
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

## POST /api/member/auth/nickname/duplicate
닉네임 중복 **단독** 검사(정책 미검사, DB 중복만). `AuthController.checkNicknameDuplicate()` → `AuthService.checkNicknameDuplicate()`가 담당. 프론트가 정책 검사를 이미 통과시킨 화면에서 "중복 확인" 버튼처럼 중복만 다시 확인하는 용도.

**인증** 불필요

**`/nickname/validate`와의 차이(핵심)**: `/nickname/validate`는 정책(길이→문자 구성) → 중복 **2단계**를 모두 수행하지만, 이 엔드포인트는 **`AuthService.checkNicknameDuplicate()`가 `isNicknameDuplicated()`(= `existsByNickname`) 딱 하나만** 호출한다 — 정책은 아예 판정하지 않는다. 그 결과 **정책 위반이지만 미점유인 닉네임(예: `"hi!"`)에도 이 엔드포인트는 `valid:true`를 반환할 수 있다.** 이때 `valid:true`("사용 가능한 닉네임입니다.")는 **"DB에 중복이 없다"는 뜻일 뿐 가입 가능을 보장하지 않는다** — 같은 닉네임으로 실제 `/signup`을 호출하면 `@ValidNickname`(정책)에 걸려 400이 날 수 있다. 정책까지 포함해 판정하려면 `POST /api/member/auth/nickname/validate`를 쓸 것.

**계약: 이 엔드포인트는 중복이어도 항상 HTTP 200을 반환한다.** "중복"도 검사가 정상 수행된 결과일 뿐 요청 자체의 오류가 아니므로 409가 아니다(`NicknameValidationRequest`에 `@Valid`/Bean Validation 애노테이션을 붙이지 않는다 — 임의 문자열 허용). **주의: signup(`POST /api/member/auth/signup`)의 닉네임 중복은 이와 달리 여전히 409(`DUPLICATE_NICKNAME`)를 반환한다** — 사전검사와 실제 가입은 상태 코드 계약이 다르다(`/nickname/validate`와 동일한 설계).

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
curl -i -X POST http://localhost:8080/api/member/auth/nickname/duplicate \
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

## POST /api/member/auth/email/send-code
회원가입용 이메일 소유 확인 절차의 1단계. 입력한 이메일로 6자리 인증번호를 발송한다.

**인증** 불필요

**요청** `EmailSendCodeRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| email | String | `@NotBlank` `@Email` `@Size(max=100)` | 인증번호를 받을 이메일 |

**응답 200 OK** `ApiResponse<Void>`
```json
{"success":true,"data":null,"message":null}
```

내부 동작(`EmailVerificationService.sendCode()`):
1. `userRepository.existsByEmail(email)`이 true면 **가입 이력이 있는 이메일**이므로 즉시 409로 거부한다(이미 탈퇴한 계정이 점유한 이메일도 soft delete라 여전히 `existsByEmail` true — signup과 동일한 재가입 불가 정책).
2. 같은 이메일에 대한 쿨다운(60초 TTL) 마커가 살아 있으면 429로 거부한다.
3. 6자리 숫자 코드를 생성하고, 그 이메일의 기존 코드·시도 카운터를 무효화한 뒤 새 코드를 TTL 5분으로 저장하고, 쿨다운 마커를 TTL 60초로 설정한다.
4. `EmailSender`로 메일을 발송한다. **`prod` 프로파일이 아니면 `LogEmailSender`가 로딩돼 실제 메일 없이 로그(`[MOCK-EMAIL] 인증번호 발송 to=... code=...`)로만 남긴다.** 실제 SMTP 발송은 `SmtpEmailSender`(`@Profile("prod")`, `spring.mail.*` 설정과 `app.mail.from` 필요)가 `prod`에서만 담당한다.

같은 이메일로 재요청(재발송)하면 쿨다운이 끝난 뒤 이전 코드·시도 횟수가 무효화되고 새 코드로 교체된다 — 여러 번 발송해도 **가장 최근에 발송한 코드만 유효**하다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `email` 형식 위반(`@NotBlank`/`@Email`/`@Size(max=100)`) |
| 409 | DUPLICATE_EMAIL | 이미 가입(또는 탈퇴 포함 가입 이력)된 이메일 |
| 429 | EMAIL_SEND_COOLDOWN | 같은 이메일로 60초 이내 재요청(**이 모듈 문서상 첫 429 응답**) |

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/auth/email/send-code \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com"}'
```

실패 예시(쿨다운, 429):
```json
{"success":false,"data":null,"message":"인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요."}
```

---

## POST /api/member/auth/email/verify
회원가입용 이메일 소유 확인 절차의 2단계. 발송받은 6자리 인증번호를 이메일과 함께 제출해 대조한다.

**인증** 불필요

**요청** `EmailVerifyRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| email | String | `@NotBlank` `@Email` | `send-code`에 사용한 것과 같은 이메일 |
| code | String | `@NotBlank` `@Pattern(regexp="\\d{6}")` (메시지: "인증번호는 6자리 숫자여야 합니다.") | 6자리 숫자 인증번호 |

**응답 200 OK** `ApiResponse<Void>`
```json
{"success":true,"data":null,"message":null}
```

내부 동작(`EmailVerificationService.verify()`, `MAX_ATTEMPTS = 5`):
1. 저장된 코드가 없으면(발송한 적이 없거나, TTL 5분이 지나 만료됐거나, 이미 검증에 성공/무효화되어 소비된 경우) 즉시 `EXPIRED_VERIFICATION_CODE`(400)를 던진다.
2. 코드는 있지만 그 이메일의 누적 시도 횟수가 이미 5회 이상이면(직전 요청까지 한도에 도달한 상태) 코드를 무효화하고 `VERIFICATION_ATTEMPTS_EXCEEDED`(400)를 던진다 — 정답을 보내도 차단된다.
3. 코드가 요청한 `code`와 다르면 시도 횟수를 1 증가시킨다. 증가 후 값이 5 이상이면 코드를 무효화하고 `VERIFICATION_ATTEMPTS_EXCEEDED`(400)를, 아니면 `INVALID_VERIFICATION_CODE`(400)를 던진다.
4. 코드가 일치하면 코드(및 시도 카운터)를 무효화하고, 그 이메일을 **인증완료 상태**로 TTL 30분 저장한다. 이 인증완료 상태가 `POST /api/member/auth/signup`의 선행 조건이다(아래 signup 절 참고).

시도 횟수 한도에 걸리면(`VERIFICATION_ATTEMPTS_EXCEEDED`) 코드가 무효화되므로, 같은 코드로 다시 시도해도 소용없고 **`send-code`를 다시 호출해 새 코드를 받아야** 한다(단, 60초 쿨다운은 별도로 적용).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `email`/`code` 형식 위반 |
| 400 | EXPIRED_VERIFICATION_CODE | 코드 미발송, 또는 TTL 5분 만료, 또는 이미 소비(검증 성공/시도초과)된 코드 |
| 400 | INVALID_VERIFICATION_CODE | 코드 불일치(아직 5회 미만 시도) |
| 400 | VERIFICATION_ATTEMPTS_EXCEEDED | 검증 실패 누적 5회 도달(정답이어도 차단) |

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/auth/email/verify \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","code":"123456"}'
```

실패 예시(만료/미발송, 400):
```json
{"success":false,"data":null,"message":"만료되었거나 유효하지 않은 인증번호입니다."}
```

---

## POST /api/member/auth/signup
회원가입. `User`(개인정보)와 `UserAccount`(로그인 계정)를 함께 생성한다.

**선행 조건: 이메일 인증 완료.** `request.email`이 `POST /api/member/auth/email/verify`로 검증 성공한 뒤 TTL 30분 이내(인증완료 상태가 살아 있는 동안)여야 가입할 수 있다. `email/send-code`를 호출한 적이 없거나, `verify`에 성공하지 못했거나, 성공했더라도 30분이 지나 인증완료 상태가 만료됐으면 `EMAIL_NOT_VERIFIED`(400)로 거부된다 — 미인증과 만료가 코드상 동일하게 취급된다(`store.isVerified()`가 키 부재를 구분하지 않음).

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

**닉네임 정책** (`com.skhynix.user.auth.policy.NicknamePolicy` — 단일 출처, 위 `POST /api/member/auth/nickname/validate` 절의 정책 단계와 완전히 동일한 규칙·메시지를 공유. **변경 이력: 과거 `@Size(max=100)`만으로 느슨했던 제약이 아래 정책으로 강화됨**)

| 규칙 | 내용 | 위반 메시지 |
|---|---|---|
| 길이 | 1~10자 (포함) | `닉네임은 1~10자여야 합니다.` |
| 문자 구성 | 한글 완성형(가–힣)·호환 자모 낱자(ㄱ–ㅎ, ㅏ–ㅣ)·영문·숫자만 허용. 공백·특수문자·이모지는 전부 거부 | `닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.` |

- 두 규칙을 동시에 위반해도 위반 메시지는 **항상 1개만** 응답한다. **길이 위반이 문자 구성 위반보다 우선**한다.
- `nickname`이 `null`이거나 `""`인 경우도 `NicknamePolicy.findViolation()`이 예외 없이 처리하며, **길이 위반 메시지**로 응답한다(`@NotBlank`를 걸지 않으므로 "공백일 수 없습니다" 류의 메시지는 나오지 않는다).
- `SignupRequest.nickname`에는 `@ValidNickname` 단일 애노테이션만 붙어 있다. `@NotBlank`·`@Size`·`@Pattern`을 겹쳐 걸면 동시 위반 시 `GlobalExceptionHandler`가 `Map`에 `put`하는 순서가 비결정적이라 응답 메시지가 호출마다 달라지는 문제(`password`가 이미 겪은 문제)가 있어 의도적으로 배제했다.
- **이 정책은 signup(Bean Validation, 400)과 `POST /api/member/auth/nickname/validate`(2단 파이프라인 1단계, 200)가 `NicknamePolicy.findViolation()`을 문자 그대로 공유**하므로, 사전 검사가 특정 닉네임에 대해 정책 위반 메시지를 반환하면 같은 닉네임으로 signup을 호출했을 때도 반드시 400 + 동일 메시지가 난다. **단, 중복은 상태 코드가 다르다**(사전 검사 200 vs signup 409) — 아래 "실패" 표와 `POST /api/member/auth/nickname/validate` 절 참고.

**비밀번호 정책** (`com.skhynix.user.auth.policy.PasswordPolicy` — 단일 출처, 위 `POST /api/member/auth/password/validate` 절과 완전히 동일한 규칙·메시지를 공유)

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
| 400 | EMAIL_NOT_VERIFIED | `email`이 이메일 인증완료 상태가 아님(미인증 또는 TTL 30분 만료) |
| 409 | DUPLICATE_EMAIL | `userRepository.existsByEmail()` true |
| 409 | DUPLICATE_TEL | `userRepository.existsByTel()` true |
| 409 | DUPLICATE_NICKNAME | `userAccountRepository.existsByNickname()` true |

**검사 순서는 `AuthService.signup()`에 고정돼 있다: 형식(`@Valid`, 400) → 이메일 인증완료 여부(`EMAIL_NOT_VERIFIED`, 400) → 중복 email → tel → nickname(순서대로 409).** 즉 형식은 통과했지만 이메일 인증도 안 됐고 이메일도 이미 가입돼 있는 경우, 응답은 `DUPLICATE_EMAIL`이 아니라 **`EMAIL_NOT_VERIFIED`가 먼저** 난다(인증완료 상태를 먼저 확인하기 때문). 여러 항목이 동시에 중복이어도 이 순서에서 가장 먼저 걸린 하나만 응답한다.

가입에 성공하면 그 이메일의 인증완료 상태는 **1회용으로 즉시 소비**된다(`emailVerificationService.consumeVerified()`, `AuthService.signup()` 마지막 단계). 가입이 성공한 이메일은 이후 `existsByEmail`이 영구히 true가 되어(soft delete라 탈퇴해도 유지) 어차피 `DUPLICATE_EMAIL`로 재가입이 막히므로, 이 소비는 재가입 방지 자체보다는 "성공한 인증 상태를 즉시 정리"하는 성격에 가깝다. 반대로 **가입이 `DUPLICATE_TEL`/`DUPLICATE_NICKNAME`으로 실패**하면(이메일 인증은 통과했으나 다른 필드에서 막힌 경우) 인증완료 상태는 소비되지 않고 TTL 30분 동안 그대로 남아, tel/nickname만 바꿔 재시도할 때 재인증 없이 통과할 수 있다.

**탈퇴한 계정이 점유한 email/tel/nickname으로는 재가입할 수 없다.** `existsByEmail`/`existsByTel`/`existsByNickname`이 탈퇴 여부를 구분하지 않으므로(탈퇴해도 `users`/`users_account` 행이 삭제되지 않는 soft delete), 탈퇴한 계정의 이메일·전화번호·닉네임 그대로 가입을 시도하면 각각 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` 409로 막힌다. `users.email`/`users.tel`에 DB unique 제약이 걸려 있어 앱 로직만으로 재가입을 열 수 없다(스키마 재설계가 필요한 별개 결정 — `docs/requirements/user/withdraw.md`의 "결정 근거 1" 참고).

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/auth/signup \
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

실패 예시(이메일 미인증/만료, 400 — `email/verify`를 안 거쳤거나 인증완료 후 30분이 지남):
```json
{ "success": false, "data": null, "message": "이메일 인증이 완료되지 않았습니다." }
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

## POST /api/member/auth/login
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
curl -i -X POST http://localhost:8080/api/member/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password123"}'
```

---

## POST /api/member/auth/refresh
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
curl -i -X POST http://localhost:8080/api/member/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"eyJ..."}'
```

---

## POST /api/member/auth/logout
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
curl -i -X POST http://localhost:8080/api/member/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"eyJ..."}'
```

---

## DELETE /api/member/users/me
회원 탈퇴(soft delete). 대상 컨트롤러는 `AuthController`가 아니라 `UserAccountController`(`/api/member/users`)다.

**인증 필요** — `Authorization: Bearer <accessToken>`. **이 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫(유일한) 엔드포인트**다. `/api/member/auth/**`는 전부 permitAll이라 탈퇴를 그쪽에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/member/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()` 규칙에 자연히 포함됨).

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
| 같은 access 토큰으로 `DELETE /api/member/users/me` 재호출 | 401 `UNAUTHENTICATED` |
| 그 계정의 refresh 토큰으로 `POST /api/member/auth/refresh` | 401 `EXPIRED_REFRESH_TOKEN` |
| 그 계정의 이메일 + 정확한 비밀번호로 `POST /api/member/auth/login` | 401 `INVALID_CREDENTIALS` (미가입 이메일과 응답 동일) |
| 그 계정의 email/tel/nickname으로 `POST /api/member/auth/signup` | 409 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` (영구 재가입 불가) |

**예시**
```bash
curl -i -X DELETE http://localhost:8080/api/member/users/me \
  -H 'Authorization: Bearer eyJ...'
```
성공: `204 No Content`, 본문 없음.

미인증 예시:
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## GET /api/member/teams
KBO 구단(팀) 전체 목록 조회. 대상 컨트롤러는 `AuthController`/`UserAccountController`가 아니라 `TeamController`(`@RequestMapping("/teams")`, `com.skhynix.user.team.controller.TeamController`) → `TeamService.getTeams()` → `TeamRepository.findAllByOrderByNameAsc()`.

**인증 불필요 — 이 모듈에서 `/api/member/auth/**` 밖으로 처음 열린 무인증 경로.** 회원가입 화면 등 로그인 이전 화면에서 구단 선택 목록으로 쓰이기 위해 `SecurityConfig`가 이 경로만 `permitAll`로 새로 열었다(`.requestMatchers(HttpMethod.GET, "/teams").permitAll()`). `/api/member/users/me`(회원탈퇴)는 여전히 인증이 필요하며 이번 변경으로 영향받지 않는다.

**단, `permitAll`은 `HttpMethod.GET`으로 좁혀져 있다 — GET만 인증 없이 열려 있고, 그 밖의 모든 메서드는 `anyRequest().authenticated()`에 걸린다.** 즉 `POST /api/member/teams`처럼 GET이 아닌 요청은 **405 Method Not Allowed가 아니라 401**이다(컨트롤러에 도달하지 못하고 인증 단계에서 걸림 — 405를 기대하지 말 것).

**요청**: 없음(경로/쿼리 파라미터 없음). `?page=`/`?size=` 등을 붙여도 서버가 해석하지 않으며 **페이징이 없다** — 항상 전체 구단을 단일 배열로 반환한다.

**응답 200 OK** `ApiResponse<List<TeamResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 구단 배열. 페이지 필드(`content`/`totalElements` 등) 없이 배열 자체가 최상위 데이터 |
| data[].id | Long | 구단 PK |
| data[].name | String | 구단 이름 |
| message | null | 사용되지 않음 |

**`code`/`createdAt`/`updatedAt`는 의도적으로 응답에 없다.** `Team.code`는 py-collector가 upsert 키로 소유하는 소스 자연키라, 클라이언트가 이 값으로 구단을 지칭하기 시작하면 수집기 쪽 코드 체계가 외부(프론트) 계약이 되어버리기 때문에 `TeamResponse`가 엔티티를 그대로 직렬화하지 않고 `id`+`name`만 골라 변환한다.

**정렬: `name` 오름차순, DB(`ORDER BY name ASC`)가 단독 수행하며 애플리케이션에서 재정렬하지 않는다.** 정렬 기준이 한국어 로케일이 아니라 MySQL 콜레이션이라, 영문 구단명이 전부 한글 구단명보다 앞에 온다(대문자 영문과 한글이 유니코드 정렬 가중치상 그렇게 갈린다). 시드(`infra/sql/teams-init.sql`) 10개 구단 적용 시 실제 기대 순서:
```
["KIA", "KT", "LG", "NC", "SSG", "두산", "롯데", "삼성", "키움", "한화"]
```
완전한 한글 가나다순(영문이 사이사이 섞이는 형태)이 아니므로, 프론트에서 이 순서를 "정렬이 깨졌다"로 오인하지 말 것.

**`teams` 테이블에 행이 없으면 200 + 빈 배열**을 반환한다(404·500이 아님):
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있어 비-GET은 `anyRequest().authenticated()`로 떨어짐 — 405 아님) |

Authorization 헤더가 있어도(만료됐거나 서명이 무효한 access 토큰이어도) 이 경로는 `permitAll`이라 검증 자체를 거치지 않고 그대로 200 + 구단 목록을 반환한다.

**예시**
```bash
curl -i -X GET http://localhost:8080/api/member/teams
```
응답(`id`는 `infra/sql/teams-init.sql`의 `INSERT` 순서를 auto-increment가 그대로 따른다고 가정한 예시일 뿐 — PK 채번은 계약이 아니고 정렬 계약은 오직 `name`에만 있다):
```json
{"success":true,"data":[{"id":6,"name":"KIA"},{"id":4,"name":"KT"},{"id":2,"name":"LG"},{"id":8,"name":"NC"},{"id":10,"name":"SSG"},{"id":1,"name":"두산"},{"id":9,"name":"롯데"},{"id":3,"name":"삼성"},{"id":5,"name":"키움"},{"id":7,"name":"한화"}],"message":null}
```

페이징 파라미터를 붙여도 무시됨(항상 전체 10개 반환):
```bash
curl -i -X GET "http://localhost:8080/api/member/teams?page=1&size=5"
```

비-GET 예시(405가 아니라 401):
```bash
curl -i -X POST http://localhost:8080/api/member/teams
```
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## GET /api/member/players
KBO 선수 목록 조회 및 이름 검색. 대상 컨트롤러는 `PlayerController`(`@RequestMapping("/players")`, `com.skhynix.user.player.controller.PlayerController`) → `PlayerService.getPlayers(Long, String)` → `PlayerRepository`의 네 메서드 중 하나.

**인증 불필요.** `GET /api/member/teams`와 같은 성격의 참조 데이터라 `SecurityConfig`가 같은 방식으로 열었다(`.requestMatchers(HttpMethod.GET, "/players").permitAll()`). **`permitAll`은 `HttpMethod.GET`으로 좁혀져 있어** `POST /api/member/players`는 405가 아니라 **401**이다.

**요청**: 쿼리 파라미터 2개(둘 다 선택).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | 아니오 | 구단 PK(`GET /api/member/teams`의 `data[].id`). 주면 해당 구단 소속 선수만, 생략하면 구단으로 거르지 않는다 |
| name | String | 아니오 | 선수 이름 **부분 일치** 검색어(`LIKE '%검색어%'`). 주면 이름에 이 문자열이 포함된 선수만 반환한다. 앞부분 일치가 아니라 **포함**이므로 `?name=도영`으로 `"김도영"`이 걸린다. 생략하면 이름으로 거르지 않는다 |

**두 파라미터를 함께 주면 AND로 결합**된다(`?teamId=6&name=도영` → KIA 소속이면서 이름에 "도영"이 든 선수). 조합에 따라 실제로 나가는 쿼리는 다음 4가지다:

| teamId | name | 리포지토리 메서드 |
|---|---|---|
| 없음 | 없음 | `findAllByOrderByNameAsc()` |
| 있음 | 없음 | `findAllByTeam_IdOrderByNameAsc(teamId)` |
| 없음 | 있음 | `findAllByNameContainingOrderByNameAsc(name)` |
| 있음 | 있음 | `findAllByTeam_IdAndNameContainingOrderByNameAsc(teamId, name)` |

**`name`의 빈 값·공백 처리**: `?name=`(값 없음), `?name=%20%20`(공백만)은 **`name`을 주지 않은 것과 동일하게** 취급한다(`LIKE '%%'`로 헛도는 쿼리를 만들지 않는다). 검색어 앞뒤 공백은 제거한 뒤 매칭하므로 `?name=%20도영%20`은 `?name=도영`과 결과가 같다.

**대소문자를 구분하지 않는다** — MySQL 기본 콜레이션(`_ci`)이 흡수한다. 다만 초성 검색(`ㄱㄷㅇ`), 오타 허용, 관련도 순 정렬은 **지원하지 않는다**(단순 `LIKE` 검색이며 정렬은 아래대로 항상 `name` 오름차순 고정).

`?page=`/`?size=` 등은 서버가 해석하지 않으며 **페이징이 없다** — 항상 단일 배열로 반환한다. 검색 결과가 많아도 마찬가지다.

**응답 200 OK** `ApiResponse<List<PlayerResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 선수 배열 |
| data[].id | Long | 선수 PK |
| data[].name | String | 선수 이름 |
| message | null | 사용되지 않음 |

**`average`/`naverPcode`/`kboPlayerId`/`team`/`createdAt`/`updatedAt`는 의도적으로 응답에 없다.** `naverPcode`(네이버 record API의 pcode)와 `kboPlayerId`(KBO 공식 playerId)는 py-collector가 upsert 키로 소유하는 소스 자연키라 `TeamResponse`가 `Team.code`를 감추는 것과 같은 이유로 제외한다. `team`을 담지 않는 것은 N+1 방지 목적도 겸한다(`Player.team`이 LAZY라 응답 변환에서 초기화되지 않는다).

**정렬: `name` 오름차순, DB(`ORDER BY name ASC`)가 단독 수행하며 애플리케이션에서 재정렬하지 않는다.** `teamId`·`name` 유무와 무관하게 같은 정렬이다(검색 결과도 관련도 순이 아니라 이름 오름차순). 구단 목록과 마찬가지로 한국어 로케일이 아닌 MySQL 콜레이션 기준이다.

**존재하지 않는 `teamId`나 일치하는 선수가 없는 `name`은 404가 아니라 200 + 빈 배열**이다. 구단 존재 여부를 따로 조회하지 않으며, `GET /api/member/teams`가 유효한 id의 출처라고 전제한다. `players` 테이블에 행이 없을 때도 동일하다:
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (래퍼 없음) | `teamId`가 숫자가 아님(예: `?teamId=abc`). 컨트롤러 진입 전 타입 변환 실패라 `GlobalExceptionHandler`가 아니라 Spring 기본 `DefaultHandlerExceptionResolver`가 처리한다 — **이 응답만 `ApiResponse` 래퍼가 아니다** |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음 — 405 아님) |

**`name`에는 400이 없다.** 문자열이라 타입 변환이 실패할 수 없고, 길이·문자 종류 제약도 걸지 않는다. 어떤 값을 줘도 200이며 일치하는 선수가 없으면 빈 배열이다.

Authorization 헤더가 있어도(만료·무효 토큰이어도) 이 경로는 `permitAll`이라 그대로 200을 반환한다.

**알려진 동작(주의)**: 검색어에 든 `%`·`_`는 `LIKE` 와일드카드로 그대로 해석된다(이스케이프하지 않는다) — `?name=%25`는 전체 조회와 같아지고 `?name=_`는 아무 한 글자에나 걸린다. 선수 이름에 이 문자가 들어갈 일이 없어 실사용 영향이 없다고 보고 남겨둔 상태다(파라미터 바인딩이라 SQL 인젝션 경로는 아니다).

**예시**
```bash
# 전체
curl -i -X GET http://localhost:8080/api/member/players
# 구단 필터
curl -i -X GET "http://localhost:8080/api/member/players?teamId=6"
# 이름 검색(부분 일치)
curl -i -X GET "http://localhost:8080/api/member/players?name=%EB%8F%84%EC%98%81"
# 구단 + 이름 (AND)
curl -i -X GET "http://localhost:8080/api/member/players?teamId=6&name=%EB%8F%84%EC%98%81"
```
```json
{"success":true,"data":[{"id":2,"name":"김도영"}],"message":null}
```

---

## GET /api/member/games
날짜별 경기 목록 조회. 대상 컨트롤러는 `GameController`(`@RequestMapping("/games")`, `com.skhynix.user.game.controller.GameController`) → `GameService.getGames(LocalDate)` → `GameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(...)`.

**인증 불필요.** `GET /api/member/teams`·`/players`와 같은 성격의 공개 참조 데이터라 `SecurityConfig`가 같은 방식으로 열었다(`.requestMatchers(HttpMethod.GET, "/games").permitAll()`). **`permitAll`은 `HttpMethod.GET`으로 좁혀져 있어** `POST /api/member/games`는 405가 아니라 **401**이다.

**요청**: 쿼리 파라미터 `date` 1개(**선택**, `@RequestParam(required = false)`).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| date | LocalDate | 아니오 | 조회할 날짜. ISO `yyyy-MM-dd` 고정(`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`). 생략하면 아래 "기본값(오늘)" 참고 |

**`date`를 생략하면 `Asia/Seoul` 기준 오늘 날짜로 조회한다(200).** 컨트롤러는 기본값을 직접 계산하지 않고 `null`을 그대로 `GameService.getGames(LocalDate)`에 넘기며, 서비스가 `date != null ? date : LocalDate.now(clock)`으로 판정한다. 이 `clock`은 `user/global/config/ClockConfig`가 등록하는 `Clock.system(ZoneId.of("Asia/Seoul"))` 빈이다 — **운영 파드가 UTC로 돌기 때문에**(실측: `kubectl exec` 로 파드 `date` 조회 시 `UTC`, `TZ` 환경변수 미설정) 시스템 기본 시간대(`LocalDate.now()`)를 쓰면 KST 자정~오전 9시 사이에 하루 전 날짜로 조회되는 오답이 나온다. 그래서 시간대를 배포 설정(`TZ`)이 아니라 코드(`ClockConfig`)에서 고정한다.

**단, "생략하면 오늘"이지 "형식이 이상해도 오늘"은 아니다.** `date=20260801`(구분자 없음)처럼 형식이 어긋난 값이나 `date=2026-13-01`처럼 존재하지 않는 날짜는 여전히 컨트롤러 진입 전 타입 변환에서 400이 난다(아래 "실패" 참고) — 오타를 오늘로 흡수하면 사용자가 잘못된 날짜를 입력했다는 사실을 알 수 없기 때문에 의도적으로 구분한다.

⚠ **주의: `date` 생략은 편의 기능이지 권장 사용법이 아니다.** 서버가 판정하는 "오늘"은 항상 `Asia/Seoul` 기준이며(`ClockConfig`가 코드로 고정 — 서버 JVM 기본 시간대·컨테이너 `TZ` 설정과 무관), 이 판정은 두 가지 이유로 클라이언트가 기대하는 날짜와 어긋날 수 있다: (a) 클라이언트가 다른 시간대에 있거나 기기 시계가 한국 시간과 다르면 "서버의 오늘"과 "클라이언트가 보고 있는 오늘"이 다를 수 있다. (b) 자정 경계 근처에서는 정확히 같은 화면이라도 요청이 언제 도달했느냐에 따라 응답이 바뀔 수 있다(응답 캐싱이나 화면에 날짜를 함께 표기하는 UI에서 특히 혼란스럽다). **화면에 표시할 날짜를 클라이언트가 이미 알고 있다면(예: 날짜 선택 UI, 캘린더) 그 날짜를 항상 `date`로 명시해 넘길 것** — 생략은 "오늘 경기를 보여주면 되는" 최초 진입 화면 정도로 한정해 쓰는 편이 안전하다.

**응답 200 OK** `ApiResponse<List<GameResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 경기 배열 |
| data[].gameId | String | `Game.naverGameId` — 네이버 스포츠 gameId(예: `"20260708LGSS02026"`). py-collector가 upsert 키로 쓰는 자연키이지만, `Team.code`/`Player.naverPcode`와 달리 이 값은 응답에 그대로 노출된다(더블헤더 구분 등 클라이언트가 식별자로 쓸 필요가 있어 보임 — `TeamResponse`/`PlayerResponse`가 자연키를 감추는 것과 다른 결정이니 주의) |
| data[].stadium | String \| null | 구장 이름(`Game.stadium.name`). **`null` 가능** — `Game.stadium`이 `Game`의 연관 중 유일하게 선택적(`optional = true`, `stadium_id` nullable)이라 구장이 아직 미정인 경기(편성 전·중립구장 미확정 등)는 `null`로 나간다. `homeTeamScore`/`awayTeamScore`가 경기 전 `null`인 것과 같은 취급이며, 표기 방식은 클라이언트가 정한다(`GameResponse.from()`이 `game.getStadium() == null ? null : game.getStadium().getName()`으로 방어) |
| data[].homeTeam | String | 홈 구단 이름(`Game.homeTeam.name`) |
| data[].awayTeam | String | 원정 구단 이름(`Game.awayTeam.name`) |
| data[].homeTeamScore | Integer \| null | 홈 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].awayTeamScore | Integer \| null | 원정 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].gameDate | LocalDateTime | 경기 시각. `LocalDateTime` 직렬화 형태 그대로(예: `"2026-08-01T18:30:00"`) — 별도 포맷 지정 없음 |
| data[].gameState | String | `Game.gameStatus.name`(`game_statuses` 테이블 값). 코드 상수가 아니라 DB 행이라 이론상 임의 문자열일 수 있으나, 현재 py-collector가 채우는 값은 `SCHEDULED`\|`IN_PROGRESS`\|`FINISHED`\|`DRAW`\|`CANCELED` 5종(`GameStatus` 엔티티 Javadoc 참고) |
| message | null | 사용되지 않음 |

**`GameResponse`의 실제 필드 순서는 `gameId`, `stadium`, `homeTeam`, `awayTeam`, `homeTeamScore`, `awayTeamScore`, `gameDate`, `gameState` 8개다** (record 컴포넌트 선언 순서, `user/src/main/java/com/skhynix/user/game/dto/GameResponse.java`).

**`Game.id`(PK)·`createdAt`·`updatedAt`은 의도적으로 응답에 없다.** `GameResponse.from()`이 엔티티를 그대로 직렬화하지 않고 8개 필드만 골라 변환한다.

**정렬: `gameDate` 오름차순, DB(`ORDER BY game_date ASC`)가 단독 수행**하며 애플리케이션에서 재정렬하지 않는다.

**조회 범위: 대상 날짜(`date` 또는 위 기본값 판정을 거친 오늘) 하루를 반개구간 `[대상일 00:00, 대상일+1일 00:00)`으로 변환해 조회한다.** `games.game_date`가 `datetime(6)`이라 날짜 등치 비교로는 매칭되지 않기 때문(서비스 Javadoc 참고). 상한을 포함하지 않으므로 자정 정각 경기가 이틀에 중복 집계되거나 마이크로초 단위 값이 누락되는 경계 문제가 없다.

**해당 날짜(생략 시 오늘)에 경기가 없으면 200 + 빈 배열**을 반환한다(404·500이 아님, `/teams`·`/players`의 빈 결과 계약과 동일):
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (래퍼 없음) | `date` 형식 위반(예: `?date=2026/08/01`, `?date=20260801`) 또는 존재하지 않는 날짜(예: `?date=2026-13-01`). **`date` 자체가 없는 것은 더 이상 오류가 아니다**(200 + 오늘) — 이 400은 오직 "값은 있는데 파싱이 안 됨"에만 해당한다. 컨트롤러 진입 전 타입 변환·바인딩 단계라 `GlobalExceptionHandler`가 아니라 Spring 기본 예외 처리(`DefaultHandlerExceptionResolver`)가 처리한다 — **`GET /api/member/players`의 `teamId` 형식 오류와 같은 사정으로, 이 응답만 `ApiResponse` 래퍼가 아니다** |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음 — 405 아님) |

Authorization 헤더가 있어도(만료·무효 토큰이어도) 이 경로는 `permitAll`이라 검증 자체를 거치지 않고 그대로 200을 반환한다.

**예시**
```bash
curl -i -X GET "http://localhost:8080/api/member/games?date=2026-08-01"
```
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":"잠실","homeTeam":"LG","awayTeam":"삼성","homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
```

구장 미정 경기 예시(`stadium: null`):
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":null,"homeTeam":"LG","awayTeam":"삼성","homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
```

`date` 생략 예시(200, `Asia/Seoul` 기준 오늘 경기):
```bash
curl -i -X GET "http://localhost:8080/api/member/games"
```

형식 오류 예시(400, `ApiResponse` 래퍼 아님 — `date=20260801`처럼 구분자가 없거나 `date=2026-13-01`처럼 존재하지 않는 날짜):
```bash
curl -i -X GET "http://localhost:8080/api/member/games?date=20260801"
```

경기 없는 날짜 예시:
```json
{"success":true,"data":[],"message":null}
```

---

## POST /api/member/support/team
응원 구단 선택·변경. 대상 컨트롤러는 `SupportController`(`@RequestMapping("/support")`) → `SupportService.selectTeam()`.
요구사항: `docs/requirements/user/support-selection.md`(USER-SP-4 ~ 13).

**인증 필수.** 이 경로는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다(`/api/member/users/me`와 같은 방식). `GET /api/member/teams`·`/players`와 달리 **`SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상**이다.

**최초 선택·변경·재선택을 이 엔드포인트 하나가 모두 처리한다.** 클라이언트가 요청 전에 자기 상태를 알 필요가 없다.

**요청** `SupportTeamRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | **예** | 응원할 구단 PK(`GET /api/member/teams`의 `data[].id`) |

대상 계정은 본문이 아니라 access 토큰에서만 정해진다 — 본문에 `userAccountId` 같은 필드를 실어도 무시된다.

**응답 200 OK** `ApiResponse<TeamResponse>`
```json
{"success":true,"data":{"id":6,"name":"KIA"},"message":null}
```

**동작 규칙 — 프론트가 반드시 알아야 할 것**

| 상황 | 결과 |
|---|---|
| 응원 이력 없음 | 새로 응원 시작 |
| 이미 같은 구단을 응원 중 | 아무 변경 없이 200(멱등) |
| 다른 구단을 선택 | 기존 구단은 **행 삭제가 아니라 `oppose` 시각 기록**, 새 구단이 활성화 |
| 과거에 응원했다 바꾼 구단을 재선택 | 새 행을 만들지 않고 기존 행 재활성 |
| **구단이 실제로 바뀜** | **그 계정의 응원 선수가 전원 자동 취소된다** |

⚠ **마지막 줄이 가장 중요하다.** 응원 선수는 응원 구단 소속이어야 하므로, 구단을 바꾸면 서버가 **경고 없이** 기존 응원 선수를 전부 취소한다. 프론트는 구단 변경 전에 "선수 선택도 초기화됩니다"를 고지해야 한다. 같은 구단 재선택은 선수를 건드리지 않는다.

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `teamId` 누락/`null` → `{"success":false,"data":{"teamId":"응원할 구단을 선택해 주세요."},"message":"입력값이 올바르지 않습니다."}` |
| 401 | UNAUTHENTICATED | 토큰 없음·만료·위조·refresh 토큰·탈퇴 계정 |
| 404 | TEAM_NOT_FOUND | `"존재하지 않는 구단입니다."` |

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/support/team \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"teamId":6}'
```

---

## POST /api/member/support/players
응원 선수 **추가**. → `SupportService.addPlayers()`. 요구사항: USER-SP-14 ~ 23.

**인증 필수.** **전체 교체가 아니라 추가다** — 요청에 없는 선수는 취소되지 않는다. 취소는 아래 `PUT /api/member/support/players/oppose`가 담당한다.

**요청** `SupportPlayersRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| playerIds | Long[] | **예**(빈 배열 허용) | 추가할 선수 PK 목록 |

- **빈 배열 `[]` 은 200**(아무 변경 없음). 선수 응원은 필수가 아니다.
- **필드 자체를 빼면 400** — `null`과 `[]`를 구분한다.
- **중복 id 는 400이 아니라 제거 후 처리**(`[3,3,7]` → 정상).
- **선수 수 상한 없음.**

**응답 200 OK** `ApiResponse<List<PlayerResponse>>` — **이번에 추가한 선수만이 아니라 현재 응원 중인 선수 전체**를 `name` 오름차순으로 반환한다(프론트가 재조회할 필요 없음).
```json
{"success":true,"data":[{"id":1,"name":"강백호"},{"id":2,"name":"김도영"}],"message":null}
```

**멱등성**: 이미 응원 중인 선수를 다시 보내면 아무 변경이 없다. 과거에 취소했던 선수를 다시 보내면 새 행이 아니라 기존 행이 재활성된다.

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `playerIds` 누락/`null` |
| 400 | SUPPORT_TEAM_REQUIRED | `"응원하는 구단을 먼저 선택해 주세요."` — **응원 구단을 고르기 전에는 선수를 고를 수 없다**(소속 검사의 기준이 없으므로 선수 검증보다 먼저 판정) |
| 400 | PLAYER_NOT_IN_SUPPORT_TEAM | `"응원하는 구단 소속 선수만 선택할 수 있습니다."` |
| 401 | UNAUTHENTICATED | 위와 동일 |
| 404 | PLAYER_NOT_FOUND | `"존재하지 않는 선수입니다."` |

⚠ **부분 반영이 없다.** 목록에 하나라도 실패 대상이 있으면 **같은 요청의 다른 선수도 저장되지 않는다**(단일 트랜잭션).

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/support/players \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1,2]}'
```

---

## PUT /api/member/support/players/oppose
응원 선수 **취소**. → `SupportService.opposePlayers()`. 요구사항: USER-SP-24 ~ 29.

**인증 필수.** **`DELETE`가 아니라 `PUT`인 이유**: 행을 지우는 것이 아니라 `oppose` 컬럼에 취소 시각을 채우는 **상태 전이**이고, 이미 취소된 대상에는 아무 일도 일어나지 않아 두 번 보내도 결과가 같다(멱등). 덕분에 본문에 리스트를 실을 수 있어 추가 API와 대칭이다.

**요청**: 추가 API와 **같은 본문 형태**(`playerIds`).

**응답 200 OK** `ApiResponse<List<PlayerResponse>>` — 취소 후 **남아 있는** 응원 선수 목록. 전원 취소하면 빈 배열이다.
```json
{"success":true,"data":[],"message":null}
```

**멱등성 / 관용**

| 상황 | 결과 |
|---|---|
| 이미 취소된 선수를 다시 취소 | **최초 취소 시각이 보존**된다(덮어쓰지 않음) |
| 실재하지만 **응원한 적 없는** 선수 id | 404가 아니라 **200, 아무 변경 없음** — 목표 상태가 이미 참이다 |
| **존재하지 않는** 선수 id | **404** |

⚠ 위 두 줄이 다르게 취급되는 것은 의도된 구분이다. "응원한 적 없음"은 멱등성, "선수가 없음"은 잘못된 입력이다.

**응원 구단은 이 API로 바뀌지 않는다** — 선수를 전원 취소해도 구단 응원은 그대로 유지된다. 구단은 필수라 취소 API 자체가 없다(변경만 가능).

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `playerIds` 누락/`null` |
| 401 | UNAUTHENTICATED | 위와 동일 |
| 404 | PLAYER_NOT_FOUND | 존재하지 않는 선수 id 포함(다른 취소도 반영되지 않음) |

**예시**
```bash
curl -i -X PUT http://localhost:8080/api/member/support/players/oppose \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1]}'
```

---

## 확인 필요 / 코드 미확인
- (정정, 2026-08-01) 이전 버전 문서에는 `GameResponse.from()`이 `stadium`을 응답에서 제외한다고 적혀 있었으나, `stadium` 필드가 응답에 추가되며 더 이상 사실이 아니다(위 `GET /api/member/games` 절 참고). `Game.id`(PK)·`createdAt`·`updatedAt`만 제외 대상이며, 이 셋의 제외 이유도 코드·Javadoc에 명시적으로 적혀 있지는 않다.
- `GET /api/member/games`의 `gameId`(`naverGameId`)는 `TeamResponse.code`/`PlayerResponse.naverPcode`·`kboPlayerId`와 달리 자연키를 그대로 노출한다 — 의도적 결정인지, 아니면 향후 별도 PK 기반 식별자로 교체될 잠정값인지 코드만으로는 판단 불가. `(확인 필요)`
- `SignupResponse` DTO는 코드상 정의되어 있으나 `AuthController.signup()`에서 실제로 사용되지 않는 죽은 코드로 확인됨(import만 존재).
- `uid`를 응답 body/URL에 노출하는 엔드포인트는 아직 없음(현재는 토큰 `sub` claim 안에만 존재). `DELETE /api/member/users/me`도 `uid`가 아니라 access 토큰으로만 대상을 식별하며 응답에 아무것도 담지 않는다. 향후 `uid`를 응답에 싣는 변경이 생기면 이 문서를 다시 갱신해야 함.
- (과거 기록, 정정됨) 이전 버전 문서에는 미인증 응답이 "401이 아니라 403"이라고 적혀 있었다 — `formLogin`/`httpBasic`을 disable하면 커스텀 엔트리포인트가 없는 한 Spring Security 기본값(`Http403ForbiddenEntryPoint`)으로 떨어지기 때문에 나온 실측이었다. 이후 `RestAuthenticationEntryPoint`가 도입되며 401로 고정됐다(위 "인증 방식" 절 참고). 과거 그 문서 기준 코드를 그대로 쓰고 있는 클라이언트가 있다면 401/403 처리 로직을 다시 확인할 것.
- (과거 기록, 정정됨) 이전 버전 문서에는 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 현재 없다"고 적혀 있었다 — `DELETE /api/member/users/me` 추가로 더 이상 사실이 아니다.
- **탈퇴 취소(복구) API·하드 딜리트·개인정보 파기 배치는 코드에 없다**(`docs/requirements/user/withdraw.md`가 범위 제외로 명시). `exit_at`은 표식만 남기고 행을 삭제하지 않는다.
- `AccessDeniedHandler`가 미도입이라 이 엔드포인트를 포함해 이 API 전체에서 403은 발생하지 않는다(위 "인증 방식" 절과 동일).
- `GET /api/member/teams` 예시 응답의 `id` 값은 `infra/sql/teams-init.sql`의 `INSERT` 나열 순서를 MySQL auto-increment가 그대로 이어받는다고 가정해 역산한 값이며, **실제 DB에서 직접 조회해 확인한 값이 아니다.** `teams` 테이블 DDL이 코드(엔티티/시드 SQL)에 명시돼 있지 않아(Hibernate `ddl-auto`가 생성) 채번 규칙 자체를 코드만으로 100% 보증할 수 없다. `id` 자체는 API 계약이 아니므로(정렬 계약은 `name`에만 있음) 문서화 목적상 문제는 없으나, 정확한 실측이 필요하면 시드 적용된 DB에 `SELECT id, name FROM teams ORDER BY name`을 직접 실행해 대조할 것.
