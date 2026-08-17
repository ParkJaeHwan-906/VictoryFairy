# 계정(account) API 명세

> **도메인** `account` — 로그인 계정 자체의 생명주기(탈퇴) + 내 프로필 요약 조회 + 내 프로필 수정(닉네임·비밀번호).
> **모듈** user (포트 8080) · **경로 접두사** `/api/users` · **엔드포인트** 4개
> **컨트롤러** `user/src/main/java/com/skhynix/user/account/controller/UserAccountController.java` (`@RequestMapping("/users")`)
> **최종 갱신** 2026-08-17 — `PATCH /api/users/me/password` 성공 시 **그 이전에 발급된 access·refresh 토큰이 즉시 무효화됨**(`main` 84f6f4a 머지 완료, PR #425). 직전 "이전 access 토큰은 최대 3h 그대로 유효하다"는 서술을 정정. (직전: 같은 날 `PATCH /api/users/me/nickname`·`PATCH /api/users/me/password`(내 프로필 수정) 신규 추가, 엔드포인트 2개→4개, 브랜치 `hwannee/be/feat-edit-profile`.)
> 공통 규약(응답 래퍼·JWT payload·401 4종·**토큰 무효화**)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| DELETE | [/api/users/me](#delete-apiusersme) | 204 | 회원 탈퇴(soft delete) |
| GET | [/api/users/me](#get-apiusersme) | 200 | 내 요약 프로필 조회(닉네임·응원 구단·응원 선수·포인트·누적 점수) |
| PATCH | [/api/users/me/nickname](#patch-apiusersmenickname) | 204 | 닉네임 변경(형식→중복→쿨다운 판정) |
| PATCH | [/api/users/me/password](#patch-apiusersmepassword) | 200 | 비밀번호 변경(성공 시 refresh 전량 만료+새 토큰 쌍 발급) |

## 이 도메인의 특이사항

**user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**가 여기다. `/api/auth/**`는 전부 permitAll이라 탈퇴를 [auth](auth.md) 도메인에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 규칙에 자연히 포함됨).

**대상 계정은 URL이 아니라 access 토큰에서만 정해진다.** 경로에 식별자가 없고, `@AuthenticationPrincipal Long userAccountId`로 주입된 내부 PK만으로 동작한다.

**탈퇴는 이 도메인 밖으로 파급된다** — [auth](auth.md)의 login/refresh/signup 응답이 모두 바뀐다. 아래 "탈퇴 후 부수 효과" 표 참고.

---

## DELETE /api/users/me
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `UserAccountController` 마지막 커밋 기준

회원 탈퇴(soft delete). `UserAccountController` → `UserAccountService.withdraw()`.

**인증 필요** — `Authorization: Bearer <accessToken>`. **user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**다([응원(support)](support.md)의 3개가 뒤이어 같은 규칙에 걸린다). [`/api/auth/**`](auth.md)는 전부 permitAll이라 탈퇴를 그쪽에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()` 규칙에 자연히 포함됨).

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

탈퇴 후 외부에 드러나는 부수 효과(**대부분 [인증(auth)](auth.md) 도메인에서 관찰된다** — 자세한 조건은 해당 문서의 각 절 참고):

| 이후 호출 | 결과 | 문서 |
|---|---|---|
| 같은 access 토큰으로 `DELETE /api/users/me` 재호출 | 401 `UNAUTHENTICATED` | 이 문서 |
| 그 계정의 refresh 토큰으로 `POST /api/auth/refresh` | 401 `EXPIRED_REFRESH_TOKEN` | [auth](auth.md#post-apiauthrefresh) |
| 그 계정의 이메일 + 정확한 비밀번호로 `POST /api/auth/login` | 401 `INVALID_CREDENTIALS` (미가입 이메일과 응답 동일) | [auth](auth.md#post-apiauthlogin) |
| 그 계정의 email/tel/nickname으로 `POST /api/auth/signup` | 409 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` (영구 재가입 불가) | [auth](auth.md#post-apiauthsignup) |
| 그 계정의 토큰으로 [응원(support)](support.md)·[채팅(chat)](chat.md)의 인증 필수 엔드포인트 | 401 `UNAUTHENTICATED` | [README](README.md) |

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

## GET /api/users/me
> 최종 변경: 2026-08-06 — 응답에 `supportPlayers`(현재 응원 중인 선수 목록) 추가. 키 4개→5개, SELECT 4회→5회로 정정

내 요약 프로필 조회(닉네임·응원 구단·응원 선수·보유 포인트·누적 획득 점수). `UserAccountController.getMyProfile()` → `UserProfileService.getMyProfile()`(클래스 레벨 `@Transactional(readOnly = true)`, 쓰기 경로 없음 — 아래 안전망이 작동해도 행을 만들지 않는다). 응원 선수 목록은 `SupportService.currentSupportedPlayers()`에 위임한다(같은 목록을 두 곳에서 따로 만들면 한쪽만 고쳐질 때 응원 API 응답과 갈라지기 때문).

**인증 필요** — `Authorization: Bearer <accessToken>`. `DELETE /api/users/me`와 같은 경로라 `SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()`에 자연히 걸린다.

**대상 계정은 URL·쿼리·본문이 아니라 access 토큰에서만 정해진다.** 요청 파라미터가 0개다 — `?userId=`·`?uid=`를 붙여도 무시되고 토큰 주체 본인의 프로필만 반환된다.

**요청**: 없음. 파라미터·본문 없음.

**응답 200 OK** `ApiResponse<UserAccountResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data.nickname | String | `users_account.nickname` 현재 값 |
| data.supportTeam | `{id, name}` \| null | 현재 응원 중인(`oppose is null`) 구단. [`team.md`](team.md)의 `TeamResponse`를 재사용. **응원 구단을 아직 선택하지 않은 계정에서는 `null`**이며 이는 오류가 아니라 200 — "가입 완료 ~ 구단 선택 전" 윈도우의 안전망 |
| data.supportPlayers | `PlayerResponse[]` | **현재 응원 중인**(`oppose is null`) 선수 전체, `playerName` 오름차순. 항목은 [선수(player)](player.md#get-apiplayers)·[응원(support)](support.md) API와 **완전히 동일한 `PlayerResponse` 재사용**(전용 DTO 없음) — 키 6개 `{teamId, teamName, playerId, playerName, playerNumber, playerPosition}`. `playerNumber`·`playerPosition`은 nullable이라 `null`이 그대로 나갈 수 있다 |
| data.point | long(JSON 숫자) | 보유 포인트. `users_account.point` |
| data.bqScore | long(JSON 숫자) | 누적 획득 점수. `users_bq.bq_score`. **그 계정의 `users_bq` 행이 없으면 `null`이 아니라 `0`**(배포 직후~백필 사이의 안전망, 아래 각주 참고) |

`data`의 키 집합은 정확히 이 5개로 닫혀 있다(2026-08-06 이전은 4개) — `id`·`uid`·`password`·`email`·`tel`·`exitAt`·`createdAt`·`updatedAt`은 응답 어디에도 없다(`UserAccount` 엔티티를 그대로 싣지 않고 전용 DTO로 조립).

⚠ **`supportTeam`(단일 값)과 `supportPlayers`(목록)의 "없음" 표현은 비대칭이다.** 구단은 단일 값이라 "없음"을 `null`로만 표현할 수 있지만, 목록은 빈 배열이 그대로 "0건"이라 `supportPlayers`는 응원 선수가 없어도 `null`이 아니라 **빈 배열 `[]`**이다. 응원 구단이 아예 없는 계정(구단 선택 전)에서도 `supportPlayers`는 (구단이 없으므로 당연히) `[]`이며 200이다 — 400 `SUPPORT_TEAM_REQUIRED`가 아니다.

⚠ **`supportPlayers`의 길이는 보통 4 이하지만, 그 상한을 강제하는 주체는 이 엔드포인트가 아니다.** 4명 상한은 [`POST /api/support/players`](support.md#post-apisupportplayers)가 추가 시점에 거부하는 것으로만 강제되며, `/me`는 "있는 그대로" 반환한다. 상한 도입(2026-08-06) 이전에 이미 5명 이상을 응원 중이던 계정은 그 초과분이 그대로 반환된다(마이그레이션 없음) — **클라이언트가 `supportPlayers.length <= 4`를 불변으로 가정하면 안 된다.**

응원 선수가 있는 경우:
```json
{"success":true,"data":{"nickname":"gildong","supportTeam":{"id":6,"name":"KIA"},"supportPlayers":[{"teamId":6,"teamName":"KIA","playerId":168,"playerName":"김도영","playerNumber":"5","playerPosition":"INFIELDER"},{"teamId":6,"teamName":"KIA","playerId":414,"playerName":"고종욱","playerNumber":null,"playerPosition":null}],"point":0,"bqScore":0},"message":null}
```

응원 구단·응원 선수 모두 없는 경우:
```json
{"success":true,"data":{"nickname":"gildong","supportTeam":null,"supportPlayers":[],"point":0,"bqScore":0},"message":null}
```

**내부 동작(SELECT 5회 고정, 응원 이력 행 수·응원 선수 수와 무관)**: `JwtAuthenticationFilter`의 uid→id 해석(`findActiveIdByUid`) 1 + 계정 조회 1 + 응원 구단 행 조회(+구단명 LAZY 프록시 초기화) 1 + 응원 선수 목록(fetch join 1쿼리로 선수·소속 구단까지 함께 가져온다, `SupportService.currentSupportedPlayers`) 1 + 누적 점수 조회 1 = 5. 응원 선수가 0명이어도 fetch join 쿼리 자체는 나가므로 등호로 고정된 횟수다(이전 문서의 "SELECT 4회 고정"은 필터 단계를 빼고 세거나 응원 선수 조회를 2쿼리로 세던 낡은 서술 — 정정됨). DTO 조립은 서비스 트랜잭션 안에서 끝난다(`open-in-view: false`인 prod에서 컨트롤러가 지연 로딩 연관을 읽으면 `LazyInitializationException`이 나기 때문).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/**탈퇴한 계정의 access 토큰**(필터가 활성 계정을 못 찾아 `SecurityContext`가 비고 `anyRequest().authenticated()`에 걸림). 필터를 통과한 뒤에도 principal의 계정이 그 사이 사라졌다면 서비스가 방어적으로 같은 `UNAUTHENTICATED`를 던진다(정상 경로에서는 발생하지 않음) |

**예시**
```bash
curl -i http://localhost:8080/api/users/me \
  -H 'Authorization: Bearer eyJ...'
```

미인증 예시:
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

> **각주 (배포 관련, 계약 아님)**: prod에서는 `users_account.point` 컬럼과 `users_bq` 테이블이 `user` 앱의 `ddl-auto=update` 기동 시점에 비로소 생긴다. 그 재기동 전까지는 이 엔드포인트가 500을 반환한다.

---

## PATCH /api/users/me/nickname
> 최종 변경: 2026-08-17 — 신규 추가(브랜치 `hwannee/be/feat-edit-profile`, `main` 84f6f4a 머지 완료)

내 닉네임을 변경한다. `UserAccountController.updateNickname()` → `UserProfileEditService.updateNickname()`(클래스 레벨 `@Transactional`).

**인증 필요** — `Authorization: Bearer <accessToken>`. `/api/users/me`와 같은 이유로 `SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()`에 자연히 걸린다.

**대상 계정은 URL·쿼리·본문이 아니라 access 토큰에서만 정해진다.** 경로에 식별자가 없고 본문에 `userId`·`uid`를 추가로 넣어도 무시된다.

**요청 본문** `NicknameUpdateRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| nickname | String | `@ValidNickname`(길이 1~10자 + `[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]` 화이트리스트, 단일 애노테이션만 — `@NotBlank`/`@Size`/`@Pattern`을 겹쳐 걸지 않는다) | 새 닉네임 |

**응답 204 No Content** — 본문 없음. 변경된 닉네임을 응답에 싣지 않는다(최신 프로필이 필요하면 `GET /api/users/me`를 다시 부른다).

**판정 순서(첫 위반 하나만 응답, 5단계)**: ①길이(1~10자, `@ValidNickname`이 컨트롤러 진입 전에 처리) → ②문자 구성(허용 문자 화이트리스트, 역시 `@ValidNickname`) → ③현재 닉네임과 동일 → ④타 계정(탈퇴 계정 포함) 점유 → ⑤쿨다운(마지막 30일 이내 재변경). ①②는 검증 단계에서 끝나 서비스 코드에 도달하지 않는다. ③~⑤는 `UserProfileEditService.updateNickname()`이 이 순서 그대로 판정한다.

⚠ **파생 계약 — 쿨다운 중인 계정이 이미 점유된 닉네임을 요청하면 429가 아니라 409다.** ⑤(쿨다운)가 판정 순서의 마지막이라, ④(타 계정 점유)가 먼저 걸린다. 429는 오직 "형식 통과 + 미점유 + 쿨다운 중"인 요청에만 나온다.

성공 시 `UserAccount.changeNickname(nickname, nowEpochSecond)`가 닉네임 교체와 `nickname_changed_epoch_second` 기록을 한 전이로 묶는다(성공했을 때만 시각이 갱신되고, 실패 경로는 전부 이 호출 앞에서 예외로 끝나 계정 값이 요청 전과 완전히 동일하게 남는다).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰 |
| 400 | (Bean Validation, ErrorCode 아님) | 길이 위반(1~10자 초과/미달, 누락·`null` 포함) → `data.nickname`에 `"닉네임은 1~10자여야 합니다."` |
| 400 | (Bean Validation, ErrorCode 아님) | 허용 문자 외 포함 → `data.nickname`에 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."` |
| 400 | SAME_AS_CURRENT_NICKNAME | 요청 닉네임이 현재 자기 닉네임과 완전히 같음 → `data:null`, `message:"현재 닉네임과 다른 닉네임을 사용해 주세요."`(409 `DUPLICATE_NICKNAME`이 **아님** — 자기 닉네임에는 "이미 사용 중"이 거짓이라 신규 코드로 분리) |
| 409 | DUPLICATE_NICKNAME | 다른 계정(탈퇴 계정 포함)이 이미 점유 → `data:null`, `message:"이미 사용 중인 닉네임입니다."` |
| 429 | NICKNAME_CHANGE_COOLDOWN | 마지막 변경으로부터 30일(2,592,000초) 미경과 → **`data`에 `nextChangeableAt` 실림**(아래 참고) |

**429 응답은 이 저장소에서 `data`가 `null`이 아닌 몇 안 되는 `BusinessException` 계열 응답이다**(`BusinessDataException` 하위 타입으로 던져 `GlobalExceptionHandler.handleBusinessData`가 처리 — 자세한 내부 규칙은 [README.md](README.md#1-응답-래퍼--도메인엔드포인트마다-다르다) 참고). `data`의 키는 정확히 `nextChangeableAt` 하나이고, 값은 `+09:00` 오프셋을 포함한 ISO-8601 문자열이다(`yyyy-MM-dd'T'HH:mm:ssXXX` — `ISO_OFFSET_DATE_TIME`을 쓰지 않는 이유는 초가 0일 때 `:00`을 생략해 자릿수가 시각마다 달라지기 때문). 판정 자체는 epoch 초 비교라 서버 시간대(운영 파드는 UTC)와 무관하고, 오프셋은 표기에만 쓰인다. 마지막 변경 이력이 없는 계정(`nickname_changed_epoch_second`가 `NULL` — 컬럼 도입 이전 계정 포함)은 쿨다운이 적용되지 않는다.

```json
{"success":false,"data":{"nextChangeableAt":"2026-09-16T14:03:21+09:00"},"message":"닉네임은 30일에 한 번만 변경할 수 있습니다."}
```

**예시**
```bash
curl -i -X PATCH http://localhost:8080/api/users/me/nickname \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"길동gil9"}'
```
성공: `204 No Content`, 본문 없음.

형식 위반 예시:
```json
{"success":false,"data":{"nickname":"닉네임은 1~10자여야 합니다."},"message":"입력값이 올바르지 않습니다."}
```

자기 자신과 동일:
```json
{"success":false,"data":null,"message":"현재 닉네임과 다른 닉네임을 사용해 주세요."}
```

---

## PATCH /api/users/me/password
> 최종 변경: 2026-08-17 — **성공 시 그 이전에 발급된 access·refresh 토큰이 즉시 무효화됨**(`UserAccount.passwordChangedEpochSecond`, PR #425, `main` 84f6f4a 머지 완료). 종전 "이전 access 토큰은 최대 3h 그대로 유효하다"는 서술을 정정. (직전: 같은 날 신규 추가, 브랜치 `hwannee/be/feat-edit-profile`)

내 비밀번호를 변경한다. `UserAccountController.updatePassword()` → `UserProfileEditService.updatePassword()`(클래스 레벨 `@Transactional`).

**인증 필요** — `Authorization: Bearer <accessToken>`. 닉네임 경로와 동일하게 `SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()`에 걸린다.

**대상 계정은 URL·쿼리·본문이 아니라 access 토큰에서만 정해진다.**

**요청 본문** `PasswordUpdateRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| currentPassword | String | 검증 애노테이션 없음(누락·`null`은 서비스가 "불일치"로 흡수) | 현재 비밀번호(평문) |
| newPassword | String | `@ValidPassword`(길이 8~12자 + 영문·숫자·특수문자 각 1자 이상, 단일 애노테이션만) | 새 비밀번호(평문) |

**응답 200 OK** `ApiResponse<TokenResponse>` — 로그인·재발급 응답과 **문자 그대로 같은 DTO**를 재사용(신규 DTO 없음)

| 필드 | 타입 | 설명 |
|---|---|---|
| data.accessToken | String | 새로 발급된 access 토큰(3h) |
| data.refreshToken | String | 새로 발급된 refresh 토큰(14d) — 기존 유효 refresh 토큰을 먼저 만료시킨 뒤 발급하므로, 변경 직후 그 계정의 유효 refresh 행은 이 값 하나뿐이다 |

**판정 순서(첫 위반 하나만 응답, 3단계)**: ①새 비밀번호 형식(`@ValidPassword`, 컨트롤러 진입 전) → ②현재 비밀번호 일치(`passwordEncoder.matches()`) → ③신·구 동일 여부(문자열 비교). ①에서 걸리면 서비스 코드가 실행되지 않아 bcrypt 대조 자체가 일어나지 않는다.

**닉네임과 달리 쿨다운이 없다** — 의도된 비대칭이다: 비밀번호가 유출됐을 때 사용자의 자구책은 즉시 교체뿐이라 그 수단을 잠그지 않기 위함이며, 아래 access 토큰 무효화의 트리거(비밀번호 변경)를 봉인하지 않기 위함이기도 하다.

**변경 전에 발급된 access·refresh 토큰은 성공 즉시 무효화된다(2026-08-17부터, PR #425).** 서비스가 `account.changePassword(encoded, Instant.now().getEpochSecond())`로 계정의 `passwordChangedEpochSecond`(비밀번호 변경 기준 시각, epoch 초)를 `issueTokens` 호출 **직전**에 기록한다 — 이후 그 계정의 access 토큰은 요청마다 `JwtAuthenticationFilter`가 `iat`를 이 값과 대조해, **이 시각보다 앞선 초에 발급된 토큰은 남은 유효기간(최대 3h)과 무관하게 그 순간부터 401** `UNAUTHENTICATED`(토큰이 아예 없을 때와 응답 완전히 동일 — 전용 코드·메시지 없음, [README](README.md#2-인증-방식-jwt) 참고)가 된다. refresh 토큰도 `AuthService.reissue()`가 같은 대조를 적용해 401 `EXPIRED_REFRESH_TOKEN`으로 거절한다.

이 응답이 돌려주는 `accessToken`·`refreshToken`이 이 대조를 항상 통과하는 **유일하게 보장된 토큰**이다 — 그 계정이 그 이전에 갖고 있던 access·refresh 토큰은 전부(아래 ≤1초 창 제외) 무효화된다.

⚠ **남는 한계 둘 — 이번 변경으로도 닫히지 않는다.** ①비밀번호 변경과 **같은 초**에 이미 발급돼 있던 이전 토큰(access·refresh 모두)은 살아남는다(`iat`가 초 단위로 내려오고 판정이 `>=`라 — 엄격하게 `>`로 비교하면 이 응답으로 방금 준 새 토큰이 자기 자신에게 거절되므로, ≤1초 창은 그 자기 무효화를 막기 위한 의도된 대가다). ②**로그아웃(`POST /api/auth/logout`)은 여전히 access 토큰을 죽이지 않는다** — 기준 시각이 로그아웃에서는 갱신되지 않으므로 로그아웃 후에도 그 access 토큰은 남은 유효기간(최대 3h) 동안 계속 인증된다(refresh만 끊긴다).

**비밀번호를 한 번도 바꾼 적 없는 계정**은 `passwordChangedEpochSecond`가 `NULL`이라 이 검사 자체를 건너뛴다 — 2026-08-17(`main` 84f6f4a) 배포 시점에 기존 로그인 세션이 일괄 로그아웃되지 않았다는 뜻이다(백필 없음, 의도된 설계 — `docs/requirements/user/access-token-invalidation.md` 결정 근거 6).

⚠ **응답에 `uid`가 키로는 담기지 않지만, JWT payload는 서명만 되고 암호화되지 않아 `accessToken`/`refreshToken`을 base64 디코드하면 `sub` 클레임에서 uid를 읽을 수 있다.** 이는 이 경로가 새로 여는 노출이 아니라 `POST /api/auth/login`·`/api/auth/refresh` 응답이 이미 갖고 있던 성질이며, 같은 토큰을 이미 가진 요청자 본인에게 같은 값을 한 번 더 주는 것뿐이다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/**이 요청 전의 다른 비밀번호 변경보다 앞서 발급된 access 토큰**(2026-08-17부터 — 세 경우 모두 응답 동일, [README](README.md#2-인증-방식-jwt) 참고) |
| 400 | (Bean Validation, ErrorCode 아님) | 새 비밀번호 길이 위반(8~12자 초과/미달, 누락·`null` 포함) → `data.newPassword`에 `"비밀번호는 8~12자여야 합니다."` |
| 400 | (Bean Validation, ErrorCode 아님) | 새 비밀번호가 영문·숫자·특수문자 중 하나라도 누락 → `data.newPassword`에 `"비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다."` |
| 400 | INVALID_CURRENT_PASSWORD | `currentPassword`가 저장된 값과 불일치 또는 `null`·누락 → `data:null`, `message:"현재 비밀번호가 올바르지 않습니다."`(기존 `INVALID_CREDENTIALS`(401)을 재사용하지 않는다 — FE의 401 인터셉터가 비밀번호 오타 한 번에 로그아웃을 유발하는 것을 피하기 위함) |
| 400 | SAME_AS_CURRENT_PASSWORD | `newPassword`가 `currentPassword`와 동일(형식은 만족) → `data:null`, `message:"현재 비밀번호와 다른 비밀번호를 사용해 주세요."` |

**예시**
```bash
curl -i -X PATCH http://localhost:8080/api/users/me/password \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Old1234!","newPassword":"New1234!"}'
```
성공:
```json
{"success":true,"data":{"accessToken":"eyJ...","refreshToken":"eyJ..."},"message":null}
```

현재 비밀번호 불일치:
```json
{"success":false,"data":null,"message":"현재 비밀번호가 올바르지 않습니다."}
```

---

## 확인 필요 / 코드 미확인

- **탈퇴 취소(복구) API·하드 딜리트·개인정보 파기 배치는 코드에 없다**(`docs/requirements/user/withdraw.md`가 범위 제외로 명시). `exit_at`은 표식만 남기고 행을 삭제하지 않는다.
- `uid`를 응답 body에 **키로** 노출하는 엔드포인트는 없다. `GET /api/users/me`도 `uid`가 아니라 access 토큰으로만 대상을 식별하며 응답에 `uid` 키를 담지 않는다. 다만 `PATCH /api/users/me/password`가 돌려주는 `accessToken`/`refreshToken`은 JWT라 그 payload를 base64 디코드하면 `sub`에서 uid를 읽을 수 있다 — 이는 `POST /api/auth/login`·`/api/auth/refresh` 응답이 이미 갖던 성질이라 이 경로가 새로 만든 노출이 아니다(위 엔드포인트 절 참고).
- (과거 기록, 정정됨) 이전 버전 문서에는 "프로필 **수정**(`PATCH`/`PUT /api/users/me` 류) 엔드포인트는 아직 없다"고 적혀 있었다 — `PATCH /api/users/me/nickname`·`PATCH /api/users/me/password` 추가(2026-08-17, 브랜치 `hwannee/be/feat-edit-profile`, `main` 84f6f4a 머지 완료)로 더 이상 사실이 아니다.
- (과거 기록, 정정됨) 직전 라운드 문서는 "`PATCH /api/users/me/password`는 변경 전 access 토큰을 무효화하지 않는다(최대 3h 그대로 유효)"고 적었다 — 2026-08-17 후속 배포(PR #425, `main` 84f6f4a)로 **정반대가 됐다**: 변경 전에 발급된 access·refresh 토큰은 즉시 무효화된다. 남는 한계는 ≤1초 창과 로그아웃 미적용 둘뿐(위 엔드포인트 절 참고).
- `point`·`bqScore`를 증감시키는 주체·경로는 이 문서 범위 밖이다(`docs/requirements/user/me-profile.md`가 명시적으로 제외 — 현재는 가입 시 `point=0`/`bqScore=0`으로 생성된 뒤 증감 경로가 없다).
- (과거 기록, 정정됨) 이전 버전 문서에는 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 현재 없다"고 적혀 있었다 — 탈퇴 엔드포인트 추가로 더 이상 사실이 아니다.

## 관련 문서

- [인증(auth)](auth.md) — 탈퇴가 login/refresh/signup 응답에 미치는 영향의 반대편 서술. signup이 `users_bq` 행을 함께 만드는 부수 효과도 그쪽 문서 참고. `PATCH /api/users/me/password`가 재사용하는 `TokenResponse`도 이쪽 문서(로그인·재발급)에서 정의된다.
- [구단(team)](team.md) — `supportTeam` 필드가 재사용하는 `TeamResponse` 정의.
- [선수(player)](player.md) · [응원(support)](support.md) — `supportPlayers` 필드가 재사용하는 `PlayerResponse` 정의. **`PlayerResponse`를 바꾸면 `GET /players`·응원 API 2개·이 엔드포인트 총 4곳이 함께 바뀐다.**
- 요구사항: `docs/requirements/user/withdraw.md`, `docs/requirements/user/me-profile.md`(USER-ME-1~36, 2026-08-06 2차 개정으로 `supportPlayers` 추가·상한 무관 서술 확정), `docs/requirements/user/profile-edit.md`(USER-PE-1~49, 승인됨 2026-08-17 — `PATCH /me/nickname`·`PATCH /me/password`의 출처. USER-PE-32는 폐기 표기 — 아래 문서로 대체됨), `docs/requirements/user/access-token-invalidation.md`(USER-ATI-1~22, 승인됨 2026-08-17 — `PATCH /me/password`의 토큰 즉시 무효화 계약의 출처)
