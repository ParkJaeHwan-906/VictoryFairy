# 응원 구단·선수 선택 요구사항
> 상태: **승인됨 · 구현 완료** (본문 2026-07-28 / 상한 2026-08-06 / 계정 락 2026-08-06) · 모듈: user · 최종 수정: 2026-08-06
> **미해결 질문 0건** — 마지막 1건(락 획득 실패의 응답 코드, USER-SP-46)이 2026-08-06 A안(현행 500 유지)으로 확정됐다(결정 기록 10). 모든 계약이 구현·테스트·실측까지 끝났다.
> 선행 도메인: `UserSupportTeam`·`UserSupportPlayer` + 각 리포지토리(이미 구현·테스트됨). 이 문서는 **그 도메인을 소비하는 쓰기 경로**를 정의한다.
> **2026-08-06 개정**: 사용자가 **응원 선수 개수 상한을 4명으로 확정**했고 구현이 끝났다. 이 결정이 USER-SP-22("상한을 두지 않는다")를 정면으로 뒤집으므로 **22는 폐기 표시로 남기고**(번호 재사용 금지) 상한 계약을 USER-SP-30~37로 신설한다. 이 개정분은 **구현 후 사후 작성**이며, 같은 날 테스트도 뒤따라 작성·실측됐다(USER-SP-36만 미커버 — "테스트 대응" 참조).
> **2026-08-06 3차 개정**: 사용자가 **비관적 락 미도입 판단을 뒤집어** 계정 행 배타 락을 도입했고 구현이 끝났다. 이 문서의 "미해결 / 후속"이 적고 있던 *"비관적 락은 도입하지 않는다"* 는 **더 이상 유효하지 않다**(정정 경위는 "결정 기록 9"). 락 계약을 **USER-SP-38~46**으로 신설한다. 이 개정분도 **구현 후 사후 작성**이며 테스트(목 회귀 12+1건)와 **로컬 동시 요청 실측**이 모두 끝났다(USER-SP-46만 테스트 미커버 — "테스트 대응" 참조). **락 대상 선택의 근거 1건은 같은 날 정정됐고**(결론 불변, 번호 불변 — USER-SP-38 표 아래 "근거 정정"), **락 실패 응답 코드도 같은 날 A안(500 유지)으로 확정돼 미해결 질문이 0건이 됐다**(결정 기록 10).

## 배경 / 목적
`.claude/modules/domain.md`는 응원 도메인에 대해 이렇게 적고 있다 — **"한 사용자는 구단을 1개만 응원한다"는 스키마 제약이 아니라 서비스 정책이며, 이를 강제할 서비스·컨트롤러 소비처가 아직 없다(= 아무도 강제하지 않는 정책이다).** 이 문서가 정의하는 3개 엔드포인트가 그 강제 주체다.

계약의 핵심 쟁점은 "저장한다"가 아니라 **네 가지 불변식**이다.

1. **구단 1개** — 스키마가 막지 않으므로 쓰기 경로가 지켜야 한다. 팀 변경은 새 행 추가가 아니라 *기존 행 취소 + 대상 행 활성*이다.
2. **선수는 응원 구단 소속** — 사용자 결정. 이 제약이 있기 때문에 **구단을 바꾸면 기존 응원 선수가 불변식을 깨뜨린다**(아래 3 참조).
3. **구단 변경 시 응원 선수 전원 자동 취소** — 2의 불변식을 항상 참으로 유지하기 위한 귀결. 사용자 결정.
4. **취소는 삭제가 아니다** — `oppose` 컬럼에 시각을 채우는 상태 전이다. 행도 최초 취소 시각도 보존된다.

## 범위
- 포함: 응원 구단 선택/변경 1개, 응원 선수 추가 1개, 응원 선수 취소 1개. 필요한 `ErrorCode` 신설. 응답 DTO. **응원 선수 개수 상한 4명의 강제**(2026-08-06 개정, USER-SP-30~37). **같은 계정의 응원 변경을 계정 행 배타 락으로 직렬화**(2026-08-06 3차 개정, USER-SP-38~46)
- 제외:
  - **응원 구단 취소(해제) 엔드포인트** — 구단은 필수라 "응원하지 않는 상태"가 존재하지 않는다. 변경만 있다. `UserSupportTeam.oppose()`는 구단 *변경* 경로와 계정 탈퇴에서만 호출된다
  - **내 응원 구단·선수 조회 엔드포인트** — 쓰기 응답이 현재 상태를 돌려주므로 이번 화면에는 불필요. 다른 화면에서 필요해지면 별도 요구사항
  - **"구단 미선택 사용자의 다른 API 차단"** — "구단 선택은 필수"를 **이 엔드포인트의 `teamId`가 필수 필드**라는 뜻으로 해석했다. 가입 직후 미선택 상태를 서버가 추적해 다른 경로를 막는 것은 별도 요구사항(→ "미해결" 참조)
  - ~~**응원 선수 수 상한** — 도메인 Javadoc이 "복수 허용, 상한 없음"으로 명시~~ **(2026-08-06 개정으로 범위 안으로 들어왔다 — USER-SP-30~37)**
  - **기존 초과 데이터의 정리(마이그레이션·백필)** — 상한 도입 이전에 5명 이상을 응원 중인 계정은 그대로 둔다. 추가만 막히고 잘라내지 않는다(USER-SP-36)
  - **취소 이력 조회** — 토글 설계라 이력 행이 쌓이지 않는다. 마지막 취소 시각 하나만 남는다
  - **락 획득 실패(대기 초과·데드락)의 전용 상태 코드 매핑** — `ApiResponse` 래퍼 없는 **500** 을 그대로 둔다(USER-SP-46). 409/503 매핑은 **하지 않기로 확정**했다(결정 기록 10 — 되돌릴 조건도 거기 적혀 있다)
  - **읽기 경로의 동시성 보장** — `GET /api/member/users/me` 등 조회는 락을 잡지 않으므로(USER-SP-44) 쓰기 커밋 직전 상태를 읽을 수 있다. 조회는 "그 시점에 커밋된 값"까지만 보장한다
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
| USER-SP-30 | 유비쿼터스 | THE 시스템 SHALL 한 계정이 동시에 응원할 수 있는 선수를 **최대 4명**으로 제한한다 (동시 요청에서의 성립 조건은 USER-SP-43) | 응원 선수 0명 계정에 `[a,b,c,d]` → 200, 응원 선수 4명. 이어 `[e]` → 400 |
| USER-SP-31 | 유비쿼터스 | THE 시스템 SHALL 상한 판정 대상을 `oppose is null` 인 **활성 응원 선수**로 한정한다 | 4명 응원 중 2명을 `PUT /support/players/oppose` 로 취소 → `[e,f]` 추가 요청이 200. 취소된 행이 남아 있어도 개수에 잡히지 않음 |
| USER-SP-32 | 유비쿼터스 | THE 시스템 SHALL 상한을 `현재 활성 응원 선수 id` 와 `요청의 중복 제거된 id` 의 **합집합 크기**로 판정한다 (판정 입력이 되는 활성 목록 조회는 락 확보 뒤다 — USER-SP-43) | 3·7 응원 중 → `[3,7,9]` 요청 → 합집합 `{3,7,9}` 크기 3 → 200(재요청분이 개수를 늘리지 않음). `[3,3,9,10,11]` → 합집합 `{3,7,9,10,11}` 크기 5 → 400 |
| USER-SP-33 | 예외 | IF 합집합의 크기가 4를 넘으면, THEN THE 시스템 SHALL 400 `SUPPORT_PLAYER_LIMIT_EXCEEDED`(`"응원 선수는 최대 4명까지 선택할 수 있습니다."`)를 반환한다 | 4명 응원 중 → `[e]` → 400, `{"success":false,"data":null,"message":"응원 선수는 최대 4명까지 선택할 수 있습니다."}` |
| USER-SP-34 | 예외 | IF 상한을 넘는 요청이면, THEN THE 시스템 SHALL 요청의 어떤 선수도 반영하지 않는다(상한까지 채우고 나머지를 버리지 않는다) | 3명 응원 중 → `[e,f]`(합집합 5) → 400 이며 e·f 중 **어느 쪽도** 저장되지 않음. `user_support_player` 의 `oppose is null` 행 수가 3으로 불변(USER-SP-3 의 연장선) |
| USER-SP-35 | 유비쿼터스 | THE 시스템 SHALL 상한 검사를 존재 검증(USER-SP-16)·소속 검증(USER-SP-17) **뒤에** 수행한다 | 4명 응원 중 계정이 `[999999]`(없는 선수) 요청 → 400 이 아니라 **404 `PLAYER_NOT_FOUND`**. 타팀 선수를 섞은 초과 요청은 400 `PLAYER_NOT_IN_SUPPORT_TEAM` |
| USER-SP-36 | 유비쿼터스 | THE 시스템 SHALL 상한 도입 이전에 5명 이상을 응원 중인 계정의 기존 행을 취소·삭제하지 않고, 추가 요청만 거부한다 | 활성 응원 선수 6명인 계정 → `GET /api/member/users/me` 의 `supportPlayers` 가 6건 그대로. `POST /support/players` 는 400. 단 빈 배열 요청(`{"playerIds":[]}`)은 상한 검사 앞의 조기 반환이라 400 이 아니라 200(USER-SP-21). 마이그레이션·백필 절차 없음 |
| USER-SP-37 | 유비쿼터스 | THE 시스템 SHALL 응원 선수 취소(`PUT /support/players/oppose`)에서 상한을 검사하지 않는다 (**상한 검사만 면제이고 계정 락은 면제가 아니다** — USER-SP-42) | 활성 응원 선수가 상한을 넘는 계정에서도 취소 요청이 200. 취소는 활성 수가 줄어드는 방향이라 상한을 위반시킬 수 없다 |

**`POST /support/players` 의 검사 순서는 계약이다**(USER-SP-35 가 그중 상한의 자리를 고정한다): `SUPPORT_TEAM_REQUIRED`(USER-SP-15) → 중복 제거·빈 요청 조기 반환(USER-SP-20/21) → `PLAYER_NOT_FOUND`(USER-SP-16) → `PLAYER_NOT_IN_SUPPORT_TEAM`(USER-SP-17) → **상한**(USER-SP-33) → 반영. **없는 선수 id 가 섞인 초과 요청은 400 이 아니라 404 다** — 순서를 바꾸면 같은 요청의 응답 코드가 바뀐다.

**상한 강제 주체는 `POST /api/member/support/players` 하나뿐이다**(USER-SP-37). `PUT /support/players/oppose`(취소)는 활성 수가 줄어드는 방향이라 검사하지 않고, `POST /support/team`(구단 변경 시 전원 취소, USER-SP-10)도 같은 이유로 검사하지 않는다. **`GET /api/member/users/me` 도 강제하지 않는다** — 읽기는 있는 그대로 반환한다(`me-profile.md` USER-ME-36).

⚠ **상한의 실제 강제 지점은 판정 하나가 아니라 "판정 + 계정 락"이다**(2026-08-06 3차 개정, USER-SP-43). USER-SP-30~34 는 "읽고 → 세고 → 저장"의 순서만 정하는데, 그 사이가 열려 있으면 같은 계정의 두 요청이 각각 합집합 4로 통과해 최종 활성 수가 4를 넘는다 — UNIQUE `(user_account_id, player_id)` 는 **같은 선수의 중복만** 막고 개수는 막지 못한다. 그래서 USER-SP-30~34 는 USER-SP-38·40·43 과 **함께여야 성립하며**, 락을 떼면 판정 코드를 그대로 두어도 상한이 뚫린다.

### 동시 요청 직렬화 — 계정 행 비관적 락 (2026-08-06 3차 개정)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-SP-38 | 유비쿼터스 | THE 시스템 SHALL 응원 상태를 변경하는 요청(`POST /support/team` · `POST /support/players` · `PUT /support/players/oppose`)에 대해 요청 계정의 `user_account` 행을 **배타 락으로 먼저 확보**한 뒤 처리한다 | 세 경로 각각 1회 호출 시 해당 계정 행에 대한 `select ... for update` 가 요청당 **정확히 1회** 발생 |
| USER-SP-39 | 상태 | WHILE 한 요청이 그 계정의 배타 락을 보유하는 동안, THE 시스템 SHALL 같은 계정의 다른 응원 변경 요청을 그 트랜잭션이 끝날 때까지 대기시킨다 | 같은 계정으로 응원 변경 2건을 동시에 보내면 두 트랜잭션이 겹치지 않는다. 활성 2명 계정에 `[a,b]`·`[c,d]` 동시 요청 → 한쪽만 200, 다른 쪽은 400 `SUPPORT_PLAYER_LIMIT_EXCEEDED`(최종 활성 수는 4 이하) |
| USER-SP-40 | 유비쿼터스 | THE 시스템 SHALL 락 확보를 그 요청의 **다른 어떤 조회·저장보다 먼저** 수행한다 | 세 쓰기 경로 각각에서 계정 락 조회가 첫 DB 접근이다. `selectTeam` 은 구단 조회보다, `addPlayers` 는 응원 구단 조회보다, `opposePlayers` 는 선수 존재 검증보다 앞 |
| USER-SP-41 | 이벤트 | WHEN `playerIds` 가 빈 배열이면, THE 시스템 SHALL 조기 반환(USER-SP-21) **전에** 락을 확보한다 | `{"playerIds":[]}` 로 추가·취소를 호출해도 계정 락 조회가 1회 발생. "쓰기 경로는 항상 락부터"에 예외를 두지 않는다 |
| USER-SP-42 | 유비쿼터스 | THE 시스템 SHALL 응원 선수 취소(`PUT /support/players/oppose`)에서도 같은 계정 락을 같은 순서로 확보한다 | 취소 요청 1건에 계정 락 조회 1회. 상한과 무관한 경로지만 생략하지 않는다 |
| USER-SP-43 | 유비쿼터스 | THE 시스템 SHALL 응원 선수 상한(USER-SP-30~34)의 **판정과 반영을 같은 락 구간 안에서** 수행한다 | 활성 응원 선수 조회(상한 판정 입력)·`save`·재활성이 모두 락 확보 이후에 일어난다. 락 확보 전에 수행되는 응원 관련 조회가 없음 |
| USER-SP-44 | 유비쿼터스 | THE 시스템 SHALL 응원 상태 **조회 경로에는 락을 걸지 않는다** | `GET /api/member/users/me` 처리 중 `user_account` 에 대한 `select ... for update` 가 0회. 프로필 조회 여러 건이 서로를 대기시키지 않음 |
| USER-SP-45 | 예외 | IF 락 대상 계정 행이 존재하지 않으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED` 를 반환하고 다른 어떤 리포지토리도 조회·저장하지 않는다 | 인증 통과 직후 계정이 사라진 상황 → 401 `{"success":false,"data":null,"message":"인증이 필요합니다."}`. 구단·선수·응원 리포지토리 접근 0회 |
| USER-SP-46 | 예외 | IF 락 획득이 대기 시간 초과(MySQL `innodb_lock_wait_timeout` 기본 50초) 또는 데드락으로 실패하면, THEN THE 시스템 SHALL `ApiResponse` 래퍼가 없는 **500** 을 반환한다 (409·503 으로 매핑하지 않는다 — 결정 기록 10) | 락 보유 트랜잭션이 50초 이상 유지된 상태에서 같은 계정으로 응원 변경 요청 → 500, 본문이 `{"success":…}` 형태가 아님. `GlobalExceptionHandler` 는 `BusinessException`·`MethodArgumentNotValidException` 만 처리한다 |

**락 대상이 `user_account` 행인 이유(재논의 방지)**: `user_support_player` 행을 잠그는 방식도 **원리상 가능하다** — MySQL 기본 격리 수준인 REPEATABLE READ 에서 InnoDB 의 잠금 읽기(`SELECT ... FOR UPDATE`)는 **넥스트키 락**이라 인덱스 레코드뿐 아니라 **갭까지** 잠그므로, 일치하는 행이 0개여도 그 자리에 들어올 INSERT 를 막을 수 있다. 계정 행을 고른 이유는 "응원 행으로는 못 막아서"가 아니라 **그 방식이 성립하는 조건이 우리 손 밖에 있기 때문**이다.

1. **갭 락은 READ COMMITTED 에서 존재하지 않는다.** 격리 수준이 바뀌는 순간 방어가 **조용히** 사라진다 — 코드도 테스트도 그대로인데 상한만 뚫린다.
2. **잠기는 범위가 어느 인덱스를 타느냐에 좌우된다.** `oppose IS NULL` 같은 비인덱스 술어가 끼면 무엇이 잠기는지 추론이 어렵고, 인덱스가 추가·변경되면 범위가 같이 움직인다.
3. **계정 행 PK 단일 행 잠금에는 그런 변수가 없다.** 격리 수준·인덱스 선택에 **의존하지 않는 쪽**을 골랐다.
4. 그리고 앞 세 가지와 별개로: 불변식이 "**비어 있을 수 있는 집합**"(활성 응원 선수 0~4명)에 대한 진술이므로 **항상 존재하는 앵커**가 필요하다. 계정 행은 응원 상태 불변식이 걸린 단위이면서 행 수가 0이 될 수 없는 유일한 대상이다.

> ⚠ **근거 정정(2026-08-06, 3차 개정 당일)**: 이 문단은 처음에 *"응원 행을 잠그는 방식은 활성 0명인 계정에 잠글 행이 없어 팬텀 INSERT 를 막지 못한다"* 로 적혀 있었다. **이 서술은 기술적으로 틀렸다** — 넥스트키 락이 갭을 잠그므로 행이 0개여도 막을 수 있다. **결론(계정 행을 잠근다)은 바뀌지 않았고 근거만 위 4개로 교체했다.** 삭제하지 않고 남기는 이유는 두 가지다: ①"막지 못한다"를 근거로 삼으면 누군가 그 반례(넥스트키 락)를 발견했을 때 **결론까지 함께 무너진 것처럼 보인다** ②정정 전 서술이 커밋 메시지·코드 주석에도 퍼져 있을 수 있어 어느 쪽이 최신인지 표시가 필요하다.

"응원 행을 잠그면 되지 않나"로 되돌리기 쉬운 자리이므로 **위 4개 근거를 지우지 말 것**(결정 기록 9).

**이 락의 실제 파급 범위 — "계정 행 하나만 잠근다"가 "영향이 그 행에만 갇힌다"는 뜻이 아니다**:
- 직접 잠기는 것은 `users_account` **한 행뿐**이고, FK 로 딸린 자식 행(`user_support_team`·`user_support_player`·`users_refreshtoken` 등)은 이 락으로 잠기지 않는다.
- **그러나** InnoDB 는 자식 행 INSERT 시 FK 검사로 **부모 행에 공유 락**을 잡는다. 따라서 이 락을 쥔 동안 **그 계정을 참조하는 자식 테이블 쓰기 전반이 대기한다** — 응원 행뿐 아니라 **로그인이 만드는 `users_refreshtoken` INSERT 도 포함된다**. 응원 API 끼리만 서로를 기다리는 것이 아니다.
- 지금은 트랜잭션이 단문 몇 개뿐이라 대기가 ms 단위다. **이 트랜잭션 안에서 오래 걸리는 작업을 하면 그 대기가 그대로 번진다** — 외부 HTTP 호출·메일 발송·대량 배치 같은 것을 이 경계 안에 넣지 말 것. 지금 무해한 이유는 설계가 아니라 **트랜잭션이 짧기 때문**이며, 그 전제가 깨지면 로그인까지 느려진다.
- **읽기는 막히지 않는다.** MVCC 라 일반 `SELECT` 는 락을 기다리지 않으며, 이것이 USER-SP-44(읽기 경로 무락)와 함께 `GET /me` 가 영향을 안 받는 근거다.

**세 쓰기 경로가 같은 순서로 잠그는 이유**: `opposePlayers` 는 개수를 줄이는 방향이라 상한과 무관하지만, `oppose` 를 채우며 응원 행 락을 잡는다. `addPlayers` 가 *계정 락 → 응원 행* 순서인데 `opposePlayers` 만 *응원 행 → 계정* 순서면 두 요청이 서로의 다음 락을 기다리는 **순서 역전 데드락**이 성립한다. USER-SP-42 는 편의가 아니라 이 조합을 없애기 위한 계약이다.

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
- **신설 `ErrorCode`(`:common`) 3종**: `TEAM_NOT_FOUND`(404) · `PLAYER_NOT_FOUND`(404) · `SUPPORT_TEAM_REQUIRED`(400) · `PLAYER_NOT_IN_SUPPORT_TEAM`(400). 기존 404 는 `CHATROOM_NOT_FOUND`/`CHAT_MESSAGE_NOT_FOUND` 뿐이라 구단·선수용이 없다. **2026-08-06 추가: `SUPPORT_PLAYER_LIMIT_EXCEEDED`(400, `"응원 선수는 최대 4명까지 선택할 수 있습니다."`).** 409 가 아닌 이유는 이것이 자원 충돌이 아니라 `SUPPORT_TEAM_REQUIRED`·`PLAYER_NOT_IN_SUPPORT_TEAM` 과 **같은 성격의 정책 위반**이기 때문이다 — 응원 선수 쓰기 경로의 정책 위반은 전부 400 으로 맞춘다. **2026-08-06 3차 개정에서는 `ErrorCode` 를 신설하지 않았고, 앞으로도 신설하지 않는다** — 락 대상 계정 부재는 기존 `UNAUTHENTICATED`(401)를 재사용하고(USER-SP-45), 락 획득 실패는 `ErrorCode` 로 표현하지 않은 채 500 으로 둔다(USER-SP-46, 결정 기록 10). **신설이 필요해지는 유일한 경우는 결정 기록 10 의 "되돌릴 조건"이 성립할 때다.**
- **상한 값(4)의 단일 출처는 `SupportService.MAX_SUPPORT_PLAYERS` 다.** 별도 정책 클래스(`PasswordPolicy`·`NicknamePolicy` 같은)는 신설하지 않았다 — 검증 주체가 이 서비스 하나뿐이라 공유할 소비처가 없다. **값을 바꾸면 `ErrorCode.SUPPORT_PLAYER_LIMIT_EXCEEDED` 의 메시지 문자열("최대 4명")도 함께 바꿔야 한다** — 숫자가 두 곳에 있고, 메시지 쪽은 컴파일러가 잡아 주지 않는다.
- **`Player.team` 은 LAZY 다.** USER-SP-17 의 소속 검사에서 선수마다 팀을 꺼내면 **선수 수만큼 조회가 나간다**. FK 값만 필요하므로 `player.getTeam().getId()`(프록시 id 접근은 초기화를 유발하지 않는다) 또는 팀 소속 선수 id 집합을 한 번에 조회하는 방식으로 처리해야 한다.
- **계정 락은 트랜잭션 안에서만 의미가 있다.** `SupportService` 는 클래스 레벨 `@Transactional` 이라 락이 커밋까지 유지된다. 락 확보를 트랜잭션 밖(또는 `readOnly` 경계)으로 옮기면 조회 직후 풀려 **아무것도 막지 못하면서 비용만 남는다** — USER-SP-38·39 가 조용히 무력화되는 형태라 테스트로도 잘 드러나지 않는다.
- **락 확보 실패의 401(USER-SP-45)은 필터의 401과 다른 경로다.** 필터 단계 401 은 `RestAuthenticationEntryPoint` 가, 이 401 은 `BusinessException(UNAUTHENTICATED)` → `GlobalExceptionHandler` 가 만든다. **응답 본문은 같다**(둘 다 `ErrorCode.UNAUTHENTICATED`) — 상태 코드·본문만으로 두 경로를 구분할 수 없다는 점은 이 모듈의 기존 특성과 같다.
- **이 트랜잭션 안에 오래 걸리는 작업을 넣지 말 것.** 계정 행 락을 쥔 동안 InnoDB 의 FK 검사(자식 INSERT 가 부모 행에 공유 락)를 통해 **그 계정을 참조하는 자식 테이블 쓰기 전반이 대기한다** — 응원 행뿐 아니라 로그인의 `users_refreshtoken` INSERT 도 포함된다. 지금 무해한 이유는 트랜잭션이 단문 몇 개(ms)이기 때문이며, 외부 HTTP 호출·메일 발송 같은 것이 들어오면 그 대기가 로그인까지 번진다. 상세는 USER-SP-38 표 아래 "이 락의 실제 파급 범위".
- **락 관련 예외는 `GlobalExceptionHandler` 밖이다**(USER-SP-46). 핸들러가 처리하는 것은 `BusinessException` 과 `MethodArgumentNotValidException` 둘뿐이라, `PessimisticLockingFailureException`·`CannotAcquireLockException` 류는 `ApiResponse` 래퍼 없는 500 으로 나간다. `?teamId=abc` 타입 변환 400 이 래퍼를 안 타는 것과 같은 종류의 함정이다.
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
9. **비관적 락을 도입한다**(2026-08-06 3차 개정, USER-SP-38~46). 사용자 결정.
   **이 문서의 종전 서술을 뒤집은 것이다.** 2026-07-28 시점 "미해결 / 후속"은 동시 요청 레이스를
   *"탈퇴 동시 호출과 같은 종류의 알려진 한계로 두고, 비관적 락은 도입하지 않는다"* 로 적고 있었다.
   같은 날 상한(결정 8)이 들어오며 그 판단의 전제가 바뀌었다 — 구단 1개 불변식은 깨져도
   `findByUserAccount_IdAndOpposeIsNull` 이 사후에 예외로 드러내 주지만, **상한은 넘어도 아무도
   드러내 주지 않는다**(활성 5명은 조회가 그대로 반환하는 정상 데이터다).
   - **사용자 근거(원문)**: *"동일 계정은 한 기기만 접속 가능하게 할 것이라, 해당 작업 시 row 에 락을
     거는 것이 맞다."* 즉 같은 계정의 응원 변경이 동시에 들어오는 것 자체가 정상 사용 패턴이 아니므로,
     직렬화로 인한 대기는 실사용에서 비용이 아니다 — 낙관적 재시도보다 단순한 쪽을 골랐다.
   - 함께 확정된 것 4가지: ①잠그는 대상은 **응원 행이 아니라 계정 행**(응원 행 잠금도 원리상 가능하지만
     그 방어가 **격리 수준·인덱스 선택에 의존**한다 — USER-SP-38 아래 근거 4개 참조) ②락은
     **쓰기 경로의 첫 동작**이며 빈 배열 요청도
     예외가 아니다(USER-SP-40/41) ③**취소 경로도 잠근다** — 상한과 무관하지만 락 순서를 통일하지
     않으면 데드락이 성립한다(USER-SP-42) ④**읽기 경로는 잠그지 않는다**(USER-SP-44) — `GET /me` 가
     `currentSupportedPlayers` 를 타므로, 여기에 쓰기 락이 붙으면 프로필 조회끼리 서로를 막는다.
     이것은 구현 편의가 아니라 지켜야 할 계약이다.
   - **탈퇴 동시 호출(`DELETE /users/me`)은 여전히 락 없이 둔다.** 이번 결정은 응원 쓰기 경로에
     한정된다 — 탈퇴는 마지막 쓰기가 이겨도 계정이 탈퇴되고 토큰도 만료돼 결과가 달라지지 않는다.
   - **⚠ 이 결정의 근거 하나가 같은 날 정정됐다.** 최초 기록은 "응원 행은 활성 0명일 때 잠글 행이 없어
     팬텀을 못 막는다"였는데, **넥스트키 락이 갭을 잠그므로 그 서술은 틀렸다.** 결론(계정 행을 잠근다)은
     유지하고 근거만 "격리 수준(REPEATABLE READ)·인덱스 선택에 의존하지 않는 쪽을 고른다"로 바꿨다 —
     자세한 경위는 USER-SP-38 표 아래 "근거 정정" 인용 블록. **결론이 바뀐 것이 아니므로 요구사항
     번호는 늘리지 않았다**(계약 문장 USER-SP-38~46 은 그대로다).
10. **락 획득 실패는 500 을 그대로 둔다 — 409·503 으로 매핑하지 않는다**(2026-08-06, USER-SP-46).
    사용자 결정. 이로써 이 문서의 **미해결 질문은 0건**이 됐다.
    - **근거**: 이 트랜잭션은 단문 쿼리 몇 개라 정상 보유 시간이 **ms 단위**다. `innodb_lock_wait_timeout`
      기본값 **50초**를 실제로 채우려면 **이미 다른 장애가 난 상태**다(커넥션 고갈·DB 응답 지연·죽은
      트랜잭션 방치 등). 즉 락 대기 초과는 "정상 운영 중 발생하는 충돌"이 아니라 **장애의 증상**이며,
      그렇다면 **500 으로 나가는 것이 오히려 사실에 부합한다** — 409 는 "재시도하면 된다"는 잘못된
      신호를 주고, 503 은 클라이언트가 자동 재시도하게 만들어 이미 막힌 DB 를 더 밀어 넣는다.
    - **폐기한 선택지**: **B(409 매핑)** — 충돌 의미가 명확하고 클라이언트 재시도 근거가 되지만,
      위 근거대로 이 실패는 충돌이 아니라 장애다. **C(503 + `Retry-After`)** — 자동 재시도 지시가
      가능하나 응원 변경은 사용자 조작이라 자동 재시도가 오히려 혼란스럽고, 장애 중 부하를 키운다.
    - **되돌릴 조건(다음 사람이 바로 집을 수 있게)**: ①이 트랜잭션 경계에 **오래 걸리는 작업**(외부 HTTP
      호출·메일 발송·대량 배치)이 들어가 정상 보유 시간이 ms 를 벗어나거나 ②**락 대기 500 이 실제로
      관측되기 시작하면** 409 매핑을 재검토한다. 그때 필요한 변경은 두 가지뿐이다 —
      **`ErrorCode` 1종 신설**(`:common`) + **`GlobalExceptionHandler` 에
      `PessimisticLockingFailureException` 처리 추가**(현재 이 핸들러는 `BusinessException` 과
      `MethodArgumentNotValidException` 둘만 처리해서 500 이 나는 것이다).

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
| USER-SP-38 | `SupportServiceTest.selectTeam_locksAccountExactlyOnce` · `…addPlayers_locksAccountExactlyOnce` · `…opposePlayers_locksAccountExactlyOnce`(쓰기 3경로 각각 `findWithLockById` 정확히 1회) |
| USER-SP-39 | **자동 테스트 없음** — 목으로는 트랜잭션 간 배타성을 재현할 수 없다(아래 "미커버 영역"). 38·40·43 이 "락을 잡는 코드 경로가 살아 있다"까지를 대신 고정하고, **실제 직렬화는 2026-08-06 로컬 동시 요청 실측이 근거다**(5회 반복 전부 한쪽 200 / 다른 쪽 400 — "런타임 검증 기록 — 계정 락") |
| USER-SP-40 | `SupportServiceTest.selectTeam_locksAccountBeforeTeamLookup` · `…addPlayers_locksAccountBeforeSupportTeamLookup` · `…opposePlayers_locksAccountBeforePlayerExistenceCheck`(셋 다 `InOrder` 로 순서 고정 — "호출했다"만으로는 락이 뒤로 밀린 회귀를 못 잡는다) |
| USER-SP-41 | `SupportServiceTest.addPlayers_emptyPlayerIds_locksAccountBeforeEarlyReturn` · `…opposePlayers_emptyPlayerIds_locksAccountBeforeEarlyReturn` |
| USER-SP-42 | `SupportServiceTest.opposePlayers_locksAccountExactlyOnce` · `…opposePlayers_locksAccountBeforePlayerExistenceCheck` |
| USER-SP-43 | `SupportServiceTest.addPlayers_locksAccountBeforeSupportTeamLookup`(상한 판정 입력인 활성 목록 조회가 락 뒤임을 락→첫 응원 조회 순서로 고정) + USER-SP-30~34 의 상한 테스트들 |
| USER-SP-44 | `SupportServiceTest.currentSupportedPlayers_neverLocksAccount` · `…currentSupportedPlayers_returnsNameAscWithSingleBatchQuery`(`never()` 검증 포함) · `UserProfileServiceTest.getMyProfile_neverLocksAccount`(`GET /me` 경로 회귀) |
| USER-SP-45 | `SupportServiceTest.selectTeam_accountNotFound_throwsUnauthenticatedAndTouchesNoOtherRepository` · `…addPlayers_accountNotFound_…` · `…opposePlayers_accountNotFound_…`(3경로 모두 `UNAUTHENTICATED` + 다른 리포지토리 접근 0회) |
| USER-SP-46 | **전용 테스트 없음** — 락 대기 초과·데드락은 실제 DB 없이는 발생시킬 수 없다(아래 "미커버 영역") |

`SupportServiceTest` **47건**(락 회귀 12건 신설) · `SupportControllerTest` 22건 · `UserProfileServiceTest` **11건**(`getMyProfile_neverLocksAccount` 신설), `:user:test` **356건** 전부 통과(2026-08-06 실측).

**락 회귀 12건이 고정하는 것(요약)**: ①쓰기 3경로가 락을 정확히 1회 호출(38·42) ②`InOrder` 로 락이 다른 리포지토리 접근보다 앞임을 고정(40·43) ③`currentSupportedPlayers` 는 락을 절대 안 잡음(44) ④계정 부재 시 `UNAUTHENTICATED` 이고 다른 리포지토리를 안 탐(45) ⑤빈 `playerIds` 도 락이 먼저(41). 여기에 `UserProfileServiceTest` 1건이 `GET /me` 쪽에서 ③을 다시 못 박는다.
`SupportServiceTest` 는 `@BeforeEach` 에서 `findWithLockById` 를 공통 스텁한다 — **쓰기 경로 3개가 첫 줄에서 락을 타므로 이 스텁이 없으면 이 클래스의 거의 모든 테스트가 `UNAUTHENTICATED` 로 깨진다.** 새 쓰기 테스트를 추가할 때 밟기 쉬운 자리다.

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
- **계정 락의 실제 배타성(USER-SP-39)은 자동 테스트가 아니라 1회성 실측으로만 확인됐다**: 목 기반
  테스트가 고정하는 것은 **"락을 잡는 코드 경로가 살아 있다"**(`findWithLockById` 호출 여부·횟수·순서)
  까지다. 두 요청이 실제로 직렬화되는지는 트랜잭션 2개와 실제 DB 라운드트립이 필요한데 저장소에
  H2·Testcontainers 가 없어 **회귀로 고정하지 못한다**. 2026-08-06 로컬 동시 요청 실측이 이 구멍을
  일부 메웠지만(아래 "런타임 검증 기록 — 계정 락"), **그 실측은 CI 에서 다시 돌지 않으며 순서 편향
  단서도 남아 있다.** 락을 떼는 회귀는 목 테스트 12건이 잡지만, **락이 있는데 실제로는 안 걸리는 회귀**
  (격리 수준 변경, `@Lock` 유실, 트랜잭션 경계 이탈)는 지금도 자동으로 잡히지 않는다.
- **락 획득 실패 경로(USER-SP-46)**: 대기 초과·데드락을 유발하려면 락을 잡은 채 50초 이상 버티는
  경쟁 트랜잭션이 필요해 유닛 테스트로 재현 불가. **"500 이 난다"는 코드 검토(`GlobalExceptionHandler`
  가 두 예외만 처리) 근거이며 실제 응답을 관측한 적은 없다.**
- **데드락 방지(USER-SP-42)**: 락 순서를 통일했다는 사실은 테스트가 고정하지만, **순서를 어겼을 때
  실제로 데드락이 나는지**(즉 이 계약이 막고 있는 것이 실재하는지)는 확인하지 않았다.

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

**계정 락(USER-SP-38~46)은 위 2026-07-28 실측에 포함돼 있지 않다** — 그 시점에는 락이 없었다. 아래가 별도 실측이다.

## 런타임 검증 기록 (2026-08-06, 계정 락 — 로컬 동시 요청 실측)
`module-verifier` 가 로컬에서 **같은 계정에 동시 요청을 발사해** 확인했다. 시나리오: **활성 응원 선수 2명**인
계정에 서로 다른 선수 2명씩을 담은 `POST /api/member/support/players` **두 건을 동시 발사**(각각 성공하면
합이 6명이 되는 조합), **5회 반복**.

- **USER-SP-39(직렬화) 성립**: **5회 전부** 한쪽 200 / 다른 쪽 400 `SUPPORT_PLAYER_LIMIT_EXCEEDED`.
  둘 다 통과한 경우는 없었다.
- **USER-SP-30/34 가 동시성 아래에서도 유지**: 최종 `supportPlayers` 개수가 **항상 4**(6으로 넘어간 적
  없음), 거부된 쪽 선수는 **전혀 반영되지 않았다**(부분 반영 0건).
- **USER-SP-38/40 성립**: `show-sql` 로 쓰기 3경로 모두 **첫 SQL 이 `... for update`** 임을 확인.
- **USER-SP-44 성립**: `GET /api/member/users/me` 로그에 `for update` **0건**, SELECT **5회 유지**
  (`me-profile.md` USER-ME-22 와 일치 — 락 도입이 조회 횟수를 늘리지 않았다).

**단서(그대로 남긴다)**: ①**5회 모두 같은 쪽이 이겼다** — 발사 스크립트상 순서 편향 가능성이 있다.
②두 트랜잭션이 완전히 같은 시각에 락을 다투는 **진짜 임계 레이스를 강제로 유발했다고 단정할 수 없다**
(한쪽이 먼저 커밋을 끝낸 뒤 다른 쪽이 시작했더라도 같은 결과가 나온다). 따라서 이 실측은 **"락이 실제
경로에서 동작하고 결과가 계약과 일치한다"까지의 근거**이며, 락이 없었다면 반드시 실패했을 것이라는
**반대 방향 대조(락 제거 후 6명 재현)는 하지 않았다.**

## 미해결 / 후속 (이번 범위 아님 — 기록만)
- **`(가정)` 2건**: USER-SP-21(빈 배열 추가 요청을 400 이 아니라 no-op 200 으로) · USER-SP-27(응원한 적 없는 선수 취소를 404 가 아니라 no-op 200 으로). 둘 다 멱등성을 우선한 해석이다. 400/404 를 원하시면 이 두 줄만 바꾸면 된다.
- **"구단 미선택 사용자 차단"의 강제 지점.** 지금 계약은 이 엔드포인트의 필드 필수성까지만 보장한다. 가입 직후 구단을 고르지 않은 계정이 다른 API 를 그대로 쓸 수 있다 — 회원가입 플로우에서 강제할지, 서버가 미선택 상태를 검사할지는 별도 요구사항이다.
- **선수 트레이드로 인한 사후 불변식 위반.** py-collector 가 `players.team_id` 를 갱신하면, 저장 시점에 유효했던 응원이 나중에 "타팀 선수 응원"이 된다. 쓰기 시점 검증만으로는 막을 수 없고 정리 배치나 조회 시점 필터가 필요하다.
- ~~**동시 요청 레이스.** … 탈퇴 동시 호출과 같은 종류의 알려진 한계로 두고, 비관적 락은 도입하지 않는다.~~
  **(정정됨 2026-08-06 3차 개정)** 이 서술은 **더 이상 유효하지 않다.** 같은 날 응원 선수 상한(결정 8)이
  들어오면서 전제가 무너졌다 — 구단 1개 불변식은 깨져도 `findByUserAccount_IdAndOpposeIsNull` 이 사후에
  예외로 드러내지만, **상한 초과는 아무도 드러내 주지 않는다.** 사용자가 판단을 뒤집어
  (근거: *"동일 계정은 한 기기만 접속 가능하게 할 것이라, 해당 작업 시 row 에 락을 거는 것이 맞다"*)
  **계정 행 비관적 락을 도입했고 구현·테스트가 끝났다** → USER-SP-38~46, 결정 기록 9.
  **삭제하지 않고 남겨 둔다** — 이 자리는 "락 없이도 되지 않나"로 되돌아오기 쉬워, 무엇이 왜 뒤집혔는지가
  같이 보여야 한다.
- **락 실패의 응답 코드(USER-SP-46) — 닫혔다.** 대기 초과·데드락은 `ApiResponse` 래퍼 없는 **500 을
  그대로 둔다**(2026-08-06 사용자 확정, 결정 기록 10). 후속으로 남는 것은 결정이 아니라 **되돌릴 조건의
  감시**다: 이 트랜잭션에 오래 걸리는 작업이 들어가거나 락 대기 500 이 실제로 관측되면 409 매핑을
  재검토한다.
- **동시성 검증을 자동화할 수단이 없다.** USER-SP-39 의 인수 기준(동시 2건 중 한쪽만 200)은 **2026-08-06
  로컬 실측으로 관측됐지만**(5회 반복 전부 일치), 이를 **회귀로 고정하려면** H2·Testcontainers 또는
  통합 테스트 환경이 필요하다. 저장소 전체에 걸린 같은 제약이라(`.claude/modules/domain.md` 동일 기록)
  이 문서 범위에서 해결할 수 없다. 그래서 격리 수준을 바꾸거나 `@Lock` 을 잃어버리는 변경은 **테스트가
  아니라 사람이 막아야 한다.**

## 미해결 질문
**없음(0건).** 2026-08-06 3차 개정에서 열려 있던 1건(락 획득 실패의 응답 코드)은 **A안(현행 500 유지)으로
확정**돼 닫혔다 — 선택지 B(409)·C(503)를 왜 폐기했는지와 **되돌릴 조건**은 지우지 않고 **결정 기록 10**에
옮겨 두었다.
