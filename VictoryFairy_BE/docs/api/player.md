# 선수(player) API 명세

> **도메인** `player` — KBO 선수 참조 데이터 및 이름 검색.
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/players` · **엔드포인트** 1개
> **컨트롤러** `user/src/main/java/com/skhynix/user/player/controller/PlayerController.java` (`@RequestMapping("/players")`)
> **최종 갱신** 2026-08-06 — **응답 항목 키가 바뀐 파괴적 변경**: `{id, name}` → `{teamId, teamName, playerId, playerName, playerNumber, playerPosition}`. 소속 구단·등번호·포지션이 추가됐고 기존 `id`·`name` 키는 **사라졌다**(직전 변경: 2026-08-04 적용 구단 오버라이딩)
> 공통 규약(응답 래퍼·401 정책)은 [README.md](README.md)를 먼저 볼 것.
> 요구사항: `docs/requirements/user/player-lookup-team-fallback.md`(USER-PLF-1~21)

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/member/players](#get-apimemberplayers) | 200 | 선수 목록 조회 + 구단 필터(응원 구단 우선) + 이름 검색 |

## 이 도메인의 특이사항

[구단(team)](team.md)·[경기(game)](game.md)와 같은 **공개 참조 데이터**로, GET 한정 `permitAll`·페이징 없음·빈 결과 200이라는 계약을 공유한다.

**이 도메인만 검색 기능을 갖는다.** `teamId`(구단 필터)와 `name`(이름 부분 일치)을 각각 또는 함께 줄 수 있어, 리포지토리 메서드가 조합에 따라 4가지로 갈린다. 검색이지만 관련도 순 정렬은 없고 항상 `name` 오름차순이다.

**2026-08-04부터: 구단 조건은 요청의 `teamId`가 아니라 "적용 구단"이다.** `PlayerService.resolveTeamId()`가 다음 순서로 적용 구단을 결정하고, `teamId`는 이 결정에 **입력값 후보 중 하나**일 뿐 항상 쓰이는 것은 아니다.

| 순위 | 조건 | 적용 구단 |
|---|---|---|
| 1 | 유효한 access 토큰의 계정에 **활성 응원 구단**(`user_support_team.oppose IS NULL`)이 있음 | **응원 구단** — 요청의 `teamId`는 값이 무엇이든(존재하지 않는 id 포함) **조용히 무시**된다 |
| 2 | 1이 아니고(비로그인·무효 토큰·응원 구단 없음/취소됨) 요청에 `teamId`가 있음 | **요청의 `teamId`** |
| 3 | 둘 다 없음 | **없음**(전 구단) |

**같은 URL이 `Authorization` 헤더 유무로 결과가 갈린다.** `GET /api/member/players?teamId=9`를 헤더 없이 보내면 9번 구단 선수만, 활성 응원 구단이 6인 계정의 토큰을 실어 보내면 **9는 무시되고 6번 구단 선수**가 나온다. 프론트가 "필터가 안 먹는다"로 오해하기 쉬운 지점이다.

여기서 반환하는 `data[].playerId`가 [응원(support)](support.md)의 `playerIds` 입력값이다.

---

## GET /api/member/players
> 최종 변경: 2026-08-06 — 응답 항목 키 교체(`id`·`name` 제거, `teamId`·`teamName`·`playerId`·`playerName`·`playerNumber`·`playerPosition` 추가). 쿼리 파라미터·상태코드·정렬은 불변(직전 변경: 2026-08-04 적용 구단 오버라이딩)

KBO 선수 목록 조회 및 이름 검색. `PlayerController` → `PlayerService.getPlayers(Long userAccountId, Long teamId, String name)` → `resolveTeamId()`로 적용 구단 결정 → `PlayerRepository`의 네 메서드 중 하나.

**인증 불필요.** [`GET /api/member/teams`](team.md)와 같은 성격의 참조 데이터라 `SecurityConfig`가 같은 방식으로 열었다(`.requestMatchers(HttpMethod.GET, "/players").permitAll()`, **이번 변경으로도 SecurityConfig는 손대지 않았다** — USER-PLF-15). **`permitAll`은 `HttpMethod.GET`으로 좁혀져 있어** `POST /api/member/players`는 405가 아니라 **401**이다.

**단, 인증이 있으면 결과가 달라진다.** 비인증 요청(헤더 없음·무효 토큰·refresh 토큰 오용·탈퇴 계정)은 종전과 동일하게 `teamId`를 그대로 필터로 쓴다(위 순위 2·3). 유효한 access 토큰의 계정에 활성 응원 구단이 있으면 순위 1이 적용되어 `teamId`가 무시된다. **무효 토큰과 헤더 없음은 이 엔드포인트에서 구분되지 않는다** — `permitAll` 경로라 필터가 principal을 비운 채 통과시킬 뿐 401을 내지 않는다.

**요청**: 쿼리 파라미터 2개(둘 다 선택), 헤더 `Authorization`(선택, 있으면 결과에 영향).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | 아니오 | 구단 PK([`GET /api/member/teams`](team.md)의 `data[].id`). **활성 응원 구단이 있는 인증 요청에서는 무시된다.** 그 외에는 해당 구단 소속 선수만, 둘 다 없으면 구단으로 거르지 않는다 |
| name | String | 아니오 | 선수 이름 **부분 일치** 검색어(`LIKE '%검색어%'`). 적용 구단(위 표로 결정된 구단)과 AND 결합한다. 생략하면 이름으로 거르지 않는다 |

**적용 구단과 `name`은 항상 AND로 결합**된다. 조합에 따라 실제로 나가는 쿼리는 다음 4가지다(모두 "적용 구단" 기준 — `teamId` 원값이 아니다):

| 적용 구단 | name | 리포지토리 메서드 |
|---|---|---|
| 없음 | 없음 | `findAllByOrderByNameAsc()` |
| 있음 | 없음 | `findAllByTeam_IdOrderByNameAsc(appliedTeamId)` |
| 없음 | 있음 | `findAllByNameContainingOrderByNameAsc(name)` |
| 있음 | 있음 | `findAllByTeam_IdAndNameContainingOrderByNameAsc(appliedTeamId, name)` |

**`name`의 빈 값·공백 처리**: `?name=`(값 없음), `?name=%20%20`(공백만)은 **`name`을 주지 않은 것과 동일하게** 취급한다(`LIKE '%%'`로 헛도는 쿼리를 만들지 않는다). 검색어 앞뒤 공백은 제거한 뒤 매칭하므로 `?name=%20도영%20`은 `?name=도영`과 결과가 같다. 이 처리는 적용 구단 결정과 무관하게 그대로다.

**`?teamId=`(빈 값)는 400이 아니라 미전달과 동일하다.** Spring의 `StringToNumberConverterFactory`가 빈 문자열을 `null`로 변환하므로 `teamId`가 없는 것과 같은 결정 규칙을 탄다(응원 구단이 있으면 그쪽, 없으면 전 구단).

**대소문자를 구분하지 않는다** — MySQL 기본 콜레이션(`_ci`)이 흡수한다. 다만 초성 검색(`ㄱㄷㅇ`), 오타 허용, 관련도 순 정렬은 **지원하지 않는다**(단순 `LIKE` 검색이며 정렬은 아래대로 항상 `name` 오름차순 고정).

`?page=`/`?size=` 등은 서버가 해석하지 않으며 **페이징이 없다** — 항상 단일 배열로 반환한다. 검색 결과가 많아도 마찬가지다.

**응답 200 OK** `ApiResponse<List<PlayerResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 선수 배열 |
| data[].teamId | Long | 소속 구단 PK |
| data[].teamName | String | 소속 구단 이름 |
| data[].playerId | Long | 선수 PK |
| data[].playerName | String | 선수 이름 |
| data[].playerNumber | String\|null | 등번호. `'0'`·`'00'` 구분과 선행 0 보존 때문에 숫자가 아니라 **문자열**이다. 미배정(일부 육성선수)이면 `null` |
| data[].playerPosition | String\|null | KBO 공식 포지션 구분 4종 중 하나 — `PITCHER`·`CATCHER`·`INFIELDER`·`OUTFIELDER`. 1군 이력이 없어 구분이 없으면 `null` |
| message | null | 사용되지 않음 |

**응답 형태(키 집합)는 적용 구단이 무엇으로 결정됐는지와 무관하게 동일하다.** 어떤 구단이 적용됐는지를 알리는 **전용** 필드(`appliedTeamId` 등)나 헤더는 여전히 **없다**(USER-PLF-14).

다만 **2026-08-06부터 항목마다 `teamId`가 실려, 오버라이딩 여부를 사실상 응답만으로 알 수 있게 됐다** — `?teamId=9`로 요청했는데 돌아온 항목의 `teamId`가 전부 6이면 요청값이 무시된 것이다. 판별이 불가능한 경우는 결과가 빈 배열일 때뿐이며, 그때는 [`GET /api/member/users/me`](account.md)의 `supportTeam`과 대조해야 한다. 이는 구단 필드 추가의 부수 효과이지 오버라이딩을 알리려고 설계한 것이 아니므로, 이 성질에 의존하는 프론트 로직을 짜기 전에 백엔드와 합의할 것.

**`playerNumber`·`playerPosition`은 `null`이 그대로 나간다.** 두 값 모두 KBO 등록명단발이라 원본에 비어 있는 선수가 실제로 존재한다(등번호 미배정 육성선수, 1군 이력이 없어 포지션 구분이 없는 선수). 서버는 `""`나 `"UNKNOWN"` 같은 대체값으로 채우지 않으며, **키가 사라지지도 않는다** — 항목은 언제나 여섯 키를 모두 갖고 값만 `null`이다. "값이 없다"와 "값이 UNKNOWN이다"를 클라이언트가 구분할 수 있게 하려는 결정이므로, 표시 문구(`-`, `미정` 등)를 고르는 것은 프론트 몫이다.

**`average`/`kboPlayerId`/`createdAt`/`updatedAt`는 의도적으로 응답에 없다.** `kboPlayerId`(KBO 공식 playerId, 네이버 record API의 pcode 와도 실측상 동일 값)는 py-collector가 upsert 키로 소유하는 소스 자연키라 `TeamResponse`가 `Team.code`를 감추는 것과 같은 이유로 제외한다.

**구단은 중첩 객체가 아니라 평평한 두 필드(`teamId`·`teamName`)다.** `{"team":{"id":6,"name":"KIA"}}` 형태가 아니다. 이 때문에 응답 변환에서 LAZY인 `Player.team`이 초기화되며, 목록 한 번에 **서로 다른 구단 수만큼**(최대 10) 추가 SELECT가 붙는다(영속성 컨텍스트 1차 캐시가 같은 구단의 반복 조회는 흡수한다). 선수 수에 비례하지는 않지만 공짜도 아니므로, 목록이 더 커지면 리포지토리 4종에 fetch join을 도입할 자리다.

**정렬: `name` 오름차순, DB(`ORDER BY name ASC`)가 단독 수행하며 애플리케이션에서 재정렬하지 않는다.** 적용 구단·`name` 유무와 무관하게 같은 정렬이다(검색 결과도 관련도 순이 아니라 이름 오름차순). 구단 목록과 마찬가지로 한국어 로케일이 아닌 MySQL 콜레이션 기준이다.

**존재하지 않는 구단 id(적용 구단이든 무인증 `teamId`든)나 일치하는 선수가 없는 `name`은 404가 아니라 200 + 빈 배열**이다. 단, **활성 응원 구단이 있는 인증 요청에서는 이 규칙이 성립하지 않을 수 있다** — 존재하지 않는 `teamId`를 보내도 그 값 자체가 무시되고 응원 구단 소속 선수가 반환되므로 빈 배열이 아니라 결과가 있는 200이 나온다(USER-PLF-18). 구단 존재 여부를 따로 조회하지 않으며, [`GET /api/member/teams`](team.md)가 유효한 id의 출처라고 전제한다. `players` 테이블에 행이 없을 때도 동일하다:
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (래퍼 없음) | `teamId`가 숫자가 아님(예: `?teamId=abc`). **토큰·응원 구단 유무와 무관하게 항상 400**이다 — 컨트롤러 진입 전 타입 변환 실패라 오버라이딩 판단(응원 구단 조회)에 도달하지 못하고, `GlobalExceptionHandler`가 아니라 Spring 기본 `DefaultHandlerExceptionResolver`가 처리한다 — **이 응답만 `ApiResponse` 래퍼가 아니다** |
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있음 — 405 아님) |

**`name`에는 400이 없다.** 문자열이라 타입 변환이 실패할 수 없고, 길이·문자 종류 제약도 걸지 않는다. 어떤 값을 줘도 200이며 일치하는 선수가 없으면 빈 배열이다.

**Authorization 헤더가 있어도(만료·무효 토큰이어도) 이 경로는 `permitAll`이라 항상 200을 반환한다.** 다만 유효한 access 토큰이고 그 계정에 활성 응원 구단이 있으면, 그 사실 자체가 `teamId`를 무시시켜 응답 내용을 바꾼다(상태 코드는 여전히 200) — "Authorization 헤더 유무·유효성이 상태 코드에 영향을 주지 않는다"는 것과 "응답 내용에 영향을 주지 않는다"는 것은 별개다.

**알려진 동작(주의)**: 검색어에 든 `%`·`_`는 `LIKE` 와일드카드로 그대로 해석된다(이스케이프하지 않는다) — `?name=%25`는 전체 조회와 같아지고 `?name=_`는 아무 한 글자에나 걸린다. 선수 이름에 이 문자가 들어갈 일이 없어 실사용 영향이 없다고 보고 남겨둔 상태다(파라미터 바인딩이라 SQL 인젝션 경로는 아니다).

**인증된 요청의 조회 비용**: `teamId` 유무와 무관하게 응원 구단 조회가 **요청당 1회** 추가된다(USER-PLF-21) — 오버라이딩 여부를 판단하려면 `teamId`가 있어도 봐야 하기 때문이다. 비인증 요청은 0회.

**예시**
```bash
# 전체(비인증)
curl -i -X GET http://localhost:8080/api/member/players
# 구단 필터(비인증)
curl -i -X GET "http://localhost:8080/api/member/players?teamId=6"
# 이름 검색(부분 일치, 비인증)
curl -i -X GET "http://localhost:8080/api/member/players?name=%EB%8F%84%EC%98%81"
# 구단 + 이름 (AND, 비인증)
curl -i -X GET "http://localhost:8080/api/member/players?teamId=6&name=%EB%8F%84%EC%98%81"
# 응원 구단이 6인 로그인 사용자 — ?teamId=9를 보내도 6번 구단 결과가 나온다
curl -i -X GET "http://localhost:8080/api/member/players?teamId=9" \
  -H 'Authorization: Bearer eyJ...'
```
```json
{"success":true,"data":[{"teamId":21,"teamName":"KIA","playerId":168,"playerName":"김도영","playerNumber":"5","playerPosition":"INFIELDER"}],"message":null}
```
등번호·포지션이 비어 있는 선수는 키를 유지한 채 값만 `null`이다:
```json
{"success":true,"data":[{"teamId":21,"teamName":"KIA","playerId":414,"playerName":"고종욱","playerNumber":null,"playerPosition":null}],"message":null}
```
> 위 두 예시는 2026-08-06 실제 응답에서 그대로 가져왔다. **`teamId`는 1~10이 아니다** — 현재 구단 PK는 16~25 범위이고 KIA는 21이다. 하드코딩하지 말고 [`GET /api/member/teams`](team.md)에서 받아 쓸 것.
>
> 전체 조회는 558건이고 그중 **260건은 `playerNumber`·`playerPosition`이 `null`** 이다(등록명단에 없는 선수). 즉 null은 예외가 아니라 절반 가까운 일반적인 경우다.

---

## 확인 필요 / 코드 미확인

- 선수 상세 조회(`GET /api/member/players/{id}`), 초성 검색·오타 허용·관련도 정렬은 코드에 없다. `Player.average` 필드는 존재하지만 어떤 응답에도 실리지 않는다.
- 노출하는 포지션은 KBO 공식 등록명단의 **4그룹 구분**뿐이다. 1루수·유격수 같은 세부 수비 포지션은 공식 소스 자체가 제공하지 않아 값이 없다 — 필요해지면 `game_lineups`의 경기별 포지션을 집계해 파생하는 것이 다음 단계다(별도 필드로, 이 값의 확장이 아니라).
- **포지션·등번호로 거르는 쿼리 파라미터는 없다.** 응답에 실릴 뿐 필터 조건은 여전히 `teamId`·`name` 둘뿐이며, 포지션별 목록이 필요하면 프론트가 받아서 거르는 수밖에 없다.
- 검색어의 `%`·`_` 미이스케이프는 알려진 동작으로 남겨둔 상태다(위 본문 참고). 선수 이름에 이 문자가 들어갈 일이 없다는 전제이며, 전제가 깨지면 이스케이프 도입이 필요하다.
- 오버라이딩을 우회하는 파라미터(`?ignoreSupport=true` 등)는 요구사항상 의도적으로 두지 않았다(요구사항 문서 "후속" 절) — 응원 구단이 있는 사용자가 타 구단 선수를 조회할 방법은 현재 이 엔드포인트에 없다.

## 관련 문서

- [구단(team)](team.md) — `?teamId=`에 넣을 값의 출처.
- [계정(account)](account.md) — `GET /api/member/users/me`의 `supportTeam`. 응답만으로 오버라이딩 발생 여부를 알 수 없을 때 대조하는 유일한 방법.
- [응원(support)](support.md) — 응원 선수 추가·취소에 이 `playerId`를 쓴다. **응원 선수는 응원 구단 소속이어야 하므로** 선수 선택 UI는 종전에 `?teamId=<응원 구단>`으로 좁혀 호출하도록 안내했으나, **2026-08-04부터는 로그인 상태라면 파라미터 없이 호출해도 같은 결과**가 나온다(적용 구단이 자동으로 응원 구단이 되므로). 비로그인 상태에서 응원 구단을 좁혀 보여줘야 한다면 여전히 `?teamId=`가 필요하다.
