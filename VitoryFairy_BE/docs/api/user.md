# user API 명세

> 코드 기준 자동 작성. 포트 **8080**(`user/src/main/resources/application.yaml`의 `server.port: 8080`), `server.servlet.context-path` 미설정이므로 base URL은 `http://localhost:8080`.
> 최종 갱신: 2026-07-15
> 대상 컨트롤러: `user/src/main/java/com/skhynix/user/auth/controller/AuthController.java` (`@RequestMapping("/api/auth")`) — user 모듈의 유일한 컨트롤러.
> 인증: JWT Bearer (`Authorization: Bearer <accessToken>`). `SecurityConfig`에서 `/api/auth/**` 전체가 `permitAll()`이므로 **본 문서의 4개 엔드포인트는 모두 인증 불필요**. 이 외 경로는 `anyRequest().authenticated()`.

## 공통 사항

### 응답 포맷 — 주의: 엔드포인트별로 다름
`AuthController`는 `ApiResponse<T>`(`:common`)를 쓰지 않고 **`ResponseEntity<T>`를 직접 반환**한다. 즉 signup/login/refresh/logout의 **성공 응답 본문은 `ApiResponse`로 감싸이지 않는다.**

반면 **에러 응답은 `GlobalExceptionHandler`(`user/src/main/java/com/skhynix/user/global/error/GlobalExceptionHandler.java`)가 `ApiResponse`로 감싸서 반환**한다. 즉 이 모듈은 "성공은 raw, 실패는 ApiResponse"인 비대칭 구조다.

- 비즈니스 예외(`BusinessException`) → `ApiResponse<Void>` = `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`.
- Bean Validation 실패(`MethodArgumentNotValidException`) → `ApiResponse<Map<String,String>>` = `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 상태코드 `400 Bad Request`. `data`에는 실패한 필드별 검증 메시지가 담긴다(모든 필드가 아니라 **위반한 필드만**).

### 인증 방식
JWT HS256. `JwtTokenProvider`가 access(3h, 10800000ms)/refresh(14d, 1209600000ms) 토큰을 발급하며 claim `type: access|refresh`로 구분한다. 이 문서의 4개 엔드포인트는 `SecurityConfig`에서 permitAll이라 Authorization 헤더가 필요 없다(로그인/재발급 전 단계이므로 당연함).

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
| nickname | String | `@NotBlank` `@Size(max=100)` | 닉네임 |
| password | String | `@NotBlank` `@Size(min=8, max=64)` (메시지: "비밀번호는 8~64자여야 합니다.") | 비밀번호(평문, 서버에서 BCrypt로 인코딩 후 저장) |

**응답 201 Created** `Boolean` (raw, `ApiResponse` 미사용)
```json
true
```
참고: `AuthService.signup()`은 생성된 `userAccountId`(Long)를 반환하지만 컨트롤러는 이를 쓰지 않고 항상 `true`만 응답한다. `SignupResponse` DTO(`userAccountId` 필드)는 `AuthController`에 import만 되어 있고 실제로 응답에 쓰이지 않는다(미사용 DTO).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | 위 제약 위반 |
| 409 | DUPLICATE_EMAIL | `userRepository.existsByEmail()` true |
| 409 | DUPLICATE_TEL | `userRepository.existsByTel()` true |
| 409 | DUPLICATE_NICKNAME | `userAccountRepository.existsByNickname()` true |

중복 체크 순서는 email → tel → nickname이며, 여러 항목이 동시에 중복이어도 가장 먼저 걸린 하나만 응답한다.

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
    "password": "password123"
  }'
```

실패 예시(닉네임 중복, 409):
```json
{ "success": false, "data": null, "message": "이미 사용 중인 닉네임입니다." }
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
| 401 | INVALID_CREDENTIALS | 이메일에 해당하는 `UserAccount`가 없거나(`findByUser_Email` 실패), 비밀번호가 `passwordEncoder.matches()`로 불일치 |

이메일 미존재와 비밀번호 불일치를 동일한 `INVALID_CREDENTIALS`로 응답해 계정 존재 여부를 노출하지 않는다.

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
| 401 | EXPIRED_REFRESH_TOKEN | DB에 저장된 토큰 레코드가 없음(`findByRefreshToken` 실패 — 이미 사용/무효화됨) 또는 `expiredAt`이 현재 시각 이전 |

재발급 시에도 계정당 유효 refresh 토큰 1개 정책이 적용되어, 재발급 직전 해당 계정의 기존 유효 토큰이 모두 만료 처리된다(전달받은 토큰 자신 포함).

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

## 확인 필요 / 코드 미확인
- `SignupResponse` DTO는 코드상 정의되어 있으나 `AuthController.signup()`에서 실제로 사용되지 않는 죽은 코드로 확인됨(import만 존재).
