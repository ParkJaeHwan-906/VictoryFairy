# 경기 선발 라인업 조회 + 경기 목록 구단 식별자 노출 요구사항
> 상태: **승인됨 (2026-08-04)** · 모듈: user · 최종 수정: 2026-08-04(승인 시점 2차 개정 — `USER-GL-22` 를 404 흡수로, 타자 판정을 `bat_order BETWEEN 1 AND 9` 로)
> **한 문서에 두 가지가 들어 있다.** ①기존 계약 변경(`GET /api/games` 응답에 구단 PK 2개 추가, `USER-GTID-*`) ②신규 엔드포인트(경기별 선발 라인업 조회, `USER-GL-*`). 둘을 묶은 이유는 ①이 오직 ②를 쓰기 위해 필요하기 때문이다 — ①만 따로 하면 쓸 곳이 없고, ②만 하면 클라이언트가 응답의 `teamId` 를 홈/원정에 대응시킬 방법이 없다.
> **2026-08-04 1차 개정 요약**: 응답 형상이 "투수 목록/타자 목록 2개"에서 **"팀 그룹 배열, 그룹 안에 투수/타자"** 로 바뀌었다(결정 5). 존재하지 않는 경기는 **404**(결정 1), 경로는 **`/api/games/lineup`**(결정 3)으로 확정. 이에 따라 `USER-GL-4~11`·`14`·`15`·`20`~`22` 를 개정하고 `USER-GL-7` 을 삭제, `USER-GL-26~28` 을 신설했다.
> **2026-08-04 2차 개정(승인 시점) 요약**: ①`USER-GL-22`(빈/공백 `gameId`)를 400 에서 **404 `GAME_NOT_FOUND` 흡수**로 바꿨다(결정 7) ②타자 판정을 `bat_order IS NOT NULL` 에서 **`bat_order BETWEEN 1 AND 9`** 로 좁히고(`USER-GL-5` 개정) 범위 밖 값 처리를 `USER-GL-29` 로 신설했다(결정 8).
> **작성 시점 주의**: `docs/requirements/user/` 에 경기(game) 관련 요구사항 문서는 이 문서가 처음이다. `GET /api/games` 의 기존 계약은 요구사항 문서가 아니라 **구현 후 작성된 `docs/api/game.md`** 에만 적혀 있다 — 아래 `USER-GTID-3`(기존 8개 필드 유지)이 참조하는 "기존 계약"의 출처는 그 문서다.

## 배경 / 목적
경기 목록 응답은 홈/원정 구단을 **이름 문자열**로만 준다(`homeTeam: "LG"`). 라인업 응답은 반대로 팀 그룹을 **구단 PK**(`teamId`)로 구분한다 — 한 경기에 두 팀 선발이 함께 나오기 때문이다. 그 사이를 잇는 값이 지금 어디에도 없어서, 클라이언트는 라인업의 `teamId` 가 홈인지 원정인지 **이름 문자열을 문자열 비교하는 방법 말고는** 알 수 없다(`docs/api/game.md` 말미의 "관련 문서" 항목이 이 구멍을 이미 지적하고 있다). `USER-GTID-*` 는 그 구멍만 메운다.

라인업 조회 자체의 계약에서 실제로 갈리는 지점은 "목록을 준다"가 아니라 **세 가지 경계**다.
1. **식별자 축** — 요청의 `gameId` 는 내부 PK 가 아니라 `Game.naverGameId`(py-collector 소스 자연키)다. 이 값은 `GameResponse.gameId` 로 **이미 외부에 노출돼 있는 유일한 경기 식별자**라, 클라이언트가 목록에서 받은 값을 그대로 돌려주는 흐름이 성립한다.
2. **투수/타자 분리 기준** — 포지션이 아니라 `bat_order` 다: NULL 이면 투수, 1~9 면 타자, **그 밖의 값은 어느 쪽도 아니다**(아래 "해석 근거 1·4").
3. **"경기가 없다"와 "라인업이 아직 없다"를 응답으로 구별한다** — KBO 선발 라인업은 경기 시작 직전에야 공시되므로 **행 0건은 오류가 아니라 정상 상태의 대다수 시간**이다. 반면 잘못된 `gameId` 는 클라이언트 오류다. 프론트가 두 경우를 다르게 처리하므로 전자는 200 + 빈 배열, 후자는 404 로 갈린다(결정 1).

## 범위
- 포함
  - `GameResponse` 에 `homeTeamId`·`awayTeamId`(구단 내부 PK, 정수) 추가. 기존 필드는 전부 유지(하위 호환)
  - 경기별 선발 라인업 조회 엔드포인트 1개(`GET /api/games/lineup`, 무인증 공개, 쿼리 파라미터 `gameId` 1개)
  - 응답을 **팀 그룹 배열**로 반환하고 그룹 안에서 투수/타자를 분리
  - `:common` 의 `ErrorCode` 에 신규 상수 `GAME_NOT_FOUND`(404, `"존재하지 않는 경기입니다."`) 추가
  - `SecurityConfig` 에 `/games/lineup` 을 GET 한정 `permitAll` 로 여는 변경
- 제외
  - **교체 출전 선수(`is_starter = false`) 조회** — 이번 화면은 "선발 라인업"이다. 같은 테이블에 교체 행이 함께 들어 있지만 이번 계약은 선발만 본다(`USER-GL-3`)
  - **`decision`(W/L/S/H) 노출** — 경기 종료 후에만 채워지는 결과 데이터라 선발 라인업 화면의 관심사가 아니다. 필요해지면 별도 요구사항
  - **팀별 필터 파라미터(`?teamId=`)** — 한 경기의 두 팀을 한 번에 주고 클라이언트가 그룹으로 나눠 쓴다(`USER-GL-15`). 서버 필터를 추가하면 홈/원정을 그리는 화면이 요청을 2회 내야 한다
  - **선수 id(`playerId`) 노출** — 이번 응답은 **전시 목적**이라 이름·포지션·타순이면 충분하다(결정 6). 라인업에서 선수 상세로 넘어가는 화면이 생기면 그때 필드를 추가한다(아래 "후속" 참조)
  - **타율·성적 등 선수 스탯** — `Player.average` 를 계약에 넣지 않는 이유는 `player-list.md` "범위" 와 동일(갱신 주기·기준 미정의)
  - **경기 상세(스코어보드·이닝별 기록)** — 이번 엔드포인트는 라인업 한 축만 본다
  - **응답에 홈/원정 구분 플래그** — 그룹의 `teamId` 를 `GET /api/games` 의 `homeTeamId`/`awayTeamId` 와 맞춰 클라이언트가 판정한다(결정 5). `isHome` 같은 필드를 라인업 응답에 넣으면 라인업 조회가 `games` 의 홈 팀 정보까지 알아야 한다
  - **`gameExists` 같은 상태 플래그 필드** — 404 로 구별하므로 불필요하다(결정 1)
  - **페이징·캐시 헤더** — 한 경기의 선발은 최대 20여 행이라 페이징이 무의미하다. 캐시 전략은 이번 범위 밖
  - **`quiz` 모듈 쪽 노출** — `user` 모듈 전용
  - **`Game.id`(내부 PK) 노출** — `gameId`(naverGameId)가 이미 외부 식별자 역할을 하므로 PK 를 추가로 열지 않는다(`docs/api/game.md` 의 현행 정책 유지)

## 응답 형상 (아래 요구사항이 참조하는 구조)
```
ApiResponse<List<GameLineupResponse>>

GameLineupResponse   : { teamId, pitchers: [...], batters: [...] }   ← 팀 그룹
  pitchers 항목      : { name, positionName }
  batters  항목      : { name, positionName, batOrder }
```
```json
{"success":true,"data":[
  {"teamId":3,"pitchers":[{"name":"원태인","positionName":"P"}],
              "batters":[{"name":"김지찬","positionName":"CF","batOrder":1}]},
  {"teamId":6,"pitchers":[{"name":"양현종","positionName":"P"}],
              "batters":[{"name":"박찬호","positionName":"SS","batOrder":1}]}
],"message":null}
```
**`teamId` 는 항목마다 반복하지 않고 그룹 키 하나로 표현한다.** "선수가 어느 팀인지"는 그룹 소속으로 결정된다.

## 요구사항 (EARS)

### [1] 경기 목록 응답에 구단 식별자 추가 (`GET /api/games`)
> 이번 개정에서 손대지 않았다(초안 그대로 확정).

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GTID-1 | 유비쿼터스 | THE 시스템 SHALL 경기 목록 응답의 각 항목에 홈 구단의 내부 식별자를 `homeTeamId`(정수)로 포함한다 | `GET /api/games?date=2026-08-01` → `data[0].homeTeamId` 가 정수이고 해당 행의 `games.home_team_id` 와 일치 |
| USER-GTID-2 | 유비쿼터스 | THE 시스템 SHALL 경기 목록 응답의 각 항목에 원정 구단의 내부 식별자를 `awayTeamId`(정수)로 포함한다 | 같은 요청 → `data[0].awayTeamId` 가 `games.away_team_id` 와 일치 |
| USER-GTID-3 | 유비쿼터스 | THE 시스템 SHALL 기존 8개 키(`gameId`·`stadium`·`homeTeam`·`awayTeam`·`homeTeamScore`·`awayTeamScore`·`gameDate`·`gameState`)를 이름·타입·의미 그대로 유지한다 | `data[0]` 의 키 집합이 정확히 기존 8개 + `homeTeamId` + `awayTeamId` = **10개**. 기존 키의 값은 변경 전과 동일(특히 `homeTeam`·`awayTeam` 은 여전히 구단 **이름 문자열**이며 id 로 바뀌지 않는다) |
| USER-GTID-4 | 유비쿼터스 | THE 시스템 SHALL `homeTeamId`·`awayTeamId` 에 `GET /api/teams` 응답의 `id` 와 동일한 식별 체계 값을 사용한다 | 두 값이 `/api/teams` 의 `data[].id` 집합에 포함되고, 그 항목의 `name` 이 같은 경기 항목의 `homeTeam`·`awayTeam` 문자열과 각각 일치 |
| USER-GTID-5 | 유비쿼터스 | THE 시스템 SHALL `homeTeamId`·`awayTeamId` 를 `null` 이 아닌 값으로 반환한다 | 응답의 모든 항목에서 두 필드가 non-null. `stadium` 이 `null` 인 경기에서도 두 필드는 값이 있다(`stadium` 과 달리 두 FK 는 not null) |
| USER-GTID-6 | 유비쿼터스 | THE 시스템 SHALL 두 필드 추가로 경기 목록 조회의 SQL 실행 횟수를 늘리지 않는다 | 같은 `date` 요청에서 Hibernate 문장 수가 변경 전과 동일(경기 N건이어도 구단 조회가 N회 추가되지 않음) |
| USER-GTID-7 | 유비쿼터스 | THE 시스템 SHALL 두 필드 추가로 경기 목록의 조회 범위와 정렬 순서를 변경하지 않는다 | 같은 `date` 요청의 `data[].gameId` 순서·개수가 변경 전과 동일(`gameDate` 오름차순 유지) |

### [2] 경기별 선발 라인업 조회 (`GET /api/games/lineup`)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GL-1 | 이벤트 | WHEN 클라이언트가 유효한 `gameId` 와 함께 라인업 조회를 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 팀 그룹 배열을 반환한다 | `GET /api/games/lineup?gameId=20260801LGSS02026` → 200, 본문 `{"success":true,"data":[…],"message":null}` 이고 `data` 는 배열 |
| USER-GL-2 | 유비쿼터스 | THE 시스템 SHALL 요청의 `gameId` 를 `Game.naverGameId`(문자열 자연키)로 해석한다 | `GET /api/games` 가 돌려준 `gameId` 값을 그대로 넣으면 그 경기의 라인업이 반환된다. 같은 경기의 내부 PK(`games.id`, 예: `1`)를 넣으면 — 그 값과 같은 `naver_game_id` 를 가진 행이 없는 한 — 404 다(`USER-GL-20`) |
| USER-GL-3 | 유비쿼터스 | THE 시스템 SHALL `game_lineups.is_starter = true` 인 행만 응답에 포함한다 | 같은 경기에 `is_starter = false` 행(교체 출전)이 있어도 그 선수 이름이 응답 어디에도 없음 |
| USER-GL-4 | 유비쿼터스 | THE 시스템 SHALL `bat_order` 가 NULL 인 선발 행을 그 행의 팀 그룹 `pitchers` 에 포함한다 | 어떤 팀의 `bat_order IS NULL AND is_starter = true AND team_id = T` 행 수 = `teamId = T` 그룹의 `pitchers` 길이 |
| USER-GL-5 | 유비쿼터스 | **(2026-08-04 개정)** THE 시스템 SHALL `bat_order` 가 1 이상 9 이하인 선발 행을 그 행의 팀 그룹 `batters` 에 포함한다 | 같은 팀의 `bat_order BETWEEN 1 AND 9 AND is_starter = true` 행 수 = 그 그룹의 `batters` 길이. 두 목록에 동시에 나타나는 선수는 없다. **정상 데이터에서는** 전 그룹의 `pitchers`+`batters` 길이 합 = 그 경기의 `is_starter = true` 행 수이며, 범위 밖 타순 행이 있으면 그 수만큼 줄어든다(`USER-GL-29`) |
| USER-GL-6 | 유비쿼터스 | THE 시스템 SHALL 각 그룹의 `batters` 를 `batOrder` 오름차순으로 정렬해 반환한다 | 한 그룹의 `batOrder` 수열이 엄격히 증가(정상 경기라면 `1,2,…,9` — 그룹 안에는 한 팀만 있어 중복이 없다) |
| USER-GL-7 | — | **(삭제됨, 2026-08-04)** 초안의 "같은 `batOrder` 를 가진 타자들의 2차 정렬 키" 요구사항. 팀 단위 그룹핑(결정 5)으로 한 그룹 안에 같은 타순이 두 번 나오는 상황 자체가 사라져 불필요해졌다. **번호는 재사용하지 않는다** | — |
| USER-GL-8 | 유비쿼터스 | THE 시스템 SHALL 각 그룹의 `pitchers` 를 `name` 오름차순(DB 콜레이션 기준)으로 정렬해 반환한다 | 동일 DB 상태에서 2회 연속 호출 시 `pitchers` 순서가 동일하고 `name` 이 비내림차순 |
| USER-GL-9 | 유비쿼터스 | THE 시스템 SHALL 팀 그룹 항목에 `teamId`·`pitchers`·`batters` 3개 필드만 포함한다 | `data[0]` 의 키 집합이 정확히 `{"teamId","pitchers","batters"}`(`teamName`·`isHome`·`gameExists` 키 없음) |
| USER-GL-10 | 유비쿼터스 | THE 시스템 SHALL `pitchers` 항목에 `name`·`positionName` 2개 필드만 포함한다 | `data[0].pitchers[0]` 의 키 집합이 정확히 `{"name","positionName"}`(`teamId`·`batOrder`·`decision`·`playerId`·`isStarter` 키 없음) |
| USER-GL-11 | 유비쿼터스 | THE 시스템 SHALL `batters` 항목에 `name`·`positionName`·`batOrder` 3개 필드만 포함한다 | `data[0].batters[0]` 의 키 집합이 정확히 `{"name","positionName","batOrder"}` 이고 `batOrder` 는 정수(non-null) |
| USER-GL-12 | 유비쿼터스 | THE 시스템 SHALL 항목의 `name` 에 `players.name` 을 가공 없이 반환한다 | 임의 항목의 `name` 이 해당 `player_id` 행의 `players.name` 과 문자열 동일 |
| USER-GL-13 | 유비쿼터스 | THE 시스템 SHALL 항목의 `positionName` 에 `positions.name`(py-collector 약어 표기)을 가공 없이 반환한다 | 선발투수 항목의 `positionName` 이 `"P"`(한글 `"투수"` 가 아님), 지명타자는 `"DH"`, 1루수는 `"1B"` |
| USER-GL-14 | 유비쿼터스 | THE 시스템 SHALL 그룹의 `teamId` 에 `game_lineups.team_id` 를 반환하며, 이 값은 `USER-GTID-1`·`USER-GTID-2` 및 `GET /api/teams` 의 `id` 와 동일한 식별 체계다 | **이번 두 변경을 잇는 인수 기준**: 같은 경기에 대해 `data[].teamId` 집합 == 경기 목록의 그 경기 `{homeTeamId, awayTeamId}` 집합. 클라이언트는 이 대응만으로 각 그룹이 홈인지 원정인지 판정할 수 있다 |
| USER-GL-15 | 유비쿼터스 | THE 시스템 SHALL 한 경기의 홈·원정 두 팀 선발을 구단 필터 없이 같은 응답에 포함한다 | 양 팀 라인업이 모두 수집된 경기에서 `data` 길이가 2 |
| USER-GL-16 | 이벤트 | WHEN `Authorization` 헤더 없이 라인업 조회 요청이 들어오면, THE 시스템 SHALL 200과 라인업 응답을 반환한다 | 헤더 없이 `GET /api/games/lineup?gameId=…` → 200 (401 `"인증이 필요합니다."` 가 아님) |
| USER-GL-17 | 예외 | IF 만료되었거나 위조된 access 토큰이 `Authorization` 헤더에 담겨 오면, THEN THE 시스템 SHALL 200과 헤더 없을 때와 동일한 응답을 반환한다 | `Authorization: Bearer not-a-jwt` → 200, 본문이 헤더 없을 때와 동일(응원 구단 등 계정 상태가 응답을 바꾸지 않음) |
| USER-GL-18 | 예외 | IF 라인업 경로에 GET 이외의 메서드로 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."` 를 반환한다 | `POST /api/games/lineup`(헤더 없음) → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (405 아님 — `permitAll` 을 GET 으로 좁힌 결과) |
| USER-GL-19 | 예외 | IF 요청에 `gameId` 파라미터가 아예 없으면, THEN THE 시스템 SHALL 400을 반환하고 조회를 수행하지 않는다 | `GET /api/games/lineup`(파라미터 없음) → 400. 서비스·리포지토리 호출 없음. **이 응답은 `ApiResponse` 래퍼가 아니다**(아래 "표기 근거 2") |
| USER-GL-20 | 예외 | IF `gameId` 와 일치하는 `naver_game_id` 행이 없으면, THEN THE 시스템 SHALL 404와 `GAME_NOT_FOUND` 오류 응답을 반환한다 | `GET /api/games/lineup?gameId=없는값` → 404, `{"success":false,"data":null,"message":"존재하지 않는 경기입니다."}` (`ApiResponse` 래퍼 — `BusinessException` 이 `GlobalExceptionHandler` 를 탄다) |
| USER-GL-21 | 예외 | IF 경기는 존재하지만 선발 라인업 행이 0건이면, THEN THE 시스템 SHALL 404가 아니라 200과 빈 배열을 반환한다 | 라인업 미수집 경기(경기 시작 전) 요청 → 200, `{"success":true,"data":[],"message":null}`. **`USER-GL-20` 과 상태 코드로 구별된다**(404 vs 200) |
| USER-GL-22 | 예외 | **(2026-08-04 개정)** IF `gameId` 가 빈 문자열이거나 공백 문자로만 이루어져 있으면, THEN THE 시스템 SHALL 존재하지 않는 `gameId` 와 동일하게 404와 `GAME_NOT_FOUND` 오류 응답을 반환한다 | `?gameId=` · `?gameId=%20` → 404, `{"success":false,"data":null,"message":"존재하지 않는 경기입니다."}` (`USER-GL-20` 과 완전히 동일한 응답. 400 도 200 도 아님) |
| USER-GL-23 | 예외 | IF 선발 행의 `position_id` 가 NULL 이면, THEN THE 시스템 SHALL 그 항목을 목록에서 제외하지 않고 `positionName` 을 `null` 로 반환한다 | `position_id IS NULL` 인 선발 행이 있는 경기 → 해당 항목이 응답에 존재하고 `positionName` 이 `null`(`"-"`·`""` 같은 대체 문자열이 아님). 목록 길이는 `USER-GL-4`·`USER-GL-5` 의 행 수와 여전히 일치 |
| USER-GL-24 | 유비쿼터스 | THE 시스템 SHALL 라인업 조회 요청으로 `games`·`game_lineups` 의 어떤 행도 생성·수정·삭제하지 않는다 | 조회 전후 `SELECT COUNT(*), MAX(updated_at) FROM game_lineups` 결과가 동일(존재하지 않는 `gameId` 요청 시에도 행이 생기지 않음) |
| USER-GL-25 | 유비쿼터스 | THE 시스템 SHALL 페이징 파라미터를 해석하지 않고 해당 경기의 선발 전체를 반환한다 | `?gameId=…&page=1&size=5` → 그룹 수·항목 수가 `page`/`size` 와 무관. `content`/`totalElements` 같은 페이지 필드 없음 |
| USER-GL-26 | 예외 | IF 한 팀의 선발만 적재돼 있으면, THEN THE 시스템 SHALL 그 팀 그룹 1개만 담은 배열을 반환한다 | 한쪽 팀 행만 있는 경기 → `data` 길이 1, 그 `teamId` 가 적재된 팀. 없는 팀의 빈 그룹(`pitchers`·`batters` 가 모두 빈 그룹)을 만들어 넣지 않는다 |
| USER-GL-27 | 유비쿼터스 | THE 시스템 SHALL 팀 그룹을 `teamId` 오름차순으로 정렬해 반환한다 | 동일 DB 상태에서 2회 연속 호출 시 `data[].teamId` 순서가 동일하고 오름차순(홈 팀이 항상 앞이라는 보장은 없다 — 홈/원정 대응은 `USER-GL-14`) |
| USER-GL-28 | 유비쿼터스 | THE 시스템 SHALL 한 팀당 그룹을 1개만 반환한다 | `data[].teamId` 에 중복 값이 없음(`data` 길이 == distinct `teamId` 개수) |
| USER-GL-29 | 예외 | **(2026-08-04 신설)** IF 선발 행의 `bat_order` 가 1~9 범위 밖(0·음수·10 이상)이면, THEN THE 시스템 SHALL 그 행을 `pitchers` 에도 `batters` 에도 포함하지 않는다 | `bat_order = 0`·`bat_order = 10`·음수인 선발 행이 있는 경기 → 그 선수 이름이 응답 어디에도 없고, 해당 그룹의 `batters` 길이가 그만큼 짧다(500·예외 없이 200). 이 행 때문에 그룹 자체가 사라지지는 않는다 |

### 표기 근거 (요구사항 아님 — 위 문장을 읽는 데 필요한 사실)
1. **`USER-GL-18` 의 401(405 아님)은 `permitAll` 을 GET 으로만 여는 것의 귀결이다.** `/teams`(USER-TM-9)·`/players`(USER-PL-12)·`/games` 가 모두 같다.
2. **`USER-GL-19` 의 "래퍼 아님"은 설계 선택이 아니라 현재 구조의 귀결이다.** 필수 `@RequestParam` 누락(`MissingServletRequestParameterException`)은 컨트롤러 진입 전 바인딩 단계에서 발생해 `GlobalExceptionHandler`(`BusinessException`·`MethodArgumentNotValidException` 둘만 처리)에 잡히지 않고 Spring 기본 `DefaultHandlerExceptionResolver` 가 처리한다. `player-list.md` USER-PL-7(`teamId` 타입 오류)·`docs/api/game.md` 의 `date` 형식 오류 400과 같은 사정이다. **2026-08-04 개정으로 `USER-GL-22` 가 404 로 옮겨가면서, 이 엔드포인트에서 래퍼 없는 응답은 `USER-GL-19` 하나만 남았다.**
3. **`USER-GL-19`(파라미터 없음, 400)와 `USER-GL-22`(파라미터는 있으나 빈 값, 404)는 서로 다른 경로다.** `?gameId=` 를 Spring 은 `null` 이 아니라 **빈 문자열로 넘기므로**(`player-list.md` USER-PL-15 가 MockMvc 로 실측해 고정한 사실) `required = true` 만으로는 이 둘이 합쳐지지 않는다 — 전자는 바인딩 단계에서 걸리고 후자는 컨트롤러 안까지 들어온다. 개정 후 계약은 "파라미터 자체가 없으면 400, 값이 비었으면 다른 못 찾는 값과 똑같이 404"다(결정 7).
4. **`USER-GL-6` 의 "엄격히 증가"는 그룹핑의 귀결이다.** 초안에서는 두 팀이 한 목록에 섞여 `1,1,2,2,…` 였고 그래서 2차 정렬 키(삭제된 `USER-GL-7`)가 필요했다. 그룹 안에는 한 팀만 있으므로 타순이 유일하다 — 만약 한 그룹에서 같은 `batOrder` 가 2번 나온다면 그것은 정렬 문제가 아니라 **적재 이상**이다.
5. **인수 기준이 고정 데이터를 쓰지 않는 이유**는 `player-list.md` 와 동일하다 — `games`·`game_lineups` 에는 시드가 없고 py-collector 적재로만 행이 생긴다. 따라서 기준은 "DB 상태와의 상대적 일치"(행 수 일치, 집합 일치)로 쓴다. 로컬·신규 DB 의 기본 상태에서는 경기 자체가 없어 **`USER-GL-20`(404)** 이 먼저 나온다는 점에 주의(초안에서는 빈 응답이었다).

## 해석 근거 (사용자 제공 SQL 을 요구사항으로 옮기며 내린 판단)
1. **원본 SQL 의 `bat` CTE 에는 타순 조건이 아예 없었다.** 그대로 옮기면 투수 CTE(`bat_order IS NULL`)와 타자 CTE 의 결과가 겹쳐 **선발투수가 타자 목록에도 나타난다.** 두 CTE 를 나눈 의도가 투수/타자 분리인 것이 분명하므로 타자 쪽에 타순 조건을 보완했고, **2026-08-04 개정에서 그 조건을 `IS NOT NULL` 이 아니라 `BETWEEN 1 AND 9` 로 좁혔다**(결정 8). `USER-GL-5` 의 인수 기준("두 목록에 동시에 나타나는 선수 없음", "정상 데이터에서 길이 합 = 선발 행 수")이 이 보완을 그대로 검증한다.
2. **분류 기준은 포지션이 아니라 `bat_order` 다.** `positions` 에 `P`(투수)가 있으므로 "포지션이 P 면 투수"로도 나눌 수 있지만, `Position` 값은 py-collector 가 매핑에 없는 표기를 만나면 **원문 그대로 적재**하는 열린 집합이라(엔티티 Javadoc) 분류 키로 쓰면 미지 표기 하나에 분류가 무너진다. `bat_order` 는 NULL 여부만 보므로 그런 취약점이 없다.
3. **위 2의 대가**: 지명타자를 쓰지 않는 경기(투수가 타순에 들어가는 경우)에서는 **선발투수가 `batters` 에 들어가고 `pitchers` 가 비게 된다.** KBO 는 지명타자제를 상시 사용하므로 실사용 영향이 없다고 보고 허용했다. 클라이언트가 "`pitchers` 는 항상 1건"을 가정하면 안 되는 이유이기도 하다(`USER-GL-21` 의 0건 상태, `USER-GL-26` 의 한쪽 팀만 적재된 상태도 마찬가지).
4. **타순 1~9 는 도메인 사실이지만 스키마가 강제하지 않는다.** `GameLineup.batOrder` 는 CHECK 제약 없는 nullable `Integer` 이고(`@Column(name = "bat_order")` 뿐), 값의 정합성은 전적으로 py-collector 적재에 달려 있다 — 즉 **`0`·`10`·음수가 물리적으로 들어올 수 있다.** `USER-GL-5` 의 범위 조건과 `USER-GL-29` 는 "일어날 리 없는 값을 막는 방어 코드"가 아니라, 원천을 신뢰하는 구조에서 **응답이 어떤 모습이어야 하는지를 계약으로 정한 것**이다(같은 성격의 열린 값 문제로 `positions.name`·`gameState` 가 이미 있다).
5. **원본 SQL 에는 팀 그룹핑이 없었다.** 두 팀 선발이 한 결과 집합에 섞여 나오는 형태였고, 그것을 팀 단위로 접는 판단(결정 5)은 응답 계약에서 내린 것이다 — 조회 자체는 여전히 팀 필터 없이 한 경기 전체를 읽는다(`USER-GL-15`).

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **`SecurityConfig` 에 `.requestMatchers(HttpMethod.GET, "/games/lineup").permitAll()` 한 줄이 별도로 필요하다.** 기존 `/games` 매처는 **정확 경로 매칭**이라 하위 경로를 커버하지 않는다 — 빠뜨리면 `anyRequest().authenticated()` 에 걸려 **모든 요청이 401** 이 되고 `USER-GL-16` 이 실패한다(선수 목록 초안이 실제로 밟은 함정). 경로가 `/games` 아래로 들어갔다는 사실이 오히려 "이미 열려 있겠지"라는 착각을 부르기 쉬우니 주의.
- **`requestMatchers` 경로에는 context-path(`/api`)를 붙이지 않는다.** 외부 경로가 `/api/games/lineup` 이어도 매처는 `/games/lineup` 이다. MockMvc 슬라이스 테스트도 접두사 없는 경로로 요청한다.
- **`:common` 의 `ErrorCode` 에 `GAME_NOT_FOUND`(404, `"존재하지 않는 경기입니다."`) 를 추가해야 한다.** 기존 `TEAM_NOT_FOUND`/`PLAYER_NOT_FOUND` 와 같은 형태이며 `BusinessException` 으로 던지면 `GlobalExceptionHandler`(`web-support`)가 `ApiResponse` 로 감싼다 — `USER-GL-20` 의 본문 형태는 이 경로의 귀결이다. **`ErrorCode` 는 `user`·`quiz` 가 공유하는 모듈이므로 상수 추가는 공유 부품 변경이다**(기존 상수의 코드·메시지를 건드리지 않는 추가라 다른 모듈 응답은 바뀌지 않는다).
- **404 를 위한 추가 쿼리는 사실상 0회다.** `USER-GL-2`(naverGameId → 내부 id 해석) 때문에 `games` 조회는 어차피 반드시 1회 필요하고, 그 결과가 비었는지 보는 것이 곧 존재 검증이다. `player-list.md` 가 `teamId` 존재 검증을 뺀 이유("존재 확인에 조회를 한 번 더 쓰지 않는다")가 여기서는 성립하지 않는다 — 이것이 결정 1의 실질적 근거 중 하나다.
- **`GameLineupRepository` 의 기존 메서드는 둘 다 `Long gameId`(내부 PK)를 받는다**(`findByGameId`, `findByGameIdAndIsStarterTrue`). 요청의 `gameId` 는 문자열 자연키라 그대로 넘길 수 없다 — 스텁의 `gameLineupRepository.findByGameId(gameId)` 는 타입이 맞지 않는다. 또한 **`GameRepository` 에는 `naverGameId` 로 경기를 찾는 메서드가 아직 없다**(현재 조회 메서드는 날짜 반개구간 하나뿐). `USER-GL-2`·`USER-GL-20` 을 만족하려면 이 두 사실 중 하나는 반드시 해소돼야 한다(어떻게 해소할지는 구현 판단).
- **`GameLineup` 의 `game`·`team`·`player`·`position` 은 전부 LAZY 다.** 응답 변환에서 `getPlayer().getName()`·`getPosition().getName()` 을 행마다 부르면 선발 20여 행에 대해 조회가 수십 회 나간다(`player-list.md` 의 LAZY 원칙, `support-selection.md` 의 "응원 선수 응답은 2쿼리" 와 같은 계열의 제약). 반면 **`teamId` 는 `getTeam().getId()` 로 읽어도 프록시가 초기화되지 않는다** — 그룹 키로 쓰는 값이 마침 프록시를 깨우지 않는 유일한 값이다.
- **prod 는 `open-in-view: false` 다.** 서비스 트랜잭션 밖에서 LAZY 연관을 건드리면 조회가 `LazyInitializationException`(500)으로 떨어진다(`GameRepository` 주석이 같은 이유로 `@EntityGraph` 목록과 DTO 가 읽는 연관을 1:1로 유지하라고 못 박고 있다).
- **빈 `gameId` 에 400 을 주려면 대가가 두 가지였고, 그래서 400 을 포기했다**(결정 7 — `USER-GL-22` 는 404 다). ①`@Validated`+`@NotBlank` 로 걸면 `ConstraintViolationException` 이 나는데 `GlobalExceptionHandler` 가 처리하는 예외는 `BusinessException`·`MethodArgumentNotValidException` 둘뿐이라 **잡히지 않고 500 이 된다.** 이를 400 으로 만들려면 `web-support` 에 핸들러를 추가해야 하고, 그것은 `quiz` 응답까지 바꾸는 공유 부품 변경이다. ②`BusinessException` 으로 던지면 400 은 나오지만 `"gameId 는 필수입니다"` 류의 **400 전용 `ErrorCode` 를 하나 더** 추가해야 한다. 반면 빈 문자열은 어차피 일치하는 `naver_game_id` 가 없어 **결과적으로 "존재하지 않는 경기"와 같다** — 별도 분기 없이 `GAME_NOT_FOUND` 로 흡수하면 두 대가가 모두 사라진다.
- **`USER-GTID-1`·`USER-GTID-2` 는 추가 쿼리를 유발하지 않는다.** `GameRepository` 가 이미 `@EntityGraph(attributePaths = {"homeTeam","awayTeam","stadium","gameStatus"})` 로 두 구단을 함께 로딩하고, id 접근은 프록시 초기화도 필요 없다. 그래서 `USER-GTID-6` 을 계약으로 걸 수 있다.
- **자연키 노출 정책과 충돌하지 않는다.** `Team.code`·`Player.kboPlayerId` 는 여전히 비노출이고, 이번에 노출하는 `homeTeamId`/`awayTeamId`/`teamId` 는 **`TeamResponse.id` 로 이미 공개된 내부 PK** 다. 요청 파라미터로 받는 `gameId`(naverGameId)도 `GameResponse.gameId` 로 이미 공개된 값이라 새로 여는 것이 없다.
- **`positions.name` 의 값 집합은 코드로 닫혀 있지 않다.** py-collector 가 매핑에 없는 표기를 warning 후 원문 그대로 적재하므로 `P`/`C`/`1B`/`2B`/`3B`/`SS`/`LF`/`CF`/`RF`/`DH`/`PH`/`PR` 밖의 값이 나타날 수 있다 — `gameState` 가 `game_statuses` 테이블 값이라 열린 집합인 것과 같은 성격이다. `USER-GL-13` 이 "가공 없이 반환"인 이유이며, 표기 변환(약어 → 한글)을 서버에서 하기로 하면 이 열린 집합을 서버가 떠안게 된다.
- **`game_lineups` 는 `(game_id, player_id)` UNIQUE 다.** 한 선수가 한 경기에서 두 행을 갖지 않으므로 `USER-GL-5` 의 "두 목록에 동시에 나타나는 선수 없음"이 데이터 차원에서도 보장된다.
- **DTO 는 앱 모듈(`user.game.dto`)에 둔다.** `:domain` 에 API 계약을 두면 `quiz` 까지 끌려간다(`player-list.md` 제약과 동일). 현재 스텁은 이미 올바른 자리에 있으나, **스텁의 `GameLineupResponse(Long teamId, String name, String positionName)` 는 개정된 응답 형상과 맞지 않는다**(그룹 DTO 1개 + 항목 DTO 2종이 필요하다).
- **이 문서는 기존 `user` 모듈 정책과 충돌하지 않는다.** 단 한 곳, "조회 필터에 존재 검증을 붙이지 않는다"는 컨벤션과 `USER-GL-20`(404)의 관계는 **의도된 예외**이며 근거는 아래 결정 1에 남겼다. `permitAll` 확대는 규칙 추가이며 인증이 필요한 기존 경로의 동작을 바꾸지 않는다. `GameResponse` 변경은 **필드 추가만** 이라 기존 클라이언트를 깨지 않는다(`USER-GTID-3`).

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
초안의 미해결 질문 6건에 대한 **사용자 확정 답변**(2026-08-04)이다.

1. **존재하지 않는 `gameId` 는 404 다**(`USER-GL-20`, `GAME_NOT_FOUND`). 이 모듈에는 "조회 필터에 존재 검증을 붙이지 않는다"는 컨벤션이 있고 `player-list.md` USER-PL-6 은 없는 `teamId` 에 200 + 빈 배열을 준다 — **그 컨벤션은 `teamId`·`name` 같은 필터에 대한 것**이고, 여기 `gameId` 는 결과를 좁히는 필터가 아니라 **대상 자원을 지정하는 식별자**다. 없는 식별자는 "결과가 빈 조회"가 아니라 "지칭 대상이 없음"이므로 예외로 둔다. **이 구분은 이 모듈에 이미 선례가 있다**: 똑같은 `teamId` 값이라도 `GET /players?teamId=999999`(필터)는 200 + 빈 배열이지만, `POST /support/team {teamId: 999999}`(대상 지정)는 `SupportService` 가 `TEAM_NOT_FOUND` 로 **404** 를 낸다. 즉 컨벤션이 뒤집힌 것이 아니라 `gameId` 가 후자에 속할 뿐이다. 비용 측면의 근거도 있다: 내부 id 해석을 위해 `games` 조회가 어차피 필요해 존재 검증이 사실상 공짜다(위 "제약" 참조).
2. **경기는 유효하나 라인업이 0건이면 200 + 빈 배열이다**(`USER-GL-21`). 선발 라인업은 경기 직전에 공시되므로 0건은 정상 상태이며, 프론트는 이 둘("잘못된 경기" vs "아직 공시 전")을 **다르게 처리한다** — 그래서 상태 코드로 구별한다. `gameExists` 같은 **플래그 필드는 두지 않는다**(상태 코드가 이미 그 역할을 하며, 필드를 두면 정상 응답 스키마에 오류 표현이 섞인다).
3. **경로는 `GET /api/games/lineup?gameId=`** 다. 스텁의 `/lineup` 은 이 모듈의 공개 조회 경로가 전부 리소스 명사(`/teams`·`/players`·`/games`)라는 컨벤션에서 혼자 떨어져 나온다. 경기 하위 리소스임을 경로에 드러내되 요청 형태는 **쿼리 파라미터를 유지**했다(경로 변수로 바꾸면 `USER-GL-19` 의 400 이 "경로 불일치 404"로 성질이 바뀌어 파라미터 누락과 없는 경기가 같은 코드로 뭉개진다). **`SecurityConfig` 한 줄이 별도로 필요하다는 대가**는 위 "제약" 첫 항목에 적었다.
4. **`position_id` 가 NULL 이면 `positionName: null` 을 그대로 내보낸다**(`USER-GL-23`). `stadium`·점수가 `null` 을 그대로 내보내고 표기를 클라이언트가 정하는 선례와 같다. 서버가 `"-"` 같은 대체 문자열을 정하면 **서버가 표시용 문자열을 소유하는 첫 사례**가 되고, 화면마다 다른 표기를 원할 때 계약을 다시 바꿔야 한다. 해당 선수를 목록에서 빼는 선택은 라인업에 구멍이 생겨 폐기.
5. **응답은 팀 단위 그룹 배열이다**(`GameLineupResponse: {teamId, pitchers, batters}`). 초안은 투수/타자 2개 목록에 항목마다 `teamId` 를 반복해 넣는 형태였는데, ①같은 값이 20번 반복되고 ②클라이언트가 팀별로 그리려면 매번 그룹핑해야 하며 ③한 목록에 두 팀 타순이 섞여 `batOrder` 가 중복되는 문제(초안 `USER-GL-7` 의 2차 정렬 키)가 있었다. 팀으로 1차 그룹핑하면 셋이 한꺼번에 사라진다 — **`USER-GL-7` 은 그래서 삭제됐다.** 그룹 정렬은 `teamId` 오름차순으로 고정해 결정적 순서를 확보하되(`USER-GL-27`), **홈/원정 대응은 서버가 하지 않고** 클라이언트가 `GET /api/games` 의 `homeTeamId`/`awayTeamId` 와 맞춘다(`USER-GL-14`) — 이 대응이 이번 두 변경을 잇는 지점이다. `pitchers` 정렬은 계약을 아예 두지 않는 선택지도 있었으나 **`name` 오름차순으로 고정**했다(`USER-GL-8`): 정상 경기라면 1건이라 사실상 무의미하지만, 비계약으로 두면 순서가 흔들릴 때 테스트가 불안정해진다.
6. **`playerId` 는 넣지 않는다.** 이번 응답은 **전시 목적**(이름·포지션·타순을 보여주는 라인업 카드)이라 식별자가 필요 없다. 라인업에서 선수 상세로 넘어가는 화면이 생기거나 동명이인 구분이 실제로 문제가 되면 그때 추가한다(아래 "후속"). 어느 쪽이든 `Player.kboPlayerId`(소스 자연키)는 계속 비노출이다.

**승인 시점(2026-08-04) 추가 결정 2건.**

7. **빈/공백 `gameId` 는 400 이 아니라 404 로 흡수한다**(`USER-GL-22`). 빈 문자열과 일치하는 `naver_game_id` 행은 없으므로 "존재하지 않는 경기"와 **실질적으로 같은 상황**이고, 400 을 고집하면 500 위험(`ConstraintViolationException` 미처리)이나 400 전용 `ErrorCode` 추가 중 하나를 떠안아야 한다(위 "제약" 참조). 사용자가 이 절충을 보고 404 를 골랐다. **다만 파라미터 자체가 없는 경우는 여전히 400 이다**(`USER-GL-19`) — "값이 잘못된 것"과 "요청 형식이 미완성인 것"은 구분한다.
8. **타자 판정은 `bat_order BETWEEN 1 AND 9` 다**(`USER-GL-5`·`USER-GL-29`). `IS NOT NULL` 로도 투수/타자는 갈리지만, **타순이 1~9 로 닫힌 범위라는 도메인 사실을 쿼리에 드러내는 쪽**을 택했다. `GameLineup.batOrder` 에는 CHECK 제약이 없어(nullable `Integer`) 범위 밖 값이 물리적으로 들어올 수 있고(해석 근거 4), 그때 `IS NOT NULL` 조건은 `bat_order = 0` 같은 이상 행을 **타순 0번 타자로 응답에 실어 보낸다.** 범위 조건은 그런 행을 조용히 빼며(`USER-GL-29`), 그 대가로 "두 목록 길이 합 = 선발 행 수"가 정상 데이터에서만 성립하게 된다 — 합이 안 맞으면 API 버그가 아니라 **적재 이상의 신호**로 읽어야 한다.

## 미해결 질문
없음 — 초안의 6건은 2026-08-04 사용자 답변으로 전부 해소됐다(위 "결정 기록" 참조).

## 후속 (이번 범위 아님 — 기록만)
- **`playerId` 추가 여지**(결정 6). 추가할 경우 `GET /api/players` 의 `id` 와 같은 체계를 쓰며, `pitchers`/`batters` 항목에 키가 하나 늘 뿐이라 하위 호환을 깨지 않는다.
- **`USER-GL-19`(파라미터 누락 400)만 `ApiResponse` 래퍼가 아니다**(표기 근거 2). 통일하려면 `web-support` 의 `GlobalExceptionHandler` 에 핸들러를 추가해야 하고 이는 `quiz` 응답까지 바꾸는 공유 부품 변경이라 별도 요구사항으로 다뤄야 한다 — `player-list.md` "미해결"에 같은 항목이 이미 있다.
- **`bat_order` 의 DB 제약 부재**(해석 근거 4). 범위 밖 값이 실제로 적재되면 `USER-GL-29` 에 따라 응답에서 조용히 사라져 **화면에 타순이 비어 보일 뿐 오류가 나지 않는다.** 적재 이상을 조기에 잡으려면 CHECK 제약이나 py-collector 쪽 검증이 필요한데, 스키마는 `ddl-auto` 가 만들고 원천은 수집기가 소유하므로 이 API 계약의 범위 밖이다.
- **LAZY 연관 로딩 전략**. 선발 20여 행에 대해 `player`·`position` 을 어떻게 한 번에 읽을지는 구현 판단이지만, 방치하면 N+1 이고 prod(`open-in-view: false`)에서는 500 이 된다. 계약(`USER-GL-12`·`USER-GL-13`)이 두 연관의 이름을 요구하는 이상 회피할 수 없는 지점이다.
- **정렬의 콜레이션 의존**(`USER-GL-8`). `players.name` 은 명시적 콜레이션 없이 `ddl-auto` 가 만든 컬럼이라 DB 기본값에 기댄다 — 외국인 투수명이 영문으로 적재되면 `team-list.md` 가 겪은 "영문 먼저" 현상이 재현된다.
