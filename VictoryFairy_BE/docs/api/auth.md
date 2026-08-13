# 인증(auth) API 명세

> **도메인** `auth` — 회원가입 전 사전 검사, 이메일 소유 확인, 가입, 로그인/토큰 수명 관리.
> **모듈** user (포트 8080) · **경로 접두사** `/api/auth` · **엔드포인트** 9개
> **컨트롤러** `user/src/main/java/com/skhynix/user/auth/controller/AuthController.java` (`@RequestMapping("/auth")`)
> **최종 갱신** 2026-08-04 — `POST /api/auth/signup`에 `users_bq` 행 생성 부수 효과 반영(요청·응답 계약은 변경 없음).
> 공통 규약(응답 래퍼·JWT payload·401 4종·403 부재)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| POST | [/api/auth/password/validate](#post-apiauthpasswordvalidate) | 200 | 비밀번호 정책 사전 검사(DB 미조회) |
| POST | [/api/auth/nickname/validate](#post-apiauthnicknamevalidate) | 200 | 닉네임 정책 + 중복 2단 검사 |
| POST | [/api/auth/nickname/duplicate](#post-apiauthnicknameduplicate) | 200 | 닉네임 중복 단독 검사 |
| POST | [/api/auth/email/send-code](#post-apiauthemailsend-code) | 200 | 이메일 인증번호 발송 |
| POST | [/api/auth/email/verify](#post-apiauthemailverify) | 200 | 이메일 인증번호 대조 |
| POST | [/api/auth/signup](#post-apiauthsignup) | 201 | 회원가입 |
| POST | [/api/auth/login](#post-apiauthlogin) | 200 | 로그인(토큰 쌍 발급) |
| POST | [/api/auth/refresh](#post-apiauthrefresh) | 200 | 토큰 쌍 재발급(rotate) |
| POST | [/api/auth/logout](#post-apiauthlogout) | 204 | refresh 토큰 무효화 |

## 이 도메인의 특이사항

**9개 전부 인증 불필요.** `SecurityConfig`에서 `/api/auth/**` 전체가 `permitAll()`이다(로그인/재발급/가입 전 단계이므로 당연함). 다른 도메인의 GET 한정 `permitAll`과 달리 메서드 제한이 없다.

**응답 래퍼가 이 도메인 안에서 갈린다** — 사전 검사 5개(`password/validate`, `nickname/validate`, `nickname/duplicate`, `email/send-code`, `email/verify`)는 `ApiResponse<T>`로 감싸고, 가입·로그인·재발급·로그아웃 4개는 **raw**로 반환한다. 반면 실패는 9개 모두 `ApiResponse`다.

**사전 검사 5개는 "판정 결과"를 200으로 돌려준다.** 정책 위반·중복도 검사가 정상 수행된 결과일 뿐 요청 자체의 오류가 아니므로 400/409를 내지 않는다. 프론트는 이들을 4xx catch 대상이 아니라 `data.valid`/`data.message`로만 판정한다. **단 실제 가입(`signup`)의 중복은 409다** — 사전 검사와 가입은 의도적으로 상태 코드 계약이 다르다.

**정책 판정 로직은 단일 출처를 공유한다.** `PasswordPolicy`·`NicknamePolicy`가 사전 검사와 `signup`의 Bean Validation 양쪽에서 문자 그대로 재사용되므로, 사전 검사가 반환한 정책 위반 메시지는 같은 값으로 `signup`을 호출했을 때도 동일하게 나온다(중복만 상태 코드가 다름).

**429가 나오는 유일한 곳**이 이 도메인이다(`email/send-code`의 60초 쿨다운, `EMAIL_SEND_COOLDOWN`).

---

## POST /api/auth/password/validate
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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

## POST /api/auth/email/send-code
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
| 429 | EMAIL_SEND_COOLDOWN | 같은 이메일로 60초 이내 재요청(**이 API 전체에서 유일한 429 응답**) |

**예시**
```bash
curl -i -X POST http://localhost:8080/api/auth/email/send-code \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com"}'
```

실패 예시(쿨다운, 429):
```json
{"success":false,"data":null,"message":"인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요."}
```

---

## POST /api/auth/email/verify
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
4. 코드가 일치하면 코드(및 시도 카운터)를 무효화하고, 그 이메일을 **인증완료 상태**로 TTL 30분 저장한다. 이 인증완료 상태가 `POST /api/auth/signup`의 선행 조건이다(아래 signup 절 참고).

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
curl -i -X POST http://localhost:8080/api/auth/email/verify \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","code":"123456"}'
```

실패 예시(만료/미발송, 400):
```json
{"success":false,"data":null,"message":"만료되었거나 유효하지 않은 인증번호입니다."}
```

---

## POST /api/auth/signup
> 최종 변경: 2026-08-04 — 부수 효과 추가: 같은 트랜잭션에서 `users_bq` 행(누적 점수 0)을 함께 생성. **요청·응답 계약·상태 코드·검사 순서는 변경 없음**

회원가입. `User`(개인정보)와 `UserAccount`(로그인 계정)를 함께 생성한다. **같은 트랜잭션에서 `users_bq` 행(`bq_score=0`)도 함께 생성한다**(`AuthService.signup()`, `UserBqRepository.save()`) — 이 행은 [계정(account)](account.md#get-apiusersme)의 `GET /api/users/me`가 `bqScore`로 노출하는 누적 획득 점수의 출처다. 트랜잭션이 어느 단계에서든 실패하면(형식 위반·`EMAIL_NOT_VERIFIED`·중복 409 등) `users_bq` 행도 남지 않는다. 이 부수 효과는 **응답 바디·상태 코드·요청 필드·검사 순서에 아무 영향을 주지 않는다** — 여전히 `Boolean` 201, 아래 요청/실패 표 그대로다.

**선행 조건: 이메일 인증 완료.** `request.email`이 `POST /api/auth/email/verify`로 검증 성공한 뒤 TTL 30분 이내(인증완료 상태가 살아 있는 동안)여야 가입할 수 있다. `email/send-code`를 호출한 적이 없거나, `verify`에 성공하지 못했거나, 성공했더라도 30분이 지나 인증완료 상태가 만료됐으면 `EMAIL_NOT_VERIFIED`(400)로 거부된다 — 미인증과 만료가 코드상 동일하게 취급된다(`store.isVerified()`가 키 부재를 구분하지 않음).

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
참고: `AuthService.signup()`은 생성된 `userAccountId`(Long)를 반환하지만 컨트롤러는 이를 받지 않고(`authService.signup(request);`, 반환값 미저장) 항상 `true`만 응답한다. 이 값을 실어 나르던 미사용 DTO `SignupResponse`(`userAccountId` 필드)는 2026-08-13 삭제됐다(#388, 저장소 전체 참조 0건 확인 후 제거) — 애초에 응답에 쓰인 적이 없어 이 삭제로 엔드포인트 계약은 바뀌지 않는다.

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
| 400 | EMAIL_NOT_VERIFIED | `email`이 이메일 인증완료 상태가 아님(미인증 또는 TTL 30분 만료) |
| 409 | DUPLICATE_EMAIL | `userRepository.existsByEmail()` true |
| 409 | DUPLICATE_TEL | `userRepository.existsByTel()` true |
| 409 | DUPLICATE_NICKNAME | `userAccountRepository.existsByNickname()` true |

**검사 순서는 `AuthService.signup()`에 고정돼 있다: 형식(`@Valid`, 400) → 이메일 인증완료 여부(`EMAIL_NOT_VERIFIED`, 400) → 중복 email → tel → nickname(순서대로 409).** 즉 형식은 통과했지만 이메일 인증도 안 됐고 이메일도 이미 가입돼 있는 경우, 응답은 `DUPLICATE_EMAIL`이 아니라 **`EMAIL_NOT_VERIFIED`가 먼저** 난다(인증완료 상태를 먼저 확인하기 때문). 여러 항목이 동시에 중복이어도 이 순서에서 가장 먼저 걸린 하나만 응답한다.

가입에 성공하면 그 이메일의 인증완료 상태는 **1회용으로 즉시 소비**된다(`emailVerificationService.consumeVerified()`, `AuthService.signup()` 마지막 단계). 가입이 성공한 이메일은 이후 `existsByEmail`이 영구히 true가 되어(soft delete라 탈퇴해도 유지) 어차피 `DUPLICATE_EMAIL`로 재가입이 막히므로, 이 소비는 재가입 방지 자체보다는 "성공한 인증 상태를 즉시 정리"하는 성격에 가깝다. 반대로 **가입이 `DUPLICATE_TEL`/`DUPLICATE_NICKNAME`으로 실패**하면(이메일 인증은 통과했으나 다른 필드에서 막힌 경우) 인증완료 상태는 소비되지 않고 TTL 30분 동안 그대로 남아, tel/nickname만 바꿔 재시도할 때 재인증 없이 통과할 수 있다.

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

## POST /api/auth/login
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `AuthController` 마지막 커밋 기준

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

## 확인 필요 / 코드 미확인

- (과거 기록, 정정됨) 이전 버전 문서는 `SignupResponse` DTO(`userAccountId` 필드)가 "코드상 정의되어 있으나 미사용"이라고 적었다 — 2026-08-13(#388) `chore(be)` 정리로 그 클래스 파일 자체가 삭제됐다(저장소 전체 참조 0건 확인 후 제거). `AuthService.signup()`이 반환하는 `userAccountId`는 여전히 컨트롤러가 받지 않고 버리며, 응답은 여전히 `ResponseEntity<Boolean>`(항상 `true`)이라 엔드포인트 계약 자체는 이 삭제와 무관하게 불변이다.
- 가입 성공 응답이 `Boolean`뿐이라 클라이언트는 방금 만든 계정의 식별자를 얻을 수 없다. 이후 `login`으로 토큰을 받아야 하며, 그 토큰의 `sub`(uid)도 응답 body에는 드러나지 않는다([README.md](README.md)의 인증 방식 절 참고).
- (과거 기록, 정정됨) 이전 버전 문서에는 미인증 응답이 "401이 아니라 403"이라고 적혀 있었다 — `formLogin`/`httpBasic`을 disable하면 커스텀 엔트리포인트가 없는 한 Spring Security 기본값(`Http403ForbiddenEntryPoint`)으로 떨어지기 때문에 나온 실측이었다. 이후 `RestAuthenticationEntryPoint`가 도입되며 401로 고정됐다. 과거 그 문서 기준 코드를 그대로 쓰고 있는 클라이언트가 있다면 401/403 처리 로직을 다시 확인할 것.

## 관련 문서

- [계정(account)](account.md) — 회원탈퇴, 그리고 `GET /api/users/me`(내 프로필 요약 조회 — signup이 만든 `users_bq` 행의 `bq_score`를 `bqScore`로 노출). 탈퇴가 이 도메인의 login/refresh/signup 응답에 미치는 영향도 정리돼 있다.
- 요구사항: `docs/requirements/user/email-verification.md`, `docs/requirements/user/nickname-policy.md`, `docs/requirements/user/withdraw.md`, `docs/requirements/user/me-profile.md`(USER-ME-23~25·30 — signup의 `users_bq` 생성 계약)
