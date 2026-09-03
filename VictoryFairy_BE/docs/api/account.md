# 계정(account) API 명세

> **도메인** `account` — 로그인 계정 자체의 생명주기(탈퇴) + 내 프로필 요약 조회 + 내 프로필 수정(닉네임·비밀번호·프로필 이미지).
> **모듈** user (포트 8080) · **경로 접두사** `/api/users` · **엔드포인트** 5개
> **컨트롤러** `user/src/main/java/com/skhynix/user/account/controller/UserAccountController.java` (`@RequestMapping("/users")`)
> **최종 갱신** 2026-09-03 — **`GET /api/users/me` 응답에 `quizAccuracy`(내 퀴즈 누적 정답률) 추가**(키 8개→9개, SELECT 7회→8회). 값은 그 계정의 `quiz_users_submit` 행 중 `is_answer = true` 수 ÷ 그 계정 행 **전부**다. 행은 제출이 아니라 **출제 시점**(`GET /rt/quizzes/today`)에 생기므로 **미답 행도 분모에 들어가 오답으로 집계된다** — 세트를 받은 직후 `/me`를 부르면 정답률이 일시적으로 떨어진다(기존 "안 내면 오답" 제품 결정의 귀결, 버그 아님). 행이 0건이면 `null`이 아니라 `0`이고 200(`bqScore` 안전망과 같은 기조). **0~1 범위의 JSON 숫자 하나**, 소수 넷째 자리에서 **HALF_UP** 반올림한 셋째 자리까지이며 **후행 0을 보존하지 않는다**(`0.5`는 `0.500`이 아니다) — 할·푼·리 표기와 세 자리 패딩은 프론트엔드 책임. 전 기간 누적이며 경기·기간 필터가 없다(경기 단위는 `GET /rt/quizzes/submissions`가 계속 갖는다). ⚠ **그 응답의 `accuracy`(반올림 없는 double, 예: `0.642857`)와 자릿수가 다르다 — 이 비대칭은 버그가 아니라 사용자 결정이며 맞추지 않는다**([quiz.md](quiz.md#get-rtquizzessubmissions)는 이번에 개정하지 않았다). 계약 원본 `docs/requirements/user/me-profile.md`(2026-09-03 개정, USER-ME-37~44). (직전: 2026-08-28 — **`GET /api/users/me` 응답에 `characterImgUrl`·`characterItems` 추가**(키 6개→8개). 아바타 캐릭터와 착용 중인 아이템의 이미지 **EP** 다 — `profileImgUrl`(프로필 사진)과 **별개이며 서로를 대체하지 않는다.** 캐릭터를 아직 못 받은 계정은 `characterImgUrl: null` + `characterItems: []` 로 200 을 유지한다. SELECT 는 5회→7회로 늘었다(캐릭터 1 + 착용 아이템 1). 새 도메인 [character.md](character.md)(상점·구매·착용 토글 3개)가 같은 날 신설됐고, 이 응답의 `characterItems[].imgUrl` 은 그쪽 목록의 `displayImg` 와 **다른 좌표계의 다른 파일**이다. 계약 원본 `docs/requirements/user/character-shop.md`(승인됨 2026-08-28, USER-CS-1~37). (직전: 2026-08-20 — **`POST /api/users/me/profile-image` 신규 추가**(업로드가 곧 프로필 변경 확정, 직전 객체는 커밋 이후 best-effort로 삭제) + **`GET /api/users/me` 응답에 `profileImgUrl` 추가**(키 5개→6개, SELECT 횟수는 그대로 5회 — 이미 조회하는 계정 행의 컬럼이라 추가 조회 없음). 계약 원본 `docs/requirements/user/profile-image.md`(승인됨 2026-08-20, USER-PI-1~121). profileImgUrl 조립 예시(BaseURL+EP, 흔한 실수 포함)와 CloudFront/S3 구분, 가입 전후 EP 완전 교체 서술 보강. (직전: 2026-08-17 `PATCH /api/users/me/password` 성공 시 **그 이전에 발급된 access·refresh 토큰이 즉시 무효화됨**(`main` 84f6f4a 머지 완료, PR #425). 직전 "이전 access 토큰은 최대 3h 그대로 유효하다"는 서술을 정정.) ) ) 그 이전 이력은 각 엔드포인트 섹션의 `최종 변경` 줄에 남아 있다.
> 공통 규약(응답 래퍼·JWT payload·401 4종·**토큰 무효화**·**시스템 예외 래핑**)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| DELETE | [/api/users/me](#delete-apiusersme) | 204 | 회원 탈퇴(soft delete) |
| GET | [/api/users/me](#get-apiusersme) | 200 | 내 요약 프로필 조회(닉네임·응원 구단·응원 선수·포인트·누적 점수·프로필 이미지) |
| PATCH | [/api/users/me/nickname](#patch-apiusersmenickname) | 204 | 닉네임 변경(형식→중복→쿨다운 판정) |
| POST | [/api/users/me/profile-image](#post-apiusersmeprofile-image) | 200 | 프로필 이미지 등록·변경(업로드가 곧 변경 확정) — 신규 |
| PATCH | [/api/users/me/password](#patch-apiusersmepassword) | 200 | 비밀번호 변경(성공 시 refresh 전량 만료+새 토큰 쌍 발급) |

## 이 도메인의 특이사항

**user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**가 여기다. `/api/auth/**`는 전부 permitAll이라 탈퇴를 [auth](auth.md) 도메인에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 규칙에 자연히 포함됨).

**대상 계정은 URL이 아니라 access 토큰에서만 정해진다.** 경로에 식별자가 없고, `@AuthenticationPrincipal Long userAccountId`로 주입된 내부 PK만으로 동작한다.

**탈퇴는 이 도메인 밖으로 파급된다** — [auth](auth.md)의 login/refresh/signup 응답이 모두 바뀐다. 아래 "탈퇴 후 부수 효과" 표 참고.

---

## DELETE /api/users/me
> 최종 변경: 2026-08-20 — 탈퇴 확정(커밋) 후 그 계정의 프로필 이미지 객체를 best-effort로 삭제하는 부수 효과 추가(`WithdrawnProfileImageListener`, `AFTER_COMMIT`). **요청·응답 계약·상태 코드는 변경 없음**(여전히 204, 본문 없음). (직전: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `UserAccountController` 마지막 커밋 기준)

회원 탈퇴(soft delete). `UserAccountController` → `UserAccountService.withdraw()`.

**인증 필요** — `Authorization: Bearer <accessToken>`. **user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**다([응원(support)](support.md)의 3개가 뒤이어 같은 규칙에 걸린다). [`/api/auth/**`](auth.md)는 전부 permitAll이라 탈퇴를 그쪽에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()` 규칙에 자연히 포함됨).

**왜 경로에 대상 식별자가 없는가**: 탈퇴 대상 계정은 URL이 아니라 access 토큰에서만 정해진다. `JwtAuthenticationFilter`가 토큰 `sub`(uid)를 활성 계정의 내부 `id`로 해석해 `@AuthenticationPrincipal Long userAccountId`로 주입하고, 컨트롤러는 이 `id`만으로 `UserAccountService.withdraw()`를 호출한다. `uid`는 여전히 응답 body나 URL 어디에도 노출되지 않는다.

**요청**: 없음. 본문 없음(비밀번호 재확인 절차 없음).

**응답 204 No Content** (`ResponseEntity<Void>`, raw — `ApiResponse` 미사용) — 본문 없음.

내부 동작(`UserAccountService.withdraw()`, 한 트랜잭션):
1. `UserAccount.withdraw(now)` — `exit_at`에 서버 현재 시각을 기록한다. **탈퇴는 즉시 완료이며 유예 기간·취소가 없다.** 이미 `exit_at`이 설정된 계정(이미 탈퇴한 계정)에 다시 호출해도 엔티티가 아무것도 하지 않고 최초 탈퇴 시각을 그대로 보존한다(멱등이 아니라 "덮어쓰지 않음" — 애초에 이미 탈퇴한 계정은 principal로 들어올 수 없어 재요청 자체가 아래 실패 표의 401로 막힌다).
2. `UserRefreshTokenRepository.expireValidTokens(account, now)` — 해당 계정의 유효한 refresh 토큰을 모두 만료 처리한다.

탈퇴 전에 발급받은 **access 토큰은 폐기되지 않는다**(stateless라 서버가 할 수 없음). 대신 이후의 모든 인증 필요 요청에서 `JwtAuthenticationFilter`가 `findActiveIdByUid()`로 매번 활성 여부를 다시 조회하므로, 탈퇴 순간부터 그 access 토큰은 남은 유효 기간(최대 3h)과 무관하게 즉시 인증되지 않는다.

**프로필 이미지 삭제(2026-08-20 신규, best-effort)**: 트랜잭션이 **커밋된 뒤**(`WithdrawnProfileImageListener`, `@TransactionalEventListener(phase = AFTER_COMMIT)`) 그 계정의 `profile_img_url`이 가리키던 S3 객체를 삭제한다. `AFTER_COMMIT`이라 롤백된 탈퇴 시도에서는 아예 호출되지 않는다(계정은 살아 있는데 사진만 사라지는 상태를 막는다). **삭제가 실패해도 탈퇴 응답은 여전히 204** — 이미지 하나 때문에 탈퇴가 막히지 않으며, 실패한 EP는 ERROR 로그로만 남는다(재시도 없음). `profile_img_url` 컬럼 값 자체는 지우지 않는다(soft delete라 행이 남고, 탈퇴 계정은 어떤 응답에도 노출되지 않는다).

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
> 최종 변경: 2026-09-03 — 응답에 `quizAccuracy`(내 퀴즈 누적 정답률) 추가. 키 8개→9개, SELECT 7회→8회. (직전: 2026-08-28 — 응답에 `characterImgUrl`·`characterItems` 추가. 키 6개→8개, SELECT 5회→7회.) (직전: 2026-08-20 — 응답에 `profileImgUrl` 추가. 키 5개→6개, SELECT 횟수는 5회 그대로(추가 조회 없음).) (직전: 2026-08-06 응답에 `supportPlayers`(현재 응원 중인 선수 목록) 추가. 키 4개→5개, SELECT 4회→5회로 정정)

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
| data.profileImgUrl | String \| null | 프로필 이미지의 **EP**(BaseURL을 뺀 오브젝트 키, `user-profile-img/{uuid}.{ext}` 형태 — 2026-08-20 신규). `users_account.profile_img_url`을 그대로 노출한다(추가 SELECT 없음). **이미지가 없으면 `null`**이며 빈 문자열도 기본 이미지 URL도 아니다(`supportTeam`이 `null`인 것과 같은 방식). 값을 실제 이미지로 쓰려면 클라이언트가 `https://victoryfairy.com/` + 이 값을 그대로 이어 붙인다(선행 슬래시·버킷명은 없다) — 자세한 내용은 아래 "profileImgUrl 값의 의미" 참고 |
| data.characterImgUrl | String \| null | **아바타 캐릭터**의 이미지 EP(`characters/{슬러그}.svg` 형태 — 2026-08-28 신규). 프로필 사진(`profileImgUrl`)과 **별개다**: 저쪽은 사용자가 올린 사진, 이쪽은 꾸미기 캐릭터다. 캐릭터를 아직 지급받지 못한 계정에서는 `null`이며(빈 문자열도 기본 이미지도 아니다) 그 경우에도 응답은 200이다 — 지급이 건너뛰어졌을 수 있고([character-shop 요구사항](../requirements/user/character-shop.md) USER-CS-12), 시드 백필이 다음 기동에 복구한다 |
| data.characterItems | `{itemType, imgUrl}[]` | **착용 중인** 아이템 전체(2026-08-28 신규). 없으면 `null`이 아니라 빈 배열이다. `itemType`은 부위(`의상`·`모자`·`소품` — 닫힌 집합이 아니다), `imgUrl`은 **착용용** 이미지 EP(`items/{부위}/{슬러그}.svg`). ⚠ [상점 목록](character.md)의 `displayImg`와 **바꿔 쓰면 안 된다** — 같은 아이템이지만 좌표계가 다른 별개 파일이다. 부위 id 오름차순으로 정렬돼 나가므로 클라이언트가 그 순서대로 겹쳐 그리면 된다 |
| data.quizAccuracy | BigDecimal(JSON 숫자, 2026-09-03 신규) | 내 퀴즈 **누적** 정답률 — 그 계정의 `quiz_users_submit`(`:domain`) 행 중 `is_answer = true` 수 ÷ 그 계정 행 **전부**(미답 행 포함). `0` 이상 `1` 이하, 소수 넷째 자리에서 **HALF_UP** 반올림한 셋째 자리까지이며 **후행 0을 보존하지 않는다**(`0.5`는 `0.500`이 아니다 — 세 자리 패딩은 프론트엔드 몫). 그 계정의 제출 행이 한 건도 없으면 `null`이 아니라 **`0`**(200 유지). 경기·기간 필터 없이 항상 전 기간 누적이다. 자세한 산식·경계값·`GET /rt/quizzes/submissions`의 `accuracy`와의 자릿수 비대칭은 아래 "quizAccuracy 값의 의미" 참고 |

`data`의 키 집합은 정확히 이 9개로 닫혀 있다(2026-09-03 이전은 8개, 2026-08-28 이전은 6개, 2026-08-20 이전은 5개, 2026-08-06 이전은 4개) — `id`·`uid`·`password`·`email`·`tel`·`exitAt`·`createdAt`·`updatedAt`은 응답 어디에도 없다(`UserAccount` 엔티티를 그대로 싣지 않고 전용 DTO로 조립).

### profileImgUrl 값의 의미 (프론트 필독)

이 API 전체(`GET /me`·`POST /me/profile-image`·`POST /api/auth/profile-image`·[채팅](chat.md)의 `MessageResponse`/`MessageEvent`)에서 `profileImgUrl`은 항상 같은 규칙을 따른다.

- **값은 BaseURL을 뺀 EP다.** 스킴(`https://`)·도메인(`victoryfairy.com`)·버킷명·선행 슬래시(`/`)를 포함하지 않는다 — `user-profile-img/9f1c4e2a-....jpg`처럼 세그먼트 2개(`접두/파일명`)뿐이다.
- **EP에 선행 슬래시가 없다는 점이 실수하기 쉬운 지점이다.** 클라이언트가 BaseURL과 EP를 **단순 문자열 결합**으로 이어 붙이면 슬래시가 통째로 빠진다. 슬래시는 서버가 넣어 주지 않으므로 **클라이언트가 직접 `/`를 끼워 넣어야 한다.**

```
BaseURL   : https://victoryfairy.com
EP        : temp/9f2c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.png
정답      : https://victoryfairy.com/temp/9f2c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.png   (BaseURL + "/" + EP)
흔한 실수 : https://victoryfairy.comtemp/9f2c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.png    (슬래시 없이 그냥 이어 붙임 — 404)
```

- **값이 없으면 `null`이다.** 빈 문자열(`""`)도 아니고 기본 이미지의 URL도 아니다 — "이미지 없음"과 "이미지가 있는데 아직 못 정했다"를 구분할 필요가 없는 API 설계다.

**`https://victoryfairy.com`은 S3 주소가 아니라 CloudFront(CDN) 주소다.** 버킷(`victoryfairy-asset`)은 퍼블릭 액세스 차단(BPA) 4종이 전부 켜진 프라이빗 버킷이라 `https://victoryfairy-asset.s3.ap-northeast-2.amazonaws.com/...` 같은 S3 직접 URL로는 애초에 읽히지 않는다. 버킷 정책이 허용하는 읽기 경로는 **지정된 CloudFront 배포(OAC) 하나뿐**이고, 그 배포 안에서도 경로 패턴이 `/user-profile-img/*`·`/temp/*` 두 개로 한정돼 있다 — 이 두 접두사 밖의 키는 버킷에 있어도 이 도메인으로 못 읽는다. FE와 API가 같은 도메인(`victoryfairy.com`)을 쓰므로 이 값을 읽을 때 CORS 설정이 필요 없다.

⚠ **이 BaseURL 값 자체는 이 저장소(BE 설정·코드) 어디에도 없다.** 서버는 EP만 응답하고 도메인 조립은 전적으로 클라이언트 몫이므로, `https://victoryfairy.com`을 서버 설정 키(`application.yaml`의 프로퍼티 등)로 착각해 찾지 말 것 — 클라이언트가 알고 있어야 하는 상수다.

```json
{"profileImgUrl": "user-profile-img/9f1c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.jpg"}
```
→ 실제 이미지: `https://victoryfairy.com/user-profile-img/9f1c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.jpg`

**가입 전후로 EP가 완전히 달라진다 — 가입 전에 쓰던 temp EP를 계속 붙들고 있으면 안 된다.** 접두사가 `temp/` → `user-profile-img/`로 바뀌는 것뿐 아니라, **파일명도 새 UUID로 다시 생성된다**(`SignupProfileImageService.move()`가 원본 이름을 물려받지 않고 새 키를 만든다 — `temp/`가 CDN으로 공개 읽히므로, 이름을 물려받으면 가입 전 미리보기 링크를 아는 사람이 가입 후 영구 주소까지 그대로 알게 되기 때문이다). 즉 `POST /api/auth/profile-image`가 돌려준 `temp/{uuid-A}.jpg`와 가입 후 `GET /api/users/me`가 돌려주는 `user-profile-img/{uuid-B}.jpg`는 **같은 파일이지만 EP 문자열이 완전히 다르다.** 클라이언트는 가입 응답을 받은 뒤에는 반드시 `GET /api/users/me`를 다시 호출해 그 값을 화면에 반영해야 하고, 가입 전 화면에서 쓰던 temp EP를 그대로 프로필 이미지 URL로 캐싱해 두면 안 된다(그 temp 객체는 이동 후 삭제되고, 정리 스케줄러·라이프사이클도 결국 회수한다).

⚠ **`supportTeam`(단일 값)과 `supportPlayers`(목록)의 "없음" 표현은 비대칭이다.** 구단은 단일 값이라 "없음"을 `null`로만 표현할 수 있지만, 목록은 빈 배열이 그대로 "0건"이라 `supportPlayers`는 응원 선수가 없어도 `null`이 아니라 **빈 배열 `[]`**이다. 응원 구단이 아예 없는 계정(구단 선택 전)에서도 `supportPlayers`는 (구단이 없으므로 당연히) `[]`이며 200이다 — 400 `SUPPORT_TEAM_REQUIRED`가 아니다.

⚠ **`supportPlayers`의 길이는 보통 4 이하지만, 그 상한을 강제하는 주체는 이 엔드포인트가 아니다.** 4명 상한은 [`POST /api/support/players`](support.md#post-apisupportplayers)가 추가 시점에 거부하는 것으로만 강제되며, `/me`는 "있는 그대로" 반환한다. 상한 도입(2026-08-06) 이전에 이미 5명 이상을 응원 중이던 계정은 그 초과분이 그대로 반환된다(마이그레이션 없음) — **클라이언트가 `supportPlayers.length <= 4`를 불변으로 가정하면 안 된다.**

### quizAccuracy 값의 의미 (2026-09-03 신규, 프론트 필독)

- **산식**: 그 계정의 `quiz_users_submit`(`:domain`) 행 중 `is_answer = true` 수 ÷ 그 계정 행 **전부**(`SELECT SUM(is_answer)/COUNT(*) FROM quiz_users_submit WHERE user_account_id = <본인>`와 동일). 행은 제출이 아니라 **출제 시점**(`GET /rt/quizzes/today`가 세트를 서빙하는 순간)에 생기므로, **답하지 않은 문제(`submit_option_id IS NULL`)도 분모에 포함되고 오답으로 집계된다.** 시한(8분)이 지난 미답과 아직 안 지난 미답을 구분하지 않는다.
  - **관측되는 결과**: `GET /rt/quizzes/today`가 세트를 내려주는 그 순간 미답 행이 최대 20건 생기므로, 세트를 받자마자 `/me`를 호출하면 정답률이 **일시적으로 떨어졌다가** 문제를 풀면서 회복한다. 세트를 받고 한 문제도 풀지 않으면 그 20건은 영구히 오답으로 남는다. 이는 기존 "안 내면 오답" 제품 결정(`QuizUserSubmit` javadoc·`quiz-submission-by-inning.md`)의 직접적 귀결이지 버그가 아니다.
- **경계값**: 그 계정의 `quiz_users_submit` 행이 한 건도 없으면 `null`·`NaN`·404·500이 아니라 **`quizAccuracy: 0`으로 200**이다(`bqScore`의 행-없음 안전망, USER-ME-19와 같은 기조).
- **자릿수**: `0` 이상 `1` 이하의 **JSON 숫자** 하나로, 소수 넷째 자리에서 **HALF_UP**(사사오입) 반올림한 **소수 셋째 자리까지**의 값이다(예: `2/3` → `0.667`, `1/16`(=0.0625) → `0.063`). **후행 0을 보존하지 않는다** — `0.5`는 `0.5`로 나가지 `0.500`이 아니며, 서버는 자릿수를 맞추려고 스케일 고정 십진 타입을 강제하지 않는다. 문자열(`"0.667"`)이 아니고 백분율(0~100) 스케일도 아니다. **할·푼·리 표기·세 자리 패딩·`"6할 6푼 7리"` 같은 표기 문자열은 서버가 만들지 않는다 — 전부 프론트엔드 책임이다.**
- **범위**: 전 기간 누적이며 경기·이닝·날짜로 좁히는 파라미터가 없다(요청 파라미터는 여전히 0개). 경기 단위 정답률은 [`GET /rt/quizzes/submissions`](quiz.md#get-rtquizzessubmissions)가 계속 갖는다.
- ⚠ **이름이 같은 값처럼 보여도 `GET /rt/quizzes/submissions`의 `accuracy`와 형식이 다르다.** 그쪽은 **반올림하지 않은 double**(예: `0.642857`, 범위도 경기 한 건)이고 이쪽은 **소수 셋째 자리까지**(범위는 전 기간)다. **이 비대칭은 버그가 아니라 사용자 결정이다 — 통일하지 말 것.** 나중에 "두 정답률의 자릿수가 다르다"를 결함으로 보고 한쪽을 조용히 고치면 그것이 계약 위반이다(`docs/requirements/user/me-profile.md` 결정 22).
- **캐시·이력 없음**: 값은 요청마다 원본 행에서 다시 센다(비정규화 컬럼 없음). 이력·추이·랭킹·다른 사용자 정답률은 노출하지 않으며 조회 시점 스냅샷 하나만 낸다.

응원 선수·프로필 이미지가 있는 경우:
```json
{"success":true,"data":{"nickname":"gildong","supportTeam":{"id":6,"name":"KIA"},"supportPlayers":[{"teamId":6,"teamName":"KIA","playerId":168,"playerName":"김도영","playerNumber":"5","playerPosition":"INFIELDER"},{"teamId":6,"teamName":"KIA","playerId":414,"playerName":"고종욱","playerNumber":null,"playerPosition":null}],"point":0,"bqScore":0,"profileImgUrl":"user-profile-img/9f1c4e2a-6b3d-4a1f-8c2e-1a2b3c4d5e6f.jpg","characterImgUrl":"characters/victory-fairy.svg","characterItems":[{"itemType":"의상","imgUrl":"items/cloth/basic.svg"},{"itemType":"모자","imgUrl":"items/head/cap-blue.svg"}],"quizAccuracy":0.667},"message":null}
```

응원 구단·응원 선수·프로필 이미지 모두 없는 경우(퀴즈 제출 행도 0건):
```json
{"success":true,"data":{"nickname":"gildong","supportTeam":null,"supportPlayers":[],"point":0,"bqScore":0,"profileImgUrl":null,"characterImgUrl":"characters/victory-fairy.svg","characterItems":[],"quizAccuracy":0},"message":null}
```

**내부 동작(SELECT 8회 고정, 응원 이력 행 수·응원 선수 수·착용 아이템 수·퀴즈 제출 행 수와 무관)**: `JwtAuthenticationFilter`의 uid→id 해석(`findActiveIdByUid`) 1 + 계정 조회 1 + 응원 구단 행 조회(+구단명 LAZY 프록시 초기화) 1 + 응원 선수 목록(fetch join 1쿼리로 선수·소속 구단까지 함께 가져온다, `SupportService.currentSupportedPlayers`) 1 + 누적 점수 조회 1 = 5. 응원 선수가 0명이어도 fetch join 쿼리 자체는 나가므로 등호로 고정된 횟수다(이전 문서의 "SELECT 4회 고정"은 필터 단계를 빼고 세거나 응원 선수 조회를 2쿼리로 세던 낡은 서술 — 정정됨). `profileImgUrl`(2026-08-20 신규)은 계정 조회 시점에 이미 로딩되는 `users_account.profile_img_url` 컬럼이라 이 횟수에서 늘지 않는다. `characterImgUrl`·`characterItems`(2026-08-28 신규)는 각각 별도 테이블이라 **2회를 더한다**(5→7): 사용 중인 캐릭터 1회(`@EntityGraph`로 `characters`까지 한 쿼리) + 착용 중인 아이템 1회(`@EntityGraph`로 `character_items`·`item_types`까지 한 쿼리). 착용 아이템이 0개여도 그 쿼리는 나가므로 등호로 고정된 횟수다. **`quizAccuracy`(2026-09-03 신규)가 8번째 SELECT를 더한다** — `QuizUserSubmitRepository.aggregateAccuracy()` 1회로 그 계정의 `quiz_users_submit` 행 수(`COUNT(*)`)와 정답 행 수(`SUM(is_answer)`)를 **한 쿼리**로 집계한다(`countBy`를 두 번 부르지 않는다). 나눗셈·HALF_UP 반올림은 서비스가 애플리케이션에서 수행하며, 이 SELECT는 제출 행이 0건인 계정과 5,000건인 계정에서 **횟수가 같다**(행을 애플리케이션으로 끌어와 세지 않는다). DTO 조립은 서비스 트랜잭션 안에서 끝난다(`open-in-view: false`인 prod에서 컨트롤러가 지연 로딩 연관을 읽으면 `LazyInitializationException`이 나기 때문).

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

성공 시 `UserAccount.changeNickname(nickname, now)`(`now`는 `LocalDateTime`)가 닉네임 교체와 `nickname_changed_at` 기록을 한 전이로 묶는다(성공했을 때만 시각이 갱신되고, 실패 경로는 전부 이 호출 앞에서 예외로 끝나 계정 값이 요청 전과 완전히 동일하게 남는다).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰 |
| 400 | (Bean Validation, ErrorCode 아님) | 길이 위반(1~10자 초과/미달, 누락·`null` 포함) → `data.nickname`에 `"닉네임은 1~10자여야 합니다."` |
| 400 | (Bean Validation, ErrorCode 아님) | 허용 문자 외 포함 → `data.nickname`에 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."` |
| 400 | SAME_AS_CURRENT_NICKNAME | 요청 닉네임이 현재 자기 닉네임과 완전히 같음 → `data:null`, `message:"현재 닉네임과 다른 닉네임을 사용해 주세요."`(409 `DUPLICATE_NICKNAME`이 **아님** — 자기 닉네임에는 "이미 사용 중"이 거짓이라 신규 코드로 분리) |
| 409 | DUPLICATE_NICKNAME | 다른 계정(탈퇴 계정 포함)이 이미 점유 → `data:null`, `message:"이미 사용 중인 닉네임입니다."` |
| 429 | NICKNAME_CHANGE_COOLDOWN | 마지막 변경으로부터 30일(2,592,000초) 미경과 → **`data`에 `nextChangeableAt` 실림**(아래 참고) |

**429 응답은 이 저장소에서 `data`가 `null`이 아닌 몇 안 되는 `BusinessException` 계열 응답이다**(`BusinessDataException` 하위 타입으로 던져 `GlobalExceptionHandler.handleBusinessData`가 처리 — 자세한 내부 규칙은 [README.md](README.md#1-응답-래퍼--도메인엔드포인트마다-다르다) 참고). `data`의 키는 정확히 `nextChangeableAt` 하나이고, 값은 `+09:00` 오프셋을 포함한 ISO-8601 문자열이다(`yyyy-MM-dd'T'HH:mm:ssXXX` — `ISO_OFFSET_DATE_TIME`을 쓰지 않는 이유는 초가 0일 때 `:00`을 생략해 자릿수가 시각마다 달라지기 때문). 저장 값(`nickname_changed_at`)은 존 없는 `LocalDateTime`이지만, 기록·판정·표기가 전부 `Clock` 빈(Asia/Seoul 고정) 하나에서만 나오므로 운영 파드의 시스템 시간대(UTC)와는 무관하다 — 오프셋은 표기에만 쓰인다. `password_changed_epoch_second`(JWT `iat`라는 외부 절대 시각과 직접 대조되는 값)와 타입이 다른 것도 이 때문이다: 닉네임 쪽은 "마지막 변경으로부터 30일"이라는 자기 자신과의 간격 계산에만 쓰여 epoch로 둘 필요가 없다. 마지막 변경 이력이 없는 계정(`nickname_changed_at`이 `NULL` — 컬럼 도입 이전 계정 포함)은 쿨다운이 적용되지 않는다.

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

## POST /api/users/me/profile-image
> 최종 변경: 2026-08-20 — 신규 추가

내 프로필 이미지를 등록·변경한다. `UserAccountController.uploadProfileImage()` → `AccountProfileImageService.upload()`. **업로드가 곧 변경 확정**이며 별도 확정·취소 단계가 없다 — 성공 응답이 오는 순간부터 `GET /api/users/me`의 `profileImgUrl`이 그 값이다.

**인증 필요** — `Authorization: Bearer <accessToken>`. `/api/users/**`는 `SecurityConfig`에 별도 `permitAll` 줄이 없어 `anyRequest().authenticated()`에 자연히 걸린다. ⚠ 여기에 `permitAll` 줄을 추가하는 것은 버그다(`/api/games/support` 선례와 같은 함정).

**대상 계정은 access 토큰에서만 정해진다.** 경로·본문 어디에도 계정 식별자가 없다.

**요청**: `multipart/form-data`, 파트 1개.

| 파트 | 타입 | 필수 | 설명 |
|---|---|---|---|
| image | 파일 | 예 | 업로드할 이미지. `@RequestPart(required = false)`로 받아 "파트 없음"과 "파트 이름이 다름"을 같은 400으로 흡수한다. `appId`는 받지 않는다 — 함께 보내도 무시된다(비인증 경로만의 한도이기 때문) |

**허용 형식·크기는 [`POST /api/auth/profile-image`](auth.md#post-apiauthprofile-image)와 완전히 동일하다**(JPEG·PNG·WebP 3종, 매직 넘버 판정, 최대 5MiB, 서버가 UUID v4로 파일명 생성, 바이트 무변형 저장 — `ProfileImagePolicy`/`ProfileImageFormat`을 공유). 다른 점은 저장 위치뿐이다: 이 경로는 **`temp/`를 경유하지 않고 처음부터 `user-profile-img/`에 저장**하며(`AccountProfileImageService.upload()`), `appId` 기반 10회/30분 한도가 적용되지 않는다(인증된 요청이라 이미 계정 단위로 식별된다).

**처리 순서(계약)**: ①S3에 새 객체 저장 → ②`users_account.profile_img_url`을 새 EP로 교체(이 단계까지가 응답을 결정) → ③커밋 이후 직전 객체를 best-effort로 삭제.

- ①이 실패하면 컬럼은 손대지 않은 채 5xx다 — 저장에 실패한 이미지가 프로필이 되는 일은 없다.
- ②까지 성공하면 **응답은 200**이다. ③(직전 객체 삭제)은 응답 이후의 부수 작업이라 **실패해도 응답을 바꾸지 않는다** — 옛 객체는 참조 없이 남고(고아), ERROR 로그만 남는다(재시도·보류 큐 없음). 첫 업로드(직전 값이 `null`)는 애초에 삭제를 시도하지 않는다.

**응답 200 OK** `ApiResponse<ProfileImageResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data.profileImgUrl | String | 새로 저장된 객체의 EP. 형태는 `user-profile-img/{uuid}.{jpg\|png\|webp}` — `GET /api/users/me`가 이후 반환하는 값과 문자 그대로 동일 |

```json
{"success":true,"data":{"profileImgUrl":"user-profile-img/1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d.png"},"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰 |
| 400 | PROFILE_IMAGE_REQUIRED | `image` 파트가 없거나 이름이 다르거나 0바이트 |
| 400 | INVALID_PROFILE_IMAGE_FORMAT | 파일 선두 바이트가 JPEG·PNG·WebP 어느 것도 아님 |
| 413 | PROFILE_IMAGE_TOO_LARGE | 이미지가 5MiB 초과(공유 `GlobalExceptionHandler`, `ApiResponse` 래퍼 붙음) |
| 415 | (`ApiResponse` 래퍼, ErrorCode 없음) | `Content-Type`이 `multipart/form-data`가 아님(2026-08-20 신설 공유 핸들러) |

S3 저장(①) 자체가 실패하면 500이다 — `GlobalExceptionHandler.handleUnexpected`(catch-all, 2026-08-20 신설)가 원인 예외를 잡아 `ErrorCode.INTERNAL_SERVER_ERROR`로 통일해 `ApiResponse` 래퍼가 붙은 500으로 응답한다(원인 예외 클래스명은 응답에 실리지 않고 서버 로그에만 남는다 — [auth](auth.md#post-apiauthprofile-image)와 같은 방식).

**예시**
```bash
curl -i -X POST http://localhost:8080/api/users/me/profile-image \
  -H 'Authorization: Bearer eyJ...' \
  -F 'image=@/path/to/photo.png;type=image/png'
```

형식 위반 예시(400):
```json
{"success":false,"data":null,"message":"JPG, PNG, WEBP 이미지만 업로드할 수 있습니다."}
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

- [인증(auth)](auth.md) — 탈퇴가 login/refresh/signup 응답에 미치는 영향의 반대편 서술. signup이 `users_bq` 행을 함께 만드는 부수 효과도 그쪽 문서 참고. `PATCH /api/users/me/password`가 재사용하는 `TokenResponse`도 이쪽 문서(로그인·재발급)에서 정의된다. `POST /api/auth/profile-image`(가입 전, 비인증, `temp/`)는 이 문서의 `POST /api/users/me/profile-image`(가입 후, 인증, `user-profile-img/`)와 짝을 이룬다.
- [구단(team)](team.md) — `supportTeam` 필드가 재사용하는 `TeamResponse` 정의.
- [선수(player)](player.md) · [응원(support)](support.md) — `supportPlayers` 필드가 재사용하는 `PlayerResponse` 정의. **`PlayerResponse`를 바꾸면 `GET /players`·응원 API 2개·이 엔드포인트 총 4곳이 함께 바뀐다.**
- [채팅(chat)](chat.md) — `MessageResponse`/`MessageEvent`의 `profileImgUrl`이 이 문서의 `profileImgUrl`과 같은 값·같은 형태를 재사용한다(둘 다 `users_account.profile_img_url` 출처).
- [퀴즈(quiz)](quiz.md) — `GET /rt/quizzes/submissions`의 `summary.accuracy`가 이 문서의 `quizAccuracy`와 이름은 비슷하지만 **자릿수·범위가 다른 별개 값**이다(반올림 없는 double·경기 한 건 대 소수 셋째 자리·전 기간 누적). 통일하지 않는다.
- 요구사항: `docs/requirements/user/withdraw.md`, `docs/requirements/user/me-profile.md`(USER-ME-1~44, 2026-09-03 개정으로 `quizAccuracy` 신설(USER-ME-37~44) 및 USER-ME-13·20·22 정정(키 9개·SELECT 8회) — 2026-08-06 2차 개정 당시의 `supportPlayers` 추가·상한 무관 서술도 여전히 이 문서), `docs/requirements/user/profile-edit.md`(USER-PE-1~49, 승인됨 2026-08-17 — `PATCH /me/nickname`·`PATCH /me/password`의 출처. USER-PE-32는 폐기 표기 — 아래 문서로 대체됨), `docs/requirements/user/access-token-invalidation.md`(USER-ATI-1~22, 승인됨 2026-08-17 — `PATCH /me/password`의 토큰 즉시 무효화 계약의 출처), `docs/requirements/user/profile-image.md`(승인됨 2026-08-20, USER-PI-1~121 — `GET /me`의 `profileImgUrl`·`POST /me/profile-image`의 출처), `docs/requirements/user/character-shop.md`(승인됨 2026-08-28, USER-CS-1~37 — `characterImgUrl`·`characterItems`의 출처)
