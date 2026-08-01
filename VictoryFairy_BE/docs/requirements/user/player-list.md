# KBO 선수 목록 조회 요구사항
> 상태: 승인됨 (2026-07-28) · 모듈: user · 최종 수정: 2026-07-28
> **작성 시점 주의**: 이 문서는 구현보다 먼저가 아니라 **구현 초안을 리뷰한 뒤 사후에 계약을 고정한 것**이다(`team-list.md`는 구현 전 작성). 아래 요구사항은 현재 코드가 실제로 하는 동작과 일치하며, 리뷰에서 갈렸던 두 지점(무인증 공개 여부, 구단 필터)은 사용자가 직접 결정했다.

## 배경 / 목적
`GET /api/member/teams`(구단 목록)에 이어, 프론트가 선수를 지칭할 수 있어야 한다. 계약의 핵심은 "목록을 준다"가 아니라 **세 가지 경계**다.

1. **공개 범위** — 구단 목록과 같은 참조 데이터라 로그인 전에도 열려야 한다. 초안은 `SecurityConfig`에 규칙을 넣지 않아 `anyRequest().authenticated()`에 걸려 **무조건 401이 나는 상태**였다. 이 문서가 공개를 계약으로 못 박는다.
2. **필터 축** — 화면이 "구단 선택 → 그 팀 선수"라 `teamId` 필터가 필요하다. 다만 필터는 **선택**이며, 없으면 전체를 준다.
3. **소스 자연키 비노출** — `Player.kboPlayerId`(KBO 공식 playerId, 네이버 record API pcode 도 실측상 동일 값)는 py-collector 가 upsert 키로 소유한다. `Team.code`와 정확히 같은 이유로 외부에 나가면 안 된다 — 클라이언트가 이 값으로 선수를 지칭하기 시작하면 수집기 코드 체계가 프론트 계약이 되어 버린다.

## 범위
- 포함: 선수 목록 조회 엔드포인트 1개(`GET /api/member/players`), 선택 쿼리 파라미터 `teamId`, 응답 DTO(`id`+`name`), 정렬 순서 고정, `SecurityConfig`에 이 경로를 GET 한정 `permitAll`로 여는 변경
- 제외:
  - **선수 단건 조회 / 생성 / 수정 / 삭제** — 데이터는 py-collector 가 소유한다. 앱에서 쓰기 경로를 열지 않는다
  - **`kboPlayerId` 노출** — 위 배경 3 참조. 어떤 응답에도 넣지 않는다
  - **`average`(타율) 노출** — 엔티티에는 있으나 이번 계약에 필요하지 않고, 값의 갱신 주기·기준(시즌/통산)이 정의돼 있지 않아 계약으로 만들면 안 된다. 필요해지면 별도 요구사항으로 다룬다
  - **응답에 소속 구단 정보 포함** — 프론트가 `teamId`로 이미 팀을 알고 요청한다는 전제다. 넣게 되면 `Player.team`이 LAZY 라 fetch join 을 함께 도입해야 하므로 **DTO 만 바꾸면 되는 변경이 아니다**(아래 "제약" 참조)
  - **페이징 / 이름 검색 / 포지션·타율 필터** — 이번 화면 요구가 아니다. `teamId` 외의 축은 열지 않는다(USER-PL-8 이 페이징 없음을 계약으로 못 박는다)
  - **애플리케이션 레벨 재정렬(`Collator` 등 한국어 로케일 정렬)** — 정렬은 DB 가 단독 수행한다(`team-list.md` USER-TM-3 과 동일한 결정)
  - **HTTP 캐시 헤더·서버 캐시** — 구단 목록과 달리 로스터는 시즌 중 변동이 있으나, 캐시 전략은 이번 범위 밖
  - **`quiz` 모듈 쪽 노출** — 이번 엔드포인트는 `user` 모듈 전용이다

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PL-1 | 이벤트 | WHEN 클라이언트가 선수 목록을 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 선수 배열을 반환한다 | `GET /api/member/players` → 200, 본문 `{"success":true,"data":[...],"message":null}` |
| USER-PL-2 | 유비쿼터스 | THE 시스템 SHALL 선수 항목에 `id`와 `name` 두 필드만 포함한다 | `data[0]`의 키 집합이 정확히 `{"id","name"}`. `average`·`kboPlayerId`·`team`·`createdAt`·`updatedAt` 키가 **응답 어디에도 없음** |
| USER-PL-3 | 유비쿼터스 | THE 시스템 SHALL 선수 목록을 `name` 오름차순(DB 콜레이션 기준)으로 정렬해 반환하며, `teamId` 유무와 무관하게 같은 정렬을 적용한다 | 동일 DB 상태에서 2회 연속 호출 시 순서 동일. `?teamId=` 를 붙인 응답도 `name` 오름차순 |
| USER-PL-4 | 유비쿼터스 | THE 시스템 SHALL `teamId` 가 없으면 `players` 테이블의 모든 행을 반환한다 | `GET /api/member/players` → `data` 길이가 `SELECT COUNT(*) FROM players` 와 일치 |
| USER-PL-5 | 이벤트 | WHEN 요청에 `teamId` 가 있으면, THE 시스템 SHALL 그 구단 소속 선수만 반환한다 | `GET /api/member/players?teamId=6` → 반환된 모든 선수의 `players.team_id` 가 6. 6이 아닌 구단 소속은 한 건도 없음 |
| USER-PL-6 | 예외 | IF `teamId` 가 존재하지 않는 구단이거나 소속 선수가 없으면, THEN THE 시스템 SHALL 404가 아니라 200과 빈 배열을 반환한다 | `GET /api/member/players?teamId=999999` → 200, `{"success":true,"data":[],"message":null}` |
| USER-PL-7 | 예외 | IF `teamId` 가 정수로 변환되지 않으면, THEN THE 시스템 SHALL 400을 반환하고 조회를 수행하지 않는다 | `GET /api/member/players?teamId=abc` → 400. 서비스·리포지토리 호출 없음. **이 응답만 `ApiResponse` 래퍼가 아니다**(아래 "표기 근거" 참조) |
| USER-PL-8 | 유비쿼터스 | THE 시스템 SHALL 페이징 파라미터를 해석하지 않고 조회 결과 전체를 단일 배열로 반환한다 | `GET /api/member/players?page=1&size=5` → 200, `data` 길이는 `page`/`size` 와 무관. `data`는 배열이며 `content`/`totalElements` 같은 페이지 필드가 없음 |
| USER-PL-9 | 이벤트 | WHEN `Authorization` 헤더 없이 선수 목록 요청이 들어오면, THE 시스템 SHALL 200과 선수 목록을 반환한다 | 헤더 없이 `GET /api/member/players` → 200 (401 `"인증이 필요합니다."` 가 아님) |
| USER-PL-10 | 예외 | IF 만료되었거나 위조된 access 토큰이 `Authorization` 헤더에 담겨 오면, THEN THE 시스템 SHALL 200과 선수 목록을 반환한다 | `Authorization: Bearer not-a-jwt` → 200, 본문은 헤더 없을 때와 동일 |
| USER-PL-11 | 예외 | IF `players` 테이블에 행이 없으면, THEN THE 시스템 SHALL 200과 빈 배열을 반환한다 | 빈 `players`에 대해 `GET /api/member/players` → 200, `{"success":true,"data":[],"message":null}` (404·500 아님) |
| USER-PL-12 | 예외 | IF 선수 목록 경로에 GET 이외의 메서드로 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | `POST /api/member/players` (헤더 없음) → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (`UNAUTHENTICATED`) |

### 표기 근거 (요구사항 아님 — 위 문장을 읽는 데 필요한 사실)
- **USER-PL-7 의 "래퍼 아님"은 설계 선택이 아니라 현재 구조의 귀결이다.** `teamId` 타입 변환 실패(`MethodArgumentTypeMismatchException`)는 **컨트롤러 진입 전**에 발생해 `@RestControllerAdvice`(`GlobalExceptionHandler`)에 잡히지 않고 Spring 기본 `DefaultHandlerExceptionResolver`가 처리한다. `GlobalExceptionHandler`가 다루는 예외는 `BusinessException`·`MethodArgumentNotValidException` 둘뿐이다. **이 모듈에서 `ApiResponse` 래퍼가 아닌 첫 실패 응답**이므로, 프론트가 모든 에러를 `{success,data,message}`로 파싱한다면 여기서 걸린다. 래퍼를 맞추려면 `web-support`의 `GlobalExceptionHandler`에 핸들러를 추가해야 하는데 이는 `quiz` 응답까지 바꾸는 공유 부품 변경이라 이번 범위 밖으로 둔다(→ "미해결" 참조).
- **USER-PL-12 의 401(405 아님)은 `permitAll`을 GET 으로만 여는 것의 귀결이다.** `/teams`(USER-TM-9)·actuator 헬스체크와 같은 선례를 따른다.
- **USER-PL-1 의 `ApiResponse` 래퍼**는 `/teams`·`/nickname/validate`·`quiz`의 `/chat/rooms` 등 데이터를 돌려주는 최근 엔드포인트가 전부 쓰는 형태다(`login`/`signup`처럼 래퍼 없는 쪽이 예외).

### USER-PL-3·PL-4 의 인수 기준이 고정 데이터로 쓰이지 않은 이유 (반드시 읽을 것)
`teams` 는 시드(`infra/sql/teams-init.sql`)가 있어 `team-list.md`가 `["KIA","KT",...]` 같은 **구체적 기대 배열**을 인수 기준으로 쓸 수 있었다. **`players` 에는 시드가 없다** — `infra/sql/` 에 `chat-init.sql`·`teams-init.sql` 뿐이고 선수 행은 오직 py-collector 적재로 생긴다. 따라서
- 로컬·신규 DB 의 기본 상태는 **빈 배열**이다(USER-PL-11 이 정상 응답임을 보장한다). 빈 목록을 보고 "API 가 깨졌다"로 오인하지 말 것 — 먼저 `SELECT COUNT(*) FROM players` 를 확인한다.
- 인수 기준은 고정 배열 대신 **DB 상태와의 상대적 일치**(`COUNT(*)` 와 길이 일치, 반환된 모든 행의 `team_id` 일치)로 쓴다.
- 정렬 순서도 같은 이유로 구체적 이름 나열이 불가능하다. 다만 콜레이션 성질은 `team-list.md` "USER-TM-3 의 한계"와 동일하다 — **선수명이 전부 한글이라 구단 목록에서 문제가 됐던 "영문이 앞에 몰리는" 현상은 선수 목록에서는 나타나지 않는다**(외국인 선수명이 영문으로 적재되면 그때 다시 드러난다).

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **정렬은 DB 단독 수행이다.** `ORDER BY name ASC`로 조회하고 애플리케이션에서 재정렬하지 않는다(`team-list.md`와 동일한 결정 — 정렬 기준이 두 곳으로 갈라지면 USER-PL-3 이 어느 쪽 규칙인지 모호해진다).
- **`SecurityConfig`의 `requestMatchers` 경로에는 context-path 를 붙이지 않는다.** 외부 경로가 `/api/member/players`여도 매처는 `/players`다. 접두사를 붙이면 매칭이 안 돼 `anyRequest().authenticated()`로 떨어지고 **USER-PL-9 가 401 로 실패한다.** 초안이 실제로 이 규칙 자체를 빠뜨려 전 요청이 401 이었다.
- **`teamId` 에 구단 존재 검증을 붙이지 않는다.** USER-PL-6 이 404 가 아니라 빈 배열인 것은 성능 타협이 아니라 계약이다 — 존재 확인에 조회를 한 번 더 쓰는 대신, 이미 공개된 `GET /api/member/teams` 가 유효한 `id` 의 출처라는 전제를 따른다.
- **`Player.team` 은 LAZY 이며 응답 변환 경로에서 초기화되지 않아야 한다.** USER-PL-2 가 `team` 을 제외하는 것이 곧 N+1 방지책이다. 이후 소속 구단을 응답에 넣게 되면 **DTO 만 고치면 선수 수만큼 팀 조회가 나간다** — 반드시 리포지토리에 fetch join 을 함께 도입해야 한다.
- **필터 조건은 FK 컬럼(`players.team_id`) 하나여야 한다.** `Team` 을 조인해 걸면 불필요한 조인이 생긴다(`findAllByTeam_IdOrderByNameAsc` 는 조인 없이 FK 로만 건다).
- **응답 DTO 는 앱 모듈(`user.player.dto`)에 둔다.** 초안은 `:domain` 의 엔티티 패키지(`domain.player.entity.PlayerResponse`)에 뒀는데, `:domain` 은 `user`·`quiz` 가 공유하는 JPA 엔티티 모듈이라 API 계약을 여기 두면 `quiz` 까지 끌려간다. `TeamResponse` 가 `user.team.dto` 에 있는 것과 같은 자리를 지킨다.
- 이 문서는 기존 `user` 모듈 정책과 **충돌하지 않는다**. `permitAll` 확대는 규칙 추가이며 `/api/member/users/me`(탈퇴) 등 인증이 필요한 기존 경로의 동작을 바꾸지 않는다.

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
1. **무인증 공개(GET 한정 `permitAll`).** 구단 목록과 같은 성격의 참조 데이터이고 로그인 전 화면에서 쓰인다. GET 으로 좁힌 이유는 `/teams` 와 동일 — 읽기 전용 의도를 보안 설정에 드러내고, 이후 같은 경로에 쓰기 엔드포인트가 인증 없이 열린 채 추가되는 사고를 구조적으로 막는다. 비-GET 이 405 가 아니라 401 인 것은 이 선택의 의도된 결과다(USER-PL-12).
2. **필터는 쿼리 파라미터 `?teamId=`, 경로 변수(`/teams/{id}/players`)가 아니다.** 필터가 **선택**이라 같은 엔드포인트가 전체 조회도 겸해야 하고(USER-PL-4), 이후 다른 필터 축이 생기면 쿼리 파라미터 쪽이 자연스럽게 확장된다. 리소스 중첩 경로는 전체 조회를 별도 엔드포인트로 쪼개야 해서 폐기.
3. **없는 `teamId` 는 404 가 아니라 200 + `[]`.** 위 "제약" 참조. 조회는 성공했고 결과가 비었을 뿐이다.
4. **응답은 `id`+`name` 만.** `average` 는 갱신 주기·기준이 정의되지 않아 계약에서 뺐고, 소스 자연키 2종은 소유권 경계상 영구 제외다.
5. **경로는 `GET /api/member/players`** (복수형 컬렉션). 컨트롤러 매핑은 `@RequestMapping("/players")` + `@GetMapping`.

## 미해결 / 후속 (이번 범위 아님 — 기록만)
- **USER-PL-7 응답이 `ApiResponse` 래퍼가 아닌 문제.** `web-support`의 `GlobalExceptionHandler`에 `MethodArgumentTypeMismatchException` 핸들러를 추가하면 해소되지만 `quiz` 응답 포맷까지 함께 바뀐다. 별도 요구사항(web-support 모듈)으로 다뤄야 한다.
- **`players` 시드 부재.** prod 는 py-collector 적재에 전적으로 의존하므로, 적재 전에는 프론트에 빈 목록이 조용히 뜬다. 구단 목록과 마찬가지로 **API 계약이 아니라 배포 체크리스트**에서 다룬다(USER-PL-4 의 인수 기준이 그 확인 쿼리 역할을 겸한다).
- **정렬의 콜레이션 의존.** `players.name` 도 `teams.name` 과 마찬가지로 명시적 콜레이션 없이 `ddl-auto` 가 만든 컬럼이라 DB 기본값에 기댄다. 외국인 선수명이 영문으로 적재되면 `team-list.md` 가 겪은 "영문 먼저" 현상이 그대로 재현된다.

## 테스트 대응 (요구사항 ID ↔ 테스트)
| ID | 테스트 |
|---|---|
| USER-PL-1 | `PlayerControllerTest.getPlayers_returns200WithApiResponseWrappedArray` |
| USER-PL-2 | `PlayerControllerTest.getPlayers_itemContainsOnlyIdAndName` · `PlayerServiceTest.getPlayers_mapsOnlyIdAndNameFromEntity` |
| USER-PL-3 | `PlayerServiceTest.getPlayers_doesNotReorderRepositoryResult` (**한계 있음** — 아래 참조) |
| USER-PL-4 | `PlayerControllerTest.getPlayers_withoutTeamId_passesNullToService` · `PlayerServiceTest.getPlayers_withoutTeamId_returnsAllRowsMappedToDto` |
| USER-PL-5 | `PlayerControllerTest.getPlayers_withTeamId_passesParameterToService` · `PlayerServiceTest.getPlayers_withTeamId_usesFilteredQueryOnly` |
| USER-PL-6 | `PlayerControllerTest.getPlayers_unknownTeamId_returns200WithEmptyArray` · `PlayerServiceTest.getPlayers_unknownTeamId_returnsEmptyListWithoutException` |
| USER-PL-7 | `PlayerControllerTest.getPlayers_nonNumericTeamId_returns400` |
| USER-PL-8 | `PlayerControllerTest.getPlayers_ignoresPagingParameters_returnsFullArray` |
| USER-PL-9 | `PlayerControllerTest.getPlayers_withoutAuthorizationHeader_returns200` |
| USER-PL-10 | `PlayerControllerTest.getPlayers_withInvalidAccessToken_returns200` |
| USER-PL-11 | `PlayerServiceTest.getPlayers_noRows_returnsEmptyListWithoutException` (컨트롤러 레벨 빈 배열 형태는 USER-PL-6 테스트가 동일하게 검증) |
| USER-PL-12 | `PlayerControllerTest.postToPlayersPath_withoutAuth_returns401` |

**미커버 영역(정직하게 기록)**
- **USER-PL-3 의 실제 정렬**: 단위·슬라이스 테스트는 리포지토리/서비스를 목으로 대체하므로 "서비스·컨트롤러가 받은 순서를 재배열하지 않는다"까지만 검증한다. `ORDER BY name ASC` 가 실제로 기대 순서를 만드는지는 **DB 통합 테스트(`@DataJpaTest`)가 필요**하다. `team-list.md` 와 동일한 한계다.
- **USER-PL-5 의 실제 필터링**: 마찬가지로 `findAllByTeam_IdOrderByNameAsc` 가 올바른 SQL 을 만드는지는 검증하지 않는다. 슬라이스가 보장하는 것은 "서비스가 그 메서드를 그 인자로 고른다"까지다(반대 메서드를 호출하지 않는지는 `verifyNoMoreInteractions` 로 고정).
- **USER-PL-4 의 "모든 행"**: 목이 준 리스트를 그대로 흘려보내는지만 본다. 실제 전수 조회는 DB 통합 테스트 몫이다.
