# API 명세 — 도메인별 문서

> 최종 업데이트: 2026-08-17 — **비밀번호 변경이 그 이전에 발급된 access·refresh 토큰을 즉시 무효화한다**(`UserAccount.passwordChangedEpochSecond`, PR #425, `main` 84f6f4a 머지 완료 — **이전 라운드가 남긴 "아직 main 미머지" 경고는 이번 개정으로 전부 제거**). 공통 인증 규약(#2 JWT 판정·#3 401 정책)에 반영: 비밀번호 변경 이전에 발급된 access 토큰은 401 `UNAUTHENTICATED`(토큰이 아예 없을 때와 응답이 **문자 그대로 동일** — 클라이언트는 "만료"와 "비밀번호 변경으로 인한 무효화"를 응답만으로 구분할 수 없다), refresh 토큰은 401 `EXPIRED_REFRESH_TOKEN`이다. 유일한 예외는 `permitAll`이면서 토큰을 읽는 `GET /api/players` — 무효화된 토큰은 401이 아니라 헤더 없음과 동일하게 200(응원 구단 오버라이딩도 안 걸림). 공유 필터(`web-support`)라 quiz(8081)의 인증 경로에도 동일 적용된다. 남는 한계 둘: ①변경과 **같은 초**에 발급된 토큰은 통과(≤1초 창, 자기 무효화 방지의 대가) ②**로그아웃은 여전히 access 토큰을 죽이지 않는다**(refresh만 끊김, 최대 3h 그대로). 비밀번호를 한 번도 안 바꾼 기존 계정은 기준값이 없어 이 검사를 건너뛴다(배포로 기존 세션이 일괄 로그아웃되지 않았다는 뜻). 반영 문서: [account.md](account.md#patch-apiusersmepassword)(`PATCH /me/password`) · [auth.md](auth.md#post-apiauthrefresh)(`POST /refresh`) · [quiz.md](quiz.md). (직전: 같은 날 account 도메인에 `PATCH /api/users/me/nickname`·`PATCH /api/users/me/password`(내 프로필 수정) 신규 추가(브랜치 `hwannee/be/feat-edit-profile`, 이후 `main`에 머지됨). `:common`에 `BusinessDataException` 신설로 **"`BusinessException` 응답의 `data`는 항상 `null`"이던 공통 규약이 바뀌었다** — `BusinessDataException`으로 던지는 응답만 `data`를 싣고(현재 유일한 사례: 닉네임 쿨다운 429), 나머지 `BusinessException`은 여전히 `data:null`이다. "어떤 엔드포인트도 uid를 노출하지 않는다"류 문구도 "키로는 노출하지 않되, 토큰을 돌려주는 응답은 payload 디코드로 uid를 읽을 수 있다"로 정정(로그인·재발급이 이미 갖던 성질이라 이번에 새로 생긴 노출은 아니다). 도메인 인덱스의 account 행 엔드포인트 수 2→4, 총 엔드포인트 수 31→33. (직전: 2026-08-13 game 도메인에 `GET /api/games/support` 신규 추가(내 활성 응원 구단이 홈 또는 원정으로 참여한 경기만, **이 도메인 최초의 인증 필수 엔드포인트** — `GET /api/games`·`GET /api/games/lineup`은 여전히 무인증). 도메인 인덱스의 game 행 `인증` 열을 "불필요(GET 한정)"에서 "혼합"으로 정정, 엔드포인트 수 2→3, 총 엔드포인트 수 30→31. (직전: 같은 날 공통 규약 정정: `web-support`의 `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 핸들러가 추가돼, **필수 쿼리 파라미터 누락(400)이 이제 `ApiResponse` 래퍼를 탄다**(공유 컴포넌트라 user·quiz 양쪽 적용). 종전엔 타입 변환 실패와 한 덩어리로 "바인딩 실패는 래퍼 아님"이라 서술했으나 이제 둘이 갈린다 — 타입 변환 실패는 여전히 래퍼가 아니다. game `GET /api/games/lineup`의 `gameId` 누락 400 서술을 이 실측으로 정정(직전: 같은 날 game·player 문서의 관련 서술은 이번 개정으로 함께 정정됨). (직전: 2026-08-12 quiz `GET /today`에 `gameId`(내부 PK가 아니라 `games.naver_game_id` 문자열) 필수 쿼리 파라미터가 신설됨(5차 개정). 응원 구단 경기가 `IN_PROGRESS`일 때만 세트를 주고 그 외 사유는 전부 403 `QUIZ_NOT_SERVABLE`로 합쳐지며, "한 이닝에 한 세트" 회차 제한이 신설돼 같은 이닝 재요청은 409 `QUIZ_ALREADY_SERVED_IN_INNING`이다. **재조회가 폐지됨**(가장 큰 FE 영향) — 종전엔 시한이 남은 미답 문제는 다시 호출해도 계속 응답에 실렸으나, 이제 행이 있는 문제는 답 여부·시한과 무관하게 전부 제외되고 FE가 받은 세트를 잃으면 되받을 수 없다. 8분 시한은 이제 제출 경로 전용(목록 재조회와 무관). 빈 배열의 뜻도 좁아짐("지금은 줄 수 없다"가 전부 403·409로 빠짐). 응답 필드·정렬·성공 상태코드(200)·다른 4개 엔드포인트는 불변. (직전: 같은 날 앞선 4차 이하 개정 — quiz 제출 자격 증명의 근거가 Redis 티켓에서 `quiz_users_submit` DB 행(미답 행)으로 전면 교체됨. `GET /today`가 서빙과 동시에 미답 행을 만드는 쓰기 트랜잭션이 됐다. `GET /{quizId}`·`GET /submissions`에 `expired` 필드 신설, `submitted`의 의미가 "받음"→"답함"으로 재정의, `myOption`/`myOptionText` nullable 완화, `submittedAt`이 `updated_at` 기준으로 재정의, 이력 요약이 미답 문제를 오답으로 집계. `POST /{quizId}/submit`은 판정 순서가 404→409→403→400에서 404→403→400→409로 바뀌어 "이미 답한 문제에 없는 보기 번호" 응답이 409→400으로 바뀜). (직전: 같은 날 최초 설계는 Redis 제출 자격 티켓(TTL 8분) 기반이었으나 위와 같이 DB 행 기반으로 대체됨). (직전: 2026-08-11 quiz 좋아요 기능 신설(`POST /rt/quizzes/{quizId}/like` 신규, 4→5필드, 단건 상세·풀이 이력 응답에 `liked`·`likeCount` 필드 추가). (직전: 같은 날 game `GET /api/games` 응답에 `inning`/`inningHalf` 필드 반영(11→13필드, `games` 테이블에 진행 이닝 컬럼 신설, 현재는 py-collector 미구현으로 항상 `null`). (직전: 같은 날 `cancelReason` 필드 반영(10→11필드, 커밋 f01d08e #281). (직전: 2026-08-10 quiz `/today` 정렬 방식 변경(선호 그룹 안에서 id ASC → 사용자별 고정 랜덤, 응답 필드·상태코드 불변). (직전: 2026-08-08 quiz 도메인 확장(1→4) — 단건 상세·제출/채점·풀이 이력 추가, 선호 정렬·`preferredOnly` 필터 추가.)))))))))

이 디렉터리는 **도메인 단위**로 나뉜다. 이전에는 Gradle 모듈 단위(`user.md`, `quiz.md`) 두 문서에 모든 엔드포인트가 들어 있었으나, 한 문서가 900줄을 넘고 서로 무관한 도메인(인증·구단·선수·경기·응원)이 뒤섞여 찾기 어려워졌다. **모듈은 배포 단위일 뿐 API 계약의 경계가 아니라는 판단**으로 문서 축을 도메인으로 바꿨다.

## 도메인 인덱스

| 도메인 | 문서 | 소속 모듈 | 경로 접두사 | 엔드포인트 | 인증 | 최종 업데이트 | Notion |
|---|---|---|---|---|---|---|---|
| 인증 | [auth.md](auth.md) | user | `/api/auth` | 9 | 전부 불필요 | 2026-08-17 | [🔗](https://app.notion.com/p/3b278fa9b0f981b39166c408778394e9) |
| 계정 | [account.md](account.md) | user | `/api/users` | 4 | 필수 | 2026-08-17 | [🔗](https://app.notion.com/p/3b278fa9b0f981f8b5bcf163fc897b12) |
| 구단 | [team.md](team.md) | user | `/api/teams` | 1 | 불필요(GET 한정) | 2026-07-28 (추정) | [🔗](https://app.notion.com/p/3b278fa9b0f981859999f42bfc4dd56b) |
| 선수 | [player.md](player.md) | user | `/api/players` | 1 | 불필요(GET 한정, 단 로그인 시 결과가 달라짐) | 2026-08-06 | [🔗](https://app.notion.com/p/3b278fa9b0f981afb501f9e94e1f32f4) |
| 경기 | [game.md](game.md) | user | `/api/games` | 3 | 혼합(`GET`·`GET /lineup` 불필요, `GET /support` 필수) | 2026-08-13 | [🔗](https://app.notion.com/p/3b278fa9b0f981938659cb3681750105) |
| 응원 | [support.md](support.md) | user | `/api/support` | 3 | 필수 | 2026-08-06 | [🔗](https://app.notion.com/p/3b278fa9b0f981f5ae03ff5df8489a63) |
| 채팅 | [chat.md](chat.md) | quiz | `/rt/chat` | 7 | 필수 | 2026-08-17 | [🔗](https://app.notion.com/p/3b278fa9b0f98165a655fd5cced543d5) |
| 퀴즈 | [quiz.md](quiz.md) | quiz | `/rt/quizzes` | 5 | 필수 | 2026-08-17 | [🔗](https://app.notion.com/p/3b578fa9b0f981c4b09bd8752fb22711) |

`최종 업데이트`는 **계약이 마지막으로 바뀐 날**이지 문서를 손댄 날이 아니다. `(추정)`은 도메인 분리 이전에 엔드포인트별 이력이 없어 해당 컨트롤러의 마지막 커밋 날짜로 역산했다는 뜻이다.

**총 33개 엔드포인트.** 도메인 이름은 코드의 패키지 구조(`com.skhynix.user.<domain>`, `com.skhynix.quiz.<domain>`)와 1:1로 대응한다 — 새 도메인 패키지가 생기면 이 디렉터리에도 같은 이름의 문서가 하나 생긴다.

## base URL과 context-path

| 모듈 | 포트 | `server.servlet.context-path` | 로컬 base URL |
|---|---|---|---|
| user | 8080 | `/api` | `http://localhost:8080` |
| quiz | 8081 | `/rt` | `http://localhost:8081` |

컨트롤러의 `@RequestMapping`은 접두사 없는 자원 경로만 갖고, 실제 외부 경로에는 context-path가 항상 붙는다(컨테이너가 필터 체인 이전에 접두사를 떼기 때문). base URL 자체(`http://localhost:8080`)는 context-path와 무관하다. 이 문서들의 모든 경로는 **접두사를 포함한 실제 외부 경로**로 표기한다.

운영에서는 `https://victoryfairy.com`이 ALB → EKS 파드로 붙으며, ALB는 경로 rewrite를 하지 않으므로 Ingress path와 context-path가 문자 그대로 일치한다.

---

## 공통 규약

아래 세 절은 여러 도메인에 걸쳐 동일하게 적용된다. 도메인 문서에서 반복하지 않고 여기를 참조한다.

### 1. 응답 래퍼 — 도메인·엔드포인트마다 다르다

`ApiResponse<T>`(`:common`) = `{ success, data, message }`.

| 범위 | 성공 응답 | 비고 |
|---|---|---|
| chat 6개 | `ApiResponse<T>` | SSE 구독만 예외(`SseEmitter`, JSON 래핑 안 함) |
| auth의 validate·email 계열 5개 | `ApiResponse<T>` | |
| auth의 signup/login/refresh/logout 4개 | **raw**(`ResponseEntity<T>` 직접 반환) | `ApiResponse`로 감싸지 않는다 |
| account의 회원탈퇴(DELETE) | **raw**, 본문 없음(204) | |
| account의 내 프로필 조회(GET) · team·player·game·support | `ApiResponse<T>` | |

**에러 응답은 (아래 예외를 빼면) 전부 `ApiResponse`로 감싸인다** — `GlobalExceptionHandler`(`@RestControllerAdvice`)가 변환한다. 즉 user 모듈의 auth·account 일부는 "성공은 raw, 실패는 ApiResponse"인 비대칭 구조다.

- 비즈니스 예외(`BusinessException`) → `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`.
- **`data`가 항상 `null`인 것은 아니다(2026-08-17부터, `hwannee/be/feat-edit-profile` 브랜치).** `BusinessException`은 `ErrorCode`(status·message)만 들고 있어 그 응답의 `data`는 여전히 예외 없이 `null`이다. 다만 `:common`에 그 하위 타입 `BusinessDataException`(`Object data` 보유)이 신설돼, **이 타입으로 던진 예외만** `data`에 도메인 값을 싣는다 — `@ExceptionHandler`가 가장 구체적인 타입([`GlobalExceptionHandler.handleBusinessData`](../../web-support/src/main/java/com/skhynix/websupport/error/GlobalExceptionHandler.java))을 고르는 원리라, 기존 `BusinessException` 응답들의 `data:null` 계약은 그대로 유지된다. 현재 실사용처는 [account 도메인](account.md)의 `PATCH /api/users/me/nickname` 쿨다운 429(`NICKNAME_CHANGE_COOLDOWN`) 하나뿐이다: `{ "success": false, "data": {"nextChangeableAt": "2026-09-16T14:03:21+09:00"}, "message": "닉네임은 30일에 한 번만 변경할 수 있습니다." }`.
- Bean Validation 실패(`MethodArgumentNotValidException`) → `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 400. `data`에는 **위반한 필드만** 담긴다.
- **쿼리 파라미터 바인딩 실패는 두 갈래로 갈린다(2026-08-13부터).** 둘 다 컨트롤러 진입 전 바인딩 단계에서 400이 나지만 경로가 다르다.
  - **타입 변환 실패**(`?teamId=abc`(player)·`?date=20260801`(game)처럼 값은 있는데 파싱이 안 됨) → 여전히 `ApiResponse` 래퍼가 **아니다**. `MethodArgumentTypeMismatchException`이 `GlobalExceptionHandler`를 타지 않고 Spring 기본 `DefaultHandlerExceptionResolver`가 400을 만든다.
  - **필수 파라미터 자체가 없음**(`gameId` 키 없이 `GET /api/games/lineup` 호출 등) → **이제 `ApiResponse` 래퍼를 탄다.** `web-support`의 `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 핸들러가 추가돼(`@ExceptionHandler(MissingServletRequestParameterException.class)`), `{ "success": false, "data": null, "message": "필수 요청 파라미터가 누락되었습니다: <파라미터명>" }`을 400으로 반환한다. 공유 컴포넌트(`web-support`)라 user·quiz 두 앱 모두에 적용된다.

quiz 모듈은 `SecurityConfig`가 `web-support`의 `GlobalExceptionHandler`를 `@Import`로 **명시 등록**해 이 변환이 이루어진다(좁은 컴포넌트 스캔 범위 밖이라 자동 감지되지 않는다 — 이 import가 빠지면 `BusinessException`이 스프링 기본 500으로 나간다).

### 2. 인증 방식 (JWT)

JWT HS256. `JwtTokenProvider`가 access(3h, 10800000ms)/refresh(14d, 1209600000ms) 토큰을 발급하며 claim `type: access|refresh`로 구분한다.

**무인증으로 열린 경로는 다음이 전부다:**

| 경로 | 범위 |
|---|---|
| `/api/auth/**` | 메서드 무관 전체 `permitAll` |
| `GET /api/teams`·`/players`·`/games`·`/games/lineup` | **GET만** `permitAll` |
| user·quiz 양쪽의 `/`, `/error`, `GET /actuator/health/**` | 문서화 대상 아님(ALB 타깃 헬스체크용). (과거 기록, 정정됨) 이전 버전 문서는 이 행을 `GET /health`로 적었으나 그 경로엔 핸들러가 없어 항상 404였다 — 실제 매처는 두 앱 `SecurityConfig` 모두 `GET /actuator/health/**`이며, 이 permitAll 자체는 새로 바뀐 게 아니라 표기만 틀려 있었다 |

그 외 전부 `anyRequest().authenticated()`다. **GET 한정 `permitAll` 경로에 비-GET으로 요청하면 405가 아니라 401이다**(컨트롤러에 도달하지 못하고 인증 단계에서 걸림).

**토큰 payload 구조** (login/refresh가 발급하는 accessToken/refreshToken 공통 — JWT는 서명만 되고 암호화는 안 되므로 base64 디코드만으로 누구나 읽을 수 있음):
```json
{"jti":"72c6e5fa-0e33-4537-9d50-72ae3bd9a3c8","sub":"36f050ef-321a-413e-8f87-998b2031ec69","type":"access","iat":1784272512,"exp":1784283312}
```

| claim | 의미 |
|---|---|
| `sub` | **`UserAccount.uid`(UUID v4)**. `JwtTokenProvider.createToken()`이 subject로 uid만 싣는다 — 내부 PK `id`는 어떤 claim에도 담기지 않는다(순차 PK 열거 방지가 목적) |
| `type` | `access` \| `refresh`. `isRefreshToken()`이 이 claim으로 판정 |
| `jti` | 토큰(발급 건)마다 랜덤 생성되는 UUID. **계정/사용자 식별자가 아니다** — `sub`와 혼동하지 말 것 |
| `iat` / `exp` | 발급/만료 시각(epoch seconds) |

`JwtAuthenticationFilter`는 요청마다 `sub`(uid)를 `UserAccountRepository.findActiveIdByUid()`로 **활성(`exit_at IS NULL`) 계정의** 내부 `id`로 변환해 그 `id`를 principal로 사용한다(uid에 해당하는 활성 계정이 없으면 — 존재하지 않거나 **탈퇴한 계정이면** — 인증 없이 그대로 통과). 컨트롤러는 이를 `@AuthenticationPrincipal Long userAccountId`로 받는다.

**이 API 전체에서 어떤 엔드포인트도 응답 본문에 `uid`를 키로 노출하지 않는다.** `uid`는 토큰의 `sub` 클레임 안에만 존재한다. 다만 JWT payload는 서명만 되고 암호화되지 않으므로, **토큰을 돌려주는 모든 엔드포인트**(로그인·재발급·2026-08-17부터는 [account](account.md)의 `PATCH /api/users/me/password`도 포함)에서 `accessToken`/`refreshToken`을 base64 디코드하면 `sub`에서 uid를 읽을 수 있다 — 이는 응답 body의 **키**로 노출되는 것과는 다르며, 토큰을 이미 가진 요청자 본인만 읽을 수 있다(제3자에게 uid를 흘리지 않는 것이 정책의 목적이지, 토큰 소지자 본인에게 감추는 것이 목적이 아니다).

**토큰 무효화 — 비밀번호 변경 이전에 발급된 토큰(2026-08-17부터, `UserAccount.passwordChangedEpochSecond`).** access 토큰 검증은 서명·만료(`exp`)만 보는 stateless 판정이었으나, 이제 한 겹이 더 있다. `UserAccountRepository.findActiveAuthByUid()`가 활성 여부(`exit_at is null`)와 함께 그 계정의 `passwordChangedEpochSecond`(마지막 비밀번호 변경 시각, epoch 초)를 **같은 조회**로 실어 오고(요청당 조회 증가 없음), `JwtAuthenticationFilter`는 토큰의 `iat`가 이 값보다 **앞선 초**면 활성 계정을 찾았어도 principal을 채우지 않는다 — 즉 **비밀번호 변경 이전에 발급된 access 토큰은 그 순간부터, 남은 유효기간(최대 3h)과 무관하게 인증되지 않는다.** `AuthService.reissue()`도 refresh 토큰의 `iat`를 같은 값과 대조해 재발급을 거절한다(탈퇴 검사 바로 다음 단계). 두 판정 모두 **전용 에러 코드·메시지가 없다** — access는 토큰이 아예 없을 때와 **문자 그대로 동일한** 401 `UNAUTHENTICATED`, refresh는 기존 401 `EXPIRED_REFRESH_TOKEN`을 그대로 쓴다. **클라이언트는 응답만으로 "토큰 만료"와 "비밀번호 변경으로 인한 무효화"를 구분할 수 없다** — FE는 두 경우를 항상 같은 방식(재로그인 유도, refresh는 재발급 시도 후 실패 시 재로그인)으로 처리해야 한다.

**예외 — `GET /api/players`.** `permitAll`이면서 토큰을 읽는 이 경로는 무효화된 토큰을 401이 아니라 **`Authorization` 헤더가 없는 요청과 동일하게 처리**한다(응원 구단 오버라이딩도 걸리지 않는다) — 필터가 principal을 못 채워도 이 경로는 애초에 인증을 요구하지 않으므로 그대로 통과한다.

**닫히지 않은 한계 둘**: ①비밀번호 변경과 **같은 초**에 이미 발급돼 있던 이전 토큰(access·refresh 모두)은 살아남는다(`UserAccount.acceptsTokenIssuedAt`이 `>=`로 판정 — `iat`가 초 단위로 내려오므로 `>`로 엄격 비교하면 변경 응답으로 방금 준 새 토큰이 자기 자신에게 거절된다. ≤1초 창은 그 자기 무효화를 막기 위한 의도된 대가). ②**로그아웃은 여전히 access 토큰을 죽이지 않는다** — `passwordChangedEpochSecond`는 비밀번호 변경에서만 갱신되고 로그아웃에서는 갱신되지 않으므로, 로그아웃 후에도 그 access 토큰은 남은 유효기간(최대 3h) 동안 계속 인증된다(refresh만 끊긴다). 또한 **비밀번호를 한 번도 바꾼 적 없는 계정**은 `passwordChangedEpochSecond`가 `NULL`이라 이 검사 자체를 건너뛴다(fail-open이 아니라 "무효화할 사건이 없었다"는 뜻) — 2026-08-17(`main` 84f6f4a) 배포 시점에 기존 로그인 세션이 일괄 로그아웃되지 않았다는 뜻이다. `PATCH /api/users/me/password`가 돌려주는 새 토큰 쌍이 이 대조를 항상 통과하는 유일하게 보장된 토큰이며, 그 계정이 그 이전에 갖고 있던 access·refresh 토큰은(위 ≤1초 창을 빼면) 전부 무효화된다 — 자세한 내용은 [account.md](account.md#patch-apiusersmepassword). 공유 필터(`web-support`)라 **quiz(8081)의 인증 경로에도 동일 적용**된다(`/rt/**` 포함).

### 3. 401 정책 — 상태 코드만으론 구분되지 않는다

**미인증 요청 → 401**(403 아님). `RestAuthenticationEntryPoint`(`web-support`)가 `ExceptionTranslationFilter` 단계에서 직접 `ApiResponse` JSON을 직렬화해 401로 응답한다(`SecurityConfig`가 `exceptionHandling().authenticationEntryPoint(...)`로 명시 등록). 실측 원문(user:8080·quiz:8081 양쪽 확인):
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```
`formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`, 403)으로 떨어지는 함정이 있었는데, `RestAuthenticationEntryPoint`를 명시 등록해 401로 고정했다.

**401이 4종류이며 `message`로만 구분된다:**

| 상황 | message | ErrorCode | 도메인 |
|---|---|---|---|
| 토큰 없음/무효/`sub`(uid)에 해당하는 **활성** 계정 없음 — 미가입이거나 **탈퇴한 계정**, 또는 **비밀번호 변경 이전에 발급된 access 토큰**(2026-08-17부터, 위 "토큰 무효화" 참고 — 세 경우 모두 응답이 완전히 동일해 구분 불가) (필터·엔트리포인트 단계) | `"인증이 필요합니다."` | `UNAUTHENTICATED` | 전 도메인 |
| 로그인 자격 오답 또는 **해당 이메일 계정이 탈퇴함**(비밀번호 정답 여부 무관, 미가입 이메일과 응답 완전히 동일) | `"이메일 또는 비밀번호가 올바르지 않습니다."` | `INVALID_CREDENTIALS` | [auth](auth.md) |
| refresh 서명/만료 무효 또는 access 토큰 오용 | `"유효하지 않은 리프레시 토큰입니다."` | `INVALID_REFRESH_TOKEN` | [auth](auth.md) |
| DB에 없거나 이미 만료된 refresh 토큰, **탈퇴한 계정의 refresh 토큰**, 또는 **비밀번호 변경 이전에 발급된 refresh 토큰**(2026-08-17부터 — 세 경우 모두 응답 동일) | `"만료되었거나 이미 무효화된 리프레시 토큰입니다."` | `EXPIRED_REFRESH_TOKEN` | [auth](auth.md) |

발생 경로는 둘로 나뉜다: `UNAUTHENTICATED`는 `RestAuthenticationEntryPoint`가 필터 단계(`DispatcherServlet` 바깥)에서 직접 직렬화하고, 나머지 3개는 컨트롤러가 던진 `BusinessException`을 `GlobalExceptionHandler`가 잡아 변환한다. 클라이언트 입장에서 이 구분이 중요한 이유: `UNAUTHENTICATED`는 "로그인하거나(토큰이 아예 없거나 계정이 사라짐) `/api/auth/refresh`로 access 토큰을 새로 받으라"는 신호이고, 나머지 셋은 각각 로그인 폼 재입력, refresh 자체의 재로그인 유도로 이어져야 한다는 뜻이다.

**403은 인증 실패로는 발생하지 않는다.** `AccessDeniedHandler`는 의도적으로 미도입 — `JwtAuthenticationFilter`가 인증된 principal의 권한을 항상 `Collections.emptyList()`로 채워 authority 기반 403이 발생할 경로 자체가 없다. 이 API 전체의 403은 [chat](chat.md)의 `SELF_REPORT_NOT_ALLOWED`(자기 메시지 신고)·`CHATROOM_TEAM_MISMATCH`(2026-08-04 신규, 응원 구단이 다른 채팅방 접근)와 [quiz](quiz.md)의 `QUIZ_LIKE_NOT_ALLOWED`(2026-08-11 신규, 제출하지 않은 문제에 좋아요 요청 — 미존재·미편성 풀과 구분 불가)·`QUIZ_SUBMIT_NOT_ALLOWED`(2026-08-12 신규, 제출 자격 없음 — `/today`로 받은 적 없거나(DB 미답 행 부재) 받았지만 8분 시한 경과, 구분 불가. 판정 근거는 같은 날 안에서 Redis 티켓에서 `quiz_users_submit` DB 행으로 바뀌었다)·`QUIZ_NOT_SERVABLE`(2026-08-12 신규, 지목한 경기가 문제를 줄 수 있는 상태가 아님 — 사유 비공개)·`GAME_NOT_STARTED`(2026-08-13 신규, 아직 시작하지 않은 경기의 결산 조회) 여섯뿐이며, 전부 인증이 아니라 도메인 규칙에서 나온다.

---

## 문서 갱신 규칙

- 엔드포인트가 추가·변경되면 **해당 도메인 문서 하나만** 고친다. 여러 도메인에 걸친 변경(예: 인증 정책)만 이 README를 함께 고친다.
- 새 도메인 패키지가 생기면 이 디렉터리에 `<domain>.md`를 만들고 위 인덱스 표에 한 줄 추가한다.
- 각 문서 상단의 `최종 갱신` 줄과 각 엔드포인트의 `최종 변경` 줄에 날짜와 변경 요지를 남긴다. **계약이 실제로 바뀐 것에만 오늘 날짜를 찍는다** — 문서 정리·오타 수정은 "변경 없음"이다.
- springdoc/Swagger는 도입하지 않는다 — 이 마크다운이 단일 출처다.
- 입력 파라미터는 종류별로 표기를 구분한다: 경로 변수는 `**경로 변수**` + 표, 쿼리 파라미터는 `**쿼리 파라미터**` + 표, 요청 본문은 `**요청 본문**`. 셋을 뭉뚱그려 `**요청**`으로 쓰지 않는다([chat.md](chat.md)가 원형).
- **Notion "API 명세서" 페이지는 이 디렉터리의 미러다.** 팀원이 실제로 보는 건 Notion 쪽이므로, 마크다운만 고치고 끝내면 작업이 끝난 게 아니다. 루트 페이지가 이 README에, 도메인 하위 페이지가 각 도메인 문서에 1:1로 대응한다 — <https://app.notion.com/p/3aa78fa9b0f980e6b732ef70a4e9a6bd>
