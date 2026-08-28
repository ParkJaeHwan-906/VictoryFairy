# API 명세 — 도메인별 문서

> 최종 업데이트: 2026-08-28 — **`character` 도메인 신설**(캐릭터 꾸미기 상점·인벤토리: `GET /api/characters/items`(상점+인벤토리 **통합** 목록, `having`으로 보유 구분) · `POST /api/characters/items/purchase`(포인트 차감, 1건, 중복 구매 409) · `PUT /api/characters/items/active`(착용 토글, **부위당 하나**)). 함께 `account`의 `GET /api/users/me` 응답에 `characterImgUrl`·`characterItems` 추가(키 6→8, SELECT 5→7). **이미지 값은 전부 `profileImgUrl`과 같은 EP 규칙**(BaseURL 없는 오브젝트 키)이며, ⚠ 상점 진열용(`displayImg`, 80x80)과 캐릭터 착용용(`characterItems[].imgUrl`, 160x200)은 **좌표계가 다른 별개 파일이라 바꿔 쓰면 안 된다.** `users_account.point`의 첫 소비처이기도 하다(적립은 여전히 퀴즈 보상 소유). 총 엔드포인트 36→39개. 계약 원본 `docs/requirements/user/character-shop.md`(승인됨 2026-08-28, USER-CS-1~37). 반영 문서: [character.md](character.md) · [account.md](account.md). ⚠ 이미지가 실제로 서빙되려면 CloudFront behavior 추가(`/characters/*`·`/items/*`·`/stores/*`)가 함께 배포돼야 한다 — `VictoryFairy_Infra` 브랜치 `hwannee/infra/feat-character-asset-cdn`. (직전: 2026-08-26 — **quiz 도메인에 `GET /rt/quizzes/{quizId}/vote-count` 신설**(아직 답하지 않은 문제의 보기별 투표 수를 폴링으로 다시 받는 경로. 종전에는 `/today` 응답 한 번이 분포를 전달하는 유일한 기회였고 갱신 수단이 없었다). 응답 항목은 `/today` 의 `options[]` 와 **같은 타입**(`{no, text, voteCount}`) — 서버가 백분율을 계산해 주지 않는다(개수 그대로). **자격이 없으면(받은 적 없음·이미 제출함·존재하지 않는 quizId) 404·403 이 아니라 200 + `data:null`** 로 합쳐진다(응답 코드로 '그 사람이 그 문제를 받았는지'가 드러나지 않게 하는, 좋아요 단일 403 과 같은 계열의 은닉). quiz 엔드포인트 수 5→6, 총 36개. 기존 엔드포인트의 요청·응답·상태코드는 전부 불변. 반영 문서: [quiz.md](quiz.md). (직전: 2026-08-20 — **프로필 이미지 업로드 기능 도입**과 **공통 시스템 예외 래핑 확장** 두 건. ①`auth`에 `POST /api/auth/profile-image`(비인증, 가입 전 임시 업로드, `temp/` 저장) 신규 + `POST /api/auth/signup` 요청에 선택 필드 `profileImgUrl` 추가(가입 성공 시 `user-profile-img/`로 이동, 이동 실패는 가입을 막지 않고 값만 `null`). ②`account`에 `POST /api/users/me/profile-image`(인증 필수, 업로드가 곧 변경 확정) 신규 + `GET /api/users/me` 응답에 `profileImgUrl` 추가(SELECT 횟수 불변) + `DELETE /api/users/me`(탈퇴)가 커밋 후 프로필 이미지 객체를 best-effort로 삭제하는 부수 효과 추가. ③`chat`의 `MessageResponse`(전송·히스토리)와 SSE `message` 이벤트(`MessageEvent`)에 `profileImgUrl` 추가(탈퇴자 메시지는 `(알수없음)` 더미 계정으로 자연히 `null`). **`profileImgUrl`은 항상 BaseURL을 뺀 EP다**(선행 슬래시·버킷명·`https://` 없음, 값이 없으면 `null` — 빈 문자열도 기본 이미지 URL도 아니다) — 클라이언트가 `https://victoryfairy.com/` + 값을 그대로 이어 붙인다. 도메인 인덱스의 auth(9→10)·account(4→5) 엔드포인트 수 갱신, 총 35개. 계약 원본 `docs/requirements/user/profile-image.md`(승인됨 2026-08-20, USER-PI-1~121). ④**공통 규약 #1(응답 래퍼) 확장**: `web-support`의 `GlobalExceptionHandler`에 415(`HttpMediaTypeNotSupportedException`)·405(`HttpRequestMethodNotSupportedException`)·400(본문 파싱 실패 `HttpMessageNotReadableException`)·400(경로변수·쿼리 타입 불일치 `MethodArgumentTypeMismatchException`)·500(미처리 예외 catch-all) 핸들러가 신설돼, 지금까지 스프링 기본 본문으로 나가던 이 다섯 응답이 전부 `ApiResponse` 래퍼를 탄다(상태 코드는 불변). `:common`에 `INTERNAL_SERVER_ERROR(500)` 신설, 500 응답 본문은 고정 문구뿐이고 예외 클래스명·스택트레이스는 서버 로그에만 남는다. 공유 컴포넌트라 user·quiz 양쪽에 자동 적용되며, 종전에 "타입 변환 실패는 래퍼가 아니다"로 서술했던 [game](game.md)·[player](player.md)·[chat](chat.md)의 관련 절도 이번 개정으로 함께 정정됐다. 자세한 내용은 아래 "공통 규약 1" 참고. (직전: 2026-08-19 **quiz 도메인 `GET /rt/quizzes/today` 응답의 보기 항목에 투표 수 필드 `voteCount` 신설**(각 `options[]` 원소에 0 이상 JSON 정수, 서빙 시점 근사 스냅샷 — Redis 장애·키 부재·TTL 만료 시에도 0으로 채워 200 유지, 갱신 경로 없음, 미제출 상태에서도 노출). 상세·제출·이력 세 응답은 불변. 엔드포인트 5개 그대로. 계약 원본 `docs/requirements/quiz/quiz-vote-exposure.md`(승인됨 2026-08-19). 반영 문서: [quiz.md](quiz.md).) ) ) 그 이전 이력은 각 도메인 문서(auth·account·game·quiz)의 `최종 갱신`·엔드포인트별 `최종 변경` 줄에 남아 있다.

이 디렉터리는 **도메인 단위**로 나뉜다. 이전에는 Gradle 모듈 단위(`user.md`, `quiz.md`) 두 문서에 모든 엔드포인트가 들어 있었으나, 한 문서가 900줄을 넘고 서로 무관한 도메인(인증·구단·선수·경기·응원)이 뒤섞여 찾기 어려워졌다. **모듈은 배포 단위일 뿐 API 계약의 경계가 아니라는 판단**으로 문서 축을 도메인으로 바꿨다.

## 도메인 인덱스

| 도메인 | 문서 | 소속 모듈 | 경로 접두사 | 엔드포인트 | 인증 | 최종 업데이트 | Notion |
|---|---|---|---|---|---|---|---|
| 인증 | [auth.md](auth.md) | user | `/api/auth` | 10 | 전부 불필요 | 2026-08-20 | [🔗](https://app.notion.com/p/3b278fa9b0f981b39166c408778394e9) |
| 계정 | [account.md](account.md) | user | `/api/users` | 5 | 필수 | 2026-08-28 | [🔗](https://app.notion.com/p/3b278fa9b0f981f8b5bcf163fc897b12) |
| 캐릭터 | [character.md](character.md) | user | `/api/characters/items` | 3 | 필수 | 2026-08-28 | [🔗](https://app.notion.com/p/3ca78fa9b0f981a6b87fdd2a9c21c1d3) |
| 구단 | [team.md](team.md) | user | `/api/teams` | 1 | 불필요(GET 한정) | 2026-07-28 (추정) | [🔗](https://app.notion.com/p/3b278fa9b0f981859999f42bfc4dd56b) |
| 선수 | [player.md](player.md) | user | `/api/players` | 1 | 불필요(GET 한정, 단 로그인 시 결과가 달라짐) | 2026-08-20 | [🔗](https://app.notion.com/p/3b278fa9b0f981afb501f9e94e1f32f4) |
| 경기 | [game.md](game.md) | user | `/api/games` | 3 | 혼합(`GET`·`GET /lineup` 불필요, `GET /support` 필수) | 2026-08-20 | [🔗](https://app.notion.com/p/3b278fa9b0f981938659cb3681750105) |
| 응원 | [support.md](support.md) | user | `/api/support` | 3 | 필수 | 2026-08-06 | [🔗](https://app.notion.com/p/3b278fa9b0f981f5ae03ff5df8489a63) |
| 채팅 | [chat.md](chat.md) | quiz | `/rt/chat` | 7 | 필수 | 2026-08-20 | [🔗](https://app.notion.com/p/3b278fa9b0f98165a655fd5cced543d5) |
| 퀴즈 | [quiz.md](quiz.md) | quiz | `/rt/quizzes` | 6 | 필수 | 2026-08-26 | [🔗](https://app.notion.com/p/3b578fa9b0f981c4b09bd8752fb22711) |

`최종 업데이트`는 **계약이 마지막으로 바뀐 날**이지 문서를 손댄 날이 아니다. `(추정)`은 도메인 분리 이전에 엔드포인트별 이력이 없어 해당 컨트롤러의 마지막 커밋 날짜로 역산했다는 뜻이다. game·player·chat·quiz의 2026-08-20 갱신은 엔드포인트 추가가 아니라 **공통 시스템 예외 래핑 확장**(아래 "공통 규약 1" 참고)과 chat의 `profileImgUrl` 필드 추가다.

**총 39개 엔드포인트.** 도메인 이름은 코드의 패키지 구조(`com.skhynix.user.<domain>`, `com.skhynix.quiz.<domain>`)와 1:1로 대응한다 — 새 도메인 패키지가 생기면 이 디렉터리에도 같은 이름의 문서가 하나 생긴다.

## base URL과 context-path

| 모듈 | 포트 | `server.servlet.context-path` | 로컬 base URL |
|---|---|---|---|
| user | 8080 | `/api` | `http://localhost:8080` |
| quiz | 8081 | `/rt` | `http://localhost:8081` |

컨트롤러의 `@RequestMapping`은 접두사 없는 자원 경로만 갖고, 실제 외부 경로에는 context-path가 항상 붙는다(컨테이너가 필터 체인 이전에 접두사를 떼기 때문). base URL 자체(`http://localhost:8080`)는 context-path와 무관하다. 이 문서들의 모든 경로는 **접두사를 포함한 실제 외부 경로**로 표기한다.

운영에서는 `https://victoryfairy.com`이 ALB → EKS 파드로 붙으며, ALB는 경로 rewrite를 하지 않으므로 Ingress path와 context-path가 문자 그대로 일치한다.

---

## 공통 규약

아래 절들은 여러 도메인에 걸쳐 동일하게 적용된다. 도메인 문서에서 반복하지 않고 여기를 참조한다.

### 1. 응답 래퍼 — 도메인·엔드포인트마다 다르다

`ApiResponse<T>`(`:common`) = `{ success, data, message }`.

| 범위 | 성공 응답 | 비고 |
|---|---|---|
| chat 6개 | `ApiResponse<T>` | SSE 구독만 예외(`SseEmitter`, JSON 래핑 안 함) |
| auth의 validate·email·프로필 이미지 계열 6개(2026-08-20부터 `profile-image` 포함) | `ApiResponse<T>` | |
| auth의 signup/login/refresh/logout 4개 | **raw**(`ResponseEntity<T>` 직접 반환) | `ApiResponse`로 감싸지 않는다 |
| account의 회원탈퇴(DELETE) | **raw**, 본문 없음(204) | |
| account의 내 프로필 조회(GET)·프로필 이미지 변경(POST, 2026-08-20 신규) · team·player·game·support | `ApiResponse<T>` | |

**에러 응답은 (아래 예외를 빼면) 전부 `ApiResponse`로 감싸인다** — `GlobalExceptionHandler`(`@RestControllerAdvice`)가 변환한다. 즉 user 모듈의 auth·account 일부는 "성공은 raw, 실패는 ApiResponse"인 비대칭 구조다.

- 비즈니스 예외(`BusinessException`) → `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`.
- **`data`가 항상 `null`인 것은 아니다(2026-08-17부터, `hwannee/be/feat-edit-profile` 브랜치).** `BusinessException`은 `ErrorCode`(status·message)만 들고 있어 그 응답의 `data`는 여전히 예외 없이 `null`이다. 다만 `:common`에 그 하위 타입 `BusinessDataException`(`Object data` 보유)이 신설돼, **이 타입으로 던진 예외만** `data`에 도메인 값을 싣는다 — `@ExceptionHandler`가 가장 구체적인 타입([`GlobalExceptionHandler.handleBusinessData`](../../web-support/src/main/java/com/skhynix/websupport/error/GlobalExceptionHandler.java))을 고르는 원리라, 기존 `BusinessException` 응답들의 `data:null` 계약은 그대로 유지된다. 현재 실사용처는 [account 도메인](account.md)의 `PATCH /api/users/me/nickname` 쿨다운 429(`NICKNAME_CHANGE_COOLDOWN`) 하나뿐이다: `{ "success": false, "data": {"nextChangeableAt": "2026-09-16T14:03:21+09:00"}, "message": "닉네임은 30일에 한 번만 변경할 수 있습니다." }`.
- Bean Validation 실패(`MethodArgumentNotValidException`) → `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 400. `data`에는 **위반한 필드만** 담긴다.
- **쿼리 파라미터 바인딩 실패는 더 이상 갈리지 않는다(2026-08-20부터 — 종전엔 두 갈래였다).** 컨트롤러 진입 전 바인딩 단계에서 400이 나는 두 경우 모두 이제 `ApiResponse` 래퍼를 탄다.
  - **타입 변환 실패**(`?teamId=abc`(player)·`?date=20260801`(game)·`/rt/quizzes/abc/submit`처럼 경로 변수·쿼리 값이 있는데 파싱이 안 됨) → **2026-08-20부터 `ApiResponse` 래퍼가 붙는다.** `web-support`의 `GlobalExceptionHandler`에 `MethodArgumentTypeMismatchException` 핸들러가 신설돼(`handleTypeMismatch`), `{ "success": false, "data": null, "message": "요청 파라미터 형식이 올바르지 않습니다: <파라미터명>" }`을 400으로 반환한다(**종전엔 Spring 기본 `DefaultHandlerExceptionResolver`가 처리해 래퍼가 없었다** — [game](game.md)·[player](player.md)·[chat](chat.md)의 관련 서술은 이번 개정으로 함께 정정됐다).
  - **필수 파라미터 자체가 없음**(`gameId` 키 없이 `GET /api/games/lineup` 호출 등) → 2026-08-13부터 `ApiResponse` 래퍼를 탄다(아래와 동일한 이유·형태). `web-support`의 `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 핸들러가 추가돼(`@ExceptionHandler(MissingServletRequestParameterException.class)`), `{ "success": false, "data": null, "message": "필수 요청 파라미터가 누락되었습니다: <파라미터명>" }`을 400으로 반환한다. 공유 컴포넌트(`web-support`)라 user·quiz 두 앱 모두에 적용된다.

quiz 모듈은 `SecurityConfig`가 `web-support`의 `GlobalExceptionHandler`를 `@Import`로 **명시 등록**해 이 변환이 이루어진다(좁은 컴포넌트 스캔 범위 밖이라 자동 감지되지 않는다 — 이 import가 빠지면 `BusinessException`이 스프링 기본 500으로 나간다).

### 1-1. 시스템 예외도 래퍼를 탄다 (2026-08-20부터 — quiz FE 영향 필독)

지금까지 `GlobalExceptionHandler`가 잡지 않던 스프링/서블릿 레벨 시스템 예외 다섯 종류가 이번에 추가됐다. 전부 **상태 코드는 그대로이고 응답 본문만 스프링 기본 형식(`{timestamp,status,error,path}`)에서 표준 래퍼(`{success,data,message}`)로 바뀐다.**

| 예외 | 상태 | 핸들러 | 바뀌기 전 | 바뀐 후 |
|---|---|---|---|---|
| `HttpMediaTypeNotSupportedException` | 415 | `handleMediaTypeNotSupported` | 스프링 기본 본문 | `{"success":false,"data":null,"message":"지원하지 않는 요청 형식(Content-Type)입니다."}` |
| `HttpRequestMethodNotSupportedException` | 405 | `handleMethodNotSupported` | 스프링 기본 본문 | `{"success":false,"data":null,"message":"지원하지 않는 요청 메서드입니다."}` |
| `HttpMessageNotReadableException`(깨진 JSON·빈 본문 등) | 400 | `handleNotReadable` | 스프링 기본 본문(DTO 클래스명·필드 경로 노출) | `{"success":false,"data":null,"message":"요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."}` |
| `MethodArgumentTypeMismatchException`(경로 변수·쿼리 타입 불일치) | 400 | `handleTypeMismatch` | 스프링 기본 본문 | `{"success":false,"data":null,"message":"요청 파라미터 형식이 올바르지 않습니다: <파라미터명>"}`(들어온 값 자체는 반사하지 않는다) |
| 그 외 모든 미처리 예외(catch-all) | 500 | `handleUnexpected` | 스프링 기본 본문(dev에서는 스택트레이스까지 노출) | `{"success":false,"data":null,"message":"서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}`(`:common`에 신설된 `ErrorCode.INTERNAL_SERVER_ERROR`) |

`:common`에 `INTERNAL_SERVER_ERROR(500)`이 새로 생겼다. **500 응답 본문은 이 고정 문구뿐이다** — 예외 클래스명·내부 경로·SQL·스택트레이스는 응답에 실리지 않고 서버 로그(`GlobalExceptionHandler`)에만 남는다.

**quiz(8081)에서 실제로 응답 형태가 바뀌는 사례:**
- `Content-Type` 헤더 없이(또는 JSON이 아닌 타입으로) `POST /rt/chat/rooms/{roomUid}/messages`(채팅 전송, `SendMessageRequest`)나 `POST /rt/quizzes/{quizId}/submit`(퀴즈 제출, `QuizSubmitRequest`) 호출 → 415(래퍼 있음). **채팅 신고(`POST /rooms/{roomUid}/messages/{messageId}/report`)는 요청 본문이 없어 이 415의 영향을 받지 않는다** — `Content-Type`을 뭘 보내든 여전히 정상 처리된다(2026-08-20 검증 — 본문이 없는 경로는 이 표의 415와 무관).
- `GET /rt/quizzes/{quizId}/submit`처럼 `POST` 전용 경로에 `GET`으로 요청 → 405(래퍼 있음). `permitAll`이 GET으로만 좁혀진 user의 `/teams`·`/players`·`/games`류는 비-GET이 시큐리티 단계에서 먼저 401이 되어 이 405까지 오지 않는다(인증을 통과한 요청만 405를 본다).
- 깨진 JSON 본문(예: `{"content":`처럼 닫히지 않은 객체)으로 채팅 전송·퀴즈 제출 호출 → 400(래퍼 있음, 종전엔 스프링 기본 본문).
- `/rt/quizzes/abc/submit`처럼 `Long quizId` 자리에 숫자가 아닌 값 → 400(래퍼 있음, 종전엔 래퍼 없음). user의 `?teamId=abc`·`?date=20260801`도 같은 방식으로 정정됐다.

**바뀌지 않는 것**: 404(`NoResourceFoundException` 등)·406(`HttpMediaTypeNotAcceptableException`)은 스프링이 이미 상태 코드를 정해 둔 `ErrorResponse` 구현체라 catch-all이 다시 던지므로 여전히 스프링 기본 응답이다. 401 엔트리포인트(`RestAuthenticationEntryPoint`)·SSE 구독 종료(`AsyncRequestNotUsableException`)도 catch-all이 명시적으로 다시 던져 그대로 둔다. 이 API 전체의 기존 `BusinessException` 매핑(403·404·409 등 도메인 에러코드)은 한 글자도 바뀌지 않았다 — catch-all은 `@ExceptionHandler` 상속 거리 규칙상 더 구체적인 핸들러(`handleBusiness`·`handleBusinessData` 등)를 가로채지 않는다.

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
