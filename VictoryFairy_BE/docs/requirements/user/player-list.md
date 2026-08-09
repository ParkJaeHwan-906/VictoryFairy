# KBO 선수 목록 조회 요구사항
> 상태: 승인됨 (2026-07-28) · 모듈: user · 최종 수정: 2026-08-06(응답 항목 키 교체 — USER-PL-2 개정 + USER-PL-2a 신설)
> **2026-08-06 개정(USER-PL-2 한정)**: 응답 항목이 `{id, name}`에서 `{teamId, teamName, playerId, playerName, playerNumber, playerPosition}` 여섯 필드로 바뀐 **파괴적 변경**이다. 기존 `id`·`name` 키는 사라졌다. 함께 무효화된 것: "`Player.team`을 응답에서 제외하는 것이 N+1 방지책"이라는 아래 "설계 제약" 항목(취소선 참조). 이 개정으로 등번호·포지션의 `null` 처리 계약(USER-PL-2a)이 새로 생겼다.
> **작성 시점 주의**: 이 문서는 구현보다 먼저가 아니라 **구현 초안을 리뷰한 뒤 사후에 계약을 고정한 것**이다(`team-list.md`는 구현 전 작성). 아래 요구사항은 현재 코드가 실제로 하는 동작과 일치하며, 리뷰에서 갈렸던 두 지점(무인증 공개 여부, 구단 필터)은 사용자가 직접 결정했다.
> **2026-08-04 개정(USER-PL-4 한정)**: `docs/requirements/user/player-lookup-team-fallback.md`(응원 구단 폴백, 사용자 결정 2026-08-04)가 **적용 구단 결정 규칙의 단일 출처**가 되었다 — 유효한 access 토큰의 계정에 활성 응원 구단이 있으면 그 구단이 우선하고 요청의 `teamId`는 무시된다(USER-PLF-16·17). 이에 따라 **USER-PL-4를 조건부(유비쿼터스 → 예외)로 좁혔다.** 나머지 항목은 이번 개정에서 손대지 않았으나, **USER-PL-5·USER-PL-6도 같은 이유로 개정 대기 상태**다(개정 문안은 폴백 문서의 "기존 계약과의 충돌" 2·3번 참조 — 승인 전까지 이 두 문장은 "활성 응원 구단이 없는 요청"에 한해서만 유효하다고 읽을 것).
> **2026-08-03 개정**: 이름 검색(`?name=`)은 최초 계약에서 **명시적으로 제외**했던 축이다(아래 "범위" 참조). 화면 요구가 생겨 열었으며, 열면서 지킨 원칙은 기존 필터와 같다 — 선택 파라미터이고, 필터링·정렬은 DB 가 단일 쿼리로 수행하며, 일치하는 것이 없으면 404 가 아니라 빈 배열이다.

## 배경 / 목적
`GET /api/teams`(구단 목록)에 이어, 프론트가 선수를 지칭할 수 있어야 한다. 계약의 핵심은 "목록을 준다"가 아니라 **세 가지 경계**다.

1. **공개 범위** — 구단 목록과 같은 참조 데이터라 로그인 전에도 열려야 한다. 초안은 `SecurityConfig`에 규칙을 넣지 않아 `anyRequest().authenticated()`에 걸려 **무조건 401이 나는 상태**였다. 이 문서가 공개를 계약으로 못 박는다.
2. **필터 축** — 화면이 "구단 선택 → 그 팀 선수"라 `teamId` 필터가 필요하다. 다만 필터는 **선택**이며, 없으면 전체를 준다.
3. **소스 자연키 비노출** — `Player.kboPlayerId`(KBO 공식 playerId, 네이버 record API pcode 도 실측상 동일 값)는 py-collector 가 upsert 키로 소유한다. `Team.code`와 정확히 같은 이유로 외부에 나가면 안 된다 — 클라이언트가 이 값으로 선수를 지칭하기 시작하면 수집기 코드 체계가 프론트 계약이 되어 버린다.

## 범위
- 포함: 선수 목록 조회 엔드포인트 1개(`GET /api/players`), 선택 쿼리 파라미터 `teamId`·`name`(이름 부분 일치, 2026-08-03 추가), 두 파라미터의 AND 결합, 응답 DTO(`id`+`name`), 정렬 순서 고정, `SecurityConfig`에 이 경로를 GET 한정 `permitAll`로 여는 변경
- 제외:
  - **선수 단건 조회 / 생성 / 수정 / 삭제** — 데이터는 py-collector 가 소유한다. 앱에서 쓰기 경로를 열지 않는다
  - **`kboPlayerId` 노출** — 위 배경 3 참조. 어떤 응답에도 넣지 않는다
  - **`average`(타율) 노출** — 엔티티에는 있으나 이번 계약에 필요하지 않고, 값의 갱신 주기·기준(시즌/통산)이 정의돼 있지 않아 계약으로 만들면 안 된다. 필요해지면 별도 요구사항으로 다룬다
  - **응답에 소속 구단 정보 포함** — 프론트가 `teamId`로 이미 팀을 알고 요청한다는 전제다. 넣게 되면 `Player.team`이 LAZY 라 fetch join 을 함께 도입해야 하므로 **DTO 만 바꾸면 되는 변경이 아니다**(아래 "제약" 참조)
  - **페이징 / 포지션·타율 필터** — 이번 화면 요구가 아니다(USER-PL-8 이 페이징 없음을 계약으로 못 박는다). ~~이름 검색~~은 2026-08-03 개정으로 **범위에 편입**됐다(USER-PL-13~16)
  - **이름 검색의 정교화** — 초성 검색(`ㄱㄷㅇ` → 김도영), 자모 분해 매칭, 오타 허용(edit distance), 관련도 순 정렬, 검색어 하이라이팅. `name` 은 단순 `LIKE '%검색어%'` 이고 정렬은 여전히 `name` 오름차순 고정이다(USER-PL-3)
  - **`LIKE` 와일드카드 이스케이프** — 검색어에 담긴 `%`·`_` 를 리터럴로 취급하지 않는다(아래 "미해결" 참조)
  - **애플리케이션 레벨 재정렬(`Collator` 등 한국어 로케일 정렬)** — 정렬은 DB 가 단독 수행한다(`team-list.md` USER-TM-3 과 동일한 결정)
  - **HTTP 캐시 헤더·서버 캐시** — 구단 목록과 달리 로스터는 시즌 중 변동이 있으나, 캐시 전략은 이번 범위 밖
  - **`quiz` 모듈 쪽 노출** — 이번 엔드포인트는 `user` 모듈 전용이다

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PL-1 | 이벤트 | WHEN 클라이언트가 선수 목록을 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 선수 배열을 반환한다 | `GET /api/players` → 200, 본문 `{"success":true,"data":[...],"message":null}` |
| USER-PL-2 | 유비쿼터스 | THE 시스템 SHALL 선수 항목에 소속 구단(`teamId`·`teamName`)과 선수(`playerId`·`playerName`·`playerNumber`·`playerPosition`) 여섯 필드만 포함한다 | `data[0]`의 키 집합이 정확히 그 여섯 개. `average`·`kboPlayerId`·`createdAt`·`updatedAt` 키가 **응답 어디에도 없고**, 구단은 중첩 객체(`team`)가 아님 |
| USER-PL-2a | 유비쿼터스 | THE 시스템 SHALL 원본에 값이 없는 `playerNumber`·`playerPosition`을 대체값으로 채우지 않고 `null`로 내보내되 **키는 유지**한다 | 등번호 미배정·포지션 없는 선수의 `data[0]`도 키 6개를 모두 갖고 해당 값만 `null`(`""`·`"UNKNOWN"`이 아니며 키 누락도 아님) |
| USER-PL-3 | 유비쿼터스 | THE 시스템 SHALL 선수 목록을 `name` 오름차순(DB 콜레이션 기준)으로 정렬해 반환하며, `teamId`·`name` 유무와 무관하게 같은 정렬을 적용한다 | 동일 DB 상태에서 2회 연속 호출 시 순서 동일. `?teamId=`·`?name=` 를 붙인 응답도 `name` 오름차순(관련도 순이 아님) |
| USER-PL-4 | 예외 | **(2026-08-04 개정)** IF 적용 구단이 결정되지 않으면(=`teamId` 미전달 **이고** 요청 계정에 활성 응원 구단도 없음), THEN THE 시스템 SHALL `players` 테이블의 모든 행을 반환한다 | 헤더 없이 `GET /api/players` → `data` 길이가 `SELECT COUNT(*) FROM players` 와 일치. **응원 구단이 있는 계정의 토큰을 실으면 이 요구사항이 적용되지 않는다**(그 구단으로 좁혀짐 — `player-lookup-team-fallback.md` USER-PLF-1) |
| USER-PL-5 | 복합 | **(2026-08-04 개정)** WHILE 적용 구단이 요청의 `teamId` 로 결정되는 상태(=활성 응원 구단이 없음)에서, WHEN 요청에 `teamId` 가 있으면, THE 시스템 SHALL 그 구단 소속 선수만 반환한다. 활성 응원 구단이 있으면 `teamId` 는 무시된다(USER-PLF-16·17 우선) | `GET /api/players?teamId=6`(무인증 또는 응원 구단 없는 계정) → 반환된 모든 선수의 `players.team_id` 가 6. 6이 아닌 구단 소속은 한 건도 없음. 응원 구단이 9인 계정 토큰으로 같은 요청 → `team_id` 가 9(이 요구사항 미적용) |
| USER-PL-6 | 예외 | IF `teamId` 가 존재하지 않는 구단이거나 소속 선수가 없으면, THEN THE 시스템 SHALL 404가 아니라 200과 빈 배열을 반환한다. **(2026-08-04 단서 추가)** 활성 응원 구단이 있는 인증 요청에서는 `teamId` 자체가 무시되므로 이 요구사항이 적용되지 않는다(USER-PLF-18) | `GET /api/players?teamId=999999`(무인증) → 200, `{"success":true,"data":[],"message":null}`. 응원 구단이 6인 계정 토큰으로 같은 요청 → 빈 배열이 아니라 `team_id=6` 목록 |
| USER-PL-7 | 예외 | IF `teamId` 가 정수로 변환되지 않으면, THEN THE 시스템 SHALL 400을 반환하고 조회를 수행하지 않는다 | `GET /api/players?teamId=abc` → 400. 서비스·리포지토리 호출 없음. **이 응답만 `ApiResponse` 래퍼가 아니다**(아래 "표기 근거" 참조) |
| USER-PL-8 | 유비쿼터스 | THE 시스템 SHALL 페이징 파라미터를 해석하지 않고 조회 결과 전체를 단일 배열로 반환한다 | `GET /api/players?page=1&size=5` → 200, `data` 길이는 `page`/`size` 와 무관. `data`는 배열이며 `content`/`totalElements` 같은 페이지 필드가 없음 |
| USER-PL-9 | 이벤트 | WHEN `Authorization` 헤더 없이 선수 목록 요청이 들어오면, THE 시스템 SHALL 200과 선수 목록을 반환한다 | 헤더 없이 `GET /api/players` → 200 (401 `"인증이 필요합니다."` 가 아님) |
| USER-PL-10 | 예외 | IF 만료되었거나 위조된 access 토큰이 `Authorization` 헤더에 담겨 오면, THEN THE 시스템 SHALL 200과 선수 목록을 반환한다 | `Authorization: Bearer not-a-jwt` → 200, 본문은 헤더 없을 때와 동일 |
| USER-PL-11 | 예외 | IF `players` 테이블에 행이 없으면, THEN THE 시스템 SHALL 200과 빈 배열을 반환한다 | 빈 `players`에 대해 `GET /api/players` → 200, `{"success":true,"data":[],"message":null}` (404·500 아님) |
| USER-PL-12 | 예외 | IF 선수 목록 경로에 GET 이외의 메서드로 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | `POST /api/players` (헤더 없음) → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (`UNAUTHENTICATED`) |
| USER-PL-13 | 이벤트 | WHEN 요청에 `name` 이 있으면, THE 시스템 SHALL 이름에 그 문자열이 **포함된**(앞부분 일치가 아니라 부분 일치) 선수만 반환한다 | `GET /api/players?name=도영` → 반환된 모든 선수의 `name` 에 `"도영"` 이 포함됨. `"도"`(이름 중간에 오는 한 글자)로도 `"김도영"` 이 반환됨 |
| USER-PL-14 | 이벤트 | WHEN `teamId` 와 `name` 이 함께 오면, THE 시스템 SHALL 두 조건을 모두 만족하는 선수만 반환한다(AND) | `GET /api/players?teamId=6&name=도영` → 반환된 모든 선수가 `team_id=6` 이면서 `name` 에 `"도영"` 포함. 6이 아닌 구단의 동명 선수는 한 건도 없음 |
| USER-PL-15 | 예외 | IF `name` 이 비어 있거나 공백 문자로만 이루어져 있으면, THEN THE 시스템 SHALL `name` 이 없는 것과 동일하게 처리한다. 또한 THE 시스템 SHALL 검색어의 앞뒤 공백을 제거한 뒤 매칭한다 | `?name=` · `?name=%20%20` → `name` 없는 응답과 동일(전체 또는 `teamId` 필터만). `?name=%20도영%20` → `?name=도영` 과 동일한 결과 |
| USER-PL-16 | 예외 | IF `name` 과 일치하는 선수가 없으면, THEN THE 시스템 SHALL 404가 아니라 200과 빈 배열을 반환한다 | `GET /api/players?name=없는이름` → 200, `{"success":true,"data":[],"message":null}` |

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
- **`SecurityConfig`의 `requestMatchers` 경로에는 context-path 를 붙이지 않는다.** 외부 경로가 `/api/players`여도 매처는 `/players`다. 접두사를 붙이면 매칭이 안 돼 `anyRequest().authenticated()`로 떨어지고 **USER-PL-9 가 401 로 실패한다.** 초안이 실제로 이 규칙 자체를 빠뜨려 전 요청이 401 이었다.
- **`teamId` 에 구단 존재 검증을 붙이지 않는다.** USER-PL-6 이 404 가 아니라 빈 배열인 것은 성능 타협이 아니라 계약이다 — 존재 확인에 조회를 한 번 더 쓰는 대신, 이미 공개된 `GET /api/teams` 가 유효한 `id` 의 출처라는 전제를 따른다.
- ~~**`Player.team` 은 LAZY 이며 응답 변환 경로에서 초기화되지 않아야 한다.**~~ **2026-08-06 무효** — 소속 구단이 응답에 들어가면서 `PlayerResponse.from()` 이 `Player.team` 을 초기화한다. 여기서 경고한 대로 **DTO 만 고쳤고 fetch join 은 아직 도입하지 않았다.** 실제 비용은 예측했던 "선수 수만큼"보다는 작다 — 영속성 컨텍스트 1차 캐시가 같은 구단의 반복 조회를 흡수해 **서로 다른 구단 수(최대 10)만큼**의 추가 SELECT 로 수렴한다. 그래도 공짜는 아니므로 리포지토리 4종의 fetch join 은 남은 숙제다. 또한 변환이 트랜잭션 밖으로 나가면 `LazyInitializationException` 이 되므로, `PlayerService`·`SupportService` 의 `@Transactional` 경계 안에서 변환한다는 전제가 이제 **필수 제약**이다.
- **필터 조건은 FK 컬럼(`players.team_id`) 하나여야 한다.** `Team` 을 조인해 걸면 불필요한 조인이 생긴다(`findAllByTeam_IdOrderByNameAsc` 는 조인 없이 FK 로만 건다).
- **`teamId`+`name` 은 단일 쿼리로 건다.** 두 필터를 각각 조회해 앱에서 교집합을 내면 안 된다(`findAllByTeam_IdAndNameContainingOrderByNameAsc` 하나로 DB 가 좁히고 정렬까지 끝낸다). 조합이 2×2 라 리포지토리 메서드가 4개인데, 이 개수를 줄이려고 `:param IS NULL OR ...` 형태의 단일 JPQL 로 합치는 리팩터링은 하지 않았다 — 조건 분기가 SQL 안으로 숨어 실행 계획이 파라미터마다 달라지는 대신, 어떤 요청이 어떤 쿼리를 타는지가 코드에 드러나는 쪽을 택했다.
- **`name` 은 `IgnoreCase` 를 붙이지 않는다.** MySQL 기본 콜레이션(`utf8mb4_..._ci`)이 이미 대소문자를 구분하지 않으므로, `IgnoreCase` 를 붙이면 양변에 `LOWER()` 만 추가돼 SQL 이 지저분해진다. 콜레이션을 `_bin`/`_cs` 로 바꾸면 이 전제가 깨진다.
- **빈 검색어를 접는 주체는 서비스다.** `?name=` 처럼 값 없이 붙는 파라미터를 Spring 은 `null` 이 아니라 빈 문자열로 넘긴다 — 컨트롤러는 받은 값을 그대로 넘기고, `null`/빈 문자열/공백을 "검색어 없음"으로 접는 판단은 `PlayerService` 한 곳에서만 한다. 두 곳에서 접으면 "검색어 없음"의 정의가 갈라진다.
- **응답 DTO 는 앱 모듈(`user.player.dto`)에 둔다.** 초안은 `:domain` 의 엔티티 패키지(`domain.player.entity.PlayerResponse`)에 뒀는데, `:domain` 은 `user`·`quiz` 가 공유하는 JPA 엔티티 모듈이라 API 계약을 여기 두면 `quiz` 까지 끌려간다. `TeamResponse` 가 `user.team.dto` 에 있는 것과 같은 자리를 지킨다.
- 이 문서는 기존 `user` 모듈 정책과 **충돌하지 않는다**. `permitAll` 확대는 규칙 추가이며 `/api/users/me`(탈퇴) 등 인증이 필요한 기존 경로의 동작을 바꾸지 않는다.

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
1. **무인증 공개(GET 한정 `permitAll`).** 구단 목록과 같은 성격의 참조 데이터이고 로그인 전 화면에서 쓰인다. GET 으로 좁힌 이유는 `/teams` 와 동일 — 읽기 전용 의도를 보안 설정에 드러내고, 이후 같은 경로에 쓰기 엔드포인트가 인증 없이 열린 채 추가되는 사고를 구조적으로 막는다. 비-GET 이 405 가 아니라 401 인 것은 이 선택의 의도된 결과다(USER-PL-12).
2. **필터는 쿼리 파라미터 `?teamId=`, 경로 변수(`/teams/{id}/players`)가 아니다.** 필터가 **선택**이라 같은 엔드포인트가 전체 조회도 겸해야 하고(USER-PL-4), 이후 다른 필터 축이 생기면 쿼리 파라미터 쪽이 자연스럽게 확장된다. 리소스 중첩 경로는 전체 조회를 별도 엔드포인트로 쪼개야 해서 폐기.
3. **없는 `teamId` 는 404 가 아니라 200 + `[]`.** 위 "제약" 참조. 조회는 성공했고 결과가 비었을 뿐이다.
4. **응답은 `id`+`name` 만.** `average` 는 갱신 주기·기준이 정의되지 않아 계약에서 뺐고, 소스 자연키 2종은 소유권 경계상 영구 제외다.
   > 2026-08-06 개정: 소속 구단·등번호·포지션이 추가돼 여섯 필드가 됐다(USER-PL-2 갱신). `average` 제외와 자연키 영구 제외 판단은 그대로다.
5. **경로는 `GET /api/players`** (복수형 컬렉션). 컨트롤러 매핑은 `@RequestMapping("/players")` + `@GetMapping`.
6. **이름 검색은 prefix 가 아니라 contains 다** (2026-08-03). 초안 코드는 `LIKE '검색어%'`(앞부분 일치)였으나, 검색어가 이름의 앞부분이라는 보장이 없어(`"도영"` 으로 `"김도영"` 을 찾는 것이 실사용 패턴이다) 부분 일치로 고정했다. 앞부분 일치는 부분 일치의 부분집합이라 계약을 넓히는 방향이며, 되돌리려면 `PlayerRepository` 의 `Containing` → `StartingWith` 한 곳만 바꾸면 된다.
7. **`name` 도 `teamId` 와 같은 선택 쿼리 파라미터다** (2026-08-03). 별도 검색 엔드포인트(`/players/search`)를 만들지 않은 이유는 결정 2와 같다 — 필터가 선택이라 같은 엔드포인트가 전체 조회를 겸해야 하고, 필터 축이 늘어도 쿼리 파라미터 쪽이 자연스럽게 확장된다.

## 미해결 / 후속 (이번 범위 아님 — 기록만)
- **USER-PL-7 응답이 `ApiResponse` 래퍼가 아닌 문제.** `web-support`의 `GlobalExceptionHandler`에 `MethodArgumentTypeMismatchException` 핸들러를 추가하면 해소되지만 `quiz` 응답 포맷까지 함께 바뀐다. 별도 요구사항(web-support 모듈)으로 다뤄야 한다.
- **`players` 시드 부재.** prod 는 py-collector 적재에 전적으로 의존하므로, 적재 전에는 프론트에 빈 목록이 조용히 뜬다. 구단 목록과 마찬가지로 **API 계약이 아니라 배포 체크리스트**에서 다룬다(USER-PL-4 의 인수 기준이 그 확인 쿼리 역할을 겸한다).
- **정렬의 콜레이션 의존.** `players.name` 도 `teams.name` 과 마찬가지로 명시적 콜레이션 없이 `ddl-auto` 가 만든 컬럼이라 DB 기본값에 기댄다. 외국인 선수명이 영문으로 적재되면 `team-list.md` 가 겪은 "영문 먼저" 현상이 그대로 재현된다.
- **`name` 검색어의 `LIKE` 와일드카드가 이스케이프되지 않는다.** Spring Data 의 `Containing` 은 검색어를 `%` 로 감싸기만 하고 안에 든 `%`·`_` 는 이스케이프하지 않는다 — `?name=%25` 는 전체 조회와 같아지고 `?name=_` 는 아무 한 글자에나 걸린다. **오동작이지 보안 문제는 아니다**(파라미터 바인딩이라 SQL 인젝션 경로가 아니고, 응답에 나가는 필드도 공개 목록 그대로다). 선수 이름에 `%`·`_` 가 들어갈 일이 없어 실사용 영향이 없다고 보고 이번 범위에서 뺐다. 고치려면 검색어를 이스케이프하고 `@Query` 에 `ESCAPE` 절을 명시해야 한다.
- **`name` 검색이 인덱스를 타지 못한다.** 선행 와일드카드(`LIKE '%...%'`)라 `players.name` 인덱스를 쓸 수 없어 풀스캔이다. 선수 테이블이 리그 전체를 합쳐도 수백 행 규모라는 전제에서 허용한 것이며, 규모가 커지면 검색 전용 인덱스나 전문 검색(FULLTEXT)으로 옮겨야 한다. `teamId` 를 함께 주면 FK 조건이 먼저 좁히므로 부담이 줄어든다.

## 테스트 대응 (요구사항 ID ↔ 테스트)
| ID | 테스트 |
|---|---|
| USER-PL-1 | `PlayerControllerTest.getPlayers_returns200WithApiResponseWrappedArray` |
| USER-PL-2 | `PlayerControllerTest.getPlayers_itemContainsTeamAndPlayerFieldsOnly` · `PlayerServiceTest.getPlayers_mapsTeamAndPlayerFieldsFromEntity` |
| USER-PL-2a | `PlayerControllerTest.getPlayers_missingNumberAndPosition_serializedAsExplicitNulls` · `PlayerServiceTest.getPlayers_nullPositionGroup_mapsToNullWithoutException` · `PlayerServiceTest.getPlayers_nullUniformNumber_mapsToNull` |
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
| USER-PL-13 | `PlayerControllerTest.getPlayers_withName_passesParameterToService` · `PlayerServiceTest.getPlayers_withNameOnly_usesNameSearchQueryOnly` · `PlayerServiceTest.getPlayers_withInfixName_passesKeywordAsIs` |
| USER-PL-14 | `PlayerControllerTest.getPlayers_withTeamIdAndName_passesBothToService` · `PlayerServiceTest.getPlayers_withTeamIdAndName_usesCombinedQueryOnly` |
| USER-PL-15 | `PlayerServiceTest.getPlayers_blankName_fallsBackToUnfilteredQuery`(`""`·`" "`·`"   "`·`"\t"` 4케이스) · `PlayerServiceTest.getPlayers_blankNameWithTeamId_usesTeamFilterOnly` · `PlayerServiceTest.getPlayers_nameWithSurroundingWhitespace_isTrimmed` · `PlayerControllerTest.getPlayers_emptyNameParameter_passesEmptyStringToService`(컨트롤러가 빈 문자열을 그대로 넘긴다는 전제를 고정) |
| USER-PL-16 | `PlayerServiceTest.getPlayers_noNameMatch_returnsEmptyListWithoutException` · `PlayerControllerTest.getPlayers_noNameMatch_returns200WithEmptyArray` |

**미커버 영역(정직하게 기록)**
- **USER-PL-3 의 실제 정렬**: 단위·슬라이스 테스트는 리포지토리/서비스를 목으로 대체하므로 "서비스·컨트롤러가 받은 순서를 재배열하지 않는다"까지만 검증한다. `ORDER BY name ASC` 가 실제로 기대 순서를 만드는지는 **DB 통합 테스트(`@DataJpaTest`)가 필요**하다. `team-list.md` 와 동일한 한계다.
- **USER-PL-5 의 실제 필터링**: 마찬가지로 `findAllByTeam_IdOrderByNameAsc` 가 올바른 SQL 을 만드는지는 검증하지 않는다. 슬라이스가 보장하는 것은 "서비스가 그 메서드를 그 인자로 고른다"까지다(반대 메서드를 호출하지 않는지는 `verifyNoMoreInteractions` 로 고정).
- **USER-PL-4 의 "모든 행"**: 목이 준 리스트를 그대로 흘려보내는지만 본다. 실제 전수 조회는 DB 통합 테스트 몫이다.
- **USER-PL-13/14 의 실제 부분 일치**: 위와 같은 한계다. `Containing` 이 실제로 `LIKE '%검색어%'` SQL 을 만들어 `"도영"` 이 `"김도영"` 에 걸리는지는 목으로 검증할 수 없다 — 단위 테스트가 고정하는 것은 **"서비스가 `Containing` 쿼리를 검색어 그대로 고른다"**(따라서 `StartingWith` 로 바꿔도 이 테스트는 안 깨진다)까지다. **prefix/contains 계약 자체를 지키는 회귀 장치는 없으며**, 이 축을 실제로 보장하려면 `@DataJpaTest` 가 필요하다.
- **USER-PL-15 의 "빈 문자열이 넘어온다"는 전제**: `PlayerControllerTest.getPlayers_emptyNameParameter_passesEmptyStringToService` 가 MockMvc 로 실측해 고정했다(Spring 이 `?name=` 을 `null` 로 바꾸는 버전이 오면 이 테스트가 먼저 깨진다).
