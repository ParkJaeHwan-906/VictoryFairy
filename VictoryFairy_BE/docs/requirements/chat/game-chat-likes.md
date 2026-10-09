# 경기 실시간 좋아요(응원 애니메이션 신호) 요구사항
> 상태: 승인됨 (2026-10-09) · 모듈: chat · 최종 수정: 2026-10-09

> **개정 2026-10-09 (1차)**: 초안의 미해결 질문 7건이 사용자 결정으로 닫혔다. 가장 큰 변경은 두 가지다. (1) 좋아요는 **채팅방이 아니라 경기(gameId) 단위 신호**가 되었다. 그래서 방 존재 확인·지연 생성·404·방 확인 503을 전부 뺐고(LK-4·5·6 결번), gameId는 형식만 검사한다(LK-48·49). (2) 발행은 **클릭마다 즉시 단일 구단 코드로** 하고(LK-41, 구 LK-19·20 결번), 게이트웨이 전달은 150ms 고정 주기 대신 **leading-edge 스로틀**(LK-42~47, 구 LK-25·26 결번)로 바꿨다. 
>
> **개정 2026-10-09 (2차, 승인)**: 1차 개정의 미해결 2건을 사용자가 결정했다. gameId 형식은 `^[A-Za-z0-9]{1,20}$`(LK-48), 202는 PUBLISH를 기다리지 않는다(LK-50). 그 결과 생기는 비동기 발행 대기열에 상한을 두고, 넘친 좋아요는 버린다(LK-51). 그 WARN 로그는 빈도를 제한한다(LK-52). 남은 `(가정)`은 없다.

## 배경 / 목적
경기를 보면서 열광을 메시지 없이 표현하는 수단이다. 좋아요는 **화면에 애니메이션을 띄우는 일회성 신호**라서 개수나 기록이 의미가 없다. 그래서 메시지 경로(Kafka·Redis Stream·dedup)를 타지 않고, **Redis pub/sub으로 흘려보내고 잃어도 되는 것**으로 설계한다. 클릭이 몰려도 각 접속자가 받는 이벤트 빈도에는 상한이 있다(초당 약 10회). 조용한 방에서는 지연 없이 바로 보인다.

이 문서는 승인된 [`game-chat.md`](game-chat.md)(CHAT-GC-*, 운영 가동 중) **위에 더하는** 계약이다. 인증·SSE 구독·역할 플래그·쓰기 풀 계약은 그 문서를 그대로 전제로 하며, 여기서 바꾸지 않는다. 다만 좋아요 엔드포인트는 그 문서의 **방 존재 확인 규칙(CHAT-GC-24·29·107)을 쓰지 않는다**. 좋아요의 대상은 경기이고, 신호를 받는 쪽은 그 gameId 방을 구독 중인 SSE 연결이다.

## 범위
- 포함:
  - 전송 엔드포인트 `POST /chat/rooms/{gameId}/likes` (202, 본문 없음)
  - gameId 형식 검증(존재 확인 없음)
  - 발신자 응원 구단 조회와 앱 메모리 캐시(TTL 5분)
  - 사용자당 속도 제한(초당 10회, 파드 메모리, 초과분은 조용히 버림)
  - Redis pub/sub 채널 `chat:likes` 발행·구독
  - 게이트웨이의 방별 leading-edge 스로틀과 SSE 이벤트 `likes`
  - 역할 플래그별 동작, 설정 키, 장애 시 동작
- 제외:
  - **좋아요 개수 집계·저장·조회.** MySQL·Redis 키·Kafka 어디에도 남기지 않으며, 개수 API도 없다
  - 좋아요 취소(unlike). 누적 상태가 없으므로 취소할 대상이 없다
  - 좋아요 경로의 gameId 존재·오늘 경기 여부 확인, 방 메타 생성
  - `likes` 이벤트의 재접속 복구(Last-Event-ID)·히스토리. 놓친 좋아요는 영원히 놓친 것이다
  - 메시지 단위 좋아요(특정 msgId에 대한 반응)
  - 애니메이션의 모양·색·재생 시간. 프론트가 정한다
  - 차단(user_blocks) 관계에 따른 좋아요 숨김
  - quiz `/rt/chat/**` 쪽 좋아요(CHAT-GC-103 승계)
  - 기존 `messages`·`deleted`·`reset` 이벤트, 하트비트, 메시지 전송 계약의 변경

## 확정 전제 (사용자와 합의 완료 — 되묻지 않는다)
1. 한 사용자가 여러 번 누를 수 있다. 개수 집계·기록·저장은 **어디에도 없다**. 화면에는 애니메이션만 재생한다.
2. 전송: `POST /chat/rooms/{gameId}/likes`(외부 경로), 본문 없음, JWT 필수. 성공 시 **202 본문 없음**.
3. **좋아요는 경기(gameId) 단위 신호다.** 방 존재 확인(`chat:room:{gameId}`)과 지연 생성은 하지 않는다. gameId는 **형식만** 검사하고, 위반하면 400을 반환한다. 존재하지 않는 gameId의 좋아요도 발행된다. 다만 구독자가 없으므로 게이트웨이에서 사라진다.
4. 처리 순서: **401(인증) → 400(gameId 형식) → 응원 구단(캐시) → 속도 제한(메모리) → PUBLISH → 202**.
5. 응원 구단은 JWT에 없다(토큰에는 `uid`·`type`만 있다). uid로 DB에서 조회하되 **앱 메모리 캐시 TTL 5분**을 둔다. 구단 변경이 최대 5분 늦게 반영되는 것을 수용한다.
6. 응원 구단 조회가 비면 **202를 주고 버리며 WARN을 남긴다**(CHAT-GC-13 유지 — 이 모듈은 `SUPPORT_TEAM_REQUIRED`를 내지 않는다). DB 조회가 실패해도 **202를 주고 버린다**.
7. 구단 값은 **`teams.code`**(예: KIA=`HT`, LG=`LG`)다. `messages` 항목의 `teamCode`와 같은 값 공간이다.
8. 사용자당 속도 제한은 **초당 10회, 파드 메모리 계수**다. 초과 클릭은 **429가 아니라 조용히 버리고 202**를 준다.
9. API 파드는 **묶지 않는다.** 속도 제한을 통과한 클릭마다 즉시 `PUBLISH chat:likes`를 하며, 메시지에는 `gameId`와 **단일 구단 코드**를 싣는다. 채널은 `chat:likes` 하나다. Redis는 기존 서비스 Redis(MySQL과 같은 EC2)를 그대로 쓴다.
10. Redis 장애로 발행이 실패해도 202를 주고 그 좋아요는 버린다(경고 로그·메트릭).
11. 모든 게이트웨이 파드가 `chat:likes`를 구독한다(발행 파드 자신도 받는다). 전달은 방별 **leading-edge 스로틀**(창 기본 100ms)이다. 조용한 방은 즉시 보내고, 창 동안 온 것은 구단 코드를 중복 없이 모아 창 끝에 한 번 보낸다. 개수는 싣지 않고 발신자도 제외하지 않는다.
12. `likes` 이벤트에는 SSE `id:`를 붙이지 않는다. Last-Event-ID 복구 대상이 아니다. 기존 `messages`/`deleted`/`reset` 계약과 하트비트는 그대로다.
13. 발행은 API 역할, 구독·전달은 게이트웨이 역할이 맡는다.
14. 기본값 묶음(설정 키 이름·기본값, 메트릭 이름, 빈 조회 미캐시, Redis 복구 후 재구독 30초 상한)은 사용자가 확정했다.

### 설정 키 (확정)

| 키 | 기본값 | 쓰는 곳 |
|---|---|---|
| `chat.likes.rate-limit.per-second` | 10 | CHAT-LK-16 |
| `chat.likes.team-cache-ttl-seconds` | 300 | CHAT-LK-11·12 |
| `chat.likes.throttle-window-ms` | 100 | CHAT-LK-43~47 |
| `chat.likes.publish-queue-capacity` | 10000 | CHAT-LK-51 |

기존 `chat.gateway.batch-interval-ms`(메시지 배처, 150)와는 **별개 키**다. 메시지 배칭 간격을 바꿔도 애니메이션 리듬이 따라 바뀌지 않게 하려는 것이다. 초안의 `publish-interval-ms`와 `deliver-interval-ms`는 삭제했다(전제 9·11). 전부 환경변수(relaxed binding, 예 `CHAT_LIKES_THROTTLE_WINDOW_MS`)로 덮어쓸 수 있다.

**`publish-queue-capacity` 기본값 10000의 근거.**
- 정상 상태에서는 PUBLISH 왕복이 1ms 미만이라 대기열이 사실상 비어 있다. 상한은 **Redis 장애 동안의 메모리 보호용**이다.
- 장애 중에는 명령마다 Redis 타임아웃(2초, `spring.data.redis.timeout`)까지 대기열에 머문다. 그래서 대기열에 쌓이는 양은 "파드가 받아들이는 좋아요 수/초 × 2초"다.
- 파드당 동시 연타 사용자 500명이면 500 × 10 × 2 = 10,000건이다. 피크 경기 한 파드의 연타 인원으로 넉넉한 수준이다. 이 정도면 장애가 짧을 때 복구 직후의 좋아요를 대부분 살린다.
- 메시지 하나가 약 200B라 상한까지 차도 약 2MB다. 파드 메모리에 의미 있는 부담이 아니다.
- 반대로 더 크게 잡으면 장애가 길어질 때 오래된 좋아요가 복구 뒤 몰려 나가며 "이미 지난 응원"을 재생한다. 애니메이션 신호라 가치가 없으므로 크게 잡을 이유가 없다.

### Redis pub/sub 메시지 (파드 간 내부 계약 — 확정)

| 채널 | 메시지 | 발행 | 구독 |
|---|---|---|---|
| `chat:likes` | JSON `{"gameId":"<경로의 gameId>","teamCode":"HT"}` — 클릭 1회(속도 제한 통과분)당 1건 | API 역할 | 게이트웨이 역할 |

`chat:likes`는 **키가 아니라 채널**이다. 그래서 CHAT-GC-25(모든 `chat:*` 키에 TTL)와 자정 정리(CHAT-GC-16)의 대상이 아니다. quiz의 `realtime:events` 채널과 이름이 겹치지 않는다.

### 메트릭 (확정)

| 이름 | 의미 |
|---|---|
| `chat.likes.publish.failed` | 실패한 `PUBLISH` 횟수 |
| `chat.likes.dropped` (태그 `reason`) | 버린 좋아요 수. `reason` = `rate-limit` / `no-team` / `team-lookup-failed` / `queue-full` |

## 요구사항 (EARS)

> ID 규칙: `CHAT-LK-<n>`. `CHAT-GC-*`와는 별개 계열이며 번호는 재사용하지 않는다. **LK-4·5·6·19·20·25·26은 2026-10-09 1차 개정에서 삭제된 결번이다.** 그 개정에서 추가된 항목은 41번 이후다.
> 경로는 접두사를 포함한 외부 경로(`/chat/...`)로 적는다. 에러 응답 본문은 공통 규약(`docs/api/README.md` 1절) `ApiResponse` 래퍼다. **202 성공 응답만 본문이 없다.**

### A. 전송 — `POST /chat/rooms/{gameId}/likes`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-1 | 이벤트 | WHEN 인증 사용자가 형식에 맞는 gameId로 좋아요를 요청하면, THE 시스템 SHALL 202를 본문 없이 반환한다 | `POST /chat/rooms/20261009HTLG0/likes`(`Authorization` 헤더, 본문 없음) → 202, 응답 본문 길이 0(`ApiResponse` 래퍼 아님). 경기가 없는 날, 어제 경기 gameId, 존재하지 않는 gameId도 형식만 맞으면 202다 |
| CHAT-LK-2 | 유비쿼터스 | THE 시스템 SHALL 좋아요 판정 순서를 **401(인증) → 400(gameId 형식) → 응원 구단 조회 → 속도 제한(초과 시 버림) → PUBLISH(실패 시 버림) → 202**로 고정한다 | 미인증 + 형식 위반 gameId → 401. 인증 + 형식 위반 + 속도 초과 상태 → 400(202 아님). 형식 위반 요청은 응원 구단 SELECT·속도 계수·`PUBLISH`를 일으키지 않는다(SQL 로그·`chat.likes.dropped` 불변·MONITOR) |
| CHAT-LK-3 | 예외 | IF 좋아요 요청에 유효한 액세스 토큰이 없으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 헤더 없음·만료 토큰·리프레시 토큰 → 401 `{"success":false,"data":null,"message":"인증이 필요합니다."}`(CHAT-GC-12와 동일) |
| CHAT-LK-4 | (삭제됨 2026-10-09) | ~~방 메타 없음 → 404~~ | 좋아요는 경기 단위 신호라 방 존재를 확인하지 않는다(LK-49). 번호 재사용 금지 |
| CHAT-LK-5 | (삭제됨 2026-10-09) | ~~지연 생성(CHAT-GC-107) 적용~~ | 같은 이유. 좋아요 경로는 방 메타를 만들지 않는다. 번호 재사용 금지 |
| CHAT-LK-6 | (삭제됨 2026-10-09) | ~~방 확인 단계 Redis 장애 → 503~~ | 방 확인 단계가 없다. 좋아요 경로의 Redis 장애는 전부 LK-22(202 + 버림)다. 번호 재사용 금지 |
| CHAT-LK-7 | 유비쿼터스 | THE 시스템 SHALL 좋아요 요청의 본문과 `Content-Type`을 읽지 않고 같은 결과를 낸다 | 본문 `{"x":1}` + `Content-Type: application/json` → 202. `Content-Type` 없음 → 202. 415·400이 나지 않는다 |
| CHAT-LK-8 | 유비쿼터스 | THE 시스템 SHALL 좋아요를 어디에도 저장하거나 집계하지 않는다 | 좋아요 100회 뒤 MySQL 행 증가 0, `chat-messages`·`chat-control` 레코드 0. 좋아요 경로에서 Redis MONITOR에 찍히는 명령은 `PUBLISH chat:likes`뿐이다(`GET chat:room:*`·`SET`·`SADD`·`INCR` 없음). 어떤 응답·이벤트에도 개수 필드가 없다 |
| CHAT-LK-9 | 유비쿼터스 | THE 시스템 SHALL 발신자 응원 구단과 경기 구단의 일치 여부와 무관하게 좋아요를 받는다 | KIA 응원 계정이 LG-두산 경기 gameId로 좋아요 → 202, 그 방 구독자에게 `HT`가 실린 `likes`. 이 엔드포인트는 `CHATROOM_TEAM_MISMATCH`를 내지 않는다(CHAT-GC-13 승계) |
| CHAT-LK-48 | 예외 | IF 경로의 gameId가 1~20자의 영문 대소문자·숫자(`^[A-Za-z0-9]{1,20}$`)가 아니면, THEN THE 시스템 SHALL 400을 반환한다 | `POST /chat/rooms/abc-def/likes` → 400 `{"success":false,"data":{"gameId":"<위반 메시지>"},"message":"입력값이 올바르지 않습니다."}`(공유 `GlobalExceptionHandler`의 경로 변수 검증 형식). 21자 gameId → 400. `20260809HTLG02026`(17자) → 202. 규칙의 근거: `games.naver_game_id` 컬럼 길이 20, 실제 값은 영숫자뿐. naver 형식이 바뀌어 다른 문자가 들어오면 이 규칙도 함께 개정해야 한다 |
| CHAT-LK-49 | 유비쿼터스 | THE 시스템 SHALL 좋아요 경로에서 gameId의 존재·오늘 경기 여부를 확인하지 않는다 | 좋아요 요청 시 `games` SELECT 0회(SQL 로그), Redis `GET chat:room:*`·`SET NX`·`SADD chat:rooms:*` 0회. 방 메타가 없는 gameId도 202이고, 그 요청 뒤에도 `EXISTS chat:room:{gameId}`는 0 그대로다 |
| CHAT-LK-50 | 유비쿼터스 | THE 시스템 SHALL 202 응답을 `PUBLISH` 완료를 기다리지 않고 반환한다 | Redis를 멈춘 상태(명령 타임아웃 2초)에서 좋아요 → 202 응답 시간이 Redis 타임아웃만큼 늘지 않는다. 같은 상태에서 좋아요를 연타해도 같은 파드의 메시지 전송·구독 응답 시간에 영향이 없다 |

### B. 응원 구단 조회

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-10 | 이벤트 | WHEN gameId 형식 검증을 통과하면, THE 시스템 SHALL 발신자의 응원 구단 `teams.code`를 메시지 전송(CHAT-GC-63)과 같은 출처(`user_support_team`의 응원 행)에서 구한다 | KIA 응원 계정의 좋아요 → 발행 메시지 `"teamCode":"HT"`. 같은 계정이 보낸 메시지의 `teamCode`와 같은 값이다 |
| CHAT-LK-11 | 유비쿼터스 | THE 시스템 SHALL 사용자별 응원 구단 조회 결과를 API 파드 메모리에 `team-cache-ttl-seconds`(기본 300) 동안 캐시한다 | 같은 사용자가 한 파드에 5분 안에 좋아요 100회 → `user_support_team` SELECT 1회(SQL 로그). Redis에 캐시 키가 생기지 않는다 |
| CHAT-LK-12 | 유비쿼터스 | THE 시스템 SHALL 응원 구단 변경을 캐시 만료 전에는 좋아요에 반영하지 않는다 | KIA로 좋아요 → 구단을 LG로 변경 → 즉시 좋아요 → `HT`(옛 값). 첫 조회부터 300초가 지난 뒤 좋아요 → `LG`. 반영 지연의 상한은 TTL이다. 파드마다 캐시가 따로라 파드별로 반영 시점이 다를 수 있다 |
| CHAT-LK-13 | 예외 | IF 응원 구단 조회 결과가 비어 있으면, THEN THE 시스템 SHALL 그 좋아요를 발행하지 않고 WARN 로그와 함께 202를 반환한다 | 응원 행이 없는 계정(정상 경로에서는 생기지 않음) → 202, `chat:likes` 발행 없음, WARN 로그에 계정 id, `chat.likes.dropped{reason=no-team}` +1. `SUPPORT_TEAM_REQUIRED`를 내지 않는다(CHAT-GC-13) |
| CHAT-LK-14 | 유비쿼터스 | THE 시스템 SHALL 비어 있는 조회 결과는 캐시하지 않는다 | 응원 행이 없던 계정이 구단을 설정한 직후 좋아요 → 5분을 기다리지 않고 그 구단 코드로 발행된다 |
| CHAT-LK-15 | 예외 | IF 응원 구단 DB 조회가 예외로 실패하면, THEN THE 시스템 SHALL 그 좋아요를 버리고 WARN 로그와 함께 202를 반환한다 | DB 연결을 끊은 상태에서 캐시에 없는 사용자의 좋아요 → 202, 발행 없음, `chat.likes.dropped{reason=team-lookup-failed}` +1. 캐시에 든 사용자는 영향 없이 발행된다 |

### C. 속도 제한

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-16 | 예외 | IF 같은 사용자의 좋아요가 1초 고정 창 안에서 `chat.likes.rate-limit.per-second`(기본 10)를 넘으면, THEN THE 시스템 SHALL 초과분을 발행하지 않고 202를 반환한다 | 한 파드에 같은 초 15회 → 15회 전부 202, `PUBLISH chat:likes` 10회, `chat.likes.dropped{reason=rate-limit}` +5. 429 `CHAT_RATE_LIMIT_EXCEEDED`가 나지 않는다. 창은 그 사용자의 첫 좋아요 시점부터 1초다 |
| CHAT-LK-17 | 유비쿼터스 | THE 시스템 SHALL 좋아요 속도 창을 사용자 단위로 경기 불문 공유하고, 메시지 전송 속도 제한(CHAT-GC-58, `chat:rate:{userAccountId}`)과는 따로 센다 | A경기 6회 + B경기 6회를 같은 초에 → 뒤의 2회가 버려진다. 같은 초에 좋아요 10회 뒤 메시지 전송 → 202(429 아님). 메시지 3회 뒤 좋아요 → 202·정상 발행 |
| CHAT-LK-18 | 유비쿼터스 | THE 시스템 SHALL 좋아요 속도 제한을 파드 메모리에서 세고 Redis 명령을 쓰지 않는다 | 좋아요 경로에서 MONITOR에 `INCR`·`EXPIRE`가 없다. 파드가 N개이고 요청이 고루 분산되면 사용자당 실효 상한은 최대 10×N/초다(알려진 결과 4) |

### D. 발행 (API 역할)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-19 | (삭제됨 2026-10-09) | ~~API 파드에서 100ms 묶어 방당 PUBLISH 1회~~ | 사용자 결정: 묶지 않는다(LK-41). 번호 재사용 금지 |
| CHAT-LK-20 | (삭제됨 2026-10-09) | ~~빈 발행 주기에는 발행 안 함~~ | 발행 주기가 없어졌다. 번호 재사용 금지 |
| CHAT-LK-41 | 이벤트 | WHEN 좋아요가 속도 제한을 통과하면, THE 시스템 SHALL 그 즉시 `PUBLISH chat:likes`를 1회 수행한다 | 한 파드에 사용자 3명이 각 1회 좋아요 → MONITOR에 `PUBLISH chat:likes` 3건, 각 메시지의 `teamCode`는 그 사용자의 구단 1개. API 파드 쪽 묶음·지연이 없다 |
| CHAT-LK-21 | 유비쿼터스 | THE 시스템 SHALL 좋아요를 채널 `chat:likes` 하나로만 발행하고, 메시지를 `{"gameId","teamCode"}` 두 필드 JSON으로 구성한다 | `SUBSCRIBE chat:likes`로 관찰한 메시지가 전부 `{"gameId":"20261009HTLG0","teamCode":"HT"}` 형태다. 방별·구단별 채널(`chat:likes:{gameId}` 등)이 없다. 메시지에 계정 id·닉네임·개수가 없다(CHAT-GC-14 승계) |
| CHAT-LK-22 | 예외 | IF `PUBLISH`가 실패하면(Redis 장애), THEN THE 시스템 SHALL 그 좋아요를 재시도 없이 버리고 WARN 로그를 남긴다 | Redis 정지 → 좋아요 202, 그 좋아요는 Redis 복구 후에도 다시 발행되지 않는다(MONITOR) |
| CHAT-LK-51 | 예외 | IF 아직 완료되지 않은 PUBLISH가 `publish-queue-capacity`(기본 10000)만큼 쌓인 상태에서 좋아요가 속도 제한을 통과하면, THEN THE 시스템 SHALL 그 좋아요를 발행 대기열에 넣지 않고 버린다 | `CHAT_LIKES_PUBLISH_QUEUE_CAPACITY=5`, Redis 정지 상태에서 서로 다른 사용자 20명이 각 1회 좋아요 → 20회 전부 202. `chat.likes.dropped{reason=queue-full}`가 15 이상 증가한다. 파드 힙 사용량이 대기열 상한을 넘어 늘지 않는다. Redis 복구 후 버린 좋아요는 발행되지 않는다(MONITOR) |
| CHAT-LK-52 | 예외 | IF LK-51에 따라 좋아요를 버리면, THEN THE 시스템 SHALL WARN 로그를 10초에 최대 1줄만 남기고, 그 줄에 직전 로그 이후 버린 건수를 싣는다 | Redis 정지 상태에서 30초 동안 대기열 초과 좋아요 수천 건 → 그 사유의 WARN이 3~4줄이고, 각 줄에 누적 버림 건수가 있다. 건수 자체는 메트릭(`chat.likes.dropped{reason=queue-full}`)으로 정확히 남는다 |
| CHAT-LK-23 | 예외 | IF `PUBLISH`가 실패하면, THEN THE 시스템 SHALL 실패 카운터 메트릭을 1 올린다 | `GET /chat/actuator/metrics/chat.likes.publish.failed`가 실패한 `PUBLISH` 횟수만큼 증가한다 |

### E. 전달 (게이트웨이 역할) — SSE `likes`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-24 | 유비쿼터스 | THE 시스템 SHALL 게이트웨이 역할 파드마다 `chat:likes`를 구독하고, 자기 파드가 발행한 메시지도 받는다 | 파드 1개(역할 기본값): 같은 파드의 구독자가 자기 좋아요를 `likes`로 받는다. 파드 2개: A로 좋아요 → B에 붙은 그 방 구독자가 받는다. Redis `PUBSUB NUMSUB chat:likes` = 게이트웨이 파드 수 |
| CHAT-LK-25 | (삭제됨 2026-10-09) | ~~방별 150ms 고정 주기 묶음 전송~~ | 사용자 결정: leading-edge 스로틀(LK-42~47)로 교체했다. 번호 재사용 금지 |
| CHAT-LK-26 | (삭제됨 2026-10-09) | ~~빈 주기에는 미전송~~ | 고정 주기가 없어졌다. 같은 성질은 LK-47이 맡는다. 번호 재사용 금지 |
| CHAT-LK-42 | 이벤트 | WHEN 스로틀 창이 열려 있지 않은 방(조용한 방)으로 좋아요 메시지를 받으면, THE 시스템 SHALL 그 구단 코드 하나를 담은 `likes` 이벤트를 그 방의 그 파드 구독자 전원에게 즉시 보낸다 | 1초 넘게 좋아요가 없던 방에 KIA 좋아요 1회 → 구독자마다 `event: likes` / `data: ["HT"]`. 수신까지 서버 측 대기가 없다(스로틀 창을 기다리지 않는다) |
| CHAT-LK-43 | 이벤트 | WHEN LK-42에 따라 즉시 전송하면, THE 시스템 SHALL 그 방에 `throttle-window-ms`(기본 100) 길이의 스로틀 창을 연다 | 즉시 전송 직후 50ms 시점에 온 좋아요는 즉시 나가지 않는다(LK-44) |
| CHAT-LK-44 | 복합 | WHILE 그 방의 스로틀 창이 열려 있는 동안, WHEN 좋아요 메시지를 받으면, THE 시스템 SHALL 그 구단 코드를 창의 모음에 중복 없이 넣고 즉시 보내지 않는다 | 창 안에 `HT`·`HT`·`LG`·`OB`·`HT` 수신 → 창이 끝나기 전에는 `likes` 프레임이 없다. 모음은 {`HT`,`LG`,`OB`}다 |
| CHAT-LK-45 | 이벤트 | WHEN 스로틀 창이 끝났을 때 모음이 비어 있지 않으면, THE 시스템 SHALL 모음 전체를 배열 하나로 담은 `likes` 이벤트를 그 방의 그 파드 구독자 전원에게 보낸다 | LK-44 예시에서 창이 끝나는 시점에 `event: likes` / `data: ["HT","LG","OB"]` 1프레임(배열 순서는 계약 아님) |
| CHAT-LK-46 | 이벤트 | WHEN LK-45에 따라 창 끝에 전송하면, THE 시스템 SHALL 그 방에 새 스로틀 창을 연다 | 좋아요가 3초 동안 끊임없이 들어오는 방 → 구독자당 `likes` 프레임이 약 100ms 간격으로 오고, 3초 동안 31개 이하(즉시 1 + 창 끝 30)다. 각 좋아요의 서버 측 대기는 100ms 이하다 |
| CHAT-LK-47 | 예외 | IF 스로틀 창이 끝났을 때 모음이 비어 있으면, THEN THE 시스템 SHALL `likes` 이벤트를 보내지 않고 그 방을 조용한 상태로 되돌린다 | 좋아요 1회 뒤 아무것도 안 오면 → 즉시 프레임 1개 뒤에는 `likes`가 없다(`data: []` 프레임 없음). 다음 좋아요는 LK-42에 따라 다시 즉시 나간다 |
| CHAT-LK-27 | 유비쿼터스 | THE 시스템 SHALL `likes` 이벤트의 `data`를 중복 없는 구단 코드 문자열 배열로만 구성하고 개수·발신자 정보를 싣지 않는다 | 한 창 안에 KIA 응원자 500명이 좋아요 → 창 끝 `data: ["HT"]`. 원소 수는 구단 수(10) 이하다. 객체·숫자·닉네임이 없다 |
| CHAT-LK-28 | 유비쿼터스 | THE 시스템 SHALL `likes` 이벤트에 SSE `id:` 필드를 붙이지 않는다 | `likes` 프레임은 `event:`·`data:` 두 줄뿐이다. `likes` 수신 후 재접속하면 클라이언트가 보내는 `Last-Event-ID`는 마지막 `messages`의 `id:` 그대로다(SSE 규약상 `id:` 없는 이벤트는 마지막 이벤트 id를 바꾸지 않는다) |
| CHAT-LK-29 | 유비쿼터스 | THE 시스템 SHALL 끊긴 동안의 좋아요를 재접속·Last-Event-ID 복구에서 재생하지 않는다 | 연결이 끊긴 사이 좋아요가 있었어도 재구독 후 복구 구간(CHAT-GC-37)에 `likes`가 없다. 재구독 이후 들어온 좋아요부터 받는다 |
| CHAT-LK-30 | 유비쿼터스 | THE 시스템 SHALL `likes`에서 발신자 본인의 좋아요를 빼지 않는다 | 방에 A만 구독 중일 때 A가 좋아요 → A가 `likes`(`["HT"]`)를 받는다. 메시지의 발신자 제외(CHAT-GC-67·88)와 다르다 |
| CHAT-LK-31 | 유비쿼터스 | THE 시스템 SHALL `likes` 전달에 차단 관계 필터를 적용하지 않는다 | A가 B를 차단해도 B의 구단 코드가 A의 `likes`에 섞인다(발신자 식별 정보가 없어 가릴 대상도 없다) |
| CHAT-LK-32 | 예외 | IF 그 파드에 해당 gameId 방의 구독자가 없을 때 좋아요 메시지를 받으면, THEN THE 시스템 SHALL 그 메시지를 버리고 스로틀 창도 열지 않는다 | 구독자가 없던 방에 좋아요 → 그 뒤 새로 구독한 사용자는 그 좋아요를 받지 않고, 다음 좋아요는 LK-42에 따라 즉시 받는다. 존재하지 않는 gameId의 메시지가 와도 오류 없이 버린다 |
| CHAT-LK-33 | 유비쿼터스 | THE 시스템 SHALL `likes` 도입 뒤에도 `messages`·`deleted`·`reset`·`:connected`·`:ping`의 형식·`id:` 규칙·주기를 바꾸지 않는다 | 좋아요가 쏟아지는 방에서도 `messages`의 `id:`·배열 규칙(CHAT-GC-87, 150ms 배칭), `deleted`(CHAT-GC-91), `:ping` 15초(CHAT-GC-32)가 기존 그대로다. `docs/api/game-chat.md` "SSE 이벤트" 표의 기존 행은 수정 없이 `likes` 행이 추가될 뿐이다 |
| CHAT-LK-34 | 유비쿼터스 | THE 시스템 SHALL `likes` 프레임 쓰기도 기존 쓰기 풀과 `write-timeout-ms`(CHAT-GC-89·90) 규칙으로 수행한다 | Redis 구독 스레드·스로틀 타이머가 소켓에 직접 쓰지 않는다. `likes` 쓰기가 1초를 넘긴 구독자는 그 스트림만 닫히고, 같은 방 다른 구독자는 계속 받는다 |
| CHAT-LK-35 | 예외 | IF 게이트웨이의 `chat:likes` 구독 연결이 끊기면, THEN THE 시스템 SHALL 열려 있는 SSE 스트림을 닫지 않는다 | Redis 재시작 중에도 구독자 스트림이 유지되고 `messages`(Kafka 경로)·`:ping`이 계속 온다(CHAT-GC-95와 같은 성질). 끊긴 동안의 좋아요는 유실된다 |
| CHAT-LK-36 | 예외 | IF 끊겼던 Redis가 복구되면, THEN THE 시스템 SHALL 파드 재시작 없이 30초 안에 `chat:likes`를 다시 구독한다 | Redis 복구 후 30초 안에 `PUBSUB NUMSUB chat:likes`가 게이트웨이 파드 수로 돌아오고, 새 좋아요가 `likes`로 전달된다. 구독이 끊긴 동안 WARN 로그가 남는다 |
| CHAT-LK-37 | 예외 | IF `chat:likes` 메시지가 형식에 맞지 않으면(JSON 파싱 실패, `gameId` 없음, `teamCode`가 비어 있지 않은 문자열이 아님), THEN THE 시스템 SHALL 그 메시지만 버리고 WARN 로그 후 구독을 계속한다 | `PUBLISH chat:likes "garbage"` → 이후 정상 메시지가 그대로 전달되고, 어떤 구독자에게도 `garbage`가 `data:`로 새지 않는다 |

### F. 역할·설정

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-LK-38 | 선택 | WHERE API 역할이 꺼진 파드이면, THE 시스템 SHALL 좋아요 엔드포인트를 등록하지 않고 발행·응원 구단 캐시도 두지 않는다 | `chat.role.api=false` → 인증된 `POST /chat/rooms/{gameId}/likes` → 404(CHAT-GC-3과 같은 스프링 기본 응답), `PUBLISH chat:likes` 없음 |
| CHAT-LK-39 | 선택 | WHERE 게이트웨이 역할이 꺼진 파드이면, THE 시스템 SHALL `chat:likes`를 구독하지 않는다 | `chat.role.gateway=false`(history-writer 전용 파드) → `PUBSUB NUMSUB chat:likes`에 그 파드가 계산되지 않는다. API 켬 + 게이트웨이 끔 조합은 기존대로 기동 거부(CHAT-GC-6)라 "발행만 하고 받지 못하는 파드"는 생기지 않는다 |
| CHAT-LK-40 | 유비쿼터스 | THE 시스템 SHALL "설정 키" 표의 키 4개와 기본값을 갖고, 환경변수로 덮어쓸 수 있게 한다 | 설정 없이 기동 → 표의 기본값. `CHAT_LIKES_RATE_LIMIT_PER_SECOND=3`으로 기동 → 같은 초 4번째부터 버려진다(여전히 202). `CHAT_LIKES_THROTTLE_WINDOW_MS=500` → 연타 방의 `likes` 간격이 약 500ms |

## 장애 시 동작 요약 (위 항목의 교차 참조 — 새 요구사항 아님)

| 장애 | 좋아요 전송 | `likes` 전달 | 근거 |
|---|---|---|---|
| Redis 다운 | 항상 202, 발행분은 버림(LK-22·23). 응답이 Redis 타임아웃을 기다리지 않는다(LK-50). 미완료 발행이 10,000건을 넘으면 그 뒤 좋아요는 바로 버린다(LK-51·52) | 끊긴 동안 유실되며 스트림은 유지된다(LK-35). 복구 후 30초 안에 재구독(LK-36) | 전제 10 |
| DB 다운 | 캐시에 든 사용자는 정상. 캐시에 없는 사용자는 202 + 버림(LK-15) | 영향 없음 | 전제 6 |
| Kafka 다운 | 영향 없음(좋아요는 Kafka를 타지 않는다) | 영향 없음 | — |
| 게이트웨이 파드 재시작 | 영향 없음 | 재시작 사이의 좋아요는 유실되며, 재구독 후 새 좋아요부터 받는다(LK-29) | 전제 12 |

## 프론트 영향 (FE 필독)

| 항목 | 내용 |
|---|---|
| 전송 | `POST /chat/rooms/{gameId}/likes`, 본문 없음, `Authorization` 필수. 성공은 **202 본문 없음**이다. `response.json()`을 하면 파싱 오류가 난다 |
| 실패 응답 | 401, 그리고 400(gameId 형식 위반 — 정상 화면 흐름에서는 나지 않는다)뿐이다. **404·429·503은 오지 않는다** |
| 202의 의미 | "받았다"일 뿐 "전달됐다"가 아니다. 속도 초과·Redis 장애·존재하지 않는 경기여도 202다 |
| SSE 신규 이벤트 | `event: likes` / `data: ["HT","LG"]`. 조용한 방에서는 첫 좋아요가 즉시 `["HT"]` 하나로 오고, 연타 중에는 약 100ms마다 모인 구단 목록이 온다(접속자당 초당 최대 약 10회). 개수는 없고 **`id:`도 없다** |
| 구단 코드 | `teams.code` 값이다(KIA=`HT`, 두산=`OB`, SSG=`SK`, 키움=`WO`, 롯데=`LT`, 삼성=`SS`, 한화=`HH`, LG·KT·NC는 이름과 같다). `messages` 항목의 `teamCode`와 같은 매핑을 쓰면 된다 |
| 본인 좋아요 | 본인 것도 `likes`로 되돌아온다(조용한 방이면 거의 즉시). 클릭할 때 로컬 애니메이션도 띄우면 **두 번 재생**될 수 있으니 둘 중 하나를 고를 것 |
| Last-Event-ID | `likes`는 `id:`가 없어 마지막 이벤트 id를 바꾸지 않는다. 재접속할 때 기존처럼 마지막 `messages`의 `id:`를 보내면 된다. 놓친 좋아요는 복구되지 않는다 |
| 구버전 클라이언트 | `likes` 리스너를 등록하지 않은 클라이언트는 이 이벤트를 무시하므로, BE를 먼저 배포해도 된다. 단, 폴리필이 모든 이벤트를 하나의 핸들러로 받으면서 `event` 이름을 보지 않는 구현이면 `likes`의 `data`(문자열 배열)를 메시지 배열로 오해할 수 있다. 이 경우인지 확인이 필요하다 |
| 클라이언트 쓰로틀 | 서버가 초당 10회를 넘는 클릭을 버리므로, 연타 시 클라이언트에서 요청을 줄여 보내도 화면 결과는 같다(선택) |

## 알려진 결과 (결함이 아니라 알고 택한 것)
1. **좋아요는 유실될 수 있다.** pub/sub은 fire-and-forget이라 구독이 끊긴 순간, 게이트웨이 재시작, Redis 장애 중의 좋아요는 사라진다. 애니메이션 신호라 수용한다(전제 10).
2. **클릭에서 화면까지의 서버 측 지연**은 조용한 방이면 0(즉시 전송), 연타 중인 방이면 최대 `throttle-window-ms`(100ms)다. 네트워크·Redis 왕복은 별도다.
3. **구단 변경은 최대 5분 늦게 반영된다**(LK-12). 파드마다 캐시가 따로라 같은 사용자의 좋아요가 잠시 두 구단으로 섞여 보일 수 있다.
4. **속도 제한은 파드 단위다**(LK-18). 파드 N개에 요청이 분산되면 사용자당 최대 10×N/초가 통과하고 그만큼 `PUBLISH`가 생긴다. 접속자가 받는 이벤트 빈도는 스로틀이 정하므로(초당 약 10회) 늘지 않는다. 늘어나는 것은 Redis `PUBLISH` 수와 게이트웨이 수신량뿐이다.
5. **Redis `PUBLISH` 수는 클릭 수에 비례한다**(LK-41 — 묶지 않음). 상한은 (동시 연타 사용자 수 × 10 × 파드 수)/초이고, 그 메시지가 게이트웨이 파드 수만큼 복제된다. Redis가 MySQL과 같은 EC2에 있으므로 피크 경기의 부하 관찰 대상이다.
6. **존재하지 않거나 오늘이 아닌 gameId의 좋아요도 발행된다**(LK-1·49). 구독자가 없으면 게이트웨이에서 사라질 뿐이다(LK-32). 이 경로의 Redis 부하는 사용자당 속도 제한으로만 제한된다.
7. **어제 경기 방의 열린 스트림도 `likes`를 받을 수 있다.** 자정 정리 뒤에도 CHAT-GC-44에 따라 스트림은 유지되고, 좋아요는 방 존재를 보지 않으므로 그 gameId로 온 좋아요가 전달된다. 새 구독은 CHAT-GC-31에 따라 404이므로 이 상황은 이미 열려 있던 스트림에만 생긴다.
8. **`likes`와 `messages`의 상대 순서는 보장하지 않는다.** 서로 다른 경로(Redis pub/sub와 Kafka)와 다른 타이머를 탄다.
9. **파드마다 스로틀이 독립적이다.** 같은 방이라도 다른 게이트웨이 파드에 붙은 구독자는 `likes`를 다른 묶음·다른 시각(최대 100ms 차이)으로 받는다.

## 미해결 질문
없음. 1차 개정의 2건(gameId 형식, 202의 PUBLISH 대기 여부)은 2026-10-09 사용자 결정으로 닫혔다(LK-48·50·51·52).

## 기존 정책과의 충돌 / 계약 성립 제약 (구현 방법 지시가 아니라 지켜야 할 사실)
1. **이 엔드포인트는 CHAT-GC-24·29·107(방 단위 경로의 404·503·지연 생성)의 예외다.** game-chat.md는 방 단위 경로가 모두 방 메타로 존재를 확인한다고 전제한다. 좋아요는 같은 `/chat/rooms/{gameId}/**` 경로 아래 있지만 그 규칙을 따르지 않는다(전제 3). game-chat.md는 수정하지 않았다. 승인 후 `docs/api/game-chat.md`의 "방의 존재 = Redis 방 메타"·"Redis 장애 시 503" 특이사항 문장에 좋아요 예외를 명시해야 한다(api-documenter). 모듈 문서(context-keeper)도 마찬가지다.
2. **`SUPPORT_TEAM_REQUIRED`를 내지 않는다**(LK-13). CHAT-GC-13·전제 5를 그대로 유지한다.
3. **CHAT-GC-6(API 켬 + 게이트웨이 끔 조합의 기동 거부) 덕분에 "발행은 하는데 받지 못하는 파드"가 생기지 않는다.** 이 조합 규칙을 완화하면 LK-24의 "발행 파드 자신도 받는다"가 깨질 수 있다.
4. **응원 구단 조회는 메시지 전송과 같은 출처**(`user_support_team`의 응원 행, `teams.code`)여야 한다. 그래야 같은 사용자의 `messages.teamCode`와 `likes` 코드가 어긋나지 않는다. `UserSupportTeam.team`은 LAZY라 코드를 트랜잭션 안에서 읽어야 한다(game-chat.md 제약 6과 같은 함정).
5. **`chat:likes`는 채널이라 키 TTL 규칙(CHAT-GC-25)과 자정 정리 목록(CHAT-GC-16)의 대상이 아니다.** 좋아요 경로는 Redis 키를 하나도 만들지 않는다(LK-8·18·49).
6. **gameId 형식 검증의 400은 공유 `GlobalExceptionHandler`의 경로 변수 검증 형식(`data`=파라미터명→메시지)을 따른다.** 같은 핸들러의 주석에 따르면, 컨트롤러에 `@Validated`를 붙이면 내장 검증 대신 AOP 검증이 돌아 400이 아니라 500(`ConstraintViolationException`)이 된다. 이는 web-support의 기존 함정이다.
7. 엔드포인트 수가 7개에서 8개로 늘고, SSE 이벤트가 하나 추가된다. `docs/api/game-chat.md`의 "엔드포인트 7개"와 SSE 이벤트 표는 구현 후 api-documenter가 갱신한다.
