# 경기 실시간 구독(SSE) 요구사항
> 상태: **초안 (2026-10-09, 구현과 함께 작성 — 사용자 승인 전)** · 모듈: user · 최종 수정: 2026-10-09
> 신규 엔드포인트 2개(`GET /api/games/subscribe`·`GET /api/games/support/subscribe`). 기존 `GET /api/games`·`GET /api/games/support`·`GET /api/games/lineup` 의 계약은 건드리지 않는다(USER-GRT-22).
> 사용자 결정(2026-10-09, 구현 전 확인): ① 점수 변동도 밀어야 하므로 수집기(dev_ai)에 상태 변화 스냅샷 적재를 추가하고 전용 SQS 큐(dev_infra)를 둔다 ② 기존 GET 은 유지하고 SSE 를 나란히 추가한다 ③ 파드 간 팬아웃은 Redis pub/sub.

## 배경 / 목적
경기 화면이 이닝·점수를 따라가려면 지금은 `GET /api/games` 를 클라이언트가 주기적으로 다시 불러야 한다. 서버 쪽에는 이미 py-collector 가 경기 시간대에 1분 간격으로 `games` 행을 갱신하는 라이브 폴링이 있으므로, 그 갱신을 **서버가 클라이언트로 밀어 주는 경로**를 연다.

원천 사실 두 가지가 설계를 정했다. (1) 기존 SQS 이닝 이벤트(`inning-events/`)는 이닝 **전환** 때만, 그것도 "막 끝난 이닝의 안타"만 담아 이닝 중간 득점을 알 수 없다 → 수집기에 "이닝·점수·상태 중 하나라도 바뀌면 지금 상태를 적재"하는 별도 경로(`game-state-events/`)를 추가했다. (2) SQS 는 pub/sub 이 아니라 소비자가 둘이면 메시지를 나눠 갖는다 → quiz-app 이 정산용으로 소비하는 큐를 같이 읽지 않고 전용 큐를 둔다. user-app 도 HPA 로 파드가 2개일 수 있어, 큐를 받은 파드가 Redis 로 재발행해 모든 파드의 구독자에게 닿게 한다(quiz 채팅 SSE 와 같은 구조).

## 범위
- 포함
  - `GET /games/subscribe`(실제 경로 `/api/games/subscribe`), **인증 불필요** — `GET /api/games` 의 실시간 판
  - `GET /games/support/subscribe`(실제 경로 `/api/games/support/subscribe`), **인증 필수** — `GET /api/games/support` 의 실시간 판
  - 연결 직후 `snapshot`(짝 GET 과 같은 13필드 배열) → 이후 `game-update`(갱신 경기 1건 + 달라진 필드 + 관측 시각)
  - SQS 리스너(`GameStateEventListener`) · 파드 간 팬아웃(Redis `game:events`, prod 전용) · 하트비트 15초
- 제외
  - **기존 GET 제거·변경** — 유지한다(폴백·최초 진입 1회 조회)
  - **경기 단위 구독**(`?gameId=`) — 날짜 단위만. `GET /api/games` 와 같은 단위
  - **쿼리 스트링 토큰 인증** — `JwtAuthenticationFilter` 는 헤더만 본다. 웹 `EventSource` 의 헤더 제약은 클라이언트가 `fetch` 기반 SSE 로 푼다
  - **`Last-Event-ID` 재전송** — 재접속 시 `snapshot` 이 끊긴 구간을 흡수한다
  - **이벤트 축약·배칭** — 수집기가 감지한 변화 1건 = 이벤트 1건
  - **선발 라인업 갱신**(`GET /games/lineup`) — 이번 범위 밖
  - 수집기·인프라 변경 자체의 계약(dev_ai·dev_infra 각 PR)

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-GRT-1 | 이벤트 | WHEN `GET /api/games/subscribe` 요청이 들어오면, THE 시스템 SHALL 200 과 `Content-Type: text/event-stream` 으로 스트림을 연다 | 헤더 없이 `GET /api/games/subscribe?date=2026-08-01` → 200, `text/event-stream`(`ApiResponse` 래퍼 없음) |
| USER-GRT-2 | 유비쿼터스 | THE 시스템 SHALL 연결 직후 `snapshot` 이벤트 1개를 보내고, 그 `data` 는 같은 `date` 의 `GET /api/games` 응답 `data` 와 항목·순서·13필드가 동일하다 | 같은 시각에 둘을 받아 비교하면 JSON 배열이 동일 |
| USER-GRT-3 | 이벤트 | WHEN 구독한 날짜의 경기에 대해 수집기가 이닝·점수·상태 변화를 적재하면, THE 시스템 SHALL 그 경기를 DB 에서 다시 읽어 `game-update` 이벤트로 보낸다 | 라이브 경기 득점 → 1분 폴링 뒤 수 초 안에 `game-update` 수신, `data.game.homeTeamScore` 가 DB 값과 일치 |
| USER-GRT-4 | 유비쿼터스 | THE 시스템 SHALL `game-update` 의 `data.game` 을 `GET /api/games` 항목과 같은 13필드로 보낸다 | 키 집합이 `{gameId, stadium, homeTeam, homeTeamId, awayTeam, awayTeamId, homeTeamScore, awayTeamScore, gameDate, gameState, cancelReason, inning, inningHalf}` 와 정확히 일치 |
| USER-GRT-5 | 유비쿼터스 | THE 시스템 SHALL `game-update` 에 `changed`(달라진 필드 이름 배열, 비어 있지 않음)와 `observedAt`(수집기 관측 시각 UTC ISO-8601)을 함께 보낸다 | 득점 이벤트의 `changed` 가 `["homeScore"]` 또는 `["awayScore"]`, 이닝 전환이 `["inning"]`, 종료가 `["inning","status"]` 를 포함 |
| USER-GRT-6 | 유비쿼터스 | THE 시스템 SHALL `game-update` 를 그 경기의 **날짜** 구독자 전원에게 보낸다 | 같은 날짜로 구독한 연결 N개가 모두 같은 이벤트를 받는다 |
| USER-GRT-7 | 유비쿼터스 | THE 시스템 SHALL 다른 날짜 구독자에게는 그 이벤트를 보내지 않는다 | `date=2026-08-02` 구독은 2026-08-01 경기 갱신을 받지 않는다 |
| USER-GRT-8 | 유비쿼터스 | THE 시스템 SHALL 15초마다 주석 프레임(`:ping`)을 보내 유휴 연결을 유지한다 | 갱신이 없는 2분 동안 `:ping` 이 8회 전후 수신, CloudFront 경유 연결이 끊기지 않음 |
| USER-GRT-9 | 복합 | WHILE 스트림이 열린 상태에서, WHEN 서버 타임아웃(30분)에 이르면, THE 시스템 SHALL 스트림을 정상 종료한다 | 30분 뒤 연결 종료, 클라이언트 재접속 시 `snapshot` 부터 다시 받는다 |
| USER-GRT-10 | 복합 | WHILE 인증 없이, WHEN `date` 없이 요청이 들어오면, THE 시스템 SHALL `Asia/Seoul` 기준 오늘로 구독한다 | `Clock.fixed` 로 KST 2026-08-02 00:30(UTC 전날) → `2026-08-02` 키로 등록·`getGames(2026-08-02)` 호출 |
| USER-GRT-11 | 예외 | IF `date` 형식이 어긋나면, THEN THE 시스템 SHALL 스트림을 열지 않고 400 `ApiResponse` 래퍼를 반환한다 | `?date=20260801` → 400, `success:false`, 구독 서비스 미호출 |
| USER-GRT-12 | 예외 | IF `GET /api/games/subscribe` 에 GET 이외 메서드로 인증 없이 요청이 들어오면, THEN THE 시스템 SHALL 401 을 반환한다 | `POST /api/games/subscribe` → 401 `"인증이 필요합니다."`(405 아님) |
| USER-GRT-13 | 이벤트 | WHEN 유효한 access 토큰과 함께 `GET /api/games/support/subscribe` 요청이 들어오면, THE 시스템 SHALL 200 `text/event-stream` 으로 열고 principal 의 계정 id 로 응원 구단을 판정한다 | 토큰 계정 1 → `subscribeSupportTeam(1, date)` 호출, 200 |
| USER-GRT-14 | 예외 | IF `Authorization` 헤더 없이·무효 토큰·refresh 토큰·탈퇴 계정으로 `GET /api/games/support/subscribe` 요청이 들어오면, THEN THE 시스템 SHALL 스트림을 열지 않고 401 과 `"인증이 필요합니다."` 를 반환한다 | 헤더 없이 → 401 `ApiResponse` 래퍼(`GET /api/games/support` 와 동일) |
| USER-GRT-15 | 유비쿼터스 | THE 시스템 SHALL `support/subscribe` 의 `snapshot` 을 같은 `date` 의 `GET /api/games/support` 응답 `data` 와 동일하게 보낸다 | 응원 구단 6 계정 → `snapshot` 이 `homeTeamId == 6 || awayTeamId == 6` 인 경기만 |
| USER-GRT-16 | 유비쿼터스 | THE 시스템 SHALL `support/subscribe` 구독자에게 활성 응원 구단이 홈 또는 원정인 경기의 `game-update` 만 보낸다 | 구단 6 구독자는 6 이 참여하지 않는 경기 갱신을 받지 않고, 홈·원정 어느 쪽이든 참여 경기는 받는다 |
| USER-GRT-17 | 예외 | IF 요청 계정에 활성 응원 구단이 없으면(`oppose IS NULL` 행 없음), THEN THE 시스템 SHALL 200 으로 열고 빈 배열 `snapshot` 하나를 보낸 뒤 스트림을 닫는다 | 응원 구단 없는 계정 → `snapshot` `[]` 수신 직후 종료, 레지스트리 등록 없음 |
| USER-GRT-18 | 유비쿼터스 | THE 시스템 SHALL `support/subscribe` 의 응원 구단 조회를 연결당 1회만 수행하고 연결 시점 구단에 고정한다 | 연결 중 `/api/support` 로 구단을 바꿔도 기존 스트림은 옛 구단 경기를 계속 보낸다; `user_support_teams` 조회 1회 |
| USER-GRT-19 | 유비쿼터스 | THE 시스템 SHALL 파드가 여러 개여도 모든 파드의 구독자에게 같은 `game-update` 를 전달한다 | prod(2파드)에서 각 파드에 붙은 구독이 동일 이벤트 수신(SQS 수신 파드 → Redis `game:events` → 전 파드) |
| USER-GRT-20 | 예외 | IF 큐 URL 설정(`user.game-events.sqs-queue-url`)이 비어 있으면, THEN THE 시스템 SHALL 리스너를 시작하지 않고 정상 기동한다 | 로컬 기동 로그에 미설정 경고 1줄, `snapshot` 은 정상 |
| USER-GRT-21 | 예외 | IF 알림이 가리키는 경기 행이 없거나 처리 중 예외가 나면, THEN THE 시스템 SHALL 행 없음은 메시지를 지우고 건너뛰며 예외는 메시지를 지우지 않아 재수신되게 한다 | 단위 테스트 `GameStateEventListenerTest` |
| USER-GRT-22 | 유비쿼터스 | THE 시스템 SHALL 기존 `GET /api/games`·`GET /api/games/support`·`GET /api/games/lineup` 의 인증 정책과 응답을 변경하지 않는다 | 헤더 없이 `GET /api/games` → 여전히 200, 항목 13필드 불변 |

### 표기 근거 (요구사항 아님)
1. **`game-update` 의 `game` 을 수집기 문서가 아니라 DB 에서 다시 읽는 이유**: 수집기 JSON 과 `GameResponse` 두 표현을 따로 맞춰 유지하지 않기 위해서다. 수집기는 DB upsert **직후** 문서를 쓰므로 알림 시점의 DB 행은 문서와 같거나 더 새롭다. 반대로 두 번 연속 변화가 났을 때 첫 알림이 두 번째 상태를 실어 갈 수 있는데, 어차피 "통째 교체" 규칙이라 결과는 같다.
2. **해상도는 1분이다.** 원천이 수집기의 1분 폴링이라 그보다 촘촘할 수 없다. 1초 단위가 필요해지면 이 설계가 아니라 수집기 주기를 바꿔야 한다.
3. **`support/subscribe` 의 "응원 구단 없음 → 열고 닫기"**: 401/400 이 아닌 이유는 `GET /api/games/support` 가 같은 경우 200 + 빈 배열인 것과 뜻을 맞추기 위해서다(USER-GSP-16). 단 `EventSource` 는 서버 종료를 재접속 신호로 보므로 클라이언트가 멈춰야 한다 — `docs/api/game.md` 에 경고로 적었다.

## 결정 기록
- 2026-10-09 (사용자 확인): 점수 변동 이벤트는 수집기 확장 + 전용 큐로 만든다(대안 "기존 이닝 큐만 재사용"은 이닝 중간 득점을 못 밀어 기각). 기존 GET 유지 + SSE 추가(대안 "GET 제거·교체"는 FE/APP 동시 배포를 강제해 기각). 파드 간 팬아웃은 Redis pub/sub(대안 "파드 1개 고정"은 경기 시간대 스케일아웃을 포기해 기각).
- 하트비트를 `@Scheduled` 가 아니라 전용 스레드로 둔 이유: user 모듈의 `@EnableScheduling` 은 정리 스위치에 조건부라 로컬 기본 꺼짐이다. 거기에 기대면 로컬에서 하트비트가 안 돌아 30초 안에 끊긴다.
