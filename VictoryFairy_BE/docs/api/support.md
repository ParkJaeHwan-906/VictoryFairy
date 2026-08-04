# 응원(support) API 명세

> **도메인** `support` — 사용자의 응원 구단·응원 선수 선택 상태.
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/support` · **엔드포인트** 3개
> **컨트롤러** `user/src/main/java/com/skhynix/user/support/controller/SupportController.java` (`@RequestMapping("/support")`)
> **최종 갱신** 2026-08-04 — 모듈별(`user.md`) 문서를 도메인별로 분리. 계약 변경 없음.
> **요구사항** `docs/requirements/user/support-selection.md` (USER-SP-4 ~ 29)
> 공통 규약(응답 래퍼·JWT·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| POST | [/api/member/support/team](#post-apimembersupportteam) | 200 | 응원 구단 선택·변경(최초/변경/재선택 공용) |
| POST | [/api/member/support/players](#post-apimembersupportplayers) | 200 | 응원 선수 **추가**(전체 교체 아님) |
| PUT | [/api/member/support/players/oppose](#put-apimembersupportplayersoppose) | 200 | 응원 선수 **취소** |

## 이 도메인의 특이사항

**3개 전부 인증 필수.** `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다 — `SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상이다. 대상 계정은 요청 본문이 아니라 access 토큰에서만 정해진다.

**구단이 선수보다 앞선다.** 응원 구단을 고르기 전에는 선수를 고를 수 없고(`SUPPORT_TEAM_REQUIRED`), 선수는 응원 구단 소속이어야 한다(`PLAYER_NOT_IN_SUPPORT_TEAM`).

⚠ **구단을 바꾸면 응원 선수가 전원 자동 취소된다 — 경고 없이.** 프론트가 구단 변경 전에 반드시 고지해야 하는, 이 도메인에서 가장 중요한 계약이다.

**삭제가 아니라 상태 전이다.** 취소는 행을 지우지 않고 `oppose` 컬럼에 시각을 기록한다. 그래서 취소 API가 `DELETE`가 아니라 `PUT`이고, 재선택 시 새 행 대신 기존 행이 재활성된다. 두 번 보내도 결과가 같다(멱등).

**세 응답 모두 "현재 상태 전체"를 돌려준다** — 방금 변경한 항목만이 아니라 반영 후의 응원 구단/응원 선수 전체라, 프론트가 재조회할 필요가 없다.

---

## POST /api/member/support/team
> 최종 변경: 2026-07-28 (추정) — 도메인 분리 이전 이력이 없어 `SupportController` 마지막 커밋 기준

응원 구단 선택·변경. `SupportController` → `SupportService.selectTeam()`. 요구사항: USER-SP-4 ~ 13.

**인증 필수.** 이 경로는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다([회원탈퇴](account.md)와 같은 방식). [`GET /api/member/teams`](team.md)·[`/players`](player.md)와 달리 **`SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상**이다.

**최초 선택·변경·재선택을 이 엔드포인트 하나가 모두 처리한다.** 클라이언트가 요청 전에 자기 상태를 알 필요가 없다.

**요청** `SupportTeamRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | **예** | 응원할 구단 PK([`GET /api/member/teams`](team.md)의 `data[].id`) |

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
curl -i -X POST http://localhost:8080/api/member/support/team \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"teamId":6}'
```

---

## POST /api/member/support/players
> 최종 변경: 2026-07-28 (추정) — 도메인 분리 이전 이력이 없어 `SupportController` 마지막 커밋 기준

응원 선수 **추가**. → `SupportService.addPlayers()`. 요구사항: USER-SP-14 ~ 23.

**인증 필수.** **전체 교체가 아니라 추가다** — 요청에 없는 선수는 취소되지 않는다. 취소는 아래 [`PUT /api/member/support/players/oppose`](#put-apimembersupportplayersoppose)가 담당한다. `playerIds`의 출처는 [`GET /api/member/players`](player.md)다.

**요청** `SupportPlayersRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| playerIds | Long[] | **예**(빈 배열 허용) | 추가할 선수 PK 목록 |

- **빈 배열 `[]` 은 200**(아무 변경 없음). 선수 응원은 필수가 아니다.
- **필드 자체를 빼면 400** — `null`과 `[]`를 구분한다.
- **중복 id 는 400이 아니라 제거 후 처리**(`[3,3,7]` → 정상).
- **선수 수 상한 없음.**

**응답 200 OK** `ApiResponse<List<PlayerResponse>>` — **이번에 추가한 선수만이 아니라 현재 응원 중인 선수 전체**를 `name` 오름차순으로 반환한다(프론트가 재조회할 필요 없음).
```json
{"success":true,"data":[{"id":1,"name":"강백호"},{"id":2,"name":"김도영"}],"message":null}
```

**멱등성**: 이미 응원 중인 선수를 다시 보내면 아무 변경이 없다. 과거에 취소했던 선수를 다시 보내면 새 행이 아니라 기존 행이 재활성된다.

**실패**

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | (필드 오류) | `playerIds` 누락/`null` |
| 400 | SUPPORT_TEAM_REQUIRED | `"응원하는 구단을 먼저 선택해 주세요."` — **응원 구단을 고르기 전에는 선수를 고를 수 없다**(소속 검사의 기준이 없으므로 선수 검증보다 먼저 판정) |
| 400 | PLAYER_NOT_IN_SUPPORT_TEAM | `"응원하는 구단 소속 선수만 선택할 수 있습니다."` |
| 401 | UNAUTHENTICATED | 위와 동일 |
| 404 | PLAYER_NOT_FOUND | `"존재하지 않는 선수입니다."` |

⚠ **부분 반영이 없다.** 목록에 하나라도 실패 대상이 있으면 **같은 요청의 다른 선수도 저장되지 않는다**(단일 트랜잭션).

**예시**
```bash
curl -i -X POST http://localhost:8080/api/member/support/players \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1,2]}'
```

---

## PUT /api/member/support/players/oppose
> 최종 변경: 2026-07-28 (추정) — 도메인 분리 이전 이력이 없어 `SupportController` 마지막 커밋 기준

응원 선수 **취소**. → `SupportService.opposePlayers()`. 요구사항: USER-SP-24 ~ 29.

**인증 필수.** **`DELETE`가 아니라 `PUT`인 이유**: 행을 지우는 것이 아니라 `oppose` 컬럼에 취소 시각을 채우는 **상태 전이**이고, 이미 취소된 대상에는 아무 일도 일어나지 않아 두 번 보내도 결과가 같다(멱등). 덕분에 본문에 리스트를 실을 수 있어 추가 API와 대칭이다.

**요청**: 추가 API와 **같은 본문 형태**(`playerIds`).

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
curl -i -X PUT http://localhost:8080/api/member/support/players/oppose \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"playerIds":[1]}'
```

---

## 확인 필요 / 코드 미확인

- **응원 상태 조회(`GET /api/member/support`) 엔드포인트가 없다.** 현재 응원 구단·선수를 알려면 위 세 API 중 하나를 호출해 그 응답을 받는 수밖에 없다(모두 반영 후 전체 상태를 반환한다). 최초 진입 화면에서 상태만 읽고 싶은 경우가 코드로 커버되지 않는다.
- **응원 구단 취소 API는 존재하지 않는다** — 구단은 필수라 변경만 가능하다.
- 선수 응원 수 상한이 없다. 정책상 상한이 필요한지는 요구사항에도 결론이 없다.

## 관련 문서

- [구단(team)](team.md) — `teamId`의 출처.
- [선수(player)](player.md) — `playerIds`의 출처. 응원 구단 소속으로 좁히려면 `?teamId=`를 함께 쓴다.
- 요구사항: `docs/requirements/user/support-selection.md`
