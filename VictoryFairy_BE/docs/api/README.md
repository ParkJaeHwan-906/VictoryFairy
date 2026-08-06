# API 명세 — 도메인별 문서

> 최종 업데이트: 2026-08-04 — `GET /api/member/games` 응답에 `homeTeamId`/`awayTeamId` 추가 + `GET /api/member/games/lineup`(경기별 선발 라인업) 신규 추가. (직전: `GET /api/member/players` 구단 조건이 응원 구단 우선(토큰 오버라이딩)으로 변경 + `quiz` 채팅에 구단 접근 제어(403 `CHATROOM_TEAM_MISMATCH`) 도입 및 `DELETE /api/game/chat/rooms/{roomUid}/subscribe` 신규 추가.)

이 디렉터리는 **도메인 단위**로 나뉜다. 이전에는 Gradle 모듈 단위(`user.md`, `quiz.md`) 두 문서에 모든 엔드포인트가 들어 있었으나, 한 문서가 900줄을 넘고 서로 무관한 도메인(인증·구단·선수·경기·응원)이 뒤섞여 찾기 어려워졌다. **모듈은 배포 단위일 뿐 API 계약의 경계가 아니라는 판단**으로 문서 축을 도메인으로 바꿨다.

## 도메인 인덱스

| 도메인 | 문서 | 소속 모듈 | 경로 접두사 | 엔드포인트 | 인증 | 최종 업데이트 | Notion |
|---|---|---|---|---|---|---|---|
| 인증 | [auth.md](auth.md) | user | `/api/member/auth` | 9 | 전부 불필요 | 2026-08-04 | [🔗](https://app.notion.com/p/3b278fa9b0f981b39166c408778394e9) |
| 계정 | [account.md](account.md) | user | `/api/member/users` | 2 | 필수 | 2026-08-04 | [🔗](https://app.notion.com/p/3b278fa9b0f981f8b5bcf163fc897b12) |
| 구단 | [team.md](team.md) | user | `/api/member/teams` | 1 | 불필요(GET 한정) | 2026-07-28 (추정) | [🔗](https://app.notion.com/p/3b278fa9b0f981859999f42bfc4dd56b) |
| 선수 | [player.md](player.md) | user | `/api/member/players` | 1 | 불필요(GET 한정, 단 로그인 시 결과가 달라짐) | 2026-08-06 | [🔗](https://app.notion.com/p/3b278fa9b0f981afb501f9e94e1f32f4) |
| 경기 | [game.md](game.md) | user | `/api/member/games` | 2 | 불필요(GET 한정) | 2026-08-04 | [🔗](https://app.notion.com/p/3b278fa9b0f981938659cb3681750105) |
| 응원 | [support.md](support.md) | user | `/api/member/support` | 3 | 필수 | 2026-08-06 | [🔗](https://app.notion.com/p/3b278fa9b0f981f5ae03ff5df8489a63) |
| 채팅 | [chat.md](chat.md) | quiz | `/api/game/chat` | 7 | 필수 | 2026-08-04 | [🔗](https://app.notion.com/p/3b278fa9b0f98165a655fd5cced543d5) |

`최종 업데이트`는 **계약이 마지막으로 바뀐 날**이지 문서를 손댄 날이 아니다. `(추정)`은 도메인 분리 이전에 엔드포인트별 이력이 없어 해당 컨트롤러의 마지막 커밋 날짜로 역산했다는 뜻이다.

**총 25개 엔드포인트.** 도메인 이름은 코드의 패키지 구조(`com.skhynix.user.<domain>`, `com.skhynix.quiz.<domain>`)와 1:1로 대응한다 — 새 도메인 패키지가 생기면 이 디렉터리에도 같은 이름의 문서가 하나 생긴다.

## base URL과 context-path

| 모듈 | 포트 | `server.servlet.context-path` | 로컬 base URL |
|---|---|---|---|
| user | 8080 | `/api/member` | `http://localhost:8080` |
| quiz | 8081 | `/api/game` | `http://localhost:8081` |

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
- Bean Validation 실패(`MethodArgumentNotValidException`) → `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 400. `data`에는 **위반한 필드만** 담긴다.
- **예외: 쿼리 파라미터 바인딩 실패는 `ApiResponse` 래퍼가 아니다.** `?teamId=abc`(player)·`?date=20260801`(game)처럼 타입 변환이 깨지거나, `gameId` 없이 `GET /api/member/games/lineup`을 호출해 **필수 파라미터 자체가 없는** 경우처럼 컨트롤러 진입 전 바인딩 단계에서 깨지면 `GlobalExceptionHandler`가 아니라 Spring 기본 `DefaultHandlerExceptionResolver`가 400을 만든다.

quiz 모듈은 `SecurityConfig`가 `web-support`의 `GlobalExceptionHandler`를 `@Import`로 **명시 등록**해 이 변환이 이루어진다(좁은 컴포넌트 스캔 범위 밖이라 자동 감지되지 않는다 — 이 import가 빠지면 `BusinessException`이 스프링 기본 500으로 나간다).

### 2. 인증 방식 (JWT)

JWT HS256. `JwtTokenProvider`가 access(3h, 10800000ms)/refresh(14d, 1209600000ms) 토큰을 발급하며 claim `type: access|refresh`로 구분한다.

**무인증으로 열린 경로는 다음이 전부다:**

| 경로 | 범위 |
|---|---|
| `/api/member/auth/**` | 메서드 무관 전체 `permitAll` |
| `GET /api/member/teams`·`/players`·`/games`·`/games/lineup` | **GET만** `permitAll` |
| quiz의 `/`, `/error`, `GET /health` | 문서화 대상 아님 |

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

**이 API 전체에서 어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다.** `uid`는 오직 발급된 토큰의 `sub` 안에만 존재하며, 클라이언트가 이를 응답 body나 URL에서 직접 얻을 방법은 현재 없다.

### 3. 401 정책 — 상태 코드만으론 구분되지 않는다

**미인증 요청 → 401**(403 아님). `RestAuthenticationEntryPoint`(`web-support`)가 `ExceptionTranslationFilter` 단계에서 직접 `ApiResponse` JSON을 직렬화해 401로 응답한다(`SecurityConfig`가 `exceptionHandling().authenticationEntryPoint(...)`로 명시 등록). 실측 원문(user:8080·quiz:8081 양쪽 확인):
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```
`formLogin`/`httpBasic`을 둘 다 disable하면 엔트리포인트를 등록하는 주체가 없어져 Spring Security 기본값(`Http403ForbiddenEntryPoint`, 403)으로 떨어지는 함정이 있었는데, `RestAuthenticationEntryPoint`를 명시 등록해 401로 고정했다.

**401이 4종류이며 `message`로만 구분된다:**

| 상황 | message | ErrorCode | 도메인 |
|---|---|---|---|
| 토큰 없음/무효/`sub`(uid)에 해당하는 **활성** 계정 없음 — 미가입이거나 **탈퇴한 계정** (필터·엔트리포인트 단계) | `"인증이 필요합니다."` | `UNAUTHENTICATED` | 전 도메인 |
| 로그인 자격 오답 또는 **해당 이메일 계정이 탈퇴함**(비밀번호 정답 여부 무관, 미가입 이메일과 응답 완전히 동일) | `"이메일 또는 비밀번호가 올바르지 않습니다."` | `INVALID_CREDENTIALS` | [auth](auth.md) |
| refresh 서명/만료 무효 또는 access 토큰 오용 | `"유효하지 않은 리프레시 토큰입니다."` | `INVALID_REFRESH_TOKEN` | [auth](auth.md) |
| DB에 없거나 이미 만료된 refresh 토큰, 또는 **탈퇴한 계정의 refresh 토큰** | `"만료되었거나 이미 무효화된 리프레시 토큰입니다."` | `EXPIRED_REFRESH_TOKEN` | [auth](auth.md) |

발생 경로는 둘로 나뉜다: `UNAUTHENTICATED`는 `RestAuthenticationEntryPoint`가 필터 단계(`DispatcherServlet` 바깥)에서 직접 직렬화하고, 나머지 3개는 컨트롤러가 던진 `BusinessException`을 `GlobalExceptionHandler`가 잡아 변환한다. 클라이언트 입장에서 이 구분이 중요한 이유: `UNAUTHENTICATED`는 "로그인하거나(토큰이 아예 없거나 계정이 사라짐) `/api/member/auth/refresh`로 access 토큰을 새로 받으라"는 신호이고, 나머지 셋은 각각 로그인 폼 재입력, refresh 자체의 재로그인 유도로 이어져야 한다는 뜻이다.

**403은 인증 실패로는 발생하지 않는다.** `AccessDeniedHandler`는 의도적으로 미도입 — `JwtAuthenticationFilter`가 인증된 principal의 권한을 항상 `Collections.emptyList()`로 채워 authority 기반 403이 발생할 경로 자체가 없다. 이 API 전체의 403은 [chat](chat.md)의 `SELF_REPORT_NOT_ALLOWED`(자기 메시지 신고)와 `CHATROOM_TEAM_MISMATCH`(2026-08-04 신규, 응원 구단이 다른 채팅방 접근) 둘뿐이며, 둘 다 인증이 아니라 도메인 규칙에서 나온다.

---

## 문서 갱신 규칙

- 엔드포인트가 추가·변경되면 **해당 도메인 문서 하나만** 고친다. 여러 도메인에 걸친 변경(예: 인증 정책)만 이 README를 함께 고친다.
- 새 도메인 패키지가 생기면 이 디렉터리에 `<domain>.md`를 만들고 위 인덱스 표에 한 줄 추가한다.
- 각 문서 상단의 `최종 갱신` 줄과 각 엔드포인트의 `최종 변경` 줄에 날짜와 변경 요지를 남긴다. **계약이 실제로 바뀐 것에만 오늘 날짜를 찍는다** — 문서 정리·오타 수정은 "변경 없음"이다.
- springdoc/Swagger는 도입하지 않는다 — 이 마크다운이 단일 출처다.
- **Notion "API 명세서" 페이지는 이 디렉터리의 미러다.** 팀원이 실제로 보는 건 Notion 쪽이므로, 마크다운만 고치고 끝내면 작업이 끝난 게 아니다. 루트 페이지가 이 README에, 도메인 하위 페이지가 각 도메인 문서에 1:1로 대응한다 — <https://app.notion.com/p/3aa78fa9b0f980e6b732ef70a4e9a6bd>
