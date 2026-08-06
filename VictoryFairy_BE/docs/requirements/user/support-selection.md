# 응원 구단·선수 선택 요구사항
> 상태: **승인됨 · 구현 완료** (2026-07-28) · 모듈: user · 최종 수정: 2026-08-06
> 선행 도메인: `UserSupportTeam`·`UserSupportPlayer` + 각 리포지토리(이미 구현·테스트됨). 이 문서는 **그 도메인을 소비하는 쓰기 경로**를 정의한다.
> **2026-08-06 개정**: 사용자가 **응원 선수 개수 상한을 4명으로 확정**했고 구현이 끝났다. 이 결정이 USER-SP-22("상한을 두지 않는다")를 정면으로 뒤집으므로 **22는 폐기 표시로 남기고**(번호 재사용 금지) 상한 계약을 USER-SP-30~37로 신설한다. 이 개정분은 **구현 후 사후 작성**이며, 같은 날 테스트도 뒤따라 작성·실측됐다(USER-SP-36만 미커버 — "테스트 대응" 참조).

## 배경 / 목적
`.claude/modules/domain.md`는 응원 도메인에 대해 이렇게 적고 있다 — **"한 사용자는 구단을 1개만 응원한다"는 스키마 제약이 아니라 서비스 정책이며, 이를 강제할 서비스·컨트롤러 소비처가 아직 없다(= 아무도 강제하지 않는 정책이다).** 이 문서가 정의하는 3개 엔드포인트가 그 강제 주체다.

계약의 핵심 쟁점은 "저장한다"가 아니라 **네 가지 불변식**이다.

1. **구단 1개** — 스키마가 막지 않으므로 쓰기 경로가 지켜야 한다. 팀 변경은 새 행 추가가 아니라 *기존 행 취소 + 대상 행 활성*이다.
2. **선수는 응원 구단 소속** — 사용자 결정. 이 제약이 있기 때문에 **구단을 바꾸면 기존 응원 선수가 불변식을 깨뜨린다**(아래 3 참조).
3. **구단 변경 시 응원 선수 전원 자동 취소** — 2의 불변식을 항상 참으로 유지하기 위한 귀결. 사용자 결정.
4. **취소는 삭제가 아니다** — `oppose` 컬럼에 시각을 채우는 상태 전이다. 행도 최초 취소 시각도 보존된다.

## 범위
- 포함: 응원 구단 선택/변경 1개, 응원 선수 추가 1개, 응원 선수 취소 1개. 필요한 `ErrorCode` 신설. 응답 DTO. **응원 선수 개수 상한 4명의 강제**(2026-08-06 개정, USER-SP-30~37)
- 제외:
  - **응원 구단 취소(해제) 엔드포인트** — 구단은 필수라 "응원하지 않는 상태"가 존재하지 않는다. 변경만 있다. `UserSupportTeam.oppose()`는 구단 *변경* 경로와 계정 탈퇴에서만 호출된다
  - **내 응원 구단·선수 조회 엔드포인트** — 쓰기 응답이 현재 상태를 돌려주므로 이번 화면에는 불필요. 다른 화면에서 필요해지면 별도 요구사항
  - **"구단 미선택 사용자의 다른 API 차단"** — "구단 선택은 필수"를 **이 엔드포인트의 `teamId`가 필수 필드**라는 뜻으로 해석했다. 가입 직후 미선택 상태를 서버가 추적해 다른 경로를 막는 것은 별도 요구사항(→ "미해결" 참조)
  - ~~**응원 선수 수 상한** — 도메인 Javadoc이 "복수 허용, 상한 없음"으로 명시~~ **(2026-08-06 개정으로 범위 안으로 들어왔다 — USER-SP-30~37)**
  - **기존 초과 데이터의 정리(마이그레이션·백필)** — 상한 도입 이전에 5명 이상을 응원 중인 계정은 그대로 둔다. 추가만 막히고 잘라내지 않는다(USER-SP-36)
  - **취소 이력 조회** — 토글 설계라 이력 행이 쌓이지 않는다. 마지막 취소 시각 하나만 남는다
  - **선수 트레이드 시 기존 응원 정리 배치** — py-collector 가 `players.team_id` 를 갱신하면 과거에 저장된 응원이 불변식 2를 사후적으로 위반할 수 있다(→ "미해결" 참조)

## 엔드포인트

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/member/support/team` | 응원 구단 선택·변경 |
| POST | `/api/member/support/players` | 응원 선수 **추가** |
| PUT | `/api/member/support/players/oppose` | 응원 선수 **취소** |

**메서드 선택 근거(재논의 방지)**: 선수 추가는 서버의 기존 상태에 얹는 동작이라 "이 자원을 이 표현으로 대체한다"는 PUT 의미와 어긋나므로 POST 다. 반대로 **취소는 행을 지우는 것이 아니라 `oppose` 컬럼을 채우는 상태 전이**이며 `oppose()` 가 이미 취소된 행에 no-op 이라 두 번 보내도 결과가 같다 — 그래서 DELETE 가 아니라 **멱등한 PUT**이고, 덕분에 본문에 리스트를 실을 수 있어 추가 API 와 대칭이 된다(DELETE 본문은 규격상 권장되지 않고 중간 장비가 버릴 수 있다).

## 요구사항 (EARS)

### 공통

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-1 | 유비쿼터스 | THE 시스템 SHALL 세 엔드포인트 모두에 유효한 access 토큰을 요구한다 | 헤더 없이/만료·위조 토큰으로 각 경로 호출 → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` |
| USER-SP-2 | 유비쿼터스 | THE 시스템 SHALL 요청 주체를 토큰에서만 식별하고 본문·경로로 대상 계정을 받지 않는다 | 어떤 요청 본문에도 `userId`/`uid` 필드가 없음. 타인 계정 응원을 조작할 입력 경로가 존재하지 않음 |
| USER-SP-3 | 유비쿼터스 | THE 시스템 SHALL 한 요청의 모든 변경을 단일 트랜잭션으로 처리한다 | 검증 실패가 하나라도 있으면 그 요청의 어떤 행도 변경되지 않음(부분 반영 없음) |

### 응원 구단 선택 — `POST /api/member/support/team`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-4 | 유비쿼터스 | THE 시스템 SHALL `teamId` 를 필수 입력으로 요구한다 | `{}` 또는 `{"teamId":null}` → 400, `GlobalExceptionHandler`의 `{"success":false,"data":{"teamId":"..."},"message":"입력값이 올바르지 않습니다."}` |
| USER-SP-5 | 예외 | IF `teamId` 가 존재하지 않는 구단이면, THEN THE 시스템 SHALL 404 `TEAM_NOT_FOUND` 를 반환하고 아무 행도 만들지 않는다 | `{"teamId":999999}` → 404. `user_support_team` 행 수 불변 |
| USER-SP-6 | 이벤트 | WHEN 응원 이력이 없는 사용자가 구단을 선택하면, THE 시스템 SHALL `oppose` 가 `null` 인 행을 새로 만든다 | 신규 계정 → 200. `user_support_team` 에 `(계정, 구단)` 행 1개, `oppose is null` |
| USER-SP-7 | 이벤트 | WHEN 이미 응원 중인 구단을 다시 선택하면, THE 시스템 SHALL 상태를 바꾸지 않고 200을 반환한다 | 같은 요청 2회 → 둘 다 200, 행 수 1개 유지, `created_at` 불변 |
| USER-SP-8 | 이벤트 | WHEN 다른 구단을 선택하면, THE 시스템 SHALL 기존 구단 행의 `oppose` 를 요청 시각으로 채우고 새 구단을 응원 상태로 만든다 | A→B 변경 후 `oppose is null` 인 행이 정확히 1개이며 그 행의 구단이 B. A 행은 남아 있고 `oppose` 가 채워짐(행 삭제 아님) |
| USER-SP-9 | 이벤트 | WHEN 과거에 취소했던 구단을 다시 선택하면, THE 시스템 SHALL 새 행을 만들지 않고 기존 행의 `oppose` 를 `null` 로 되돌린다 | A→B→A 후 A 행이 1개(2개 아님)이고 `oppose is null`. UNIQUE 위반 500이 발생하지 않음 |
| USER-SP-10 | 이벤트 | WHEN 구단이 실제로 변경되면, THE 시스템 SHALL 그 사용자가 응원 중인 모든 선수의 `oppose` 를 같은 시각으로 채운다 | A팀 선수 3명 응원 중 → B팀으로 변경 → `user_support_player` 에서 `oppose is null` 인 행 0개. 세 행의 `oppose` 값이 구단 행의 `oppose` 와 동일 |
| USER-SP-11 | 유비쿼터스 | THE 시스템 SHALL 구단이 변경되지 않은 재선택(USER-SP-7)에서는 응원 선수를 취소하지 않는다 | 같은 구단 재선택 후 응원 선수 목록 불변 |
| USER-SP-12 | 유비쿼터스 | THE 시스템 SHALL 처리 후 `oppose is null` 인 구단 행이 정확히 1개임을 유지한다 | 어떤 호출 순서로도 `SELECT COUNT(*) ... WHERE user_account_id=? AND oppose IS NULL` = 1 |
| USER-SP-13 | 이벤트 | WHEN 구단 선택이 성공하면, THE 시스템 SHALL 200과 현재 응원 구단을 반환한다 | `{"success":true,"data":{"id":6,"name":"KIA"},"message":null}` |

### 응원 선수 추가 — `POST /api/member/support/players`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-14 | 유비쿼터스 | THE 시스템 SHALL `playerIds` 배열을 입력으로 받으며 요청된 선수를 **기존 응원에 추가**한다(전체 교체 아님) | `[3]` 응원 중 → `[7]` 요청 → 응원 선수가 `[3,7]`. 3이 취소되지 않음 |
| USER-SP-15 | 예외 | IF 요청자가 응원 구단을 선택하지 않은 상태면, THEN THE 시스템 SHALL 400 `SUPPORT_TEAM_REQUIRED` 를 반환한다 | 구단 미선택 계정 → 400. 소속 검사의 기준 자체가 없으므로 선수 검증보다 먼저 판정 |
| USER-SP-16 | 예외 | IF `playerIds` 에 존재하지 않는 선수가 있으면, THEN THE 시스템 SHALL 404 `PLAYER_NOT_FOUND` 를 반환하고 같은 요청의 다른 선수도 저장하지 않는다 | `[3, 999999]` → 404. 3도 저장되지 않음(USER-SP-3) |
| USER-SP-17 | 예외 | IF `playerIds` 에 응원 구단 소속이 아닌 선수가 있으면, THEN THE 시스템 SHALL 400 `PLAYER_NOT_IN_SUPPORT_TEAM` 을 반환하고 같은 요청의 다른 선수도 저장하지 않는다 | KIA 응원 중 + LG 선수 포함 → 400, 아무것도 저장되지 않음 |
| USER-SP-18 | 이벤트 | WHEN 이미 응원 중인 선수가 포함되면, THE 시스템 SHALL 그 선수에 대해 아무 변경도 하지 않는다(멱등) | 같은 요청 2회 → 행 수 불변, `created_at` 불변 |
| USER-SP-19 | 이벤트 | WHEN 과거에 취소했던 선수가 포함되면, THE 시스템 SHALL 새 행을 만들지 않고 기존 행의 `oppose` 를 `null` 로 되돌린다 | 추가→취소→추가 후 그 선수 행이 1개이고 `oppose is null`. UNIQUE 위반 500 없음 |
| USER-SP-20 | 유비쿼터스 | THE 시스템 SHALL `playerIds` 의 중복을 제거한 뒤 처리한다 | `[3,3,7]` → 400이 아니라 200, 3에 대한 행 1개 |
| USER-SP-21 | 이벤트 | WHEN `playerIds` 가 빈 배열이면, THE 시스템 SHALL 아무 변경 없이 200과 현재 응원 선수 목록을 반환한다 **(가정)** | `{"playerIds":[]}` → 200, 상태 불변 |
| USER-SP-22 | ~~유비쿼터스~~ | **(폐기됨 2026-08-06)** ~~THE 시스템 SHALL 응원 선수 수에 상한을 두지 않는다~~ → **USER-SP-30~37 으로 대체**. 번호는 재사용하지 않는다 | — |
| USER-SP-23 | 이벤트 | WHEN 추가가 성공하면, THE 시스템 SHALL 200과 현재 응원 중인 선수 전체 목록을 반환한다 | `data` 는 이번에 추가한 선수만이 아니라 **현재 응원 중 전체** |

### 응원 선수 개수 상한 — `POST /api/member/support/players` (2026-08-06 개정)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-30 | 유비쿼터스 | THE 시스템 SHALL 한 계정이 동시에 응원할 수 있는 선수를 **최대 4명**으로 제한한다 | 응원 선수 0명 계정에 `[a,b,c,d]` → 200, 응원 선수 4명. 이어 `[e]` → 400 |
| USER-SP-31 | 유비쿼터스 | THE 시스템 SHALL 상한 판정 대상을 `oppose is null` 인 **활성 응원 선수**로 한정한다 | 4명 응원 중 2명을 `PUT /support/players/oppose` 로 취소 → `[e,f]` 추가 요청이 200. 취소된 행이 남아 있어도 개수에 잡히지 않음 |
| USER-SP-32 | 유비쿼터스 | THE 시스템 SHALL 상한을 `현재 활성 응원 선수 id` 와 `요청의 중복 제거된 id` 의 **합집합 크기**로 판정한다 | 3·7 응원 중 → `[3,7,9]` 요청 → 합집합 `{3,7,9}` 크기 3 → 200(재요청분이 개수를 늘리지 않음). `[3,3,9,10,11]` → 합집합 `{3,7,9,10,11}` 크기 5 → 400 |
| USER-SP-33 | 예외 | IF 합집합의 크기가 4를 넘으면, THEN THE 시스템 SHALL 400 `SUPPORT_PLAYER_LIMIT_EXCEEDED`(`"응원 선수는 최대 4명까지 선택할 수 있습니다."`)를 반환한다 | 4명 응원 중 → `[e]` → 400, `{"success":false,"data":null,"message":"응원 선수는 최대 4명까지 선택할 수 있습니다."}` |
| USER-SP-34 | 예외 | IF 상한을 넘는 요청이면, THEN THE 시스템 SHALL 요청의 어떤 선수도 반영하지 않는다(상한까지 채우고 나머지를 버리지 않는다) | 3명 응원 중 → `[e,f]`(합집합 5) → 400 이며 e·f 중 **어느 쪽도** 저장되지 않음. `user_support_player` 의 `oppose is null` 행 수가 3으로 불변(USER-SP-3 의 연장선) |
| USER-SP-35 | 유비쿼터스 | THE 시스템 SHALL 상한 검사를 존재 검증(USER-SP-16)·소속 검증(USER-SP-17) **뒤에** 수행한다 | 4명 응원 중 계정이 `[999999]`(없는 선수) 요청 → 400 이 아니라 **404 `PLAYER_NOT_FOUND`**. 타팀 선수를 섞은 초과 요청은 400 `PLAYER_NOT_IN_SUPPORT_TEAM` |
| USER-SP-36 | 유비쿼터스 | THE 시스템 SHALL 상한 도입 이전에 5명 이상을 응원 중인 계정의 기존 행을 취소·삭제하지 않고, 추가 요청만 거부한다 | 활성 응원 선수 6명인 계정 → `GET /api/member/users/me` 의 `supportPlayers` 가 6건 그대로. `POST /support/players` 는 400. 단 빈 배열 요청(`{"playerIds":[]}`)은 상한 검사 앞의 조기 반환이라 400 이 아니라 200(USER-SP-21). 마이그레이션·백필 절차 없음 |
| USER-SP-37 | 유비쿼터스 | THE 시스템 SHALL 응원 선수 취소(`PUT /support/players/oppose`)에서 상한을 검사하지 않는다 | 활성 응원 선수가 상한을 넘는 계정에서도 취소 요청이 200. 취소는 활성 수가 줄어드는 방향이라 상한을 위반시킬 수 없다 |

**`POST /support/players` 의 검사 순서는 계약이다**(USER-SP-35 가 그중 상한의 자리를 고정한다): `SUPPORT_TEAM_REQUIRED`(USER-SP-15) → 중복 제거·빈 요청 조기 반환(USER-SP-20/21) → `PLAYER_NOT_FOUND`(USER-SP-16) → `PLAYER_NOT_IN_SUPPORT_TEAM`(USER-SP-17) → **상한**(USER-SP-33) → 반영. **없는 선수 id 가 섞인 초과 요청은 400 이 아니라 404 다** — 순서를 바꾸면 같은 요청의 응답 코드가 바뀐다.

**상한 강제 주체는 `POST /api/member/support/players` 하나뿐이다**(USER-SP-37). `PUT /support/players/oppose`(취소)는 활성 수가 줄어드는 방향이라 검사하지 않고, `POST /support/team`(구단 변경 시 전원 취소, USER-SP-10)도 같은 이유로 검사하지 않는다. **`GET /api/member/users/me` 도 강제하지 않는다** — 읽기는 있는 그대로 반환한다(`me-profile.md` USER-ME-36).

### 응원 선수 취소 — `PUT /api/member/support/players/oppose`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-24 | 이벤트 | WHEN 취소 요청이 오면, THE 시스템 SHALL 해당 행을 삭제하지 않고 `oppose` 에 요청 시각을 채운다 | 취소 후에도 `user_support_player` 행이 남아 있고 `oppose` 가 채워짐 |
| USER-SP-25 | 이벤트 | WHEN 이미 취소된 선수를 다시 취소하면, THE 시스템 SHALL 최초 취소 시각을 보존한다(멱등) | 같은 요청 2회 → `oppose` 값이 1회차와 동일 |
| USER-SP-26 | 예외 | IF `playerIds` 에 존재하지 않는 선수가 있으면, THEN THE 시스템 SHALL 404 `PLAYER_NOT_FOUND` 를 반환하고 같은 요청의 다른 취소도 반영하지 않는다 | `[3, 999999]` → 404, 3도 취소되지 않음 |
| USER-SP-27 | 예외 | IF 존재하는 선수지만 응원한 적이 없으면, THEN THE 시스템 SHALL 404가 아니라 아무 변경 없이 성공으로 처리한다 **(가정)** | 응원한 적 없는 선수 id → 200. "이미 응원하지 않는 상태"가 요청의 목표 상태와 같으므로 멱등 |
| USER-SP-28 | 유비쿼터스 | THE 시스템 SHALL 취소 시 응원 구단을 변경하지 않는다 | 선수 전원 취소 후에도 `oppose is null` 인 구단 행 1개 유지 |
| USER-SP-29 | 이벤트 | WHEN 취소가 성공하면, THE 시스템 SHALL 200과 남아 있는 응원 선수 목록을 반환한다 | 전원 취소 시 `{"success":true,"data":[],"message":null}` |

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **`SecurityConfig` 를 수정하지 않는다.** 세 경로 모두 `/auth/**` 밖이고 `permitAll` 목록에 없으므로 `anyRequest().authenticated()` 에 그대로 걸린다. `/api/member/users/me`(탈퇴)와 같은 방식이다 — **`/teams`·`/players` 때와 반대로, 이번엔 아무것도 추가하지 않는 것이 정답이다.** 실수로 `permitAll` 을 추가하면 USER-SP-1 이 깨진다.
- **principal 은 `Long id` 다.** `JwtAuthenticationFilter` 가 요청마다 `findActiveIdByUid` 로 uid→id 를 해석해 넣는다. 토큰의 `sub` 는 `uid`(UUID)지만 컨트롤러가 보는 값은 내부 PK 다 — 리포지토리 시그니처(`findByUserAccount_Id...`)와 그대로 맞는다.
- **탈퇴 계정은 별도 검사가 필요 없다.** `findActiveIdByUid` 가 `exit_at is null` 을 포함하므로 탈퇴 계정은 필터에서 `SecurityContext` 가 비워지고 401 로 떨어진다.
- **취소는 `oppose()` 를 호출해야 하며 `LocalDateTime.now()` 를 엔티티가 직접 읽지 않는다.** 엔티티가 호출자에게서 시각을 받도록 설계돼 있으므로, **한 요청 안의 모든 취소는 같은 시각 값을 공유해야 한다**(USER-SP-10 의 인수 기준이 이를 검사한다).
- **재응원은 새 행이 아니라 기존 행 재활성이다.** `(user_account_id, team_id)`·`(user_account_id, player_id)` UNIQUE 때문에 새 행을 만들면 **500(제약 위반)** 이 난다. 그래서 `findByUserAccount_IdAndTeam_Id`/`findByUserAccount_IdAndPlayer_Id`(oppose 무관 조회)가 존재한다 — 이 조회를 건너뛰고 바로 `save` 하면 USER-SP-9/19 가 깨진다.
- **`findByUserAccount_IdAndOpposeIsNull` 은 정책이 깨진 데이터에서 예외를 던진다.** 조용히 첫 행을 고르지 않는 것이 의도된 설계이므로, 쓰기 경로가 구단 1개 정책(USER-SP-12)을 반드시 지켜야 한다.
- **응답 DTO 는 앱 모듈(`user.support.dto`)에 둔다.** `:domain` 은 `user`·`quiz` 공유 모듈이라 API 계약을 두면 quiz 까지 끌려간다(`player-list.md` 와 같은 결정). 응원 구단·선수 응답은 기존 `TeamResponse`/`PlayerResponse` 를 재사용할 수 있는지 구현 시 판단한다.
- **신설 `ErrorCode`(`:common`) 3종**: `TEAM_NOT_FOUND`(404) · `PLAYER_NOT_FOUND`(404) · `SUPPORT_TEAM_REQUIRED`(400) · `PLAYER_NOT_IN_SUPPORT_TEAM`(400). 기존 404 는 `CHATROOM_NOT_FOUND`/`CHAT_MESSAGE_NOT_FOUND` 뿐이라 구단·선수용이 없다. **2026-08-06 추가: `SUPPORT_PLAYER_LIMIT_EXCEEDED`(400, `"응원 선수는 최대 4명까지 선택할 수 있습니다."`).** 409 가 아닌 이유는 이것이 자원 충돌이 아니라 `SUPPORT_TEAM_REQUIRED`·`PLAYER_NOT_IN_SUPPORT_TEAM` 과 **같은 성격의 정책 위반**이기 때문이다 — 응원 선수 쓰기 경로의 정책 위반은 전부 400 으로 맞춘다.
- **상한 값(4)의 단일 출처는 `SupportService.MAX_SUPPORT_PLAYERS` 다.** 별도 정책 클래스(`PasswordPolicy`·`NicknamePolicy` 같은)는 신설하지 않았다 — 검증 주체가 이 서비스 하나뿐이라 공유할 소비처가 없다. **값을 바꾸면 `ErrorCode.SUPPORT_PLAYER_LIMIT_EXCEEDED` 의 메시지 문자열("최대 4명")도 함께 바꿔야 한다** — 숫자가 두 곳에 있고, 메시지 쪽은 컴파일러가 잡아 주지 않는다.
- **`Player.team` 은 LAZY 다.** USER-SP-17 의 소속 검사에서 선수마다 팀을 꺼내면 **선수 수만큼 조회가 나간다**. FK 값만 필요하므로 `player.getTeam().getId()`(프록시 id 접근은 초기화를 유발하지 않는다) 또는 팀 소속 선수 id 집합을 한 번에 조회하는 방식으로 처리해야 한다.
- **배포 선행조건**: `user_support_team`·`user_support_player` 는 **prod 에 아직 생성되지 않았다**(`.claude/modules/domain.md` 기준, 2026-07-28). `user` 앱이 `ddl-auto=update` 로 재기동해야 반영된다 — 이 엔드포인트는 테이블 생성 전에는 500 이 난다.

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
1. **선수는 추가 방식(전체 교체 아님)이며 별도 취소 API 를 둔다.** 사용자 결정. 전체 교체였다면 취소 API 없이 `oppose()` 전이가 커버됐겠지만, 추가 방식에서는 취소 경로가 없으면 `oppose()` 를 호출할 주체가 사라진다.
2. **취소는 `PUT` + 본문 리스트.** 행 삭제가 아니라 컬럼 상태 전이이고 `oppose()` 가 no-op 멱등이라 PUT 이 성립한다. DELETE 를 쓰면 본문을 싣기 껄끄러워 추가 API 와 비대칭이 된다. 사용자 결정.
3. **선수는 응원 구단 소속으로 제한(USER-SP-17).** 사용자 결정.
4. **구단 변경 시 응원 선수 전원 자동 취소(USER-SP-10).** 3의 불변식을 항상 참으로 유지하기 위한 선택. 사용자 결정. **프론트는 구단 변경 전에 "선수 선택도 초기화됩니다" 를 고지해야 한다** — 서버는 경고 없이 조용히 취소한다.
5. **없는 대상은 404**(USER-SP-5/16/26). 사용자 결정. 조회 API(`GET /players`)가 없는 `teamId` 를 빈 배열로 흡수한 것과 다르다 — 쓰기는 대상이 실재해야 하고, 검증을 생략하면 FK 제약에서 500 이 난다.
6. **구단 취소 API 는 만들지 않는다.** 구단은 필수라 "응원하지 않는 상태"가 없다.
7. **구단 최초 선택·변경·재선택을 엔드포인트 하나(`POST /support/team`)가 모두 처리한다.** 사용자 결정.
   `POST`(최초, 이미 있으면 409) + `PUT`(변경, 없으면 404) 로 쪼개는 안은 폐기했다 — 두 경우의 서버 로직
   차이가 "기존 행이 있느냐"뿐인데, 분리하면 **클라이언트가 요청 전에 자기 상태를 알아야** 하고 앱 재설치·
   다른 기기에서 이미 고른 경우에 409→PUT 재시도 왕복이 실제로 발생한다. 분리의 실익은 "의도를 URL 에
   드러낸다"뿐이었다.
8. **응원 선수 상한은 4명이며 `POST /support/players` 가 강제한다**(2026-08-06, USER-SP-30~37). 사용자 결정.
   초안의 "상한 없음"(USER-SP-22)을 뒤집은 것이라 그 항목은 **폐기 표시로 남기고 번호를 재사용하지 않았다.**
   함께 확정된 것 4가지: ①센다/안 센다의 기준은 **활성(`oppose is null`)** 이라 취소한 자리는 다시 채울 수 있다
   ②판정은 **합집합**이라 이미 응원 중인 선수를 다시 보내도 억울하게 막히지 않는다 ③초과 시 **부분 반영 없이
   요청 전체 거부** — "4명까지만 채우고 나머지 버림"은 어떤 선수가 반영됐는지 응답에서 구분할 수 없어
   클라이언트가 복구할 수 없다(USER-SP-3 의 연장선) ④**기존 초과 데이터는 잘라내지 않는다** — 상한은
   앞으로의 추가에만 적용되고 마이그레이션·백필은 없다.
   - **`/me` 쪽에 상한을 두는 안(B)은 폐기했다.** `me-profile.md` 의 미해결 질문 1번이 그 선택지였고
     사용자가 C(응원 API 강제)를 골랐다 — `/me` 목록과 응원 API 목록이 갈라지지 않는 쪽이다.

## 테스트 대응 (요구사항 ID ↔ 테스트)
| ID | 테스트 |
|---|---|
| USER-SP-1 | `SupportControllerTest` 6건(무인증 3경로 · 위조 토큰 · 탈퇴 계정 토큰 · refresh 토큰) |
| USER-SP-2 | `SupportControllerTest.selectTeam_returns200AndDelegatesWithResolvedAccountId` · `…_ignoresAccountIdInBody` |
| USER-SP-3 | `SupportServiceTest` 의 `…SavesNothing`/`…ChangesNothing` 3건(SP-16/17/26 과 겸함) |
| USER-SP-4 | `SupportControllerTest.selectTeam_missingTeamId_returns400` |
| USER-SP-5 | `SupportServiceTest.selectTeam_unknownTeam_throwsTeamNotFound` · `SupportControllerTest.selectTeam_teamNotFound_returns404` |
| USER-SP-6 | `SupportServiceTest.selectTeam_noHistory_createsActiveRow` |
| USER-SP-7 | `SupportServiceTest.selectTeam_sameTeam_isNoOp` |
| USER-SP-8 | `SupportServiceTest.selectTeam_differentTeam_opposesPreviousAndActivatesTarget` |
| USER-SP-9 | `SupportServiceTest.selectTeam_previouslyOpposedTeam_reactivatesExistingRow` |
| USER-SP-10 | `SupportServiceTest.selectTeam_changed_opposesAllSupportedPlayersWithSameTimestamp` |
| USER-SP-11 | `SupportServiceTest.selectTeam_sameTeam_doesNotOpposeSupportedPlayers` |
| USER-SP-12 | `SupportServiceTest.selectTeam_differentTeam_opposesPreviousAndActivatesTarget` (+ 아래 런타임 검증) |
| USER-SP-13 | `SupportControllerTest.selectTeam_returns200AndDelegatesWithResolvedAccountId` |
| USER-SP-14 | `SupportServiceTest.addPlayers_addsToExistingSupportAndReturnsAll` |
| USER-SP-15 | `SupportServiceTest.addPlayers_withoutSupportTeam_throwsSupportTeamRequired` · `SupportControllerTest.addPlayers_withoutSupportTeam_returns400` |
| USER-SP-16 | `SupportServiceTest.addPlayers_unknownPlayer_throwsPlayerNotFoundAndSavesNothing` · `SupportControllerTest.addPlayers_unknownPlayer_returns404` |
| USER-SP-17 | `SupportServiceTest.addPlayers_playerOfAnotherTeam_throwsAndSavesNothing` · `SupportControllerTest.addPlayers_playerOfAnotherTeam_returns400` |
| USER-SP-18 | `SupportServiceTest.addPlayers_alreadySupported_isNoOp` |
| USER-SP-19 | `SupportServiceTest.addPlayers_previouslyOpposed_reactivatesExistingRow` |
| USER-SP-20 | `SupportServiceTest.addPlayers_duplicateIds_areDeduplicated` |
| USER-SP-21 | `SupportServiceTest.addPlayers_emptyList_isNoOp` · `SupportControllerTest.addPlayers_emptyArray_returns200` · `…_nullPlayerIds_returns400` |
| USER-SP-22 | (폐기됨 2026-08-06 — USER-SP-30~37 참조) |
| USER-SP-23 | `SupportServiceTest.addPlayers_addsToExistingSupportAndReturnsAll` · `SupportControllerTest.addPlayers_returns200WithAllCurrentlySupported` |
| USER-SP-24 | `SupportServiceTest.opposePlayers_fillsOpposeWithoutDeletingRow` |
| USER-SP-25 | `SupportServiceTest.opposePlayers_alreadyOpposed_preservesFirstTimestamp` |
| USER-SP-26 | `SupportServiceTest.opposePlayers_unknownPlayer_throwsAndChangesNothing` |
| USER-SP-27 | `SupportServiceTest.opposePlayers_neverSupportedPlayer_succeedsWithoutChange` |
| USER-SP-28 | `SupportServiceTest.opposePlayers_doesNotTouchSupportTeam` |
| USER-SP-29 | `SupportServiceTest.currentSupportedPlayers_returnsNameAscWithSingleBatchQuery` · `SupportControllerTest` 2건 |
| USER-SP-30 | `SupportServiceTest.addPlayers_zeroActiveRequestExactlyFour_succeeds`(경계 4명 통과) · `…_fourActiveAddOne_throwsLimitExceeded`(5명째 거부) |
| USER-SP-31 | `SupportServiceTest.addPlayers_opposedPlayersDoNotCountTowardLimit_succeeds` |
| USER-SP-32 | `SupportServiceTest.addPlayers_threeActivePlusOverlappingAndNew_countsUnionNotSum_succeeds` · `…_fourActiveRequestSameFour_succeeds` |
| USER-SP-33 | `SupportServiceTest.addPlayers_fourActiveAddOne_throwsLimitExceeded` · `SupportControllerTest.addPlayers_limitExceeded_returns400`(400 + 상한 안내 메시지) |
| USER-SP-34 | `SupportServiceTest.addPlayers_limitExceeded_touchesNoRowsAtAll`(`save` 도 재활성 조회도 0건) |
| USER-SP-35 | `SupportServiceTest.addPlayers_excessiveRequestWithUnknownPlayer_throwsPlayerNotFoundBeforeLimitCheck` · `…_excessiveRequestWithWrongTeamPlayer_throwsNotInSupportTeamBeforeLimitCheck` |
| USER-SP-36 | (전용 테스트 없음 — 아래 "미커버 영역") |
| USER-SP-37 | `SupportServiceTest.opposePlayers_isUnaffectedByPlayerLimit` ⚠ 이 테스트의 `@DisplayName` 이 ID 가 아니라 **"요구사항 문서 91행"** 이라는 줄 번호를 참조한다(작성 시점에 이 ID 가 없었다) — 문서가 한 줄만 밀려도 가리키는 곳이 달라지므로 `[USER-SP-37]` 로 교체할 것 |

`SupportServiceTest` 30건 · `SupportControllerTest` 20건, `:user:test` 336건 전부 통과(2026-08-06 실측).

**미커버 영역(정직하게 기록)**
- **USER-SP-36(상한 도입 이전 초과 데이터)**: 전용 테스트가 없다. 구조상 충족한다 — 상한을 보는 코드가
  `addPlayers` 하나뿐이라 다른 경로가 기존 행을 건드릴 방법이 없다 — 그러나 **"활성 6명인 계정"이라는
  경계 케이스를 세워 확인한 적은 없다.** 확인하려면 목이 아니라 실제 데이터가 필요하고(응원 6행을 만든
  뒤 `GET /me` 6건 + `POST /support/players` 400), 저장소에 H2/Testcontainers 가 없어 유닛 테스트로는
  절반만 세울 수 있다. **정책 이전 데이터가 실제로 있는지도 미확인이다.**
- **대량 요청 성능**: 상한이 생기면서 한 요청에 실제로 반영되는 선수 수는 4로 닫혔지만, **상한 검사보다
  앞서는 존재·소속 검증은 여전히 요청 배열 전체를 훑는다**(수천 개 id 를 보내면 `findAllById` 가 그만큼
  커진다 — 상한에 걸려 결국 400 이 될 요청이라도 그렇다). 요청 배열 길이 자체에 대한 제한은 두지 않았다.
- **상한 초과 거부의 "부분 반영 없음"(SP-34)은 롤백을 검증하지 않는다**: 목 기반이라 확인하는 것은
  **"쓰기 호출 자체가 없었다"**(`save`·재활성 조회 0건)이다. 실제 트랜잭션 롤백은 아래 항목과 같은 이유로
  미검증이며, 이 경로는 애초에 쓰기 전에 던지므로 롤백에 기대지 않는다.
- **UNIQUE 제약·FK CASCADE·트랜잭션 롤백**: 단위 테스트는 리포지토리를 목으로 대체하므로 제약 자체를
  검증하지 않는다. 저장소에 H2/Testcontainers 가 없어 `@DataJpaTest` 라운드트립이 불가한 상태다
  (`.claude/modules/domain.md` 의 동일 기록 참조). **다만 아래 런타임 검증이 실제 MySQL 로 이 부분을
  메웠다** — SP-9/19 의 재활성이 UNIQUE 위반 없이 동작함을 실 DB 에서 확인했다.
- **동시 요청 레이스**: 아래 "미해결" 참조. 테스트로 재현하지 않았다.

## 런타임 검증 기록 (2026-07-28, 로컬 실측)
`:user:bootRun` + 로컬 MySQL/Redis 로 실제 요청을 보내 확인했다. 이메일 인증 → 가입 → 로그인으로 실토큰을
발급받고, `players` 가 비어 있어 검증용 선수 3건(KIA 2 · LG 1)을 로컬 DB 에 넣었다.

- **테이블 생성**: `ddl-auto=update` 기동으로 `user_support_team`·`user_support_player` 가 생성됨(그 전에는
  prod·로컬 모두 미생성 상태였다).
- **HTTP 응답 실측**: SP-1(3경로 401) · SP-4(400+필드메시지) · SP-5(404) · SP-15(400) · SP-6/13(200) ·
  SP-7(재선택 200) · SP-16(404) · SP-17(400) · SP-14(추가 누적 확인) · SP-18/20/21(멱등·중복·빈배열) ·
  SP-24/25/26/27 · SP-8/9/10 전부 문서의 인수 기준과 일치.
- **불변식 실측(DB 직접 조회)**:
  - 구단 변경 후 `user_support_team` 행 2개 중 `oppose is null` 인 행이 **정확히 1개**(SP-12).
  - 변경 전 구단 행이 삭제되지 않고 `oppose` 만 채워짐(SP-8).
  - 응원 선수 2명 전원 `oppose` 채워짐, `oppose is null` 인 선수 행 **0개**(SP-10).
  - **구단 취소 시각과 두 선수 취소 시각의 distinct 값이 1개**(`2026-07-28 18:39:13.898561`) — "한 요청에서
    `now()` 를 한 번만 읽는다"는 계약이 마이크로초 단위로 성립함을 확인.
  - 추가→취소→추가 후 선수 행이 늘지 않고 재활성(SP-19), KIA→LG→KIA 후 구단 행이 2개로 유지되며 재활성
    (SP-9) — **실제 UNIQUE 제약 아래에서 500 이 나지 않음을 확인**.

## 미해결 / 후속 (이번 범위 아님 — 기록만)
- **`(가정)` 2건**: USER-SP-21(빈 배열 추가 요청을 400 이 아니라 no-op 200 으로) · USER-SP-27(응원한 적 없는 선수 취소를 404 가 아니라 no-op 200 으로). 둘 다 멱등성을 우선한 해석이다. 400/404 를 원하시면 이 두 줄만 바꾸면 된다.
- **"구단 미선택 사용자 차단"의 강제 지점.** 지금 계약은 이 엔드포인트의 필드 필수성까지만 보장한다. 가입 직후 구단을 고르지 않은 계정이 다른 API 를 그대로 쓸 수 있다 — 회원가입 플로우에서 강제할지, 서버가 미선택 상태를 검사할지는 별도 요구사항이다.
- **선수 트레이드로 인한 사후 불변식 위반.** py-collector 가 `players.team_id` 를 갱신하면, 저장 시점에 유효했던 응원이 나중에 "타팀 선수 응원"이 된다. 쓰기 시점 검증만으로는 막을 수 없고 정리 배치나 조회 시점 필터가 필요하다.
- **동시 요청 레이스.** 같은 계정이 구단 변경을 동시에 2건 보내면 둘 다 기존 행을 읽고 각자 새 행을 활성화해 `oppose is null` 인 구단 행이 2개가 될 수 있다(USER-SP-12 위반). `findByUserAccount_IdAndOpposeIsNull` 이 그때 예외를 던져 **드러나기는 하지만 이미 깨진 뒤다.** 탈퇴 동시 호출과 같은 종류의 알려진 한계로 두고, 비관적 락은 도입하지 않는다.
