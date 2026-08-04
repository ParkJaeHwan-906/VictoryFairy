# 계정(account) API 명세

> **도메인** `account` — 로그인 계정 자체의 생명주기(현재는 탈퇴만).
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/users` · **엔드포인트** 1개
> **컨트롤러** `user/src/main/java/com/skhynix/user/account/controller/UserAccountController.java` (`@RequestMapping("/users")`)
> **최종 갱신** 2026-08-04 — 모듈별(`user.md`) 문서를 도메인별로 분리. 계약 변경 없음.
> 공통 규약(응답 래퍼·JWT payload·401 4종)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| DELETE | [/api/member/users/me](#delete-apimemberusersme) | 204 | 회원 탈퇴(soft delete) |

## 이 도메인의 특이사항

**user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**가 여기다. `/api/member/auth/**`는 전부 permitAll이라 탈퇴를 [auth](auth.md) 도메인에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/member/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 규칙에 자연히 포함됨).

**대상 계정은 URL이 아니라 access 토큰에서만 정해진다.** 경로에 식별자가 없고, `@AuthenticationPrincipal Long userAccountId`로 주입된 내부 PK만으로 동작한다.

**탈퇴는 이 도메인 밖으로 파급된다** — [auth](auth.md)의 login/refresh/signup 응답이 모두 바뀐다. 아래 "탈퇴 후 부수 효과" 표 참고.

---

## DELETE /api/member/users/me
> 최종 변경: 2026-07-27 (추정) — 도메인 분리 이전 이력이 없어 `UserAccountController` 마지막 커밋 기준

회원 탈퇴(soft delete). `UserAccountController` → `UserAccountService.withdraw()`.

**인증 필요** — `Authorization: Bearer <accessToken>`. **user 모듈에서 `anyRequest().authenticated()`에 실제로 걸리는 첫 엔드포인트**다([응원(support)](support.md)의 3개가 뒤이어 같은 규칙에 걸린다). [`/api/member/auth/**`](auth.md)는 전부 permitAll이라 탈퇴를 그쪽에 두면 인증이 걸리지 않으므로, 의도적으로 `/api/member/users/me`에 배치했다(`SecurityConfig` 변경 없이 기존 `anyRequest().authenticated()` 규칙에 자연히 포함됨).

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
| 같은 access 토큰으로 `DELETE /api/member/users/me` 재호출 | 401 `UNAUTHENTICATED` | 이 문서 |
| 그 계정의 refresh 토큰으로 `POST /api/member/auth/refresh` | 401 `EXPIRED_REFRESH_TOKEN` | [auth](auth.md#post-apimemberauthrefresh) |
| 그 계정의 이메일 + 정확한 비밀번호로 `POST /api/member/auth/login` | 401 `INVALID_CREDENTIALS` (미가입 이메일과 응답 동일) | [auth](auth.md#post-apimemberauthlogin) |
| 그 계정의 email/tel/nickname으로 `POST /api/member/auth/signup` | 409 `DUPLICATE_EMAIL`/`DUPLICATE_TEL`/`DUPLICATE_NICKNAME` (영구 재가입 불가) | [auth](auth.md#post-apimemberauthsignup) |
| 그 계정의 토큰으로 [응원(support)](support.md)·[채팅(chat)](chat.md)의 인증 필수 엔드포인트 | 401 `UNAUTHENTICATED` | [README](README.md) |

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

## 확인 필요 / 코드 미확인

- **탈퇴 취소(복구) API·하드 딜리트·개인정보 파기 배치는 코드에 없다**(`docs/requirements/user/withdraw.md`가 범위 제외로 명시). `exit_at`은 표식만 남기고 행을 삭제하지 않는다.
- `uid`를 응답 body/URL에 노출하는 엔드포인트는 아직 없다. 이 엔드포인트도 `uid`가 아니라 access 토큰으로만 대상을 식별하며 응답에 아무것도 담지 않는다. 향후 `uid`를 응답에 싣는 변경이 생기면 이 문서와 [README.md](README.md)를 함께 갱신해야 한다.
- 프로필 조회·수정(`GET`/`PATCH /api/member/users/me` 류) 엔드포인트는 아직 없다. 이 도메인에 생길 자리다.
- (과거 기록, 정정됨) 이전 버전 문서에는 "user 모듈에 실제로 인증이 걸리는 엔드포인트는 현재 없다"고 적혀 있었다 — 이 엔드포인트 추가로 더 이상 사실이 아니다.

## 관련 문서

- [인증(auth)](auth.md) — 탈퇴가 login/refresh/signup 응답에 미치는 영향의 반대편 서술.
- 요구사항: `docs/requirements/user/withdraw.md`
