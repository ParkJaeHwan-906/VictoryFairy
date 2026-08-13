# 내 응원 구단 경기 목록 조회 요구사항
> 상태: **승인됨 (2026-08-13, 사용자 승인)** · 모듈: user · 최종 수정: 2026-08-13
> 신규 엔드포인트 1개(`GET /api/games/support`). 기존 `GET /api/games`·`GET /api/games/lineup` 의 계약은 건드리지 않는다(USER-GSP-24).
> **2026-08-13 승인 시점 개정**: 초안의 미해결 질문 2건이 전부 A안으로 확정됐다 — `USER-GSP-16` 의 `(가정)` 표시를 떼고(활성 응원 구단 없음 → 200 + 빈 배열), 경기 없는 날의 "다음 경기" 폴백은 **도입하지 않는 것으로 확정**했다(아래 "결정 기록"). 그 외 초안 계약은 수정 없이 승인됐다.

## 배경 / 목적
지금 "내 응원 구단 오늘 경기"를 그리려면 프론트가 `GET /api/users/me` 로 구단 id 를 받고 `GET /api/games` 전체를 받아 클라이언트에서 골라내야 한다(2회 왕복 + 클라이언트 필터). 이 엔드포인트는 그 필터를 서버로 옮긴다.

주의할 점은 기능이 아니라 **경로가 만드는 착시**다. `/api/games`·`/api/games/lineup` 은 무인증 공개 참조 데이터인데 이 경로만 인증 필수다 — 같은 `/games` 접두사 아래에서 인증 정책이 갈리는 첫 사례이며, `SecurityConfig` 의 정확 매칭 특성 덕분에 **설정을 건드리지 않는 것이 정답**이라는 점(`/games/lineup` 때와 정반대)도 여기서 처음 발생한다.

## 범위
- 포함
  - 신규 엔드포인트 1개: `GET /games/support`(context-path 포함 실제 경로 `/api/games/support`), **인증 필수**
  - 활성 응원 구단이 **홈 또는 원정**으로 출전하는 경기만 반환하는 필터 규칙
  - 선택 쿼리 파라미터 `date`(ISO `yyyy-MM-dd`), 생략 시 `Clock`(Asia/Seoul) 기준 오늘
  - 응답은 기존 `GET /api/games` 와 **동일한 형식**(`ApiResponse<List<GameResponse>>`, 13필드)
- 제외
  - **응답 형식 변경** — 어느 쪽(홈/원정)이 내 응원 구단인지 알리는 전용 필드(`isHome`·`mySupportTeamId` 등)를 넣지 않는다. 클라이언트가 `homeTeamId`/`awayTeamId` 와 `GET /api/users/me` 의 `supportTeam` 을 대조한다(`game-lineup.md` 결정 5 와 같은 판단)
  - **`GET /api/games` 의 동작 변경** — 기존 경로에 응원 구단 필터를 얹지 않는다. `player-lookup-team-fallback.md` 의 "토큰이 파라미터를 오버라이딩" 방식(같은 URL 이 헤더에 따라 갈림)을 **쓰지 않고 경로를 분리**한 것이 이번 선택이다
  - **구단 파라미터**(`?teamId=`) — 응원 구단 외 다른 구단을 이 경로로 조회하는 수단을 두지 않는다. 특정 구단 경기가 필요하면 `GET /api/games` + 클라이언트 필터
  - **기간 조회**(`from`/`to`·주간·월간) — 하루 단위만. `GET /api/games` 와 같다
  - **경기 상태 필터**(진행 중만·취소 제외 등) — 상태와 무관하게 전부 반환하고 클라이언트가 `gameState` 로 판단한다(USER-GSP-9)
  - **"오늘 경기가 없으면 다음 경기" 폴백** — 빈 배열을 그대로 반환한다. **`/games/support/next` 같은 별도 경로도 만들지 않는다**(결정 2, 사용자 확정 — 다시 제안하지 말 것)
  - **페이징·캐시 헤더** — 하루 최대 1~2경기라 무의미하다(`GET /api/games` 와 동일)
  - **`quiz` 모듈 쪽 노출** — `user` 모듈 전용

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GSP-1 | 이벤트 | WHEN 유효한 access 토큰과 함께 응원 구단 경기 목록 조회 요청이 들어오면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 경기 배열을 반환한다 | 응원 구단이 6인 계정 토큰으로 `GET /api/games/support?date=2026-08-01` → 200, 본문 `{"success":true,"data":[…],"message":null}` 이고 `data` 는 배열 |
| USER-GSP-2 | 유비쿼터스 | THE 시스템 SHALL 응답 항목의 키 집합을 `GET /api/games` 항목과 동일하게 유지한다 | `data[0]` 의 키 집합이 정확히 `{gameId, stadium, homeTeam, homeTeamId, awayTeam, awayTeamId, homeTeamScore, awayTeamScore, gameDate, gameState, cancelReason, inning, inningHalf}` 13개(추가·누락 키 없음) |
| USER-GSP-3 | 유비쿼터스 | THE 시스템 SHALL 각 항목의 값을 `GET /api/games` 의 같은 `gameId` 항목과 동일하게 반환한다 | 같은 `date` 로 두 엔드포인트를 호출해 `gameId` 가 같은 항목끼리 비교하면 13개 필드 값이 전부 일치(`stadium`·점수·`cancelReason`·`inning`·`inningHalf` 의 `null` 여부까지 동일 — 이 경로 전용 가공·대체값 없음) |
| USER-GSP-4 | 유비쿼터스 | THE 시스템 SHALL 활성 응원 구단이 홈 구단인 경기와 원정 구단인 경기를 **모두** 포함한다 | 응원 구단 6이 홈인 경기와 원정인 경기가 같은 날 각각 있을 때 두 경기가 모두 `data` 에 있다. 원정 경기만 있는 날 요청 → 빈 배열이 아니라 그 경기 1건 |
| USER-GSP-5 | 유비쿼터스 | THE 시스템 SHALL 활성 응원 구단이 홈도 원정도 아닌 경기를 응답에서 제외한다 | 같은 `date` 의 `GET /api/games` 응답과 비교했을 때 `data` 는 그 부분집합이며, 남은 항목은 전부 `homeTeamId == 6` 또는 `awayTeamId == 6`. 6이 안 낀 경기의 `gameId` 는 응답에 없다 |
| USER-GSP-6 | 유비쿼터스 | THE 시스템 SHALL "활성 응원 구단"을 그 계정의 `user_support_teams` 중 `oppose IS NULL` 인 행의 구단으로 판정한다 | 구단 6을 응원 중인 계정(활성 행 1개, 취소 행 여러 개 가능) → 필터에 쓰이는 구단이 6. 취소 행의 구단 경기는 포함되지 않는다 |
| USER-GSP-7 | 유비쿼터스 | THE 시스템 SHALL 응답을 `gameDate` 오름차순으로 정렬한다 | 같은 날 더블헤더(2경기)가 있는 응원 구단으로 요청 → `data[0].gameDate <= data[1].gameDate` |
| USER-GSP-8 | 유비쿼터스 | THE 시스템 SHALL 대상 날짜를 반개구간 `[대상일 00:00, 대상일+1일 00:00)` 으로 해석한다 | `game_date` 가 `2026-08-01T00:00:00.000001` 인 경기는 `?date=2026-08-01` 응답에만 있고 `?date=2026-07-31` 응답에는 없다. 어떤 경기도 이틀에 중복 등장하지 않는다 |
| USER-GSP-9 | 유비쿼터스 | THE 시스템 SHALL 경기 상태(`gameState`)로 결과를 거르지 않는다 | 응원 구단의 `CANCELED`·`FINISHED` 경기가 있는 날 요청 → 그 경기가 `data` 에 포함되고 `gameState`·`cancelReason` 이 `GET /api/games` 와 동일 |
| USER-GSP-10 | 복합 | WHILE 유효한 access 토큰으로 인증된 상태에서, WHEN `date` 없이 요청이 들어오면, THE 시스템 SHALL `Asia/Seoul` 기준 오늘 날짜로 조회한다 | `Clock.fixed` 로 UTC 기준 전날이 되는 시각(예: KST 2026-08-02 00:30 = UTC 2026-08-01 15:30)을 고정하고 `GET /api/games/support`(파라미터 없음) → `?date=2026-08-02` 응답과 동일(2026-08-01 아님) |
| USER-GSP-11 | 이벤트 | WHEN `date` 가 유효한 ISO `yyyy-MM-dd` 값으로 전달되면, THE 시스템 SHALL 오늘이 아니라 그 날짜로 조회한다 | `?date=2026-08-01` → 반환된 모든 항목의 `gameDate` 가 2026-08-01 (오늘 경기가 섞이지 않음). 과거·미래 날짜 모두 동일하게 동작 |
| USER-GSP-12 | 예외 | IF `Authorization` 헤더 없이 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."` 를 반환한다 | 헤더 없이 `GET /api/games/support` → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}`. **`GET /api/games` 가 무인증 200 인 것과 정반대다** |
| USER-GSP-13 | 예외 | IF 만료·위조된 토큰이거나 refresh 타입 토큰이 실려 오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."` 를 반환한다 | `Authorization: Bearer not-a-jwt` · 만료 access 토큰 · refresh 토큰 각각 → 401, 본문이 헤더 없을 때와 동일. 200 + 빈 배열이 아니다 |
| USER-GSP-14 | 예외 | IF 탈퇴한(`exit_at IS NOT NULL`) 계정의 유효기간 남은 access 토큰이 실려 오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."` 를 반환한다 | 탈퇴 직전 발급된 access 토큰으로 요청 → 401(필터의 `findActiveIdByUid` 가 principal 을 비워 `anyRequest().authenticated()` 에 걸림). 탈퇴 전 응원하던 구단의 경기가 반환되지 않는다 |
| USER-GSP-15 | 예외 | IF 대상 날짜에 활성 응원 구단의 경기가 없으면, THEN THE 시스템 SHALL 404가 아니라 200과 빈 배열을 반환한다 | 응원 구단이 쉬는 날(월요일 등) 요청 → 200, `{"success":true,"data":[],"message":null}` |
| USER-GSP-16 | 예외 | IF 요청 계정에 활성 응원 구단이 없으면, THEN THE 시스템 SHALL 400이 아니라 200과 빈 배열을 반환한다 | 응원 구단을 한 번도 선택하지 않은 계정의 토큰으로 요청 → 200, `{"success":true,"data":[],"message":null}`. `SUPPORT_TEAM_REQUIRED` 류의 오류 코드·메시지가 없고, `USER-GSP-15`(경기 없는 날)와 응답이 완전히 동일하다(결정 1 — 의도된 결과) |
| USER-GSP-17 | 예외 | IF 계정의 응원 구단 행이 전부 취소 상태(`oppose` 에 시각이 채워짐)이면, THEN THE 시스템 SHALL 그 계정을 "활성 응원 구단 없음"으로 취급한다 | 구단 6을 응원했다가 취소한 계정 토큰으로 요청 → `USER-GSP-16` 과 동일한 응답(6의 경기가 나오지 않는다) |
| USER-GSP-18 | 예외 | IF `date` 값의 형식이 어긋나거나 존재하지 않는 날짜이면, THEN THE 시스템 SHALL 400을 반환하고 조회를 수행하지 않는다 | `?date=20260801` · `?date=2026/08/01` · `?date=2026-13-01` → 400. **이 응답은 `ApiResponse` 래퍼가 아니다**(아래 "표기 근거 2"). 응원 구단 조회·경기 조회 모두 미수행. 오타를 오늘로 흡수하지 않는다 |
| USER-GSP-19 | 예외 | IF `date` 파라미터가 값 없이(`?date=`) 전달되면, THEN THE 시스템 SHALL `GET /api/games` 와 동일하게 처리한다 | 같은 시각에 `GET /api/games?date=` 와 `GET /api/games/support?date=`(인증) 를 호출하면 **상태 코드가 같고**, 200 이라면 `/games/support` 응답이 `/games` 응답의 응원 구단 부분집합과 일치한다(둘의 판정이 갈리지 않는다) |
| USER-GSP-20 | 예외 | IF 이 경로에 GET 이외의 메서드로 인증 없이 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."` 를 반환한다 | `POST /api/games/support`(헤더 없음) → 401(405 아님 — 인증이 메서드 판정보다 앞선다) |
| USER-GSP-21 | 유비쿼터스 | THE 시스템 SHALL 경기 목록 조회를 경기 건수와 무관한 고정 횟수의 SQL 로 수행한다 | 응원 구단 경기가 1건인 날과 2건인 날의 Hibernate 문장 수가 동일하고, 항목마다 구단·구장·상태 조회가 추가로 나가지 않는다(N+1 없음) |
| USER-GSP-22 | 유비쿼터스 | THE 시스템 SHALL 응원 구단 조회를 요청당 1회만 수행한다 | 요청 1건당 `user_support_teams` 조회 1회(`JwtAuthenticationFilter` 의 uid→id 해석 1회는 별도). 경기 건수와 무관하게 1회 |
| USER-GSP-23 | 유비쿼터스 | THE 시스템 SHALL 이 조회로 어떤 행도 생성·수정·삭제하지 않는다 | 조회 전후 `games`·`user_support_teams` 의 `COUNT(*)`·`MAX(updated_at)` 이 동일(응원 구단이 없는 계정 요청 시에도 행이 생기지 않는다) |
| USER-GSP-24 | 유비쿼터스 | THE 시스템 SHALL 기존 `GET /api/games`·`GET /api/games/lineup` 의 인증 정책과 응답을 변경하지 않는다 | 헤더 없이 `GET /api/games`·`GET /api/games/lineup?gameId=…` → 여전히 200(401 아님). `GET /api/games` 응답의 항목 수·키·정렬이 변경 전과 동일 |

### 표기 근거 (요구사항 아님 — 위 문장을 읽는 데 필요한 사실)
1. **`USER-GSP-12`~`14` 의 401 은 `RestAuthenticationEntryPoint` 가 내는 응답이다**(`ApiResponse` 래퍼, `ErrorCode.UNAUTHENTICATED`). 이 경로에서 "인증되지 않음"은 헤더 없음·무효 토큰·refresh 타입·탈퇴/미존재 계정을 **구분하지 않는다** — `JwtAuthenticationFilter` 가 네 경우 모두 `SecurityContext` 를 비운 채 통과시키고 `anyRequest().authenticated()` 가 401 로 떨어뜨린다. `/players`(`permitAll` + 토큰 읽기)에서 같은 네 경우가 200 으로 흡수되던 것과 정반대다.
2. **`USER-GSP-18` 의 "래퍼 아님"은 설계 선택이 아니라 현재 구조의 귀결이다.** `date` 타입 변환 실패는 컨트롤러 진입 전 바인딩 단계에서 발생해 `GlobalExceptionHandler` 에 잡히지 않고 Spring 기본 `DefaultHandlerExceptionResolver` 가 처리한다(`docs/api/game.md` 의 `GET /api/games` 400, `player-list.md` USER-PL-7 과 같은 사정). 2026-08-13 에 `web-support` 에 추가된 `MissingServletRequestParameterException` 핸들러는 **필수 파라미터 누락**용이라 이 경로와 무관하다(`date` 는 선택 파라미터다).
3. **`USER-GSP-19` 를 "동일 처리"로 쓴 이유**: `?date=` 의 바인딩 결과는 `GET /api/games` 에서도 요구사항 문서로 고정된 적이 없다(`docs/api/game.md` 는 "값은 있는데 파싱이 안 됨"만 다룬다). 두 엔드포인트가 같은 바인딩 경로를 쓰므로, 값을 지정하는 대신 **`/games` 와 갈리지 않는다**를 계약으로 걸어 두 경로가 함께 움직이도록 한다.
4. **`USER-GSP-20` 이 405 가 아닌 이유**는 `/games`(GET 한정 `permitAll`)와 표면적으로 같지만 원인이 다르다. `/games` 는 "GET 만 열어서" 비-GET 이 401 이고, 이 경로는 **애초에 전 메서드가 인증 필요**라 미인증이면 메서드와 무관하게 401 이다. (유효 토큰을 실은 비-GET 요청이 405 로 떨어지는지는 이번 계약의 범위 밖 — 요구사항으로 고정하지 않았다.)
5. **`USER-GSP-5` 의 "부분집합" 인수 기준은 고정 데이터를 쓰지 않는다.** `games` 에는 시드가 없고 py-collector 적재로만 행이 생기므로(`game-lineup.md` 표기 근거 5) 기준은 "같은 날짜 `/games` 응답과의 상대적 일치"로 쓴다.

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **`SecurityConfig` 를 수정하지 않는 것이 정답이다.** `.requestMatchers(HttpMethod.GET, "/games").permitAll()` 은 **정확 경로 매칭**이라 `/games/support` 를 커버하지 않고, 그 결과 이 경로는 `anyRequest().authenticated()` 에 자연히 걸린다(`/support/**` 와 같은 성격). ⚠ `/games/lineup` 때는 **정반대로** 한 줄을 추가해야 했다 — 같은 정확 매칭 특성이 한 번은 "추가하라", 한 번은 "추가하지 마라"가 된다. 실수로 `.requestMatchers(HttpMethod.GET, "/games/support").permitAll()` 을 넣으면 `USER-GSP-12`~`14`·`20` 이 한꺼번에 깨진다.
- **매처·MockMvc 경로에는 context-path(`/api`)를 붙이지 않는다.** 외부 경로가 `/api/games/support` 여도 내부 경로는 `/games/support` 다.
- **`GameRepository` 에는 구단 조건이 붙은 조회 메서드가 없다.** 현재 조회는 날짜 반개구간 1종 + `naverGameId` 2종뿐이다 — `USER-GSP-4`·`USER-GSP-5` 를 만족하려면 조회 경로가 하나 필요하다(어떻게 해소할지는 구현 판단).
- **새 조회 경로에도 `@EntityGraph(attributePaths = {"homeTeam","awayTeam","stadium","gameStatus"})` 와 같은 로딩 범위가 필요하다.** `GameRepository` 주석이 "`@EntityGraph` 목록은 `GameResponse` 가 읽는 연관과 1:1 유지"를 못 박고 있고, 어기면 ①N+1(`USER-GSP-21` 위반) ②prod(`open-in-view: false`)에서 `LazyInitializationException`(500, `USER-GSP-1` 위반)이 난다. `stadium` 은 `optional = true` 라 반드시 left join 이어야 한다(구장 미정 경기가 목록에서 빠지면 `USER-GSP-3`·`USER-GSP-5` 위반).
- **필터링·정렬은 DB 가 수행한다는 컨벤션이 있다**(`player-list.md` USER-PL-3). 전체를 읽어 앱에서 거르면 `USER-GSP-5`·`USER-GSP-7` 은 만족시킬 수 있지만 이 컨벤션에서 벗어난다.
- **응원 구단 조회에는 구단명이 필요 없다.** 응답에 구단명은 `Game.homeTeam`/`awayTeam` 에서 나오지 응원 행에서 나오지 않는다 — `@EntityGraph` 가 붙은 `findWithTeamByUserAccount_IdAndOpposeIsNull` 대신 id 전용인 `findByUserAccount_IdAndOpposeIsNull` 이 용도에 맞다(리포지토리 Javadoc 이 두 메서드 용도를 이미 갈라 놓았다). 프록시의 id 접근은 초기화를 유발하지 않는다.
- **"오늘"의 유일한 출처는 `ClockConfig` 의 `Clock` 빈이다.** 운영 파드가 UTC 로 돌아 `LocalDate.now()` 를 쓰면 KST 자정~오전 9시 사이에 하루 전 날짜로 조회된다(`GameService` 는 이미 `Clock` 주입으로 이 함정을 피했다). `USER-GSP-10` 의 인수 기준이 `Clock.fixed` 를 쓰는 이유이며, 기존 `GameServiceTest` 에 같은 성격의 회귀 테스트가 이미 있다.
- **principal 은 `UserAccount.uid`(UUID)가 아니라 내부 PK `Long` id 다**(`JwtAuthenticationFilter` 가 `findActiveIdByUid` 로 해석). 인증 필수 경로라 `@AuthenticationPrincipal Long` 은 `null` 이 될 수 없다 — `/players` 처럼 `null` 분기를 둘 필요가 없다.
- **홈/원정 양쪽을 보는 판정은 이 저장소에 선례가 있다**: `quiz` 의 `QuizService.servableGame` 이 `supportTeamId` 를 `getHomeTeam().getId()`·`getAwayTeam().getId()` 양쪽과 비교하며, 주석이 "한쪽만 보면 원정 경기 날 전 사용자가 막힌다"고 적어 두었다. `USER-GSP-4` 는 같은 판정을 `user` 쪽 계약으로 옮긴 것이다.
- **`GameService` 에 협력자가 하나 늘면(`UserSupportTeamRepository`) 기존 테스트가 깨진다**: `GameServiceTest`(12건)의 `@InjectMocks` 에 mock 을 등록하지 않으면 런타임 NPE, `GameControllerTest`(14건)의 `@WebMvcTest` 슬라이스에 새 빈을 `@MockitoBean` 으로 넣지 않으면 컨텍스트 로딩 실패다(모듈 컨텍스트에 기록된 재발 함정 — `PlayerServiceTest` 가 실제로 밟았다).
- **`:common` 의 `ErrorCode` 추가는 이 계약에 없다.** `USER-GSP-16` 이 200 으로 확정돼 신규 상수가 필요 없다(결정 1) — `SUPPORT_TEAM_REQUIRED` 류를 추가하려 들지 말 것. 그것은 `user`·`quiz` 가 공유하는 부품 변경이기도 하다.
- **`docs/api/game.md` 의 도메인 서술이 더 이상 통째로 참이 아니게 된다.** 지금 문서는 game 도메인을 "team·player 와 같은 공개 참조 데이터(GET 한정 `permitAll`)"로 소개하는데, 이 엔드포인트는 같은 도메인에서 처음으로 인증 필수다. 구현 후 `api-documenter` 가 갱신할 지점(요구사항 위반은 아니다).

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
초안의 미해결 질문 2건에 대한 **사용자 확정 답변**(2026-08-13)이다. 둘 다 초안 권장안(A)이 그대로 채택됐다.

1. **활성 응원 구단이 없는 계정은 200 + 빈 배열이다**(`USER-GSP-16`·`USER-GSP-17`). `me-profile.md` 의 확정 정책 "백엔드는 응원 구단 선택을 강제하지 않는다"(`supportTeam: null` + 200, 필수화는 프론트 온보딩 담당)와 `player-lookup-team-fallback.md` USER-PLF-6(응원 구단 없어도 400 아님)을 **그대로 따른다** — 이 엔드포인트가 그 정책의 예외가 되지 않는다. **대가는 인지하고 택한 것이다**: "대상 날짜에 경기가 없음"(`USER-GSP-15`)과 "응원 구단 미선택"(`USER-GSP-16`)이 응답에서 전혀 구분되지 않는다. 둘을 갈라야 하는 화면은 `GET /api/users/me` 의 `supportTeam` 으로 판별한다. **사용자 확정 사항이니 재조사하지 말 것** — 400·신규 `ErrorCode`(`SUPPORT_TEAM_REQUIRED`)·`message` 안내 문자열은 모두 검토 후 폐기된 선택지다.
2. **대상 날짜에 경기가 없으면 폴백 없이 빈 배열이다**(`USER-GSP-15`). "가장 가까운 다음 경기"를 대신 돌려주는 동작은 도입하지 않으며, **`/games/support/next` 같은 별도 경로도 만들지 않는 것으로 확정**했다. `date` 의 의미를 "그 날짜"로 고정해(`USER-GSP-11`) 응답의 `gameDate` 가 요청 날짜와 어긋나는 상황을 아예 만들지 않는다. KBO 월요일처럼 경기가 없는 날 화면에 무엇을 띄울지는 프론트가 정한다. **다시 제안하지 말 것.**

## 미해결 질문
없음 — 초안의 2건은 2026-08-13 사용자 답변으로 전부 해소됐다(위 "결정 기록" 참조).
