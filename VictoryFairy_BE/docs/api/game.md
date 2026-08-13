# 경기(game) API 명세

> **도메인** `game` — 날짜별 KBO 경기 일정·스코어, 경기별 선발 라인업, 내 활성 응원 구단 경기 목록.
> **모듈** user (포트 8080) · **경로 접두사** `/api/games` · **엔드포인트** 3개
> **컨트롤러** `user/src/main/java/com/skhynix/user/game/controller/GameController.java`(`@RequestMapping("/games")`, `GET`+`GET /support`) · `GameLineupController.java`(`@RequestMapping("/games/lineup")`)
> **최종 갱신** 2026-08-13 — `GET /api/games/support` 신규 추가: 인증된 계정의 **활성 응원 구단**(홈 또는 원정)이 참여한 경기만 돌려주는 조회. **이 도메인 최초의 인증 필수 엔드포인트**라 아래 "이 도메인의 특이사항"의 "전부 공개 참조 데이터" 서술이 더 이상 도메인 전체에 참이 아니게 됐다(범위를 `/games`·`/games/lineup` 두 경로로 좁혀 정정). 응답 형식·13필드·정렬·날짜 해석 규칙은 `GET /api/games`와 완전히 동일하며, `GET /api/games`·`GET /api/games/lineup`의 기존 계약은 변경 없음(요구사항 USER-GSP-24). (직전: 같은 날 `GET /api/games/lineup`의 `gameId` 파라미터 누락 400 서술 정정: `web-support`의 `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 핸들러가 추가돼(공유 컴포넌트, user·quiz 공통) **이제 이 400도 `ApiResponse` 래퍼를 탄다**(종전엔 래퍼 아님으로 서술). 엔드포인트 자체·다른 400(타입 변환 실패 등)은 불변. (직전: 2026-08-11 `GET /api/games` 응답에 `inning`/`inningHalf` 필드 추가(11→13필드, `games` 테이블에 `current_inning`/`inning_half` 컬럼 신설). 같은 날 devdb 실측으로 현재는 항상 `null`임을 확인(아래 참고). (직전: 같은 날 `cancelReason` 필드 반영(10→11필드, 커밋 f01d08e #281). 같은 날 운영 DB 실측으로 py-collector 쓰기가 이미 동작 중임을 확인해 서술 정정, `cancelReason` 미채움 시 클라이언트 fallback(`"경기취소"`) 권장 규칙 추가(아래 참고).)))
> 공통 규약(응답 래퍼·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 | 인증 |
|---|---|---|---|---|
| GET | [/api/games](#get-apigames) | 200 | 날짜별 경기 목록 | 불필요 |
| GET | [/api/games/support](#get-apigamessupport) | 200 | 내 활성 응원 구단 경기 목록 | **필수** |
| GET | [/api/games/lineup](#get-apigameslineup) | 200 | 경기별 선발 라인업 | 불필요 |

## 이 도메인의 특이사항

**`GET /api/games`·`GET /api/games/lineup` 두 경로는** [구단(team)](team.md)·[선수(player)](player.md)와 같은 **공개 참조 데이터**로, GET 한정 `permitAll`·페이징 없음·빈 결과 200이라는 계약을 공유한다.

**`GET /api/games/support`는 이 패턴의 예외다 — 인증이 필수다.** 같은 `/games` 접두사 아래에서 인증 정책이 갈리는 첫 사례이며, `SecurityConfig`의 `.requestMatchers(HttpMethod.GET, "/games").permitAll()`이 **정확 경로 매칭**이라 하위 경로 `/games/support`를 커버하지 않는 덕에(`/games/lineup` 때와 정반대로) **`SecurityConfig`를 손대지 않는 것이 정답**이었다 — 별도 매처를 추가하지 않으면 자연히 `anyRequest().authenticated()`에 걸린다. 응답 형식(13필드)·날짜 해석 규칙(`Asia/Seoul` 기준 오늘, 반개구간)은 `GET /api/games`와 동일하게 유지한다.

**단, 자연키 노출 정책만 다르다.** `TeamResponse`가 `Team.code`를, `PlayerResponse`가 `kboPlayerId`를 감추는 것과 달리 `GameResponse.gameId`는 `Game.naverGameId`(네이버 스포츠 gameId)를 그대로 내보내고, `GET /games/lineup`의 쿼리 파라미터 `gameId`도 내부 PK가 아니라 이 값이다.

**"오늘"의 정의가 코드에 고정돼 있다.** `date`를 생략하면 `ClockConfig`가 등록한 `Clock.system(ZoneId.of("Asia/Seoul"))` 기준 오늘로 조회한다 — 운영 파드가 UTC로 돌기 때문에 시간대를 배포 설정(`TZ`)이 아니라 코드에서 고정했다. 다만 아래 본문의 경고대로 **클라이언트가 날짜를 알고 있다면 항상 명시해 넘기는 편이 안전하다.**

**`/games/lineup`은 `/games`의 하위 경로이지만 별도 `permitAll` 매처가 필요하다.** `SecurityConfig`의 `.requestMatchers(HttpMethod.GET, "/games").permitAll()`은 정확 경로 매칭이라 `/games/lineup`을 커버하지 않는다 — `HttpMethod.GET, "/games/lineup"`을 별도로 열어 뒀다(컨트롤러 Javadoc에도 이 함정이 명시돼 있다).

---

## GET /api/games
> 최종 변경: 2026-08-11 — 응답에 `inning`/`inningHalf` 추가(11→13필드, `games.current_inning`/`games.inning_half` 컬럼 신설). **현재는 항상 `null`**이다 — 이 값을 채우는 주체는 py-collector이고 수집기 쪽 구현이 아직 없다(`cancelReason`이 처음 추가됐을 때와 같은 상태, 아래 참고). (직전: 같은 날 응답에 `cancelReason` 추가(10→11필드, 커밋 f01d08e #281), 같은 날 운영 DB 실측 결과 반영("수집기 미구현·항상 null" 서술을 실측값으로 정정), `cancelReason` 미채움 시 클라이언트 fallback(`cancelReason ?? "경기취소"`, `CANCELED`일 때만 적용) 권장 규칙 추가. (그 이전: 2026-08-04 `homeTeamId`/`awayTeamId` 추가, 8→10필드))

날짜별 경기 목록 조회. `GameController` → `GameService.getGames(LocalDate)` → `GameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(...)`.

**인증 불필요.** [`GET /api/teams`](team.md)·[`/players`](player.md)와 같은 성격의 공개 참조 데이터라 `SecurityConfig`가 같은 방식으로 열었다(`.requestMatchers(HttpMethod.GET, "/games").permitAll()`). **`permitAll`은 `HttpMethod.GET`으로 좁혀져 있어** `POST /api/games`는 405가 아니라 **401**이다.

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
| data[].homeTeamId | Long | 홈 구단 PK(`Game.homeTeam.id`). **2026-08-04 신규.** `null` 아님(`Game.homeTeam`은 `optional = false`). [`GET /api/teams`](team.md)의 `id`와 값 체계가 같다. 추가 목적은 [`GET /games/lineup`](#get-apigameslineup) 응답의 `teamId`를 홈/원정에 대응시키기 위함 |
| data[].awayTeam | String | 원정 구단 이름(`Game.awayTeam.name`) |
| data[].awayTeamId | Long | 원정 구단 PK(`Game.awayTeam.id`). **2026-08-04 신규.** `homeTeamId`와 동일한 목적·성질(`null` 아님) |
| data[].homeTeamScore | Integer \| null | 홈 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].awayTeamScore | Integer \| null | 원정 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].gameDate | LocalDateTime | 경기 시각. `LocalDateTime` 직렬화 형태 그대로(예: `"2026-08-01T18:30:00"`) — 별도 포맷 지정 없음 |
| data[].gameState | String | `Game.gameStatus.name`(`game_statuses` 테이블 값). 코드 상수가 아니라 DB 행이라 이론상 임의 문자열일 수 있으나, 현재 py-collector가 채우는 값은 `SCHEDULED`\|`IN_PROGRESS`\|`FINISHED`\|`DRAW`\|`CANCELED` 5종(`GameStatus` 엔티티 Javadoc 참고) |
| data[].cancelReason | String \| null | 경기 취소 사유. `Game.cancelReason`(`games.cancel_reason`, `VARCHAR(50)`, nullable). **`gameState`가 `CANCELED`일 때만 값이 채워지고 그 외 상태는 항상 `null`이다**(실측: 아래 참고, `CANCELED`가 아닌데 값이 채워진 행 0건). ⚠ **역은 완전히 성립하지는 않는다 — `CANCELED`인데 `cancelReason`이 `null`인 경우도 스키마상 가능하다**(nullable 컬럼, 취소 직후 수집기가 아직 채우기 전 구간, KBO 일정표 비고가 비어 있는 취소 등). 다만 **실측상(아래 참고) 현재 그런 행은 0건**이라 "드물지만 가능"한 수준으로 봐야 한다("항상 null"이 아니다). 값을 채우는 주체는 py-collector이며 **이 값의 출처는 KBO 공식 일정표다**(네이버 스케줄 API는 취소를 `"경기취소"`로만 알려줄 뿐 사유 필드가 없어 이 값의 소스가 될 수 없다). **실측(2026-08-11, EKS `victoryfairy` 네임스페이스에 일회용 `mysql:8.0` 파드를 띄워 user-app이 실제로 읽는 서빙 DB `mysql.victoryfairy.svc.cluster.local`에 접속해 `games` 전수 조회):** 총 564건 중 `CANCELED` 30건 전부 `cancel_reason`이 채워져 있고(null 0건), `FINISHED`(487)·`SCHEDULED`(35)·`DRAW`(12) 564-30=534건은 전부 `null`이었다. 30건 전부 값이 동일하게 `"폭염취소"`이고, 대상 경기 날짜 범위는 2026-08-01~2026-08-09, `updated_at`이 2026-08-10 06:57 UTC로 이 컬럼을 추가한 커밋 f01d08e(2026-08-10 06:16 UTC 머지) 약 40분 뒤였다 — **py-collector의 쓰기 로직은 이미 구현돼 동작 중이다.** ⚠ **단 이 30건은 2026-08 스냅샷 기준 관측치일 뿐 값의 종류가 닫혔다는 뜻이 아니다** — 사유를 `game_statuses` 행으로 세분화하지 않고 별도 컬럼으로 둔 이유도 `positionName`과 같은 논리다("닫힌 집합이 아니라 계속 늘어난다"). 지금까지는 `"폭염취소"` 하나만 관측됐지만 `"우천취소"` 등 다른 사유가 언제든 나타날 수 있다. **실제로 값이 채워지는 지금, 클라이언트가 특정 문자열로 `switch`하면 안 된다는 경고가 이전보다 더 중요해졌다**([`/games/lineup`의 `positionName` 경고](#get-apigameslineup)와 같은 성격) |
| data[].inning | Integer \| null | **2026-08-11 신규.** 현재 진행 중인 이닝. `Game.currentInning`(`games.current_inning`, `TINYINT`, nullable). 값이 있으면 범위는 **1~11**(정규 9회 + 연장 2회) — DB CHECK 제약 `ck_games_current_inning`(`current_inning BETWEEN 1 AND 11`)이 강제한다. **`gameState`가 `IN_PROGRESS`일 때만 값이 있고 그 외(예정·종료·취소·무승부)에는 `null`이다.** ⚠ **현재는 `IN_PROGRESS` 경기에서도 항상 `null`이다** — 이 값을 채우는 주체는 py-collector이고, `cancelReason`과 달리 **수집기 쪽 쓰기 구현이 아직 없다**(2026-08-11 devdb 실측 참고, 아래). 표시 형태(예: `"9회초"`로 합쳐 보여주기)는 서버가 정하지 않는다 — **클라이언트가 `inning`과 `inningHalf`를 조합해 표시 문자열을 만든다** |
| data[].inningHalf | String \| null | **2026-08-11 신규.** 이닝 초/말. `Game.inningHalf`(`games.inning_half`, `TINYINT`, `@Enumerated(EnumType.ORDINAL)`, nullable — DB에는 `TOP=0`/`BOTTOM=1`로 저장). **응답에는 ORDINAL 값이 아니라 enum 이름 문자열**(`"TOP"`\|`"BOTTOM"`)로 나간다 — `gameState`가 `GameStatus.getName()` 문자열을 그대로 내보내는 것과 같은 방식(`GameResponse.from()`이 `game.getInningHalf() == null ? null : game.getInningHalf().name()`으로 변환, DB 선언 순서가 API 계약이 되는 것을 피하기 위함). `inning`과 동일하게 **`IN_PROGRESS`일 때만 값이 있고 그 외에는 `null`**이며, **현재는 py-collector 미구현으로 항상 `null`**이다 |
| message | null | 사용되지 않음 |

**`inning`/`inningHalf`는 2026-08-11 신규 필드이며 현재는 항상 `null`이다.** `games` 테이블에 진행 중 이닝 정보를 담을 컬럼(`current_inning`/`inning_half`)이 막 추가됐지만, 이 값을 채우는 주체인 py-collector 쪽에는 아직 쓰기 로직이 없다 — `cancelReason`이 컬럼 추가 시점엔 마찬가지로 항상 `null`이었다가 이후 py-collector 구현이 배포되며 실제 값이 채워지기 시작한 것과 같은 경로를 밟을 것으로 예상된다(위 `cancelReason` 필드 설명의 실측 이력 참고). **실측(2026-08-11, devdb, `GET /api/games?date=2026-08-13`, 대상 경기 전부 `SCHEDULED`):** 응답의 모든 항목에서 `"inning":null,"inningHalf":null`을 확인했다. `IN_PROGRESS` 상태 경기에 대한 실측은 아직 없다(devdb에 해당 상태 데이터가 없었음 — 아래 "확인 필요" 참고).

**`cancelReason`의 권장 표시 규칙(클라이언트 구현, 서버 미구현).** `gameState`가 `CANCELED`인데 `cancelReason`이 `null`인 드문 경우(위 필드 설명 참고)를 화면에서 자연스럽게 처리하려면, 클라이언트가 다음 fallback을 적용하는 것을 권장한다:

```
cancelReason ?? "경기취소"   // gameState === "CANCELED" 일 때만 적용
```

**이 fallback은 서버가 아니라 프론트엔드가 구현한다** — 서버 응답에는 넣지 않는다. 이유 두 가지: (1) 서버가 `null` 자리를 `"경기취소"`로 채워 내보내면 "사유를 아는 경우"와 "모르는 경우"의 구분이 응답에서 영영 사라진다. 그러면 사유 수집이 안 되는 케이스가 얼마나 되는지 응답만 보고는 알 수 없게 된다. (2) `"경기취소"`는 한국어 UI 표시 문자열이지 API 계약이 아니다 — 표시 문구를 API 응답에 박으면 클라이언트가 정할 몫을 서버가 가로채는 셈이다. 이 원칙은 이 문서에 이미 선례가 있다: 위 `stadium`이 `null`일 때 "표기 방식은 클라이언트가 정한다"고 한 것과 같은 원칙이다.

⚠ **이 fallback은 `gameState === "CANCELED"`일 때만 적용해야 한다.** 다른 상태에서는 `cancelReason`이 항상 `null`이므로(위 필드 설명 참고), 상태 구분 없이 이 fallback을 적용하면 취소되지 않은 정상 경기에도 `"경기취소"`가 표시되는 오류가 난다.

**`GameResponse`의 실제 필드 순서는 `gameId`, `stadium`, `homeTeam`, `homeTeamId`, `awayTeam`, `awayTeamId`, `homeTeamScore`, `awayTeamScore`, `gameDate`, `gameState`, `cancelReason`, `inning`, `inningHalf` 13개다**(record 컴포넌트 선언 순서, `user/src/main/java/com/skhynix/user/game/dto/GameResponse.java`). 기존 11개 필드의 이름·순서·의미는 그대로이며, `inning`/`inningHalf`가 맨 뒤에 추가됐다.

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
| 400 | (래퍼 없음) | `date` 형식 위반(예: `?date=2026/08/01`, `?date=20260801`) 또는 존재하지 않는 날짜(예: `?date=2026-13-01`). **`date` 자체가 없는 것은 더 이상 오류가 아니다**(200 + 오늘) — 이 400은 오직 "값은 있는데 파싱이 안 됨"에만 해당한다. 컨트롤러 진입 전 타입 변환·바인딩 단계라 `GlobalExceptionHandler`가 아니라 Spring 기본 예외 처리(`DefaultHandlerExceptionResolver`)가 처리한다 — **`GET /api/players`의 `teamId` 형식 오류와 같은 사정으로, 이 응답만 `ApiResponse` 래퍼가 아니다** |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음 — 405 아님) |

Authorization 헤더가 있어도(만료·무효 토큰이어도) 이 경로는 `permitAll`이라 검증 자체를 거치지 않고 그대로 200을 반환한다.

**예시**
```bash
curl -i -X GET "http://localhost:8080/api/games?date=2026-08-01"
```
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":"잠실","homeTeam":"LG","homeTeamId":1,"awayTeam":"삼성","awayTeamId":5,"homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED","cancelReason":null,"inning":null,"inningHalf":null}],"message":null}
```

구장 미정 경기 예시(`stadium: null`):
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":null,"homeTeam":"LG","homeTeamId":1,"awayTeam":"삼성","awayTeamId":5,"homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED","cancelReason":null,"inning":null,"inningHalf":null}],"message":null}
```

취소 경기 예시(`gameState: "CANCELED"`) — **실측**(2026-08-11, 운영 서빙 DB `games` 전수 조회, 위 필드 설명 참고). `naver_game_id`는 실제 취소 경기 중 하나인 `20260809HTLG02026`(2026-08-09, `cancelReason: "폭염취소"`)를 예로 든다. `inning`/`inningHalf`는 이 실측 시점엔 아직 컬럼이 없어 직접 확인되지 않았으나, `CANCELED`는 `IN_PROGRESS`가 아니므로 위 필드 설명에 따라 `null`이다:
```json
{"success":true,"data":[{"gameId":"20260809HTLG02026","stadium":"잠실","homeTeam":"LG","homeTeamId":1,"awayTeam":"KIA","awayTeamId":2,"homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-09T18:30:00","gameState":"CANCELED","cancelReason":"폭염취소","inning":null,"inningHalf":null}],"message":null}
```
(실측상 2026-08-11 기준 `CANCELED` 30건 전부 `"폭염취소"` 하나로만 관측됐다. 값의 종류가 닫힌 집합이라는 뜻은 아니므로 — 위 필드 설명 참고 — `switch`로 분기하지 말 것)

`inning`/`inningHalf` 실측 예시(2026-08-11, devdb, `GET /api/games?date=2026-08-13`, 200 OK, 대상 경기 전부 `SCHEDULED`) — 응답의 각 항목에 다음 두 키가 그대로 확인됐다(다른 필드 값은 이 실측 대상이 아니므로 생략):
```bash
curl -i -X GET "http://localhost:8080/api/games?date=2026-08-13"
```
```json
{"inning":null,"inningHalf":null}
```
`IN_PROGRESS` 상태 경기에 대한 `inning`/`inningHalf` 실측은 아직 없다(py-collector가 값을 채우지 않아 devdb에도 그런 데이터가 없다).

`date` 생략 예시(200, `Asia/Seoul` 기준 오늘 경기):
```bash
curl -i -X GET "http://localhost:8080/api/games"
```

형식 오류 예시(400, `ApiResponse` 래퍼 아님 — `date=20260801`처럼 구분자가 없거나 `date=2026-13-01`처럼 존재하지 않는 날짜):
```bash
curl -i -X GET "http://localhost:8080/api/games?date=20260801"
```

경기 없는 날짜 예시:
```json
{"success":true,"data":[],"message":null}
```

---

## GET /api/games/support
> 최종 변경: 2026-08-13 — 신규 추가(요구사항 `docs/requirements/user/support-team-games.md`, USER-GSP-1~24)

인증된 요청 계정의 **활성 응원 구단**(홈 또는 원정)이 참여하는 경기만 좁혀서 돌려준다. `GameController.getSupportTeamGames` → `GameService.getSupportTeamGames(Long, LocalDate)` → `UserSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull` + `GameRepository.findAllByTeamAndGameDateRange(teamId, start, end)`.

같은 날짜 [`GET /api/games`](#get-apigames) 응답의 **부분집합**이다 — 이 경로 전용 가공·대체값은 없고, 겹치는 `gameId` 항목은 13필드 값이 완전히 동일하다(USER-GSP-3).

**인증 필수.** `Authorization: Bearer <access>`. `SecurityConfig`는 이 경로를 별도로 `permitAll`하지 않는다 — `/games`의 `permitAll` 매처가 정확 경로 매칭이라 `/games/support`를 커버하지 않고, 그 결과 `anyRequest().authenticated()`에 자연히 걸린다(위 "이 도메인의 특이사항" 참고). **GET 이외의 메서드로 인증 없이 요청해도 405가 아니라 401이다** — `/games`(GET 한정 `permitAll`)와 표면적으로 같아 보이지만 원인이 다르다: `/games`는 "GET만 열려 있어서" 비-GET이 401이고, 이 경로는 **애초에 전 메서드가 인증 필요**라 미인증이면 메서드와 무관하게 401이다(USER-GSP-20).

**쿼리 파라미터**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| date | LocalDate | 아니오 | 조회할 날짜. ISO `yyyy-MM-dd`(`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`). 생략하면 `Asia/Seoul` 기준 오늘(`GET /api/games`와 동일한 `ClockConfig`의 `Clock` 빈 사용). `?date=`(값 없이 키만)도 생략과 동일하게 `null`로 바인딩된다(USER-GSP-19, 실측: `GameControllerSupportTest`) |

날짜 해석·반개구간 조회(`[대상일 00:00, 대상일+1일 00:00)`)는 [`GET /api/games`](#get-apigames)와 완전히 동일한 규칙이다 — 별도 서술을 반복하지 않는다.

**"활성 응원 구단"의 판정 기준**: 요청 계정의 `user_support_teams` 중 `oppose IS NULL`인 행의 구단(`UserSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull`). 응원 구단을 취소(`oppose` 전이)했거나 한 번도 선택하지 않은 계정은 이 조회가 빈 `Optional`을 반환하고, 서비스는 `GameRepository`를 아예 호출하지 않은 채 빈 리스트를 반환한다(USER-GSP-16·17).

**응답 200 OK** `ApiResponse<List<GameResponse>>` — 항목 키·타입·의미는 [`GET /api/games`](#get-apigames)의 13필드 표와 완전히 동일하다(`gameId`/`stadium`/`homeTeam`/`homeTeamId`/`awayTeam`/`awayTeamId`/`homeTeamScore`/`awayTeamScore`/`gameDate`/`gameState`/`cancelReason`/`inning`/`inningHalf`). **이 경로 전용 필드는 없다** — 어느 쪽(홈/원정)이 응원 구단인지 알리는 필드(`isHome` 등)를 넣지 않는다. 클라이언트는 `homeTeamId`/`awayTeamId`를 `GET /api/users/me`의 `supportTeam.id`와 대조해 판별한다(범위 제외 결정, 요구사항 문서 "제외" 절).

**경기 상태로 거르지 않는다.** `CANCELED`·`FINISHED` 등 상태와 무관하게 활성 응원 구단이 낀 경기는 전부 포함되고, `gameState`·`cancelReason`은 `GET /api/games`와 동일한 값 그대로 나간다(USER-GSP-9).

**정렬: `gameDate` 오름차순**, DB(`@Query`, `order by g.gameDate asc`)가 단독 수행한다.

**활성 응원 구단이 없는 계정은 200 + 빈 배열이다(에러 아님).** `SUPPORT_TEAM_REQUIRED` 류의 전용 오류 코드·메시지는 없다(결정 기록, 요구사항 문서 참고 — 2026-08-13 사용자 확정, 재조사 대상 아님):
```json
{"success":true,"data":[],"message":null}
```
**이 응답은 "대상 날짜에 경기가 없음"과 완전히 동일하다** — 응답만으로는 "응원 구단 미선택"과 "경기 없는 날"을 구분할 수 없다. 두 상황을 갈라야 하는 화면은 이 응답이 아니라 [`GET /api/users/me`](account.md)의 `supportTeam`(선택 여부)으로 판별한다(**사용자 확정 사항**).

**대상 날짜에 응원 구단 경기가 없어도 다음 경기로 대체(폴백)하지 않는다.** `date`가 가리키는 그 날짜만 조회하며, 별도의 "다음 경기" 경로(`/games/support/next` 등)도 없다(요구사항 문서 결정 기록 2, 재조사 대상 아님).

**SQL 호출 특성(요구사항 USER-GSP-21·22, 코드 근거).** 응원 구단 조회(`findByUserAccount_IdAndOpposeIsNull`)는 요청당 1회이고, 경기 조회(`findAllByTeamAndGameDateRange`)는 활성 응원 구단이 있을 때만 1회 추가된다 — 둘 다 경기 건수와 무관한 고정 횟수다. 경기 조회에는 `GET /api/games`와 동일한 `@EntityGraph(attributePaths = {"homeTeam", "awayTeam", "stadium", "gameStatus"})`가 붙어 있어 N+1이 없다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (래퍼 없음) | `date` 형식 위반(예: `?date=20260801`) 또는 존재하지 않는 날짜(예: `?date=2026-13-01`). `GET /api/games`와 같은 사정으로 컨트롤러 진입 전 타입 변환 단계라 `GlobalExceptionHandler`를 타지 않는다 — **`ApiResponse` 래퍼가 아니다**. 인증 여부와 무관하게 이 400이 우선한다(실측: `GameControllerSupportTest`, 유효 토큰을 실어도 400) |
| 401 | UNAUTHENTICATED(`"인증이 필요합니다."`) | `Authorization` 헤더 없음 · 위조/만료 access 토큰 · refresh 타입 토큰 실림 · 탈퇴(`exit_at IS NOT NULL`)했거나 존재하지 않는 계정을 가리키는 uid · GET 이외 메서드로 미인증 요청. 네 경우 모두 응답이 구분되지 않는다(위 "인증 필수" 참고, `/players`가 같은 네 경우를 200으로 흡수하는 것과 정반대) |

**예시**

응원 구단(LG, `homeTeamId`/`awayTeamId` 값 체계는 [`GET /api/teams`](team.md)의 `id`와 동일)이 원정으로 출전한 경기 1건:
```bash
curl -i -X GET "http://localhost:8080/api/games/support?date=2026-08-01" \
  -H "Authorization: Bearer <access-token>"
```
```json
{"success":true,"data":[{"gameId":"20260801HTLG02026","stadium":"광주기아챔피언스필드","homeTeam":"KIA","homeTeamId":6,"awayTeam":"LG","awayTeamId":3,"homeTeamScore":4,"awayTeamScore":2,"gameDate":"2026-08-01T18:30:00","gameState":"FINISHED","cancelReason":null,"inning":null,"inningHalf":null}],"message":null}
```

활성 응원 구단이 없거나 그 날 경기가 없는 경우(둘 다 같은 응답):
```json
{"success":true,"data":[],"message":null}
```

인증 없이 요청(401):
```bash
curl -i -X GET "http://localhost:8080/api/games/support"
```
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## GET /api/games/lineup
> 최종 변경: 2026-08-13 — `gameId` 파라미터 누락 400의 응답 형태 정정(래퍼 없음 → `ApiResponse` 래퍼, 아래 "실패" 참고). 엔드포인트·다른 400은 불변. (직전: 2026-08-04 신규 추가 + `positionName` 매핑 표에 실측 58종 복수 포지션 원문(2글자) 절 추가)

경기별 선발 라인업 조회(홈·원정 두 팀이 한 응답에 함께 나온다). `GameLineupController` → `GameLineupService.getLineup(String)` → `GameRepository.findByNaverGameId` + `GameLineupRepository.findStarterPitchers`/`findStarterBatters`.

**인증 불필요.** `GET`만 `SecurityConfig`에서 `permitAll`(`.requestMatchers(HttpMethod.GET, "/games/lineup").permitAll()`). `/games`의 `permitAll` 매처는 정확 경로 매칭이라 이 하위 경로를 커버하지 않으므로 **별도 줄로 열려 있다** — 컨트롤러 Javadoc이 "이 줄을 빠뜨리면 전 요청이 401"이라고 경고하는 지점이다.

**요청**: 쿼리 파라미터 `gameId` 1개(**필수**, `@RequestParam String gameId`).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gameId | String | 예 | **내부 PK가 아니라 `Game.naverGameId`(네이버 자연키 문자열)다.** [`GET /api/games`](#get-apigames) 응답의 `gameId` 필드와 같은 값을 그대로 넣는다. **경기 내부 PK를 넣으면 항상 404가 난다** — 오해하기 쉬운 지점이니 주의 |

**응답 200 OK** `ApiResponse<List<GameLineupResponse>>`

- 배열 원소 = 팀 1개. **`teamId` 오름차순**으로 정렬돼 있으며, 라인업이 실제로 존재하는 팀만 등장한다(반대 팀이 아직 미공시면 그 팀의 빈 그룹조차 나오지 않는다 — 경기당 원소 0~2개).

| 필드 | 타입 | 설명 |
|---|---|---|
| data[].teamId | Long | 구단 PK. [`GET /api/teams`](team.md)의 `id`, [`GET /api/games`](#get-apigames)의 `homeTeamId`/`awayTeamId`와 값 체계가 같다. **서버는 이 팀이 홈인지 원정인지 판정하지 않는다** — 클라이언트가 `GET /games`의 `homeTeamId`/`awayTeamId`와 대조해 알아내야 한다 |
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
| 400 | (공통 래퍼, `ErrorCode` 아님) | `gameId` 쿼리 파라미터 **자체가 없을 때**(`?gameId=` 아니라 `gameId=` 키조차 없음)만 해당. `@RequestParam String gameId`가 필수라 Spring이 바인딩 단계에서 `MissingServletRequestParameterException`을 던지며, **2026-08-13부터 `web-support`의 `GlobalExceptionHandler`가 이를 처리하는 `@ExceptionHandler`를 갖는다**(공유 컴포넌트, user·quiz 공통) — `{ "success": false, "data": null, "message": "필수 요청 파라미터가 누락되었습니다: gameId" }`을 400으로 반환한다. **`BusinessException`이 아니라서 `ErrorCode`는 없지만, 래퍼는 씌워진다** — `GET /api/games`의 `date` 형식 오류·`GET /api/players`의 `teamId` 형식 오류(둘 다 타입 변환 실패, 여전히 래퍼 아님)와는 다른 경로다 |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음) |

**예시**(홈·원정 두 팀, 각 팀 투수 1명·타자 9명 예시. 나머지는 `...`로 생략):
```bash
curl -i -X GET "http://localhost:8080/api/games/lineup?gameId=20260801LGSS02026"
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
curl -i -X GET "http://localhost:8080/api/games/lineup?gameId=존재하지않는값"
```
```json
{"success":false,"data":null,"message":"존재하지 않는 경기입니다."}
```

`gameId` 파라미터 누락 예시(400, 실측: 러닝 user 앱, 공통 `ApiResponse` 래퍼):
```bash
curl -i -X GET "http://localhost:8080/api/games/lineup"
```
```json
{"success":false,"data":null,"message":"필수 요청 파라미터가 누락되었습니다: gameId"}
```

---

## 확인 필요 / 코드 미확인

- `gameId`(`naverGameId`)가 자연키를 그대로 노출하는 것이 의도적 결정인지, 아니면 향후 별도 PK 기반 식별자로 교체될 잠정값인지 코드만으로는 판단 불가. `(확인 필요)`
- `Game.id`(PK)·`createdAt`·`updatedAt`이 응답에서 제외되는 이유는 코드·Javadoc에 명시적으로 적혀 있지 않다.
- 경기 상세 조회, 기간 범위 조회(`from`/`to`), 구단별 필터 엔드포인트는 없다 — 하루 단위 조회가 전부다.
- `gameState`는 코드 상수가 아니라 `game_statuses` 테이블 행이라 이론상 임의 문자열일 수 있다. 현재 py-collector가 채우는 5종 밖의 값이 나타날 가능성은 코드로 막혀 있지 않다.
- **`inning`/`inningHalf`를 채우는 py-collector 쓰기 로직의 소스코드는 이 저장소 범위 밖이라 확인 불가**(`games.current_inning`/`games.inning_half` 컬럼과 `GameResponse` 노출은 코드로 확인됨). 2026-08-11 devdb 실측(`GET /api/games?date=2026-08-13`, 대상 전부 `SCHEDULED`)에서는 두 필드 모두 `null`이었다. `IN_PROGRESS` 상태 경기에서 실제로 값이 채워지는지는 이 저장소만으로는 확인 불가하고, `cancelReason`처럼 이후 py-collector 구현이 배포되면 값이 채워지기 시작할 것으로 예상만 할 뿐이다. `(확인 필요)`
- **`cancelReason`을 채우는 py-collector 쓰기 로직 자체의 소스코드는 이 저장소 범위 밖이라 확인 불가**(`games.cancel_reason` 컬럼과 `GameResponse.cancelReason` 노출은 코드로 확인됨 — user·domain 양쪽에 이 앱이 이 컬럼에 쓰는 경로는 없음, `Game`은 `@Builder`로만 생성되고 setter가 없다). 다만 **"이미 채우고 있는지" 자체는 코드가 아니라 운영 DB 실측으로 확인했다**: 2026-08-11, EKS `victoryfairy` 네임스페이스에서 user-app이 실제로 읽는 서빙 DB(`mysql.victoryfairy.svc.cluster.local`)에 일회용 `mysql:8.0` 파드로 접속해 `games` 테이블을 전수 조회한 결과 `CANCELED` 30건 전부 `cancel_reason`이 채워져 있었고(전부 `"폭염취소"`, 대상 날짜 2026-08-01~09), `updated_at`이 커밋 f01d08e 머지(2026-08-10 06:16 UTC) 약 40분 뒤인 2026-08-10 06:57 UTC였다. **py-collector 쓰기 로직 자체는 확인 불가지만, 그 로직이 이미 배포·동작 중이라는 사실은 실측으로 확인됐다.** `(확인 필요)`로 남기는 부분은 이 로직이 "정확히 언제부터" 동작했는지(커밋 머지~2026-08-10 06:57 UTC 사이 어느 시점인지는 실측 범위 밖)와, `"폭염취소"` 외 다른 사유 문자열이 실제로 나온 사례가 있는지(2026-08 스냅샷에서는 관측되지 않음, 값 집합이 닫혔다는 뜻은 아님) 두 가지뿐이다.

## 관련 문서

- [구단(team)](team.md) — `GET /games`의 `homeTeam`/`awayTeam`은 구단 **이름** 문자열, `homeTeamId`/`awayTeamId`·`GET /games/lineup`의 `teamId`는 구단 **PK**다. 값 체계는 `GET /teams`의 `id`와 동일하다.
