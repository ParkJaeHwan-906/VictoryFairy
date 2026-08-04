# 경기(game) API 명세

> **도메인** `game` — 날짜별 KBO 경기 일정·스코어.
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/games` · **엔드포인트** 1개
> **컨트롤러** `user/src/main/java/com/skhynix/user/game/controller/GameController.java` (`@RequestMapping("/games")`)
> **최종 갱신** 2026-08-04 — 모듈별(`user.md`) 문서를 도메인별로 분리. 계약 변경 없음.
> 공통 규약(응답 래퍼·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/member/games](#get-apimembergames) | 200 | 날짜별 경기 목록 |

## 이 도메인의 특이사항

[구단(team)](team.md)·[선수(player)](player.md)와 같은 **공개 참조 데이터**로, GET 한정 `permitAll`·페이징 없음·빈 결과 200이라는 계약을 공유한다.

**단, 자연키 노출 정책만 다르다.** `TeamResponse`가 `Team.code`를, `PlayerResponse`가 `kboPlayerId`를 감추는 것과 달리 `GameResponse.gameId`는 `Game.naverGameId`(네이버 스포츠 gameId)를 그대로 내보낸다.

**"오늘"의 정의가 코드에 고정돼 있다.** `date`를 생략하면 `ClockConfig`가 등록한 `Clock.system(ZoneId.of("Asia/Seoul"))` 기준 오늘로 조회한다 — 운영 파드가 UTC로 돌기 때문에 시간대를 배포 설정(`TZ`)이 아니라 코드에서 고정했다. 다만 아래 본문의 경고대로 **클라이언트가 날짜를 알고 있다면 항상 명시해 넘기는 편이 안전하다.**

---

## GET /api/member/games
> 최종 변경: 2026-08-01 — 응답에 `stadium` 필드 추가

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
| data[].gameId | String | `Game.naverGameId` — 네이버 스포츠 gameId(예: `"20260708LGSS02026"`). py-collector가 upsert 키로 쓰는 자연키이지만, `Team.code`/`Player.kboPlayerId`와 달리 이 값은 응답에 그대로 노출된다(더블헤더 구분 등 클라이언트가 식별자로 쓸 필요가 있어 보임 — `TeamResponse`/`PlayerResponse`가 자연키를 감추는 것과 다른 결정이니 주의) |
| data[].stadium | String \| null | 구장 이름(`Game.stadium.name`). **`null` 가능** — `Game.stadium`이 `Game`의 연관 중 유일하게 선택적(`optional = true`, `stadium_id` nullable)이라 구장이 아직 미정인 경기(편성 전·중립구장 미확정 등)는 `null`로 나간다. `homeTeamScore`/`awayTeamScore`가 경기 전 `null`인 것과 같은 취급이며, 표기 방식은 클라이언트가 정한다(`GameResponse.from()`이 `game.getStadium() == null ? null : game.getStadium().getName()`으로 방어) |
| data[].homeTeam | String | 홈 구단 이름(`Game.homeTeam.name`) |
| data[].awayTeam | String | 원정 구단 이름(`Game.awayTeam.name`) |
| data[].homeTeamScore | Integer \| null | 홈 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].awayTeamScore | Integer \| null | 원정 팀 점수. 경기 전(`SCHEDULED`)이면 `null` |
| data[].gameDate | LocalDateTime | 경기 시각. `LocalDateTime` 직렬화 형태 그대로(예: `"2026-08-01T18:30:00"`) — 별도 포맷 지정 없음 |
| data[].gameState | String | `Game.gameStatus.name`(`game_statuses` 테이블 값). 코드 상수가 아니라 DB 행이라 이론상 임의 문자열일 수 있으나, 현재 py-collector가 채우는 값은 `SCHEDULED`\|`IN_PROGRESS`\|`FINISHED`\|`DRAW`\|`CANCELED` 5종(`GameStatus` 엔티티 Javadoc 참고) |
| message | null | 사용되지 않음 |

**`GameResponse`의 실제 필드 순서는 `gameId`, `stadium`, `homeTeam`, `awayTeam`, `homeTeamScore`, `awayTeamScore`, `gameDate`, `gameState` 8개다** (record 컴포넌트 선언 순서, `user/src/main/java/com/skhynix/user/game/dto/GameResponse.java`).

**`Game.id`(PK)·`createdAt`·`updatedAt`은 의도적으로 응답에 없다.** `GameResponse.from()`이 엔티티를 그대로 직렬화하지 않고 8개 필드만 골라 변환한다.

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
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":"잠실","homeTeam":"LG","awayTeam":"삼성","homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
```

구장 미정 경기 예시(`stadium: null`):
```json
{"success":true,"data":[{"gameId":"20260801LGSS02026","stadium":null,"homeTeam":"LG","awayTeam":"삼성","homeTeamScore":null,"awayTeamScore":null,"gameDate":"2026-08-01T18:30:00","gameState":"SCHEDULED"}],"message":null}
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

## 확인 필요 / 코드 미확인

- `gameId`(`naverGameId`)가 자연키를 그대로 노출하는 것이 의도적 결정인지, 아니면 향후 별도 PK 기반 식별자로 교체될 잠정값인지 코드만으로는 판단 불가. `(확인 필요)`
- `Game.id`(PK)·`createdAt`·`updatedAt`이 응답에서 제외되는 이유는 코드·Javadoc에 명시적으로 적혀 있지 않다.
- (정정, 2026-08-01) 이전 버전 문서에는 `GameResponse.from()`이 `stadium`을 응답에서 제외한다고 적혀 있었으나, `stadium` 필드가 응답에 추가되며 더 이상 사실이 아니다.
- 경기 상세 조회, 기간 범위 조회(`from`/`to`), 구단별 필터 엔드포인트는 없다 — 하루 단위 조회가 전부다.
- `gameState`는 코드 상수가 아니라 `game_statuses` 테이블 행이라 이론상 임의 문자열일 수 있다. 현재 py-collector가 채우는 5종 밖의 값이 나타날 가능성은 코드로 막혀 있지 않다.

## 관련 문서

- [구단(team)](team.md) — 응답의 `homeTeam`/`awayTeam`은 구단 **이름** 문자열이며 구단 PK가 아니다. 이 응답만으로는 구단 목록의 `id`와 연결할 수 없다.
