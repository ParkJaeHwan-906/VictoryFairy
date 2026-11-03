# 경기 승리확률(승률) 노출 요구사항
> 상태: **승인됨 (2026-10-13, 사용자 승인)** · 모듈: user (+ domain `games` 컬럼 추가) · 최종 수정: 2026-10-13
> 기존 응답 형식(`GameResponse`)에 필드 2개를 **추가**한다. 엔드포인트 신설·삭제는 없다. 영향받는 기존 요구사항은 아래 "기존 요구사항과의 관계"에 모았다.
> 사용자 결정(2026-10-13, 작성 전 확인): ① 수집기가 진행 중 경기에 한해 현재 이닝 relay 를 추가 호출해 최신 유효 `metricOption` 을 `games` 에 저장한다 ② 승률 변화도 `game-state-events/` 변화 감지 대상에 넣는다 ③ `GameResponse`(REST·SSE 공통)에 필드를 더하고 값이 없으면 `null` ④ 1분 폴링 지연(최대 약 1분)을 수용한다.
> **2026-10-13 승인 시점 개정**: 초안 미해결 질문 7건 중 1~4·6·7 은 초안 가정대로 확정해 `(가정)` 표시를 뗐다. 5(운영 DDL)는 **변경** — `ddl-auto=update` 에 맡기지 않고 운영·devdb 에 수동 ALTER 를 BE 배포 전에 먼저 적용한다(USER-GWR-14·23 개정, "운영 DDL" 절 신설). 같은 날 "진행 중인데 처음부터 승률이 없는 경우"를 명시하려고 USER-GWR-5 인수 기준을 보강하고 USER-GWR-24 를 추가했다.

## 배경 / 목적
경기 화면에 "지금 어느 팀이 몇 % 확률로 이기고 있나"를 보여 주려 한다. 원천은 네이버 relay API 의 `textRelays[].metricOption`(`homeTeamWinRate`·`awayTeamWinRate`·`wpaByPlate`, 단위 %, float 예 `35.800003`)인데 **비공식 필드라 언제든 빠질 수 있고**, 이닝 헤더 줄("5회말 LG 공격")은 `0.0/0.0` 이라 걸러야 한다. 그래서 이 문서의 절반은 "값이 없거나 이상할 때"의 계약이다.

전달 경로는 새로 만들지 않는다 — 기존 `games` upsert → `game-state-events/` → SQS → DB 재조회 → SSE `game-update` 경로(`game-realtime.md`)에 값 하나를 더 싣는다. 응답의 승률은 언제나 **DB 에서 다시 읽은 값**이다(수집기 문서 값을 쓰지 않는다, USER-GRT 표기 근거 1).

## 범위
- 포함
  - `GameResponse` 에 `homeTeamWinRate`·`awayTeamWinRate` 추가 → `GET /api/games`·`GET /api/games/support`·SSE `snapshot`·SSE `game-update` 네 곳에 동시에 나타난다
  - 경기 상태별 승률 노출 규칙(`IN_PROGRESS` 만 값, 그 외 `null` — 종료 경기 포함)
  - 진행 중이지만 승률이 아직 없는 경우의 규칙(수집기 배포 전·첫 유효 타석 전·원천 필드 부재)
  - 저장값이 짝이 안 맞거나 범위를 벗어났을 때의 방어 규칙
  - 승률 변화가 `game-update` 를 일으키는 계약(`changed` 토큰 `winRate`)
  - `games` 컬럼 2개 추가(nullable) — 수동 ALTER 선행("운영 DDL" 절)
  - 수집기(VictoryFairy_AI)가 지켜야 할 쓰기 계약 — "외부 의존" 절에 문장으로만 정의(구현·테스트는 그 저장소 몫)
- 제외
  - **`wpaByPlate`(타석별 승률 기여도) 노출·저장** — 어느 타석의 값인지 식별자 없이 내보내면 의미가 없고, 1분 사이의 타석들은 유실된다
  - **승률 이력·추이(그래프용 시계열)** — 저장은 "현재 값 1개"뿐. 1분 간격 값을 쌓지 않는다
  - **1분보다 촘촘한 갱신** — 수집기 폴링 주기 자체의 한계(USER-GRT 표기 근거 2)
  - **서버 자체 승률 계산·보정** — 네이버 값을 반올림 외에 가공하지 않는다. 두 값의 합이 정확히 100.0 이 되도록 맞추지 않는다
  - **`games` 에 승률 CHECK 제약 추가** — 아래 "제약" 참고(운영 `games` 테이블 리빌드 위험)
  - **선발 라인업(`GET /api/games/lineup`)** — 응답 형식이 `GameResponse` 가 아니라 영향 없음

## 요구사항 (EARS)

### BE (user 모듈 — 이 저장소의 테스트 대상)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GWR-1 | 유비쿼터스 | THE 시스템 SHALL `GameResponse` 항목에 `homeTeamWinRate`·`awayTeamWinRate` 두 키를 값이 `null` 이어도 항상 포함한다 | `GET /api/games?date=2026-08-01` 의 각 항목 키 집합이 기존 13개 + `homeTeamWinRate`·`awayTeamWinRate` = 정확히 15개. `GET /api/games/support`·SSE `snapshot` 항목·SSE `game-update` 의 `data.game` 도 같은 15개 |
| USER-GWR-2 | 유비쿼터스 | THE 시스템 SHALL 기존 13필드의 이름·타입·값 규칙을 바꾸지 않는다 | 승률 컬럼이 채워진 경기와 비어 있는 경기 모두, 13필드 값이 이 변경 전 응답과 동일 |
| USER-GWR-3 | 유비쿼터스 | THE 시스템 SHALL 승률 값을 JSON 숫자(문자열 아님)로, 단위 %(0.0 이상 100.0 이하), 소수 첫째 자리까지로 반환한다 | DB `home_win_rate=35.8`, `away_win_rate=64.2` → `"homeTeamWinRate":35.8,"awayTeamWinRate":64.2`(따옴표 없음). `35.800003` 같은 꼬리는 응답에 나타나지 않는다 |
| USER-GWR-4 | 상태 | WHILE 경기의 `gameState` 가 `IN_PROGRESS` 이고 두 승률 값이 모두 유효하면, THE 시스템 SHALL DB 에 저장된 두 값을 그대로 반환한다 | `IN_PROGRESS` + DB 35.8/64.2 → 응답 35.8/64.2 |
| USER-GWR-5 | 예외 | IF `IN_PROGRESS` 경기인데 승률이 아직 수집되지 않았으면(두 컬럼 모두 `NULL`), THEN THE 시스템 SHALL 두 필드를 `null` 로 반환한다 | `IN_PROGRESS` + DB `NULL/NULL` → 200, 두 키 존재·값 `null`, 나머지 13필드 정상(에러 아님). 이 상태가 되는 경우: ① 수집기 승률 쓰기 배포 전 ② 경기 시작 직후 첫 유효 타석 전 ③ 네이버가 그 경기에 `metricOption` 을 주지 않음 — 셋 모두 같은 응답 |
| USER-GWR-6 | 예외 | IF 두 승률 컬럼 중 한쪽만 `NULL` 이면, THEN THE 시스템 SHALL 두 필드를 모두 `null` 로 반환한다 | `IN_PROGRESS` + DB `35.8/NULL` → 두 필드 `null`(한쪽만 보여 주지 않는다) |
| USER-GWR-7 | 예외 | IF 두 승률 값 중 하나라도 0.0~100.0 범위를 벗어나면, THEN THE 시스템 SHALL 두 필드를 모두 `null` 로 반환한다 | `IN_PROGRESS` + DB `101.0/-1.0` 또는 `120.0/30.0` → 두 필드 `null`, 응답은 200 |
| USER-GWR-8 | 상태 | WHILE 경기의 `gameState` 가 `SCHEDULED`·`CANCELED` 이거나 5종(`SCHEDULED`·`IN_PROGRESS`·`FINISHED`·`DRAW`·`CANCELED`) 밖의 값이면, THE 시스템 SHALL DB 값과 무관하게 두 필드를 `null` 로 반환한다 | `SCHEDULED` + DB 50.0/50.0 → `null`/`null`. `CANCELED`(`cancelReason` 있음) + DB 값 있음 → `null`/`null` |
| USER-GWR-9 | 상태 | WHILE 경기의 `gameState` 가 `FINISHED`·`DRAW` 이면, THE 시스템 SHALL DB 값과 무관하게 두 필드를 `null` 로 반환한다 | `FINISHED` + DB 98.7/1.3 → `null`/`null`. `DRAW` + DB 값 있음 → `null`/`null` |
| USER-GWR-10 | 이벤트 | WHEN 수집기가 승률 변화를 담은 `game-state-events` 문서를 적재하면, THE 시스템 SHALL 그 경기를 DB 에서 다시 읽어 승률이 반영된 `game-update` 를 보낸다 | 점수·이닝·상태는 그대로이고 승률만 35.8→41.2 로 바뀐 문서 → 그 날짜 구독자가 `game-update` 수신, `data.game.homeTeamWinRate == 41.2`(DB 값). 첫 유효값(`NULL`→35.8) 문서도 같은 방식으로 `game-update` 수신 |
| USER-GWR-11 | 유비쿼터스 | THE 시스템 SHALL 수집기가 보낸 `changed` 의 `winRate` 토큰을 걸러내지 않고 `game-update.changed` 에 그대로 싣는다 | 문서 `changed=["winRate"]` → 이벤트 `changed == ["winRate"]`. 문서 `changed=["homeScore","winRate"]` → 이벤트 1건, `changed` 에 둘 다 포함 |
| USER-GWR-12 | 예외 | IF `changed` 에 `winRate` 가 있는데 DB 재조회 결과 승률이 `null`(USER-GWR-5~9 규칙 적용 후)이면, THEN THE 시스템 SHALL 이벤트를 버리지 않고 두 필드가 `null` 인 `game-update` 를 보낸다 | 종료 처리로 수집기가 `changed=["inning","status","winRate"]` 적재 → `gameState:"FINISHED"`, 승률 `null`/`null` 인 `game-update` 수신(클라이언트가 승률 표시를 내리는 신호) |
| USER-GWR-13 | 유비쿼터스 | THE 시스템 SHALL `support/subscribe` 구독자에게도 USER-GWR-10~12 와 같은 승률 갱신을 기존 응원 구단 필터(USER-GRT-16) 그대로 보낸다 | 구단 6 구독자 → 6 이 참여한 경기의 승률 전용 `game-update` 는 수신, 6 이 없는 경기의 것은 미수신 |

### 외부 의존 — `games` 컬럼·수집기(VictoryFairy_AI) 쓰기 계약 (이 저장소 테스트 대상 아님, BE 가 기대하는 사실)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GWR-14 | 유비쿼터스 | THE `games` 테이블 SHALL `home_win_rate`·`away_win_rate` 두 컬럼을 `DECIMAL(4,1) NULL`(기본값 `NULL`)로 가지며, `Game` 엔티티의 두 필드 매핑은 이 타입과 일치한다 (2026-10-13 개정: 컬럼은 `ddl-auto` 가 아니라 아래 "운영 DDL"의 수동 ALTER 로 만든다) | ALTER 적용 후 기존 행은 두 컬럼 모두 `NULL`. 수집기가 `35.8` 을 쓰면 그대로 읽힌다. 적용 후 user 앱을 기동해도 `ddl-auto=update` 가 두 컬럼에 대해 `alter table` 을 내지 않는다(타입 불일치 없음) |
| USER-GWR-15 | 이벤트 | WHEN 수집기가 `IN_PROGRESS` 경기를 폴링하면, THE 수집기 SHALL 현재 이닝 relay(`/schedule/games/{gameId}/relay?inning={N}`)의 `textRelays` 중 **가장 최근 타석의 유효한** `metricOption` 의 `homeTeamWinRate`·`awayTeamWinRate` 를 두 컬럼에 쓴다 | 5회말 진행 중, 최신 유효 줄 35.800003/64.199997 → DB `35.8/64.2` |
| USER-GWR-16 | 유비쿼터스 | THE 수집기 SHALL 두 값이 모두 존재하는 숫자이고 둘 다 0.0~100.0 이며 "둘 다 0.0" 이 아닌 줄만 유효로 본다 | 이닝 헤더 줄 `0.0/0.0`, 필드 누락 줄, `metricOption` 자체가 없는 줄은 무시 |
| USER-GWR-17 | 유비쿼터스 | THE 수집기 SHALL 저장 전에 두 값을 각각 소수 첫째 자리로 반올림(HALF_UP)한다 | `35.800003` → `35.8`, `64.25` → `64.3` |
| USER-GWR-18 | 예외 | IF 현재 이닝 relay 에 유효한 줄이 없거나(이닝 시작 직후 헤더만 있는 경우 포함), relay 호출이 실패하거나, `metricOption` 필드가 응답에서 사라졌으면, THEN THE 수집기 SHALL 두 컬럼의 기존 값을 덮어쓰지 않고 유지한다 | 6회초 시작 직후 헤더 줄만 존재 → DB 는 5회말 마지막 값 유지, 승률 `changed` 없음 |
| USER-GWR-19 | 예외 | IF relay 호출이 실패하면, THEN THE 수집기 SHALL 그 경기의 점수·이닝·상태 upsert 와 `game-state-events` 적재는 평소대로 진행한다 | relay 타임아웃 중 득점 발생 → `homeScore` 변화 이벤트는 정상 적재 |
| USER-GWR-20 | 이벤트 | WHEN 경기 상태가 `IN_PROGRESS` 가 아닌 값으로 upsert 되면, THE 수집기 SHALL 두 컬럼을 `NULL` 로 쓴다 | `IN_PROGRESS`→`FINISHED` 전이 폴링 → DB 두 컬럼 `NULL`. `SCHEDULED`·`CANCELED` 행은 처음부터 `NULL` |
| USER-GWR-21 | 이벤트 | WHEN 반올림 후 두 값 중 하나라도 직전 저장값과 다르면(`NULL` ↔ 값 전이 포함), THE 수집기 SHALL `game-state-events/` 문서의 `changed` 에 `winRate` 를 넣어 적재한다 | 35.8→41.2 → `changed` 에 `winRate`. 첫 유효값 `NULL`→35.8 → `changed` 에 `winRate`. `35.800003`→`35.800001`(반올림 후 동일) → 승률 변화로 보지 않음 |
| USER-GWR-22 | 유비쿼터스 | THE 수집기 SHALL 승률을 포함한 `games` upsert 를 커밋한 **뒤에** 해당 `game-state-events` 문서를 쓴다(기존 순서 계약 유지) | 리스너가 문서를 받아 DB 를 읽었을 때 승률이 문서 시점 값과 같거나 더 새롭다 |
| USER-GWR-23 | 예외 | IF 운영 `games` 에 두 컬럼이 아직 없으면, THEN THE 수집기 SHALL 승률 컬럼 쓰기를 배포하지 않는다 (2026-10-13 개정: 배포 순서는 ① 운영·devdb 수동 ALTER ② BE(user) 배포 ③ 수집기 배포로 고정) | 수집기 배포 전 운영 DB `SHOW COLUMNS FROM games LIKE '%win_rate'` → 2행·`decimal(4,1)`·`Null=YES` 확인. 컬럼 없이 쓰면 `games` upsert 전체가 실패해 **점수·상태 동기화까지 멈춘다** |
| USER-GWR-24 | 예외 | IF 직전 저장값과 이번 폴링 결과가 모두 "승률 없음"(`NULL`→`NULL`)이면, THEN THE 수집기 SHALL 승률 변화로 보지 않아 `changed` 에 `winRate` 를 넣지 않는다 | 경기 시작 후 첫 유효 타석 전, 또는 네이버가 `metricOption` 을 주지 않는 경기의 매 폴링 → 승률로 인한 `game-state-events` 문서 없음(다른 필드 변화가 없으면 문서 자체가 없다). 구독자는 `game-update` 를 받지 않고 `snapshot`·GET 의 두 필드는 `null`(USER-GWR-5) |

## 운영 DDL (BE 배포 전 수동 적용)
`ddl-auto=update` 에 맡기지 않는다. 운영 DB 와 devdb **둘 다**, BE(user) 배포 **전에** 아래를 적용한다. 두 컬럼 모두 nullable, 기본값 `NULL`, 위치 지정(`AFTER`) 없음.

```sql
ALTER TABLE games
  ADD COLUMN home_win_rate DECIMAL(4,1) NULL DEFAULT NULL,
  ADD COLUMN away_win_rate DECIMAL(4,1) NULL DEFAULT NULL;
```

- 엔티티 매핑은 이 타입(`DECIMAL(4,1)`, nullable)과 일치해야 한다(USER-GWR-14) — 어긋나면 user 기동 시 `ddl-auto=update` 가 다른 DDL 을 내거나 스키마와 엔티티가 서로 다른 값을 말하게 된다.
- 순서: ① 운영·devdb ALTER 적용 → ② BE 배포 → ③ 컬럼 확인(USER-GWR-23) 후 수집기 배포. ①~② 사이에는 컬럼이 있어도 모두 `NULL` 이라 응답은 USER-GWR-5 와 같다.

## 기존 요구사항과의 관계
- `game-realtime.md` **USER-GRT-4**(키 집합 13개 "정확히 일치")는 이 문서의 USER-GWR-1 로 15개가 된다 → 같은 날 GRT-4 를 개정했다. **USER-GRT-2·GRT-15**("13필드 동일")는 "짝 GET 과 동일" 이라는 뜻이 유지되므로 문장은 그대로 두고 필드 수만 15 로 읽는다. **USER-GRT-5** 의 `changed` 값 집합에 `winRate` 가 더해진다(GRT-5 인수 기준은 "포함" 판정이라 깨지지 않는다).
- `support-team-games.md`(승인됨) 범위 절의 "13필드"를 이 기능 승인에 따라 15필드로 개정했다(2026-10-13). USER-GSP-3(같은 `gameId` 항목끼리 동일)은 그대로 성립한다.

## 제약 (기존 정책·함정에서 온 것 — 구현 지시 아님)
- **운영 `games` 는 `ddl-auto=update` 가 리빌드한 이력이 있다** — CHECK 2건 추가 때 Hibernate 가 테이블을 재생성했다(`.claude/modules/domain.md`). 그래서 이번 컬럼은 수동 ALTER 로 만들고, CHECK 는 선언하지 않는다(범위 "제외"). 범위 검증은 USER-GWR-7(응답 측 방어)로 대신한다.
- **dev_* 머지가 곧 main 배포다.** 수동 ALTER → BE 배포 → 수집기 배포 순서가 깨지면 USER-GWR-23 의 장애(점수 동기화 정지)가 난다. 수동 ALTER 는 dev_be 머지 **전에** 끝나 있어야 한다.

## 미해결 질문
(없음)

## 결정 기록
- 2026-10-13 (사용자 승인): 종료 경기 `null` · 소수 첫째 자리(HALF_UP, `DECIMAL(4,1)`) · 필드명 `homeTeamWinRate`/`awayTeamWinRate`(평평한 구조) · `wpaByPlate` 미노출·미저장 · 수집 실패·값 부재 시 직전 값 유지 · BE 측 상태 마스킹 — 초안 가정 그대로 확정.
- 2026-10-13 (사용자 결정, 초안과 다름): 운영 DDL 은 `ddl-auto=update` 가 아니라 운영·devdb 수동 ALTER 를 BE 배포 전에 선행한다. 대안("BE 배포로 자동 추가 후 확인")은 배포 순서와 Hibernate 의 리빌드 여부를 통제할 수 없어 기각.
