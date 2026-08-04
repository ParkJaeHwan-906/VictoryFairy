# 경기(game) API 명세

> **도메인** `game` — 날짜별 KBO 경기 일정·스코어, 경기별 선발 라인업.
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/games` · **엔드포인트** 2개
> **컨트롤러** `user/src/main/java/com/skhynix/user/game/controller/GameController.java`(`@RequestMapping("/games")`) · `GameLineupController.java`(`@RequestMapping("/games/lineup")`)
> **최종 갱신** 2026-08-04 — `positionName` 매핑 표에 실측 58종 복수 포지션 원문(2글자) 절 추가.
> 공통 규약(응답 래퍼·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/member/games](#get-apimembergames) | 200 | 날짜별 경기 목록 |
| GET | [/api/member/games/lineup](#get-apimembergameslineup) | 200 | 경기별 선발 라인업 |

## 이 도메인의 특이사항

[구단(team)](team.md)·[선수(player)](player.md)와 같은 **공개 참조 데이터**로, GET 한정 `permitAll`·페이징 없음·빈 결과 200이라는 계약을 공유한다.

**단, 자연키 노출 정책만 다르다.** `TeamResponse`가 `Team.code`를, `PlayerResponse`가 `kboPlayerId`를 감추는 것과 달리 `GameResponse.gameId`는 `Game.naverGameId`(네이버 스포츠 gameId)를 그대로 내보내고, `GET /games/lineup`의 쿼리 파라미터 `gameId`도 내부 PK가 아니라 이 값이다.

**"오늘"의 정의가 코드에 고정돼 있다.** `date`를 생략하면 `ClockConfig`가 등록한 `Clock.system(ZoneId.of("Asia/Seoul"))` 기준 오늘로 조회한다 — 운영 파드가 UTC로 돌기 때문에 시간대를 배포 설정(`TZ`)이 아니라 코드에서 고정했다. 다만 아래 본문의 경고대로 **클라이언트가 날짜를 알고 있다면 항상 명시해 넘기는 편이 안전하다.**

**`/games/lineup`은 `/games`의 하위 경로이지만 별도 `permitAll` 매처가 필요하다.** `SecurityConfig`의 `.requestMatchers(HttpMethod.GET, "/games").permitAll()`은 정확 경로 매칭이라 `/games/lineup`을 커버하지 않는다 — `HttpMethod.GET, "/games/lineup"`을 별도로 열어 뒀다(컨트롤러 Javadoc에도 이 함정이 명시돼 있다).

---

## GET /api/member/games
> 최종 변경: 2026-08-04 — 응답에 `homeTeamId`/`awayTeamId` 추가(8→10필드)

날짜별 경기 목록 조회. `GameController` → `GameService.getGames(LocalDate)` → `GameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(...)`.

**인증 불필요.** [`GET /api/member/teams`](team.md)·[`/players`](player.md)와 같은 성격의 공개 참조 데이터라 `SecurityConfig`가 같은 방식으로 열었다(`.requestMatchers(HttpMethod.GET, "/games").permitAll()`). **`permitAll`은 `HttpMethod.GET`으로 좁혀져 있어** `POST /api/member/games`는 405가 아니라 **401**이다.

**요청**: 쿼리 파라미터 `date` 1개(**선택**, `@RequestParam(required = false)`).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| date | LocalDate | 아니오 | 조회할 날짜. ISO `yyyy-MM-dd` 고정(`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`). 생략하면 아래 "기본값(오늘)" 참고 |

**`date`를 생략하면 `Asia/Seoul` 기준 오늘 날짜로 조회한다(200).** 컨트롤러는 기본값을 직접 계산하지 않고 `null`을 그대로 `GameService.getGames(LocalDate)`에 넘기며, 서비스가 `date != null ? date : LocalDate.now(clock)`으로 판정한다. 이 `clock`은 `user/global/config/ClockConfig`가 등록하는 `Clock.system(ZoneId.of("Asia/Seoul"))` 빈이다 — **운영 파드가 UTC로 돌기 때문에**(실측: `kubectl exec` 로 파드 `date` 조회 시 `UTC`, `TZ` 환경변수 미설정) 시스템 기본 시간대(`LocalDate.now()`)를 쓰면 KST 자정~오전 9시 사이에 하루 전 날짜로 조회되는 오답이 나온다. 그래서 시간대를 배포 설정(`TZ`)이 아니라 코드(`ClockConfig`)에서 고정한다.

**단, "생략하면 오늘"이지 "형식이 이상해도 오늘"은 아니다.** `date=20260801`(구분자 없음)처럼 형식이 어긋난 값이나 `date=2026-13-01`처럼 존재하지 않는 날짜는 여전히 컨트롤러 진입 전 타입 변환에서 400이 난다(아래 "실패" 참고) — 오타를 오늘로 흡수하면 사용자가 잘못된 날짜를 입력했다는 사실을 알 수 없기 때문에 의도적으로 구분한다.

⚠ **주의: `date` 생략은 편의 기능이지 권장 사용법이 아니다.** 서버가 판정하는 "오늘"은 항상 `Asia/Seoul` 기준이며(`ClockConfig`가 코드로 고정 — 서버 JVM 기본 시간대·컨테이너 `TZ` 설정과 무관), 이 판정은 두 가지 이유로 클라이언트가 기대하는 날짜와 어긋날 수 있다: (a) 클라이언트가 다른 시간대에 있거나 기기 시계가 한국 시간과 다르면 "서버의 오늘"과 "클라이언트가 보고 있는 오늘"이 다를 수 있다. (b) 자정 경계 근처에서는 정확히 같은 화면이라도 요청이 언제 도달했느냐에 따라 응답이 바뀔 수 있다(응답 캐싱이나 화면에 날짜를 함께 표기하는 UI에서 특히 혼란스럽다). **화면에 표시할 날짜를 클라이언트가 이미 알고 있다면(예: 날짜 선택 UI, 캘린더) 그 날짜를 항상 `date`로 명시해 넘길 것** — 생략은 "오늘 경기를 보여주면 되는" 최초 진입 화면 정도로 한정해 쓰는 편이 안전하다.

**응답 200 OK** `ApiResponse<List<GameResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 경기 배열 |
| data[].gameId | String | `Game.naverGameId` — 네이버 스포츠 gameId(예: `"20260708LGSS02026"`). py-collector가 upsert 키로 쓰는 자연키이지만, `Team.code`/`Player.kboPlayerId`와 달리 이 값은 응답에 그대로 노출된다(더블헤더 구분 등 클라이언트가 식별자로 쓸 필요가 있어 보임 — `TeamResponse`/`PlayerResponse`가 자연키를 감추는 것과 다른 결정이니 주의). **`GET /games/lineup`의 쿼리 파라미터 `gameId`가 바로 이 값이다**(내부 PK 아님) |
| data[].stadium | String \| null | 구장 이름(`Game.stadium.name`). **`null` 가능** — `Game.stadium`이 `Game`의 연관 중 유일하게 선택적(`optional = true`, `stadium_id` nullable)이라 구장이 아직 미정인 경기(편성 전·중립구장 미확정 등)는 `null`로 나간다. `homeTeamScore`/`awayTeamScore`가 경기 전 `null`인 것과 같은 취급이며, 표기 방식은 클라이언트가 정한다(`GameResponse.from()`이 `game.getStadium() == null ? null : game.getStadium().getName()`으로 방어) |
| data[].homeTeam | String | 홈 구단 이름(`Game.homeTeam.name`) |
| data[].homeTeamId | Long | 홈 구단 PK(`Game.homeTeam.id`). **2026-08-04 신규.** `null` 아님(`Game.homeTeam`은 `optional = false`). [`GET /api/member/teams`](team.md)의 `id`와 값 체계가 같다. 추가 목적은 [`GET /games/lineup`](#get-apimembergameslineup) 응답의 `teamId`를 홈/원정에 대응시키기 위함 |
| data[].awayTeam | String | 원정 구단 이름(`Game.awayTeam.name`) |
| data[].awayTeamId | Long | 원정 구단 PK(`Game.awayTeam.id`). **2026-08-04 신규.** `homeTeamId`와 동일한 목적·성질(`null` 아님) |
| data[].homeTeamScore | Integer \| null | 홈 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].awayTeamScore | Integer \| null | 원정 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].gameDate | LocalDateTime | 경기 시각. `LocalDateTime` 직렬화 형태 그대로(예: `"2026-08-01T18:30:00"`) — 별도 포맷 지정 없음 |
| data[].gameState | String | `Game.gameStatus.name`(`game_statuses` 테이블 값). 코드 상수가 아니라 DB 행이라 이론상 임의 문자열일 수 있으나, 현재 py-collector가 채우는 값은 `SCHEDULED`\|`IN_PROGRESS`\|`FINISHED`\|`DRAW`\|`CANCELED` 5종(`GameStatus` 엔티티 Javadoc 참고) |
| message | null | 사용되지 않음 |

**`GameResponse`의 실제 필드 순서는 `gameId`, `stadium`, `homeTeam`, `homeTeamId`, `awayTeam`, `awayTeamId`, `homeTeamScore`, `awayTeamScore`, `gameDate`, `gameState` 10개다**(record 컴포넌트 선언 순서, `user/src/main/java/com/skhynix/user/game/dto/GameResponse.java`). 기존 8개 필드의 이름·순서·의미는 그대로이며, `homeTeamId`/`awayTeamId` 두 필드가 각각 `homeTeam`/`awayTeam` 바로 뒤에 추가됐다.

**`Game.id`(PK)·`createdAt`·`updatedAt`은 의도적으로 응답에 없다.** `GameResponse.from()`이 엔티티를 그대로 직렬화하지 않고 필드를 골라 변환한다.

**정렬: `gameDate` 오름차순, DB(`ORDER BY game_date ASC`)가 단독 수행**하며 애플리케이션에서 재정렬하지 않는다.

**조회 범위: 대상 날짜(`date` 또는 위 기본값 판정을 거친 오늘) 하루를 반개구간 `[대상일 00:00, 대상일+1일 00:00)`으로 변환해 조회한다.** `games.game_date`가 `datetime(6)`이라 날짜 등치 비교로는 매칭되지 않기 때문(서비스 Javadoc 참고). 상한을 포함하지 않으므로 자정 정각 경기가 이틀에 중복 집계되거나 마이크로초 단위 값이 누락되는 경계 문제가 없다.

**해당 날짜(생략 시 오늘)에 경기가 없으면 200 + 빈 배열**을 반환한다(404·500이 아님, [`/teams`](team.md)·[`/players`](player.md)의 빈 결과 계약과 동일):
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (래퍼 없음) | `date` 형식 위반(예: `?date=2026/08/01`, `?date=20260801`) 또는 존재하지 않는 날짜(예: `?date=2026-13-01`). **`date` 자체가 없는 것은 더 이상 오류가 아니다**(200 + 오늘) — 이 400은 오직 "값은 있는데 파싱이 안 됨"에만 해당한다. 컨트롤러 진입 전 타입 변환·바인딩 단계라 `GlobalExceptionHandler`가 아니라 Spring 기본 예외 처리(`DefaultHandlerExceptionResolver`)가 처리한다 — **`GET /api/member/players`의 `teamId` 형식 오류와 같은 사정으로, 이 응답만 `ApiResponse` 래퍼가 아니다** |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음 — 405 아님) |

Authorization 헤더가 있어도(만료·무효 토큰이어도) 이 경로는 `permitAll`이라 검증 자체를 거치지 않고 그대로 200을 반환한다.

**예시**
```bash
curl -i -X GET "http://localhost:8080/api/member/games?date=2026-08-01"
```
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":"잠실","homeTeam":"LG","homeTeamId":1,"awayTeam":"삼성","awayTeamId":5,"homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
```

구장 미정 경기 예시(`stadium: null`):
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":null,"homeTeam":"LG","homeTeamId":1,"awayTeam":"삼성","awayTeamId":5,"homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
```

`date` 생략 예시(200, `Asia/Seoul` 기준 오늘 경기):
```bash
curl -i -X GET "http://localhost:8080/api/member/games"
```

형식 오류 예시(400, `ApiResponse` 래퍼 아님 — `date=20260801`처럼 구분자가 없거나 `date=2026-13-01`처럼 존재하지 않는 날짜):
```bash
curl -i -X GET "http://localhost:8080/api/member/games?date=20260801"
```

경기 없는 날짜 예시:
```json
{"success":true,"data":[],"message":null}
```

---

## GET /api/member/games/lineup
> 최종 변경: 2026-08-04 — 신규 추가 + `positionName` 매핑 표에 실측 58종 복수 포지션 원문(2글자) 절 추가

경기별 선발 라인업 조회(홈·원정 두 팀이 한 응답에 함께 나온다). `GameLineupController` → `GameLineupService.getLineup(String)` → `GameRepository.findByNaverGameId` + `GameLineupRepository.findStarterPitchers`/`findStarterBatters`.

**인증 불필요.** `GET`만 `SecurityConfig`에서 `permitAll`(`.requestMatchers(HttpMethod.GET, "/games/lineup").permitAll()`). `/games`의 `permitAll` 매처는 정확 경로 매칭이라 이 하위 경로를 커버하지 않으므로 **별도 줄로 열려 있다** — 컨트롤러 Javadoc이 "이 줄을 빠뜨리면 전 요청이 401"이라고 경고하는 지점이다.

**요청**: 쿼리 파라미터 `gameId` 1개(**필수**, `@RequestParam String gameId`).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gameId | String | 예 | **내부 PK가 아니라 `Game.naverGameId`(네이버 자연키 문자열)다.** [`GET /api/member/games`](#get-apimembergames) 응답의 `gameId` 필드와 같은 값을 그대로 넣는다. **경기 내부 PK를 넣으면 항상 404가 난다** — 오해하기 쉬운 지점이니 주의 |

**응답 200 OK** `ApiResponse<List<GameLineupResponse>>`

- 배열 원소 = 팀 1개. **`teamId` 오름차순**으로 정렬돼 있으며, 라인업이 실제로 존재하는 팀만 등장한다(반대 팀이 아직 미공시면 그 팀의 빈 그룹조차 나오지 않는다 — 경기당 원소 0~2개).

| 필드 | 타입 | 설명 |
|---|---|---|
| data[].teamId | Long | 구단 PK. [`GET /api/member/teams`](team.md)의 `id`, [`GET /api/member/games`](#get-apimembergames)의 `homeTeamId`/`awayTeamId`와 값 체계가 같다. **서버는 이 팀이 홈인지 원정인지 판정하지 않는다** — 클라이언트가 `GET /games`의 `homeTeamId`/`awayTeamId`와 대조해 알아내야 한다 |
| data[].pitchers | array | 선발 투수 배열. `name` 오름차순 |
| data[].pitchers[].name | String | 선수 이름 |
| data[].pitchers[].positionName | String \| null | py-collector 약어 원문(`"P"` 등, 아래 매핑 표 참고). `position_id`가 NULL이면 `null` |
| data[].batters | array | 선발 타자 배열. `batOrder` 오름차순 |
| data[].batters[].name | String | 선수 이름 |
| data[].batters[].positionName | String \| null | py-collector 약어 원문(`"1B"`·`"DH"` 등, 한글 아님, 아래 매핑 표 참고). `position_id`가 NULL이면 `null` |
| data[].batters[].batOrder | Integer | 타순 1~9 |
| message | null | 사용되지 않음 |

**`playerId`는 노출하지 않는다.** `GameLineupResponse`·`Pitcher`·`Batter` 어디에도 선수 PK 필드가 없다.

**`positionName`의 값 집합**(`domain/src/main/java/com/skhynix/domain/game/entity/Position.java` 클래스 Javadoc, py-collector `db.py POSITION_CODES`와 동일 매핑):

| 네이버 원문 | 약어 | 의미 |
|---|---|---|
| 투 | `P` | 투수 |
| 포 | `C` | 포수 |
| 一 | `1B` | 1루수 |
| 二 | `2B` | 2루수 |
| 三 | `3B` | 3루수 |
| 유 | `SS` | 유격수 |
| 좌 | `LF` | 좌익수 |
| 중 | `CF` | 중견수 |
| 우 | `RF` | 우익수 |
| 지 | `DH` | 지명타자 |
| 타 | `PH` | 대타 |
| 주 | `PR` | 대주자 |

**`PH`(대타)·`PR`(대주자)는 수비 위치가 아니라 출전 형태다** — 나머지 10개(`P`~`DH`)와 성격이 다르니 클라이언트가 "수비 위치"로 뭉뚱그려 표기하면 오해를 부를 수 있다.

**이 12개는 닫힌 enum이 아니라 열린 집합이다.** `Position`은 코드 상수가 아니라 DB 테이블(`positions`)이고, py-collector가 매핑에 없는 미지 표기를 만나면 warning만 남기고 **원문(한자·한글)을 그대로 적재**한다(위 매핑에 추가하기 전까지). 즉 이 12개 밖의 값이 `positionName`에 나타날 수 있다 — **클라이언트가 이 12개로만 `switch`를 짜고 `default`를 두지 않으면 미지 표기가 왔을 때 깨진다.**

### 복수 포지션 표기(2글자 원문) — "열린 집합" 경고의 실제 사례

> 최종 변경: 2026-08-04 — dev DB `positions` 테이블 전수 조회 결과 반영(신규 절)

**실측(2026-08-04, dev DB `positions` 테이블 전수 70행) 기준, 위 12종 약어 외에 58종이 한글·한자 원문 2글자 그대로 `positionName`에 실려 나간다.** "열린 집합" 경고가 이론이 아니라 실제로 이 정도 규모로 벌어지고 있다는 뜻이다 — 70종 중 약어로 매핑되는 건 12종뿐이고, **58종(83%)이 원문**이라 오히려 원문 쪽이 흔한 경우다.

**구조는 예외 없이 일정하다.** 58종 전부 위 12개 매핑에 쓰이는 **단일 표기(투·포·一·二·三·유·좌·중·우·지·타·주) 2개를 이어붙인 2글자**다. 앞 글자가 그 경기에서 먼저 맡은 포지션, 뒷 글자가 다음에 바뀐 포지션을 뜻한다 — 네이버 박스스코어가 한 경기 안에서 수비 위치를 바꾼 선수를 이렇게 표기하고, py-collector의 `POSITION_CODES`가 **단일 문자만 알기 때문에** 이 2글자 조합은 약어 변환에 걸리지 않고 원문 그대로 적재된다. 수집 파이프라인의 버그가 아니라 현재 동작이다.

- 예: `주좌` = 주(대주자)로 들어왔다가 좌(좌익수)로 전환, `二一` = 2루수 → 1루수로 전환, `타지` = 대타 → 지명타자로 전환, `우중` = 우익수 → 중견수로 전환

**클라이언트 영향(중요).** `positionName`은 다음 두 형태 중 하나다: (1) 약어(`"CF"`·`"1B"` 등, 위 12종), (2) 한글·한자 원문 2글자(`"주좌"`·`"二一"` 등, 아래 58종). **약어 12종만 `switch`로 처리하고 `default`를 두지 않으면 교체 출전 선수의 라인업에서 반드시 깨진다** — 실측상 드문 예외가 아니라 흔한 경우(70종 중 58종)이므로 "원문 그대로 표시" 또는 "2글자를 앞/뒤로 분해해 표시" 같은 fallback을 반드시 구현해야 한다.

**닫힌 목록이 아니다.** 아래 58종은 2026-08-04 dev DB 스냅샷이며, 수집이 계속되면 같은 구조(단일 표기 2개 조합)의 새 조합이 더 나타날 수 있다. 이 표를 하드코딩된 전체 목록으로 믿지 말 것 — 위 "클라이언트 영향" 문단의 fallback이 필요한 이유이기도 하다.

<details>
<summary>실측 58종 전체 목록(2026-08-04 dev DB 스냅샷, 펼치기)</summary>

```
주좌 주유 중우 주우 주중 타지 타二 타우 중좌 二一 주二 주三 좌一 우좌 타포
三유 주一 좌우 타좌 三一 타一 주지 타三 타중 지포 우중 타유 二三 一二 유三
三二 우一 一三 주포 좌중 二유 지三 三우 중二 유一 二중 지一 유二 좌二 二우
지유 좌三 우三 좌유 지二 三중 지좌 三좌 지우 포一 一유 一좌 유좌
```

</details>

**선발 판정 규칙**: `GameLineup.isStarter = true`인 행만 본다. 그중 **투수**는 `bat_order`가 NULL인 행(`findStarterPitchers`), **타자**는 `bat_order`가 1~9인 행(`findStarterBatters`) — 두 조회는 서로 겹치지 않는다. 포지션으로 투수/타자를 가르지 않는 이유는 `position`이 py-collector가 원문을 그대로 적재하는 열린 집합이라 미지 표기 하나에 분류가 무너지기 때문(코드 주석 근거).

**라인업이 아직 공시되지 않은 경기는 200 + 빈 배열이다(경기 자체는 존재).** KBO 선발은 경기 직전에야 공시되므로 대부분의 시간이 이 상태다:
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | `GAME_NOT_FOUND`(`"존재하지 않는 경기입니다."`) | `gameId`와 일치하는 `naver_game_id`가 없을 때. **`?gameId=`처럼 값이 빈 경우도 이 404다**(빈 문자열이 그냥 일치하는 경기가 없는 것으로 자연히 흡수될 뿐, 별도 400 분기가 없다) |
| 400 | (래퍼 없음) | `gameId` 쿼리 파라미터 **자체가 없을 때**(`?gameId=` 아니라 `gameId=` 키조차 없음)만 해당. `@RequestParam String gameId`가 필수인데 Spring이 바인딩 단계에서 `MissingServletRequestParameterException`을 던져 `GlobalExceptionHandler`를 타지 않는다 — **이 400만 `ApiResponse` 래퍼가 아니다**(`GET /api/member/games`의 `date` 형식 오류·`GET /api/member/players`의 `teamId` 형식 오류와 같은 성격의 예외) |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음) |

**예시**(홈·원정 두 팀, 각 팀 투수 1명·타자 9명 예시. 나머지는 `...`로 생략):
```bash
curl -i -X GET "http://localhost:8080/api/member/games/lineup?gameId=20260801LGSS02026"
```
```json
{
  "success": true,
  "data": [
    {
      "teamId": 1,
      "pitchers": [
        {"name": "홍길동", "positionName": "P"}
      ],
      "batters": [
        {"name": "김철수", "positionName": "2B", "batOrder": 1},
        {"name": "이영희", "positionName": "DH", "batOrder": 2}
        // ... batOrder 3~9
      ]
    },
    {
      "teamId": 5,
      "pitchers": [
        {"name": "박민수", "positionName": "P"}
      ],
      "batters": [
        {"name": "최지훈", "positionName": "1B", "batOrder": 1}
        // ... batOrder 2~9
      ]
    }
  ],
  "message": null
}
```

없는 경기 예시(404):
```bash
curl -i -X GET "http://localhost:8080/api/member/games/lineup?gameId=존재하지않는값"
```
```json
{"success":false,"data":null,"message":"존재하지 않는 경기입니다."}
```

`gameId` 파라미터 누락 예시(400, `ApiResponse` 래퍼 아님):
```bash
curl -i -X GET "http://localhost:8080/api/member/games/lineup"
```

---

## 확인 필요 / 코드 미확인

- `gameId`(`naverGameId`)가 자연키를 그대로 노출하는 것이 의도적 결정인지, 아니면 향후 별도 PK 기반 식별자로 교체될 잠정값인지 코드만으로는 판단 불가. `(확인 필요)`
- `Game.id`(PK)·`createdAt`·`updatedAt`이 응답에서 제외되는 이유는 코드·Javadoc에 명시적으로 적혀 있지 않다.
- 경기 상세 조회, 기간 범위 조회(`from`/`to`), 구단별 필터 엔드포인트는 없다 — 하루 단위 조회가 전부다.
- `gameState`는 코드 상수가 아니라 `game_statuses` 테이블 행이라 이론상 임의 문자열일 수 있다. 현재 py-collector가 채우는 5종 밖의 값이 나타날 가능성은 코드로 막혀 있지 않다.

## 관련 문서

- [구단(team)](team.md) — `GET /games`의 `homeTeam`/`awayTeam`은 구단 **이름** 문자열, `homeTeamId`/`awayTeamId`·`GET /games/lineup`의 `teamId`는 구단 **PK**다. 값 체계는 `GET /teams`의 `id`와 동일하다.
