# 응원(support) API 명세

> **도메인** `support` — 사용자의 응원 구단·응원 선수 선택 상태.
> **모듈** user (포트 8080) · **경로 접두사** `/api/support` · **엔드포인트** 3개
> **컨트롤러** `user/src/main/java/com/skhynix/user/support/controller/SupportController.java` (`@RequestMapping("/support")`)
> **최종 갱신** 2026-08-06 — `POST /api/support/players`에 **활성 응원 선수 4명 상한** 도입(`SUPPORT_PLAYER_LIMIT_EXCEEDED`, 400). 같은 날 응원 선수 응답이 재사용하는 `PlayerResponse`의 항목 키가 `{id, name}`에서 여섯 필드로도 바뀜([player.md](player.md) 참고). 이 응답 재사용은 `GET /api/users/me`(`supportPlayers`)에도 번진다 — [account.md](account.md) 참고. `PUT /players/oppose`(취소)는 상한과 무관, 계약 불변.
> **요구사항** `docs/requirements/user/support-selection.md` (USER-SP-4 ~ 36, USER-SP-22는 2026-08-06 폐기·USER-SP-30~36으로 대체)
> 공통 규약(응답 래퍼·JWT·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| POST | [/api/support/team](#post-apisupportteam) | 200 | 응원 구단 선택·변경(최초/변경/재선택 공용) |
| POST | [/api/support/players](#post-apisupportplayers) | 200 | 응원 선수 **추가**(전체 교체 아님) |
| PUT | [/api/support/players/oppose](#put-apisupportplayersoppose) | 200 | 응원 선수 **취소** |

## 이 도메인의 특이사항

**3개 전부 인증 필수.** `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다 — `SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상이다. 대상 계정은 요청 본문이 아니라 access 토큰에서만 정해진다.

**구단이 선수보다 앞선다.** 응원 구단을 고르기 전에는 선수를 고를 수 없고(`SUPPORT_TEAM_REQUIRED`), 선수는 응원 구단 소속이어야 한다(`PLAYER_NOT_IN_SUPPORT_TEAM`).

⚠ **구단을 바꾸면 응원 선수가 전원 자동 취소된다 — 경고 없이.** 프론트가 구단 변경 전에 반드시 고지해야 하는, 이 도메인에서 가장 중요한 계약이다.

**삭제가 아니라 상태 전이다.** 취소는 행을 지우지 않고 `oppose` 컬럼에 시각을 기록한다. 그래서 취소 API가 `DELETE`가 아니라 `PUT`이고, 재선택 시 새 행 대신 기존 행이 재활성된다. 두 번 보내도 결과가 같다(멱등).

**세 응답 모두 "현재 상태 전체"를 돌려준다** — 방금 변경한 항목만이 아니라 반영 후의 응원 구단/응원 선수 전체라, 프론트가 재조회할 필요가 없다.

**응원 선수는 최대 4명이다(2026-08-06 도입).** 강제 주체는 추가 API(`POST /players`) 하나뿐이고, 취소 API는 상한과 무관하다. 자세한 판정 규칙은 [`POST /api/support/players`](#post-apisupportplayers) 절 참고.

---

## POST /api/support/team
> 최종 변경: 2026-07-28 (추정) — 도메인 분리 이전 이력이 없어 `SupportController` 마지막 커밋 기준

응원 구단 선택·변경. `SupportController` → `SupportService.selectTeam()`. 요구사항: USER-SP-4 ~ 13.

**인증 필수.** 이 경로는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다([회원탈퇴](account.md)와 같은 방식). [`GET /api/teams`](team.md)·[`/players`](player.md)와 달리 **`SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상**이다.

**최초 선택·변경·재선택을 이 엔드포인트 하나가 모두 처리한다.** 클라이언트가 요청 전에 자기 상태를 알 필요가 없다.

**요청** `SupportTeamRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | **예** | 응원할 구단 PK([`GET /api/teams`](team.md)의 `data[].id`) |

대상 계정은 본문이 아니라 access 토큰에서만 정해진다 — 본문에 `userAccountId` 같은 필드를 실어도 무시된다.

**응답 200 OK** `ApiResponse<TeamResponse>`
```json
{"success":true,"data":{"id":6,"name":"KIA"},"message":null}
```

**동작 규칙 — 프론트가 반드시 알아야 할 것**

| 상황 | 결과 |
|---|---|
| 응원 이력 없음 | 새로 응원 시작 |
| 이미 같은 구단을 응원 중 | 아무 변경 없이 200(멱등) |
| 다른 구단을 선택 | 기존 구단은 **행 삭제가 아니라 `oppose` 시각 기록**, 새 구단이 활성화 |
| 과거에 응원했다 바꾼 구단을 재선택 | 새 행을 만들지 않고 기존 행 재활성 |
| **구단이 실제로 바뀜** | **그 계정의 응원 선수가 전원 자동 취소된다** |

⚠ **마지막 줄이 가장 중요하다.** 응원 선수는 응원 구단 소속이어야 하므로, 구단을 바꾸면 서버가 **경고 없이** 기존 응원 선수를 전부 취소한다. 프론트는 구단 변경 전에 "선수 선택도 초기화됩니다"를 고지해야 한다. 같은 구단 재선택은 선수를 건드리지 않는다.

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `teamId` 누락/`null` → `{"success":false,"data":{"teamId":"응원할 구단을 선택해 주세요."},"message":"입력값이 올바르지 않습니다."}` |
| 401 | UNAUTHENTICATED | 토큰 없음·만료·위조·refresh 토큰·탈퇴 계정 |
| 404 | TEAM_NOT_FOUND | `"존재하지 않는 구단입니다."` |

**예시**
```bash
curl -i -X POST http://localhost:8080/api/support/team \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"teamId":6}'
```

---

## POST /api/support/players
> 최종 변경: 2026-08-06 — **활성 응원 선수 4명 상한** 신설(400 `SUPPORT_PLAYER_LIMIT_EXCEEDED`). 같은 날 응답 항목 키도 교체(공유 DTO `PlayerResponse` 변경분)

응원 선수 **추가**. → `SupportService.addPlayers()`. 요구사항: USER-SP-14 ~ 23.

**인증 필수.** **전체 교체가 아니라 추가다** — 요청에 없는 선수는 취소되지 않는다. 취소는 아래 [`PUT /api/support/players/oppose`](#put-apisupportplayersoppose)가 담당한다. `playerIds`의 출처는 [`GET /api/players`](player.md)다.

**요청** `SupportPlayersRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| playerIds | Long[] | **예**(빈 배열 허용) | 추가할 선수 PK 목록 |

- **빈 배열 `[]` 은 200**(아무 변경 없음). 선수 응원은 필수가 아니다.
- **필드 자체를 빼면 400** — `null`과 `[]`를 구분한다.
- **중복 id 는 400이 아니라 제거 후 처리**(`[3,3,7]` → 정상).
- **활성 응원 선수 4명 상한(2026-08-06 신설).** 판정은 **합집합**: `현재 활성(oppose is null) 응원 선수 id` ∪ `요청 distinct id`의 크기가 4를 넘으면 거부한다. 이미 응원 중인 선수를 다시 보내는 것은 no-op이라 합집합 크기를 늘리지 않으므로 재요청이 억울하게 막히지 않는다. **취소된 선수는 상한에 안 잡힌다** — 4명 응원 중 2명을 [`PUT /players/oppose`](#put-apisupportplayersoppose)로 취소하면 다시 2명 추가 가능. 상한 도입 이전에 이미 초과 응원 중이던 계정의 기존 행은 잘라내지 않는다(마이그레이션 없음) — 초과 상태 그대로 추가 요청만 막힌다.

**응답 200 OK** `ApiResponse<List<PlayerResponse>>` — **이번에 추가한 선수만이 아니라 현재 응원 중인 선수 전체**를 `name` 오름차순으로 반환한다(프론트가 재조회할 필요 없음). 항목 형태는 [선수(player)](player.md#get-apiplayers)의 응답과 **동일한 DTO**를 재사용하므로, 그쪽 계약이 바뀌면 이 응답도 함께 바뀐다.
```json
{"success":true,"data":[{"teamId":21,"teamName":"KIA","playerId":168,"playerName":"김도영","playerNumber":"5","playerPosition":"INFIELDER"},{"teamId":21,"teamName":"KIA","playerId":414,"playerName":"고종욱","playerNumber":null,"playerPosition":null}],"message":null}
```
`playerNumber`·`playerPosition`은 `null`일 수 있다(등록명단발이라 원본이 비어 있는 선수가 있다 — [player.md](player.md) 참고).

**멱등성**: 이미 응원 중인 선수를 다시 보내면 아무 변경이 없다. 과거에 취소했던 선수를 다시 보내면 새 행이 아니라 기존 행이 재활성된다.

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `playerIds` 누락/`null` |
| 400 | SUPPORT_TEAM_REQUIRED | `"응원하는 구단을 먼저 선택해 주세요."` — **응원 구단을 고르기 전에는 선수를 고를 수 없다**(소속 검사의 기준이 없으므로 선수 검증보다 먼저 판정) |
| 404 | PLAYER_NOT_FOUND | `"존재하지 않는 선수입니다."` |
| 400 | PLAYER_NOT_IN_SUPPORT_TEAM | `"응원하는 구단 소속 선수만 선택할 수 있습니다."` |
| 400 | SUPPORT_PLAYER_LIMIT_EXCEEDED | `"응원 선수는 최대 4명까지 선택할 수 있습니다."` — 활성 응원 선수 id ∪ 요청 distinct id 의 크기가 4 초과(2026-08-06 신설) |
| 401 | UNAUTHENTICATED | 위와 동일 |

**검사 순서(위 표의 행 순서가 곧 판정 우선순위다) — 상한이 마지막이라는 점이 중요하다:**

`SUPPORT_TEAM_REQUIRED`(400) → 빈 요청 조기 반환(200) → `PLAYER_NOT_FOUND`(404) → `PLAYER_NOT_IN_SUPPORT_TEAM`(400) → `SUPPORT_PLAYER_LIMIT_EXCEEDED`(400). **없는 선수 id 가 섞인 초과 요청은 400이 아니라 404가 먼저 난다** — 예: 4명 응원 중인 계정이 `[999999]`(존재하지 않는 선수 하나)만 보내면 상한 계산까지 가지 않고 `PLAYER_NOT_FOUND`로 끝난다.

⚠ **부분 반영이 없다.** 목록에 하나라도 실패 대상이 있으면 **같은 요청의 다른 선수도 저장되지 않는다**(단일 트랜잭션). 상한 초과도 동일 원칙이다 — **상한까지만 채우고 나머지를 버리는 동작이 아니라 요청 전체를 거부**한다. 어떤 선수가 반영되고 어떤 선수가 잘렸는지 응답으로 구분할 수 없어, 부분 성공은 클라이언트가 복구할 수 없는 상태를 만들기 때문이다.

**예시**
```bash
curl -i -X POST http://localhost:8080/api/support/players \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1,2]}'
```

상한 초과 예시(이미 4명 응원 중인 상태에서 5번째 선수 추가 시도):
```json
{"success":false,"data":null,"message":"응원 선수는 최대 4명까지 선택할 수 있습니다."}
```

---

## PUT /api/support/players/oppose
> 최종 변경: 2026-08-06 — 응답 항목 키 교체(공유 DTO `PlayerResponse` 변경분). 요청 본문·상태코드는 불변

응원 선수 **취소**. → `SupportService.opposePlayers()`. 요구사항: USER-SP-24 ~ 29.

**인증 필수.** **`DELETE`가 아니라 `PUT`인 이유**: 행을 지우는 것이 아니라 `oppose` 컬럼에 취소 시각을 채우는 **상태 전이**이고, 이미 취소된 대상에는 아무 일도 일어나지 않아 두 번 보내도 결과가 같다(멱등). 덕분에 본문에 리스트를 실을 수 있어 추가 API와 대칭이다.

**요청**: 추가 API와 **같은 본문 형태**(`playerIds`).

**2026-08-06 도입된 응원 선수 4명 상한과 무관하다.** 취소는 활성 응원 선수 수를 줄이는 방향이라 상한 검사 자체가 없다 — 몇 명을 취소하든 항상 처리된다.

**응답 200 OK** `ApiResponse<List<PlayerResponse>>` — 취소 후 **남아 있는** 응원 선수 목록. 전원 취소하면 빈 배열이다.
```json
{"success":true,"data":[],"message":null}
```

**멱등성 / 관용**

| 상황 | 결과 |
|---|---|
| 이미 취소된 선수를 다시 취소 | **최초 취소 시각이 보존**된다(덮어쓰지 않음) |
| 실재하지만 **응원한 적 없는** 선수 id | 404가 아니라 **200, 아무 변경 없음** — 목표 상태가 이미 참이다 |
| **존재하지 않는** 선수 id | **404** |

⚠ 위 두 줄이 다르게 취급되는 것은 의도된 구분이다. "응원한 적 없음"은 멱등성, "선수가 없음"은 잘못된 입력이다.

**응원 구단은 이 API로 바뀌지 않는다** — 선수를 전원 취소해도 구단 응원은 그대로 유지된다. 구단은 필수라 취소 API 자체가 없다(변경만 가능).

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `playerIds` 누락/`null` |
| 401 | UNAUTHENTICATED | 위와 동일 |
| 404 | PLAYER_NOT_FOUND | 존재하지 않는 선수 id 포함(다른 취소도 반영되지 않음) |

**예시**
```bash
curl -i -X PUT http://localhost:8080/api/support/players/oppose \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1]}'
```

---

## 확인 필요 / 코드 미확인

- **응원 상태 조회(`GET /api/support`) 엔드포인트가 없다.** 현재 응원 구단·선수를 알려면 위 세 API 중 하나를 호출하거나 [`GET /api/users/me`](account.md#get-apiusersme)(`supportPlayers`)를 호출해 그 응답을 받는 수밖에 없다(모두 반영 후 전체 상태를 반환한다). 최초 진입 화면에서 상태만 읽고 싶은 경우가 코드로 커버되지 않는다.
- **응원 구단 취소 API는 존재하지 않는다** — 구단은 필수라 변경만 가능하다.
- **(과거 기록, 정정됨)** 이전 버전 문서에는 "선수 응원 수 상한이 없다"고 적혀 있었다 — 2026-08-06 결정으로 활성 응원 선수 4명 상한이 도입돼 더 이상 사실이 아니다(`POST /players` 절 참고). `docs/requirements/user/support-selection.md` USER-SP-22는 이 결정으로 폐기 표시됐고 USER-SP-30~36으로 대체됐다.
- 상한(USER-SP-30~36) 도입분은 구현만 끝났고 자동화 테스트가 아직 없다(`SupportServiceTest` 21건에 상한 케이스 0건, `docs/requirements/user/support-selection.md` "테스트 대응" 참고) — 이 문서의 서술은 로컬 실측(4명까지 200 → 5번째 400 메시지 일치 → 초과 거부 후 실제 반영 0건 → 취소 후 재추가 200)으로 확인했다.

## 관련 문서

- [구단(team)](team.md) — `teamId`의 출처.
- [선수(player)](player.md) — `playerIds`의 출처. 응원 구단 소속으로 좁히려면 `?teamId=`를 함께 쓴다.
- [계정(account)](account.md) — `GET /api/users/me`의 `supportPlayers`가 이 도메인의 응원 선수 목록(`PlayerResponse`)을 그대로 재사용한다. **`PlayerResponse`를 바꾸면 `/players`·응원 API 2개·`/me` 총 4곳이 함께 바뀐다.**
- 요구사항: `docs/requirements/user/support-selection.md`(USER-SP-4~36, USER-SP-22는 폐기)
