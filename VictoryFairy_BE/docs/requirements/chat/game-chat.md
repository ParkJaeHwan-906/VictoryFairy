# 경기별 채팅(Kafka + Redis Stream) 요구사항
> 상태: 승인됨(2026-10-09) · 모듈: chat (신규 Gradle 모듈) · 최종 수정: 2026-10-09

> **개정 2026-10-09 — 초안의 미해결 질문 11건·충돌 지점 3건 전부 사용자 결정으로 닫힘.** 가장 큰 변경은 **msgId의 정의**다: 초안의 `INCR chat:seq:{gameId}` 발급 번호를 폐기하고 **그 메시지의 Kafka 파티션 오프셋**을 msgId로 쓴다(한 방 = key `gameId` = 한 파티션이라 오프셋이 도착 순서대로 단조 증가하고, 프로듀서가 ack와 함께 `RecordMetadata.offset()`을 받는다). 이로써 `chat:seq:` 키, seq fail-closed 503(구 CHAT-GC-66), msgId 순서 역전으로 인한 XADD 거부(구 CHAT-GC-99의 영구 누락), Redis 재시작 시 seq 소실 위험이 전부 사라졌다. 멱등 키는 **PENDING → 확정** 두 단계가 되었고(CHAT-GC-60·105·106), 202 본문에 마스킹된 `content`가 실린다(CHAT-GC-50). 방 메타는 자정 생성에 더해 **첫 요청 시 지연 생성**된다(CHAT-GC-107). 욕설 탐지 장치는 새 라이브러리 모듈 `:profanity`로 추출한다("선행 리팩터" 절). 구 CHAT-GC-59·66은 결번(삭제됨)이다. 남은 `(가정)`은 dedup 키 값의 직렬화 형식 하나(CHAT-GC-106)뿐이다.

## 배경 / 목적
구단별 영구 채팅(quiz 앱, MySQL `chats` 저장 + Redis pub/sub)을 **경기별·하루 수명 채팅**으로 바꾼다. 바꾸는 이유는 세 가지다 — 하루 뒤 가치 없는 메시지가 RDB에 쌓이고, pub/sub은 fire-and-forget이라 SSE가 끊긴 구간의 메시지를 잃으며, 한 방 접속자 수에 비례하는 팬아웃 비용이 전송 경로에 묶여 있다. 새 구조는 **Kafka가 유일한 진실(쓰기 지점 하나), Redis Stream은 조회용 사본, SSE 전달은 배칭**이다. 설계 근거 원문은 사용자가 작성한 "게임별 채팅 설계 정리"(2026-10-08)이며, 이 문서는 그 위에서 **무엇이 참이어야 하는가**만 정한다. 원문과 아래 "확정 전제"가 다르면 확정 전제가 우선한다(예: 응원 구단 규칙 폐지, 신고 기록 테이블 없음, Kafka는 EC2 KRaft 단일 브로커, msgId = Kafka 오프셋).

## 범위
- 포함:
  - 신규 Gradle 모듈 `chat`(포트 8082, context-path `/chat`)의 엔드포인트 7개: 방 목록·상세·SSE 구독·퇴장·전송·히스토리·신고
  - 방 수명(매일 00:00 KST 생성·정리, 첫 요청 시 지연 생성, TTL 안전망)
  - 전송 경로의 검증 순서·마스킹·속도 제한·멱등(dedup 2단계)·Kafka produce·202 계약
  - 백그라운드 두 역할: 게이트웨이 컨슈머(팬아웃·배칭·툼스톤·종료 명령) · history-writer(Redis Stream 적재·blind 집합)
  - SSE 이벤트 계약(`messages`·`deleted`·`reset`·하트비트)과 Last-Event-ID 복구
  - 세 역할의 설정 플래그와 앱이 의존하는 환경변수
  - 장애 시 동작(Redis·Kafka·파드 재시작)
  - 프론트 계약 변경 정리, 선행 리팩터(`:profanity` 추출)의 범위
- 제외:
  - **기존 quiz `/rt/chat/**` 코드·계약의 제거 또는 변경** — 전환 기간 동안 병행한다(M절). 구단별 채팅 데이터(`chatrooms`·`chats`)의 이관·삭제도 없다
  - Kafka(EC2)·Redis·Ingress·Deployment 등 **인프라 구축 자체** — 앱이 요구하는 선행 조건만 "인프라 선행 조건" 절에 적는다
  - 뜨거운 방 **샘플링 로직** — 임계값 설정 키만 둔다(CHAT-GC-93)
  - 신고 기록 테이블·관리자 모드·unblind — 신고는 툼스톤 발행뿐이다
  - 경기 종료 신호 연동(종료 시 쓰기 차단·TTL 재설정) — 방은 자정까지 열려 있다
  - 창(MAXLEN) 밖 과거 메시지의 콜드 스토어, Kafka 보존 기간 정책
  - 참여 인원 노출(기존 결정 QUIZ-CHAT-52 유지)
  - 부하 검증(k6) 계획과 파드당 접속 상한 튜닝
  - `:profanity` 추출 후 quiz 쪽 동작의 요구사항(quiz 동작 불변이 조건이며, 그 검증은 quiz 기존 테스트가 담당)

## 확정 전제 (사용자와 합의 완료 — 되묻지 않는다)

### 모듈·배포
1. 새 모듈 `chat`, 포트 **8082**, `server.servlet.context-path` **`/chat`**. 컨트롤러 `@RequestMapping`과 Security matcher는 접두사를 뺀 `/rooms/**`로 쓴다(컨테이너가 접두사를 뗀다 — user·quiz와 같은 규약). 외부 경로는 `/chat/rooms/**`다.
2. 전용 Deployment. **Kafka는 EKS 안이 아니라 전용 EC2 인스턴스(별도 EBS)에서 KRaft 단일 브로커**로 운영한다. 앱 관점에서는 bootstrap 주소가 환경변수로 주입될 뿐이며, EKS 파드 → EC2 Kafka 9092 접근은 보안그룹으로 열려 있어야 한다(인프라 선행 조건 절). Redis는 기존 EC2 Redis를 그대로 쓴다(AOF everysec — 인프라 선행 조건 3).
3. chat 모듈 안에서 세 역할을 설정 플래그로 켜고 끈다: **(1) API**(REST + SSE 구독 + 방 수명 스케줄), **(2) 게이트웨이 컨슈머**(팬아웃), **(3) history-writer 컨슈머**. 처음엔 한 파드에서 셋 다 켜고, 운영에서 Deployment를 나눌 수 있어야 한다. API를 켜고 게이트웨이를 끈 조합은 기동 거부다(CHAT-GC-6).
4. 인증은 `web-support`의 `JwtAuthenticationFilter`·`RestAuthenticationEntryPoint`·`GlobalExceptionHandler`를 그대로 쓴다. principal은 `@AuthenticationPrincipal Long userAccountId`.
5. **구단(응원 팀) 검사는 모든 경로에서 없다.** 로그인한 사용자는 누구나 어느 방이든 보고 쓴다. `SUPPORT_TEAM_REQUIRED`·`CHATROOM_TEAM_MISMATCH`는 이 모듈에서 나가지 않는다.
6. 로컬·dev도 **실제 Kafka**를 쓴다(compose에 Kafka 컨테이너). 테스트는 Testcontainers. 인메모리 대체 구현은 두지 않는다.

### 메시지 식별자
7. **msgId = 그 메시지 레코드의 `chat-messages` 파티션 오프셋(Long).** 한 방의 메시지는 key=`gameId`로 한 파티션에 들어가므로 방 안에서 도착 순서대로 단조 증가한다. 프로듀서는 동기 ack로 `RecordMetadata.offset()`을 받아 202에 싣는다. 요청 경로에 Redis 번호 발급(`INCR`)이 없다.
8. 번호는 **띄엄띄엄하다** — 같은 파티션을 다른 방이 공유하고, 재시도·실패로 빈 번호가 생긴다. 클라이언트는 크기 비교만 하고 연속성을 가정하지 않는다.
9. Stream 엔트리 id = `{offset}-1`, SSE `id:` = 묶음 마지막 레코드의 offset, 히스토리 커서 = offset, `Last-Event-ID` = offset. 네 자리가 전부 같은 값 공간이다. 시퀀스 부분이 0 이 아니라 1 인 이유: Redis 는 `0-0` 을 Stream id 로 받지 않아서, 파티션 첫 레코드(offset 0)가 `0-0` 이 되면 XADD 가 거부된다(2026-10-09 테스트로 발견).

### 방 수명
10. 방 ID = `games.naver_game_id`(String). 방은 **오늘 날짜(Asia/Seoul) 경기**에만 존재한다.
11. 매일 00:00 KST 스케줄 작업: (a) 어제 방 집합 `chat:rooms:{yyyyMMdd}`를 읽어 방마다 메타·Stream·blind 집합 키를 `UNLINK`(`KEYS`/`SCAN` 금지), (b) 오늘 경기를 `games`에서 읽어 방마다 `SET chat:room:{gameId} <meta> NX EX 86400` + `SADD chat:rooms:{오늘} gameId`. NX라 다중 파드가 동시에 돌아도 안전. 스케줄러 zone은 Asia/Seoul 명시(파드는 UTC). API 역할 파드 기동 시에도 1회 실행한다.
12. **지연 생성**: 상세·구독·전송에서 메타가 없는데 `games`에 오늘(Asia/Seoul) 경기로 존재하면 그 자리에서 `SET chat:room:{gameId} <meta> NX EX {다음 00:00 KST까지 남은 초}` + `SADD chat:rooms:{오늘}`로 만들고 정상 진행한다. 그래도 없으면 404. "아직 안 열림" 상태는 없다.
13. 경기 없는 날은 아무것도 만들지 않는다 — 목록 `[]`, 방 단위 경로 전부 404. 경기 종료 신호 연동 없음(자정까지 전송 가능). TTL은 안전망.
14. Redis Stream 키는 첫 `XADD` 때 생기므로 history-writer가 쓸 때마다 `EXPIREAT <다음 00:00 KST>`를 건다(고정 시각이라 멱등).

### 장애 동작
15. 설계 원문 3-4를 따른다: Redis 다운 시 속도 제한·dedup은 fail-open(전송 계속), 히스토리·복구만 불가, 실시간 전달은 Kafka 경로라 영향 없음 / Kafka 다운 시 전송 503 / 파드 재시작 시 클라이언트가 Last-Event-ID로 복구.

### Redis 키 (전부 TTL 보유 — CHAT-GC-25)

| 키 | 타입 | 쓰는 쪽 | 수명 |
|---|---|---|---|
| `chat:rooms:{yyyyMMdd}` | Set(gameId) | 스케줄러·지연 생성 | 48h |
| `chat:room:{gameId}` | String(메타 — 내용은 구현 재량, 존재 여부만 계약) | 스케줄러·지연 생성 | `EX 86400`(자정 생성) 또는 `EX 다음 자정까지`(지연 생성) + 자정 UNLINK |
| `chat:dedup:{gameId}:{clientMsgId}` | String(`PENDING` 또는 확정값 — CHAT-GC-106) | API 전송 | 120s(확정 단계는 `KEEPTTL`) |
| `chat:rate:{userAccountId}` | String(정수) | API 전송(`INCR`) | 1s |
| `chat:game:{gameId}` | Stream(엔트리 id `{offset}-1`) | history-writer(`XADD MAXLEN ~ 50000`) | `EXPIREAT 다음 00:00 KST` + 자정 UNLINK |
| `chat:blind:{gameId}` | Set(msgId=offset) | history-writer(`SADD`) | `EXPIREAT 다음 00:00 KST` + 자정 UNLINK |

### Kafka 토픽

| 토픽 | key | value | 생산자 | 소비자 |
|---|---|---|---|---|
| `chat-messages` | `gameId` | 메시지(CHAT-GC-63 필드 7개 — msgId는 value가 아니라 레코드 오프셋) | API 전송(`enable.idempotence=true`, `acks=all`) | 게이트웨이(assign, 그룹 없음) · history-writer(그룹 `chat-history-writer`) |
| `chat-control` | 툼스톤=`gameId` / 종료 명령=`targetUserAccountId` | `blind` 툼스톤 `{type, gameId, msgId}` · `subscription-close` 명령 `{type, targetUserAccountId, originInstanceId, allRooms, gameId}` | API 신고·구독·퇴장 | 게이트웨이 · history-writer(툼스톤만 처리) |

### 설정 키 (CHAT-GC-11 — 확정)

| 키 | 기본값 | 쓰는 곳 |
|---|---|---|
| `chat.role.api` / `chat.role.gateway` / `chat.role.history-writer` | `true` | A절 |
| `chat.history.max-len` | 50000 | CHAT-GC-97 |
| `chat.recovery.batch-size` / `chat.recovery.max-batches` | 500 / 5 | CHAT-GC-37·39 |
| `chat.gateway.batch-interval-ms` | 150 | CHAT-GC-87 |
| `chat.gateway.write-timeout-ms` | 1000 | CHAT-GC-90 |
| `chat.gateway.sampling-threshold-per-sec` | 0(미사용) | CHAT-GC-93 |
| `chat.rate-limit.per-second` | 3 | CHAT-GC-58 |
| `chat.dedup.ttl-seconds` | 120 | CHAT-GC-60 |
| `chat.kafka.send-timeout-ms` | 3000 | CHAT-GC-62 |

전부 환경변수(relaxed binding, 예 `CHAT_ROLE_API`)로 덮어쓸 수 있다.

### 신규·재사용 `ErrorCode` (확정)

| 코드 | 상태 | 문구 | 비고 |
|---|---|---|---|
| `CHAT_RATE_LIMIT_EXCEEDED` | 429 | 메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요. | 신규 |
| `CHAT_MESSAGE_IN_FLIGHT` | 409 | 같은 메시지를 처리하고 있습니다. 잠시 후 다시 시도해 주세요. | 신규 — dedup PENDING 재시도 |
| `CHAT_BROKER_UNAVAILABLE` | 503 | 채팅 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요. | 신규 — Kafka·Redis 장애 공용 |
| `CHATROOM_NOT_FOUND` | 404 | 존재하지 않는 채팅방입니다. | 재사용 |
| `CHAT_MESSAGE_NOT_FOUND` | 404 | 존재하지 않는 메시지입니다. | 재사용 |
| `SELF_REPORT_NOT_ALLOWED` | 403 | 자신의 메시지는 신고할 수 없습니다. | 재사용 |

## 요구사항 (EARS)

> ID 규칙: `CHAT-GC-<n>`. 기존 `QUIZ-CHAT-*`·`QUIZ-CTAC-*`·`QUIZ-CPF-*`와 별개 계열이며 번호는 재사용하지 않는다. **CHAT-GC-59·66은 2026-10-09 개정에서 삭제된 결번이다**(seq 발급·seq fail-closed — msgId가 Kafka 오프셋이 되어 대상이 사라짐). 개정에서 추가된 항목은 105 이후다.
> 아래 경로는 전부 **접두사를 포함한 외부 경로**(`/chat/...`)로 적는다. 상태코드 응답 본문은 공통 규약(`docs/api/README.md` 1절) `ApiResponse` 래퍼다.

### A. 모듈·배포·역할·설정

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-1 | 유비쿼터스 | THE 시스템 SHALL 포트 8082, context-path `/chat`으로 기동해 모든 채팅 경로를 `/chat/rooms/**`로 노출한다 | 미인증으로 `GET http://localhost:8082/chat/rooms` → 401(`RestAuthenticationEntryPoint` 본문). 접두사 없는 `GET http://localhost:8082/rooms` → 404. 컨트롤러 `@RequestMapping`·Security matcher는 `/rooms/**` |
| CHAT-GC-2 | 유비쿼터스 | THE 시스템 SHALL 세 역할(API·게이트웨이·history-writer)을 서로 독립인 boolean 설정 `chat.role.api`·`chat.role.gateway`·`chat.role.history-writer`로 켜고 끄며, 기본값은 셋 다 켜짐이다 | 역할 설정 없이 기동한 파드 1개에서 REST 응답·`chat-messages` assign 소비·컨슈머 그룹 가입이 전부 관측된다 |
| CHAT-GC-3 | 선택 | WHERE API 역할이 꺼진 파드이면, THE 시스템 SHALL `/rooms/**` 핸들러를 등록하지 않고 방 수명 스케줄(C절)도 실행하지 않는다 | `chat.role.api=false`로 기동 → 인증된 `GET /chat/rooms` → 404(스프링 기본 `NoResourceFoundException`), 00:00 KST에 Redis 쓰기 없음. `GET /chat/actuator/health`는 여전히 200 |
| CHAT-GC-4 | 선택 | WHERE 게이트웨이 역할이 꺼진 파드이면, THE 시스템 SHALL `chat-messages`·`chat-control`의 assign 컨슈머를 띄우지 않는다 | `chat.role.gateway=false` → 브로커에 그룹 없는 컨슈머 연결이 없다 |
| CHAT-GC-5 | 선택 | WHERE history-writer 역할이 꺼진 파드이면, THE 시스템 SHALL 컨슈머 그룹에 가입하지 않고 Redis Stream·blind 집합에 쓰지 않는다 | `chat.role.history-writer=false` → 브로커 컨슈머 그룹 목록에 그 파드 멤버 없음, `chat:game:*`에 `XADD` 없음(MONITOR) |
| CHAT-GC-6 | 예외 | IF API 역할은 켜고 게이트웨이 역할은 끈 채 기동하면, THEN THE 시스템 SHALL 기동을 거부한다 | `chat.role.api=true`·`chat.role.gateway=false` → `APPLICATION FAILED TO START`, 사유 로그에 "SSE 구독을 받는 파드는 게이트웨이 역할이 필요하다"는 취지. 근거: `SseEmitterRegistry`가 파드 로컬이라 구독을 받은 파드에 팬아웃 컨슈머가 없으면 그 구독자는 영원히 아무것도 못 받는다(제약 절 2) |
| CHAT-GC-7 | 유비쿼터스 | THE 시스템 SHALL Kafka bootstrap 주소를 환경변수 `KAFKA_BOOTSTRAP_SERVERS`(`spring.kafka.bootstrap-servers`)로 받고, 비어 있으면 기동을 거부한다 | 환경변수 없이 기동 → 기동 실패. `KAFKA_BOOTSTRAP_SERVERS=10.0.0.x:9092`로 기동 → 정상. 기본값 없음 |
| CHAT-GC-8 | 유비쿼터스 | THE 시스템 SHALL Redis·DB·JWT 환경변수를 quiz와 같은 이름(`REDIS_HOST`/`REDIS_PORT`, `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`, `JWT_SECRET`)으로 받는다 | 기존 `.env` 그대로 + `KAFKA_BOOTSTRAP_SERVERS` 한 줄 추가로 로컬 기동이 된다. `JWT_SECRET`이 user와 다르면 모든 인증 요청이 401 |
| CHAT-GC-9 | 유비쿼터스 | THE 시스템 SHALL `chat-messages`·`chat-control` 토픽을 스스로 만들지 않는다 | 토픽이 없는 브로커에 기동해도 토픽 생성 요청이 없고, 그 상태의 전송은 CHAT-GC-62의 503이다. 토픽 생성은 인프라 선행 조건 2 |
| CHAT-GC-10 | 유비쿼터스 | THE 시스템 SHALL `/`, `/error`, `GET /actuator/health/**` 외 모든 경로에 인증을 요구한다 | 미인증 `GET /chat/actuator/health` → 200. 미인증 `DELETE /chat/rooms/x/subscribe` → 401 |
| CHAT-GC-11 | 유비쿼터스 | THE 시스템 SHALL "확정 전제 — 설정 키" 표의 키와 기본값을 갖고 환경변수로 덮어쓸 수 있게 한다 | 설정 없이 기동 → 표의 기본값. `CHAT_RATE_LIMIT_PER_SECOND=5`로 기동 → 같은 초 6번째가 429 |
| CHAT-GC-108 | 유비쿼터스 | THE 시스템 SHALL `chat-messages`·`chat-control` 프로듀서를 `enable.idempotence=true`·`acks=all`로 구성한다 | 프로듀서 설정 로그(`ProducerConfig values`)에 두 값이 찍힌다. 단일 브로커라 `acks=all`은 사실상 1이다(제약 12) |

### B. 인증·인가

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-12 | 예외 | IF `/chat/rooms/**` 요청에 유효한 액세스 토큰이 없으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 헤더 없음·만료·리프레시 토큰·비밀번호 변경 이전 발급 토큰 전부 `{"success":false,"data":null,"message":"인증이 필요합니다."}` 401. SSE 구독도 스트림을 열기 전 같은 401 |
| CHAT-GC-13 | 유비쿼터스 | THE 시스템 SHALL 응원 구단의 유무·일치와 무관하게 인증된 모든 사용자에게 모든 방의 목록·상세·구독·퇴장·전송·히스토리·신고를 허용한다 | 응원 구단이 없는 계정이 `POST /chat/rooms/{gameId}/messages` → 202. KIA 응원 계정이 LG-두산 경기 방 구독 → 200 스트림. 이 모듈의 어떤 응답에도 `SUPPORT_TEAM_REQUIRED`·`CHATROOM_TEAM_MISMATCH`가 없다 |
| CHAT-GC-14 | 유비쿼터스 | THE 시스템 SHALL 응답 본문·SSE payload 어디에도 계정 내부 id·`uid`를 싣지 않는다 | 히스토리·SSE 항목의 발신자 식별은 `senderNickname`·`profileImgUrl`·`teamCode`뿐이다. Kafka 레코드의 `senderId`는 내부용이며 외부 응답으로 나가지 않는다 |

### C. 방 수명

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-15 | 유비쿼터스 | THE 시스템 SHALL 방 식별자로 `games.naver_game_id`를 그대로 쓰고, 모든 방 단위 경로의 `{gameId}`를 그 문자열로 해석한다 | `games.naver_game_id='20261009HTLG0'`인 경기 → `GET /chat/rooms/20261009HTLG0` 200. 내부 PK(`games.id`)로는 404 |
| CHAT-GC-16 | 이벤트 | WHEN 00:00 KST가 되면, THE 시스템 SHALL `chat:rooms:{어제}` 집합의 gameId마다 `chat:room:`·`chat:game:`·`chat:blind:` 세 키를 `UNLINK`하고 마지막에 그 집합 키도 `UNLINK`한다 | 00:00 직후 어제 방의 세 키와 집합 키가 모두 없다. MONITOR에 `KEYS`·`SCAN` 명령이 한 건도 없다 |
| CHAT-GC-17 | 이벤트 | WHEN 00:00 KST가 되면, THE 시스템 SHALL `games`에서 `game_date`가 오늘(Asia/Seoul)인 경기를 읽어 방마다 `SET chat:room:{gameId} <meta> NX EX 86400`과 `SADD chat:rooms:{오늘} {gameId}`를 수행한다 | 오늘 경기 5건 → `chat:room:*` 5개(TTL ≤ 86400) + `chat:rooms:{yyyyMMdd}` 원소 5개. 경기 상태(SCHEDULED/CANCELED 등)와 무관하게 전부 만든다 |
| CHAT-GC-18 | 유비쿼터스 | THE 시스템 SHALL 방 수명 스케줄의 시간대를 Asia/Seoul로 명시해 UTC 파드에서도 한국 자정에 실행한다 | UTC 파드에서 15:00 UTC에 실행된다. "오늘" 판정도 같은 시간대다(09:00 UTC 이전에 하루가 어긋나지 않는다) |
| CHAT-GC-19 | 이벤트 | WHEN API 역할 파드가 기동하면, THE 시스템 SHALL CHAT-GC-16·17과 같은 작업을 1회 실행한다 | 00:00 이후 배포·재시작된 파드가 뜬 직후 오늘 방이 존재한다. 이미 있으면 NX로 no-op라 메타가 리셋되지 않는다 |
| CHAT-GC-20 | 유비쿼터스 | THE 시스템 SHALL 방 수명 작업을 여러 파드가 동시에 실행해도 같은 결과가 되게 한다 | 파드 2개가 같은 초에 실행 → 메타는 먼저 쓴 값 하나, UNLINK는 두 번째가 0을 반환할 뿐 에러가 아니다 |
| CHAT-GC-21 | 예외 | IF 오늘 경기가 0건이면, THEN THE 시스템 SHALL 아무 키도 만들지 않고 작업을 정상 종료한다 | 경기 없는 날 `chat:room:*` 0개, `GET /chat/rooms` → 200 `data: []`, 임의 gameId 상세·구독·전송·히스토리·신고 → 404 |
| CHAT-GC-22 | 예외 | IF 방 수명 작업 중 Redis 명령이 실패하면, THEN THE 시스템 SHALL ERROR 로그를 남기고 5분 간격으로 성공할 때까지 재시도한다 | Redis를 내린 채 자정 통과 → 복구 후 5분 이내에 오늘 방이 생성된다(그 사이 요청은 CHAT-GC-107 지연 생성이 메운다). 실패가 파드 기동을 막지 않는다 |
| CHAT-GC-23 | 유비쿼터스 | THE 시스템 SHALL 경기 상태 변화(진행·종료·취소)에 반응해 방을 닫거나 지우지 않는다 | FINISHED·CANCELED 경기 방에도 자정 전까지 전송 202·구독 200. "종료됨" 응답 코드가 없다 |
| CHAT-GC-24 | 예외 | IF `chat:room:{gameId}` 메타가 없고 CHAT-GC-107의 지연 생성 조건도 아니면, THEN THE 시스템 SHALL 상세·구독·전송·히스토리·신고를 404 `CHATROOM_NOT_FOUND`로 거부한다 | 어제 경기·내일 경기·존재하지 않는 gameId 전부 같은 404. "아직 안 열림"과 "없음"을 구분하는 코드가 없다. 퇴장(F절)만 예외 |
| CHAT-GC-25 | 유비쿼터스 | THE 시스템 SHALL `chat:*` 접두 키 전부에 TTL을 두어 자정 정리가 실패해도 48시간 안에 소멸하게 한다 | 전송·구독·신고·스케줄을 한 바퀴 돌린 뒤 `chat:*` 키 전부 `TTL > 0`. `chat:rooms:{yyyyMMdd}`는 48h |
| CHAT-GC-107 | 예외 | IF 상세·구독·전송 요청의 gameId에 메타가 없는데 `games`에 `game_date`가 오늘(Asia/Seoul)인 경기로 존재하면, THEN THE 시스템 SHALL `SET chat:room:{gameId} <meta> NX EX {다음 00:00 KST까지 남은 초}`와 `SADD chat:rooms:{오늘} {gameId}`를 수행한 뒤 요청을 정상 진행한다 | 자정 작업 뒤 py-collector가 적재한 경기 → `GET /chat/rooms/{gameId}` 200(404 아님), 직후 `TTL chat:room:{gameId}`가 다음 자정까지의 초 이하, `SISMEMBER chat:rooms:{오늘}` 1. 같은 요청이 두 파드에 동시에 와도 NX라 메타 하나. **히스토리·신고에는 이 지연 생성을 적용하지 않는다**(메타 없으면 그대로 404 — 메타는 상세·구독·전송 중 하나를 먼저 거쳐야 생긴다) |

### D. 방 목록·상세

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-26 | 이벤트 | WHEN 인증 사용자가 방 목록을 요청하면, THE 시스템 SHALL `games`에서 오늘(Asia/Seoul) 경기를 `game_date` 오름차순으로 읽어 200으로 반환하고 Redis를 조회하지 않는다 | `GET /chat/rooms` → 200, `data: [{gameId, homeTeam, homeTeamId, awayTeam, awayTeamId, gameDate, gameState}]`(user `GameResponse`와 같은 규약). MONITOR에 Redis 명령 0건. `gameState`는 `game_statuses.name` 문자열(`SCHEDULED`/`IN_PROGRESS`/`FINISHED`/`DRAW`/`CANCELED`) |
| CHAT-GC-27 | 이벤트 | WHEN 인증 사용자가 방 상세를 요청하면, THE 시스템 SHALL `GET chat:room:{gameId}`(없으면 CHAT-GC-107)로 존재를 확인한 뒤 `games`의 **현재 값**으로 목록과 같은 필드를 200으로 반환한다 | `GET /chat/rooms/{gameId}` → 200, 필드 집합이 목록 항목과 동일. 경기가 진행 중이 되면 재호출 시 `gameState`가 `IN_PROGRESS`로 바뀐다(메타는 상태를 들고 있지 않다). 요청당 `games` SELECT 1회 |
| CHAT-GC-28 | 예외 | IF 방 상세 요청의 gameId에 메타가 없고 오늘 경기도 아니면, THEN THE 시스템 SHALL 404 `CHATROOM_NOT_FOUND`를 반환한다 | 어제 경기 gameId → 404 |
| CHAT-GC-29 | 예외 | IF 방 상세·구독·전송·히스토리·신고의 존재 확인(`GET chat:room:`) 또는 지연 생성(`SET NX`)에서 Redis가 응답하지 않으면, THEN THE 시스템 SHALL 503 `CHAT_BROKER_UNAVAILABLE`을 반환한다 | Redis 정지 → `GET /chat/rooms/{gameId}` 503. 같은 상태의 `GET /chat/rooms`(목록)는 200 |

### E. 구독(SSE)·복구

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-30 | 이벤트 | WHEN 인증 사용자가 존재하는 방의 구독을 요청하면, THE 시스템 SHALL `text/event-stream` 스트림을 200으로 열어 유지한다 | `GET /chat/rooms/{gameId}/subscribe`(fetch 폴리필, `Authorization` 헤더) → 200, `Content-Type: text/event-stream`, 즉시 끊기지 않는다 |
| CHAT-GC-31 | 예외 | IF 구독 요청의 gameId에 메타가 없고 오늘 경기도 아니면, THEN THE 시스템 SHALL 404를 반환하고 스트림을 열지 않는다 | 어제 경기 → 404 JSON, 레지스트리에 등록 없음 |
| CHAT-GC-32 | 상태 | WHILE SSE 연결이 열려 있는 동안, THE 시스템 SHALL 15초마다 SSE 주석 `:ping`을 보낸다 | 유휴 스트림에서 `:ping`이 15초 간격으로 관찰된다(`data:` 이벤트 아님) |
| CHAT-GC-33 | 유비쿼터스 | THE 시스템 SHALL SSE 연결 타임아웃을 30분으로 두고, 타임아웃된 연결을 `complete()`하고 레지스트리에서 제거한다 | 30분 뒤 스트림 종료. `spring.mvc.async.request-timeout`이 30분 미만이 아니다 |
| CHAT-GC-34 | 이벤트 | WHEN 사용자의 새 구독이 성립하면, THE 시스템 SHALL 그 사용자의 기존 구독을 방 불문 전부 종료한다(last-one-wins) — 로컬은 즉시, 다른 파드는 `chat-control` 종료 명령(`allRooms=true`)으로 | 같은 계정이 재구독 → 새 스트림 200, 기존 스트림은 서버가 닫는다. 재구독 전후 그 계정의 총 구독 수 1→1 |
| CHAT-GC-35 | 선택 | WHERE 파드가 여럿이면, THE 시스템 SHALL 다른 파드에 있던 기존 구독도 CHAT-GC-34의 명령으로 종료하고, 명령을 발행한 파드는 되받은 자기 명령을 `originInstanceId`로 무시한다 | 파드 A 구독 → 파드 B로 재구독 → A의 emitter 종료, B의 새 연결은 자기 축출 명령에 끊기지 않는다 |
| CHAT-GC-36 | 예외 | IF 하트비트 전송이 실패하면, THEN THE 시스템 SHALL 그 연결을 레지스트리에서 회수한다 | 죽은 emitter에 `:ping` 실패 → 이후 팬아웃 대상에서 빠진다 |
| CHAT-GC-37 | 이벤트 | WHEN 구독 요청에 `Last-Event-ID` 헤더(0 이상의 정수 offset)가 있으면, THE 시스템 SHALL 등록 직후 `XRANGE chat:game:{gameId} ({lastId}-1 + COUNT {batch-size}`로 놓친 구간을 읽어 `messages` 이벤트로 먼저 흘린 뒤 실시간에 합류시킨다 | `Last-Event-ID: 120`으로 재구독 → 첫 이벤트들이 offset > 120인 메시지들(오름차순)이고 그 뒤 실시간 메시지가 이어진다. 페이지(최대 500건) 하나가 `messages` 이벤트 하나이며 `id:`는 그 페이지의 마지막 offset |
| CHAT-GC-38 | 유비쿼터스 | THE 시스템 SHALL 복구 구간에서 `chat:blind:{gameId}`에 든 msgId와 구독자 본인이 보낸 메시지를 제외한다 | 끊긴 사이 blind된 메시지와 본인 메시지는 복구 이벤트에 없다(실시간과 같은 규칙) |
| CHAT-GC-39 | 예외 | IF 복구가 `max-batches`(5)회 동안 매번 `batch-size`(500)건을 꽉 채우고도 마지막 페이지가 꽉 차 있으면, THEN THE 시스템 SHALL `reset` 이벤트 하나를 보내고 복구를 중단한 채 실시간에 합류시킨다 | 2,500건 초과 놓침 → 복구 이벤트 5개 뒤 `event: reset` / `data: {}`. 클라이언트는 화면을 비우고 `GET .../messages`로 다시 받는다 |
| CHAT-GC-40 | 예외 | IF `Last-Event-ID`가 Stream의 가장 오래된 엔트리 id보다 작으면(MAXLEN 트리밍·자정 재생성), THEN THE 시스템 SHALL 복구 없이 `reset` 이벤트 하나를 보낸다 | 트리밍된 구간을 가리키는 offset으로 재구독 → 조용한 공백 대신 `reset` |
| CHAT-GC-41 | 예외 | IF `Last-Event-ID`가 0 이상의 정수로 파싱되지 않으면, THEN THE 시스템 SHALL 헤더를 무시하고 복구 없이 실시간만 시작한다 | `Last-Event-ID: abc` → 200 스트림, 복구·`reset` 없음 |
| CHAT-GC-42 | 예외 | IF 방 존재 확인을 통과한 뒤 복구 중 Redis 명령(XRANGE·blind 조회)이 실패하면, THEN THE 시스템 SHALL `reset` 이벤트 하나를 보내고 실시간에 합류시킨다 | 존재 확인 뒤 복구 조회만 실패 → 200 + `reset`, 스트림은 유지되고 이후 실시간은 정상. Redis 가 처음부터 정지 상태면 존재 확인 단계에서 CHAT-GC-29 에 따라 503 이다(2026-10-09 검증으로 정정) |
| CHAT-GC-43 | 유비쿼터스 | THE 시스템 SHALL 복구와 실시간이 겹쳐 같은 msgId가 두 번 전달될 수 있음을 허용하고, 중복 제거를 클라이언트 책임으로 둔다 | 계약: 클라이언트는 자신이 마지막으로 처리한 msgId 이하의 항목을 버린다. 서버는 중복 억제를 시도하지 않는다 |
| CHAT-GC-44 | 유비쿼터스 | THE 시스템 SHALL 방 존재 확인을 구독 시점 1회만 하고, 열린 스트림은 자정에 방이 정리돼도 타임아웃·퇴장·축출 전까지 유지한다 | 23:59 구독 → 00:01에도 스트림 열려 있음(이벤트는 더 이상 오지 않음). 재구독은 404 |

### F. 퇴장 — `DELETE /chat/rooms/{gameId}/subscribe`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-45 | 이벤트 | WHEN 인증 사용자가 퇴장을 요청하면, THE 시스템 SHALL 그 사용자의 그 방 로컬 구독을 종료하고 `chat-control`에 종료 명령(`allRooms=false`, `gameId`)을 발행한 뒤 200 `{"success":true,"data":null,"message":null}`을 반환한다 | 구독 성립 후 `DELETE` → 200, 클라이언트 스트림 종료 |
| CHAT-GC-46 | 유비쿼터스 | THE 시스템 SHALL 퇴장 경로에서 방 존재를 확인하지 않는다 | 어제 경기·임의 문자열 gameId로 `DELETE` → 200(404 아님). 메타 `GET` 명령이 MONITOR에 없다 |
| CHAT-GC-47 | 이벤트 | WHEN 종료할 구독이 없는 상태에서 퇴장을 요청하면, THE 시스템 SHALL 상태를 바꾸지 않고 200을 반환한다 | 연속 2회 `DELETE` → 둘 다 200 |
| CHAT-GC-48 | 예외 | IF 종료 명령 발행이 실패하면(Kafka 장애), THEN THE 시스템 SHALL 로컬 종료만 수행하고 WARN 로그와 함께 200을 반환한다 | 브로커 정지 → `DELETE` 200, 다른 파드의 구독은 타임아웃·하트비트 안전망으로 회수 |
| CHAT-GC-49 | 선택 | WHERE 파드가 여럿이면, THE 시스템 SHALL 구독을 들고 있지 않은 파드로 들어온 퇴장도 그 구독이 있는 파드에서 종료되게 한다 | 파드 A 구독 → 파드 B로 `DELETE` → A의 emitter 종료 |

### G. 전송 — `POST /chat/rooms/{gameId}/messages`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-50 | 이벤트 | WHEN 인증 사용자가 존재하는 방에 유효한 `{content, clientMsgId}`로 전송을 요청하면, THE 시스템 SHALL Kafka `chat-messages`에 1건 produce하고 202 `{gameId, msgId, content}`를 반환한다 | `{"content":"오늘 이긴다","clientMsgId":"3f9c2e10-…"}` → 202 `{"success":true,"data":{"gameId":"20261009HTLG0","msgId":<레코드 오프셋>,"content":"오늘 이긴다"},"message":null}`. `msgId`는 브로커가 돌려준 `RecordMetadata.offset()`, `content`는 마스킹 결과. MySQL INSERT 0건, `XADD` 0건, `INCR chat:seq:*` 없음 |
| CHAT-GC-51 | 유비쿼터스 | THE 시스템 SHALL 전송 판정 순서를 **404(방 메타, 지연 생성 포함) → 400(본문 검증) → 마스킹 → 429(속도) → dedup 선점(`SET NX`: 있으면 409 또는 재반환) → Kafka produce(실패 503) → dedup 확정 → 202**로 고정한다 | 없는 방 + 빈 content → 404(400 아님). 있는 방 + 501자 → 400, `chat:rate:` INCR 없음. 속도 초과 → 429, dedup 키 생성 없음. 브로커 정지 → 503, dedup 키 없음 |
| CHAT-GC-52 | 예외 | IF content가 null·빈 문자열·공백뿐이거나 500자(UTF-16 code unit)를 넘으면, THEN THE 시스템 SHALL 400을 반환하고 produce하지 않는다 | `{"content":"   ","clientMsgId":"…"}` → 400, `data.content`에 위반 메시지(공유 `GlobalExceptionHandler`의 Bean Validation 형식). 500자 통과·501자 거부 |
| CHAT-GC-53 | 예외 | IF `clientMsgId`가 없거나 36자 UUID 형식이 아니면, THEN THE 시스템 SHALL 400을 반환하고 produce하지 않는다 | `clientMsgId` 누락 → 400 `data.clientMsgId`. `"abc"` → 400. 키 공간(`chat:dedup:{gameId}:{clientMsgId}`)에 임의 문자열이 들어오지 않게 하는 방어 |
| CHAT-GC-54 | 유비쿼터스 | THE 시스템 SHALL 욕설 매칭이 되돌린 원문 구간을 **같은 길이의 `*`**로 바꾸고, 구단별 치환어 표(`MaskWordTable`)를 쓰지 않는다 | `"시발 오늘"` → content `"** 오늘"`, `"시 발"` → `"***"`(가운데 공백 포함 구간 길이 3), `"개새끼"` → `"***"`. 마스킹 결과 길이 = 원문 길이. 응원 구단이 달라도 결과 동일 |
| CHAT-GC-55 | 유비쿼터스 | THE 시스템 SHALL 금지어가 포함되었다는 이유로 전송을 거절하지 않는다 | `{"content":"시발"}` → 202 `data.content` `"**"`, Kafka 레코드 content `"**"` |
| CHAT-GC-56 | 유비쿼터스 | THE 시스템 SHALL 욕설 탐지를 `:profanity` 모듈의 장치·데이터로 수행해 quiz 채팅 필터(QUIZ-CPF-10~25·38~44)와 **탐지된 원문 구간이 같게** 한다 | 같은 문장을 quiz 전송과 chat 전송에 넣었을 때 탐지 구간이 같다(치환 결과만 다르다 — quiz는 단어, chat은 `*`). `"야 발표 준비하자"`·`"새끼손가락"`은 양쪽 모두 미탐지. chat 모듈 안에 금지어 JSON 사본이 없다 |
| CHAT-GC-57 | 예외 | IF 마스킹 처리가 예외로 실패하면, THEN THE 시스템 SHALL 전송을 실패(500)로 처리하고 원문을 produce하지 않는다(QUIZ-CPF-36과 동일 규칙) | 필터 예외 → 202 아님, Kafka 레코드 없음, dedup 키 없음 |
| CHAT-GC-58 | 예외 | IF 같은 사용자의 전송이 1초 창 안에서 `chat.rate-limit.per-second`(기본 3)를 넘으면, THEN THE 시스템 SHALL 429 `CHAT_RATE_LIMIT_EXCEEDED`를 반환하고 dedup·produce를 하지 않는다 | 같은 초에 4번째 요청 → 429. 창은 첫 요청 시점부터 고정 1초(`INCR` + 첫 증가 때만 `EXPIRE 1`). 방이 달라도 같은 사용자면 한 창을 공유한다(`chat:rate:{userAccountId}`) |
| CHAT-GC-59 | (삭제됨 2026-10-09) | ~~seq 발급~~ | msgId가 Kafka 오프셋이 되어 발급 단계가 없다. 번호 재사용 금지 |
| CHAT-GC-60 | 이벤트 | WHEN 속도 제한을 통과하면, THE 시스템 SHALL `SET chat:dedup:{gameId}:{clientMsgId} PENDING NX EX {dedup.ttl-seconds}`로 키를 선점하고, 선점에 성공했을 때만 produce로 진행한다 | 첫 요청 직후 `GET chat:dedup:{gameId}:{clientMsgId}` → `PENDING`, TTL ≤ 120. 선점이 실패하면(키 존재) CHAT-GC-105·106에 따라 분기한다 |
| CHAT-GC-105 | 예외 | IF dedup 키가 이미 있고 값이 `PENDING`이면, THEN THE 시스템 SHALL 409 `CHAT_MESSAGE_IN_FLIGHT`를 반환하고 produce하지 않는다 | 첫 요청이 Kafka ack를 기다리는 동안 같은 `clientMsgId`로 재요청 → 409, Kafka 레코드는 여전히 1건. 클라이언트는 짧게 기다렸다가 같은 `clientMsgId`로 재시도한다 |
| CHAT-GC-106 | 이벤트 | WHEN Kafka ack를 받으면, THE 시스템 SHALL `SET 같은 키 <확정값> KEEPTTL`로 값을 바꾼다 — 확정값은 JSON `{"msgId":<offset>,"content":"<마스킹 content>"}` (가정: 직렬화 형식) | ack 직후 `GET chat:dedup:…` → `{"msgId":1234,"content":"오늘 이긴다"}`, TTL이 선점 시점부터 이어진다(120으로 되돌아가지 않는다). 키가 이미 있고 값이 이 JSON이면 **그 값으로 같은 202 `{gameId, msgId, content}`를 재반환**하고 produce하지 않는다 — 120초 안 재요청 → 첫 응답과 바이트 단위 동일 본문, Kafka 레코드 1건. 121초 뒤 재요청은 새 produce·새 offset |
| CHAT-GC-61 | 유비쿼터스 | THE 시스템 SHALL dedup 재반환·409 요청도 속도 제한에 계수한다 | 1초 안에 같은 `clientMsgId` 재시도 4회 → 4번째는 429(dedup 판정보다 429가 먼저다 — CHAT-GC-51) |
| CHAT-GC-62 | 예외 | IF Kafka produce가 `send-timeout-ms` 안에 ack되지 않거나 실패하면, THEN THE 시스템 SHALL 503 `CHAT_BROKER_UNAVAILABLE`을 반환하고 방금 선점한 dedup 키를 `DEL`한다 | 브로커 정지 → 503, `EXISTS chat:dedup:…` 0. 직후 같은 `clientMsgId` 재시도 → 409·재반환이 아니라 다시 produce 시도(브로커 복구 시 202, 새 offset). 키를 남기면 재시도가 produce된 적 없는 메시지에 대해 영원히 409를 받는다 |
| CHAT-GC-63 | 유비쿼터스 | THE 시스템 SHALL `chat-messages` 레코드를 key=`gameId`, value=`{gameId, senderId, senderNickname, teamCode, profileImgUrl, content, sentAt}`로 produce한다 | 필드 7개 고정 — **msgId는 value에 없다**(소비자가 레코드 오프셋에서 읽는다). `teamCode`=발신자 현재 응원 구단 `teams.code`(없으면 `null`), `profileImgUrl`=BaseURL 없는 EP(없으면 `null`), `content`=마스킹 결과, `sentAt`=서버 수신 시각 ISO-8601 **오프셋 포함**(예 `2026-10-09T19:03:21.123+09:00`) |
| CHAT-GC-64 | 유비쿼터스 | THE 시스템 SHALL 발신자 표시 정보(닉네임·구단·프로필)를 전송 시점 스냅샷으로 싣고 이후 변경을 소급하지 않는다 | 전송 후 닉네임 변경 → 히스토리·복구의 그 메시지는 옛 닉네임. 조회 경로에 계정 조인이 없다 |
| CHAT-GC-65 | 예외 | IF 속도 제한 또는 dedup의 Redis 명령이 실패하면, THEN THE 시스템 SHALL 그 단계를 건너뛰고(fail-open) WARN 로그 후 다음 단계로 진행한다 | 속도·dedup 키 명령만 실패하도록 꾸민 상태에서 전송 → 202(멱등 보장 없이 produce). 존재 확인 단계에서 Redis가 죽어 있으면 그보다 먼저 CHAT-GC-29의 503이다 |
| CHAT-GC-66 | (삭제됨 2026-10-09) | ~~seq fail-closed 503~~ | seq가 없어졌다. 전송 경로의 Redis fail-closed 지점은 존재 확인(CHAT-GC-29)뿐이다. 번호 재사용 금지 |
| CHAT-GC-67 | 유비쿼터스 | THE 시스템 SHALL 발신자에게 자기 메시지를 SSE로 되돌려 주지 않는다 | 발신자의 스트림에 그 msgId가 실린 `messages` 이벤트가 없다. 발신자는 202의 `msgId`·`content`로 렌더한다 |

### H. 히스토리 — `GET /chat/rooms/{gameId}/messages?cursor=`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-68 | 이벤트 | WHEN 인증 사용자가 존재하는 방의 히스토리를 요청하면, THE 시스템 SHALL `XREVRANGE chat:game:{gameId} <상한> - COUNT 30`으로 읽고 필터를 거쳐 200 `{messages, nextCursor, hasNext}`를 반환한다 | `GET /chat/rooms/{gameId}/messages` → 200 `data: {messages:[…최대 30건, 최신순], nextCursor: <정수|null>, hasNext: <boolean>}`. `page`·`totalElements`·`totalPages` 키가 없다 |
| CHAT-GC-69 | 유비쿼터스 | THE 시스템 SHALL `cursor`가 없으면 상한 `+`(최신부터), 있으면 `({cursor}-1`(그 offset **미포함**, 더 오래된 것부터)으로 읽는다 | 첫 페이지의 가장 오래된 msgId가 7100이면 `?cursor=7100` 두 번째 페이지의 첫 항목은 msgId < 7100. 존재하지 않는 offset을 커서로 줘도 그보다 작은 엔트리부터 정상 반환 |
| CHAT-GC-70 | 유비쿼터스 | THE 시스템 SHALL `nextCursor`·`hasNext`를 필터 이전 원본 페이지 기준으로 계산한다 — `hasNext`=원본이 30건을 꽉 채움, `nextCursor`=원본 마지막(가장 작은) msgId, 원본 0건이면 `nextCursor:null`·`hasNext:false` | blind·차단으로 30건 중 10건이 걸러져도 `hasNext:true`·`nextCursor`는 원본 30번째의 msgId. 클라이언트는 `messages.length < 30`을 끝으로 해석하면 안 된다(기존 quiz 히스토리와 같은 규칙) |
| CHAT-GC-71 | 유비쿼터스 | THE 시스템 SHALL `chat:blind:{gameId}` 집합에 든 msgId를 응답에서 제외한다 | blind 처리된 메시지가 어느 페이지에도 없다 |
| CHAT-GC-72 | 유비쿼터스 | THE 시스템 SHALL 요청자와 차단 관계(양방향, `UserBlockRepository.findRelatedAccountIds`)인 계정의 `senderId` 메시지를 응답에서 제외하며, 그 조회는 페이지당 1회다 | A가 B를 차단 → A·B 모두 상대 메시지가 히스토리에 없다. 저장·전송·SSE는 영향 없음(`user-block.md` USER-BLK-14~16 규칙 승계). 페이지 크기와 무관하게 `user_blocks` SELECT 1회 |
| CHAT-GC-73 | 유비쿼터스 | THE 시스템 SHALL 항목 필드를 `{msgId, content, senderNickname, teamCode, profileImgUrl, sentAt}`로 고정한다 | 필드 6개. `id`·`roomUid`·`createdAt`·계정 id 없음. `msgId`는 엔트리 id의 offset 부분, `sentAt`은 Kafka 레코드 값 그대로(CHAT-GC-63 형식) |
| CHAT-GC-74 | 예외 | IF `cursor`가 정수로 파싱되지 않으면, THEN THE 시스템 SHALL 400을 반환한다 | `?cursor=abc` → 400 `"요청 파라미터 형식이 올바르지 않습니다: cursor"`(공유 `GlobalExceptionHandler`) |
| CHAT-GC-75 | 예외 | IF `XREVRANGE` 또는 blind 집합 조회가 실패하면, THEN THE 시스템 SHALL 503 `CHAT_BROKER_UNAVAILABLE`을 반환한다 | Redis 정지 → 503(빈 배열 200이 아니다 — 빈 방과 장애를 한 모양으로 덮지 않는다) |
| CHAT-GC-76 | 유비쿼터스 | THE 시스템 SHALL 히스토리에 history-writer가 적재한 것만 싣고, 202를 받은 직후의 메시지가 아직 없을 수 있음을 계약으로 둔다 | 202 직후 즉시 조회 → 그 msgId가 없을 수 있다(정상 랙 수십 ms). 발신자는 202로 렌더하고 히스토리로 자기 메시지 존재를 확인하지 않는다 |

### I. 신고·블라인드 — `POST /chat/rooms/{gameId}/messages/{msgId}/report`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-77 | 이벤트 | WHEN 인증 사용자가 존재하는 방의 타인 메시지를 신고하면, THE 시스템 SHALL `chat-control`에 blind 툼스톤 `{type:"blind", gameId, msgId}`를 발행하고 200 `{"success":true,"data":null,"message":null}`을 반환한다 | 신고 → 200, `chat-control` 레코드 1건. MySQL INSERT 0건 |
| CHAT-GC-78 | 유비쿼터스 | THE 시스템 SHALL 신고 판정 순서를 **404(방 메타 — 지연 생성 없음) → 404(Stream 엔트리 `{msgId}-1`) → 403(본인) → 발행**으로 고정한다 | 없는 방 + 본인 msgId → 404 `CHATROOM_NOT_FOUND` |
| CHAT-GC-79 | 예외 | IF `chat:game:{gameId}`에 `{msgId}-1` 엔트리가 없으면(미도착·트리밍·만료·존재한 적 없음), THEN THE 시스템 SHALL 404 `CHAT_MESSAGE_NOT_FOUND`를 반환하고 발행하지 않는다 | 202 직후 history-writer 랙 안에 신고 → 404일 수 있다(계약 — 클라이언트는 짧게 재시도). 트리밍된 옛 msgId → 404 |
| CHAT-GC-80 | 예외 | IF 엔트리의 `senderId`가 요청자와 같으면, THEN THE 시스템 SHALL 403 `SELF_REPORT_NOT_ALLOWED`를 반환하고 발행하지 않는다 | 자기 메시지 신고 → 403, `chat-control` 레코드 없음 |
| CHAT-GC-81 | 예외 | IF 이미 blind된 메시지를 신고하면, THEN THE 시스템 SHALL 200을 반환하고 결과 상태를 바꾸지 않는다(멱등) | 같은 msgId 2회 신고 → 둘 다 200, blind 집합 원소 1개. 툼스톤이 다시 발행되어 `deleted` 이벤트가 한 번 더 갈 수 있다(클라이언트는 중복 `deleted`를 무해하게 처리) |
| CHAT-GC-82 | 예외 | IF 툼스톤 발행이 실패하면(Kafka 장애), THEN THE 시스템 SHALL 503 `CHAT_BROKER_UNAVAILABLE`을 반환한다 | 브로커 정지 → 신고 503(200으로 삼키면 "신고됐다"가 거짓이 된다) |
| CHAT-GC-83 | 유비쿼터스 | THE 시스템 SHALL 신고자·사유·횟수를 어디에도 저장하지 않는다 | 신고 후 MySQL 어떤 테이블에도 행 증가 없음, Redis에 신고자 키 없음 |
| CHAT-GC-84 | 상태 | WHILE 메시지가 blind 집합에 있는 동안, THE 시스템 SHALL 그 메시지를 히스토리·복구에서 제외하고 구독자에게 `deleted` 이벤트로 알린다 | blind 이후 신규 구독자·히스토리·Last-Event-ID 복구 어디에도 없다. 이미 받은 구독자는 `event: deleted` / `data: {"msgId": 4200}`를 받는다(J절). unblind 경로 없음 |
| CHAT-GC-85 | 예외 | IF 경로의 `{msgId}`가 정수로 파싱되지 않으면, THEN THE 시스템 SHALL 400을 반환한다 | `/messages/abc/report` → 400 `"요청 파라미터 형식이 올바르지 않습니다: msgId"` |

### J. 게이트웨이 컨슈머(팬아웃)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-86 | 유비쿼터스 | THE 시스템 SHALL 게이트웨이 역할에서 `chat-messages`·`chat-control`의 전 파티션을 컨슈머 그룹 없이 `assign()`으로 구독하고, 기동 시 latest부터 읽으며 오프셋을 저장하지 않는다 | 브로커에 게이트웨이용 컨슈머 그룹이 없다. 파드 재시작 사이에 produce된 메시지는 재시작 후 SSE로 나가지 않는다(복구는 Last-Event-ID 경로뿐). 파드 2개가 같은 메시지를 각자 받아 자기 구독자에게만 보낸다 |
| CHAT-GC-87 | 유비쿼터스 | THE 시스템 SHALL 방별 배처가 `batch-interval-ms`(기본 150)마다 그 방의 큐를 비워 **`messages` 이벤트 하나**에 offset 오름차순 배열로 담고, `id:`를 배열 마지막 레코드의 offset으로 둔다 | 150ms 안에 3건 도착 → `event: messages` / `id: 4402` / `data: [{msgId:4398,…},{msgId:4401,…},{msgId:4402,…}]` 1프레임(번호가 띄엄띄엄한 것은 정상). 항목 필드는 히스토리와 같은 6개(CHAT-GC-73) |
| CHAT-GC-88 | 유비쿼터스 | THE 시스템 SHALL 구독자마다 자기 `senderId` 메시지를 배열에서 뺀 뒤 보내고, 비게 되면 그 구독자에게는 이벤트를 보내지 않는다 | 배치가 A의 메시지 1건뿐이면 A는 아무 프레임도 받지 않고 B는 받는다. 같은 배치라도 구독자마다 배열이 다를 수 있다 |
| CHAT-GC-89 | 유비쿼터스 | THE 시스템 SHALL Kafka 컨슈머 스레드에서는 방별 큐 적재만 하고, emitter 쓰기는 전용 쓰기 스레드풀에서 수행한다 | 쓰기 지연을 인위적으로 늘린 구독자 1명이 있어도 같은 파드의 컨슈머 랙이 늘지 않는다 |
| CHAT-GC-90 | 예외 | IF 어떤 emitter의 쓰기가 `write-timeout-ms` 안에 끝나지 않으면, THEN THE 시스템 SHALL 그 emitter만 닫고 레지스트리에서 제거하며 같은 방의 다른 구독자 전달은 계속한다 | 느린 클라이언트 1명 → 그 스트림만 종료, 나머지 구독자는 다음 배치를 정상 수신 |
| CHAT-GC-91 | 이벤트 | WHEN `chat-control`에서 blind 툼스톤을 받으면, THE 시스템 SHALL 그 방의 모든 구독자(발신자 포함)에게 `event: deleted` / `data: {"msgId": <n>}`를 보낸다 | 신고 직후 구독자 전원이 `deleted` 1건 수신. `id:` 필드 없음(Last-Event-ID 워터마크는 msgId 축이며 삭제는 워터마크를 움직이지 않는다) |
| CHAT-GC-92 | 이벤트 | WHEN `chat-control`에서 종료 명령을 받으면, THE 시스템 SHALL 기존 `SubscriptionCloseCommand` 의미대로 처리한다 — `originInstanceId`가 자기 인스턴스면 무시, `allRooms=true`면 그 사용자의 전 구독, `false`면 `gameId` 방의 구독만 종료 | 파드 A에서 발행한 축출 명령이 A로 되돌아와도 A의 새 구독이 끊기지 않는다. 명령이 어떤 구독자에게도 `data:`로 새지 않는다 |
| CHAT-GC-93 | 유비쿼터스 | THE 시스템 SHALL 샘플링 임계값 설정 키(`chat.gateway.sampling-threshold-per-sec`)를 두되 어떤 값이어도 전달 동작을 바꾸지 않는다 | 키를 1로 두고 초당 100건 전송 → 전부 전달된다(로직은 범위 밖, 키는 자리만) |
| CHAT-GC-94 | 유비쿼터스 | THE 시스템 SHALL 실시간 전달에 blind·차단 필터를 적용하지 않는다 | 차단 관계인 B의 메시지도 A의 스트림에 실린다(숨김은 히스토리 조회 시점에만 — 기존 USER-BLK 규칙). blind는 `deleted` 이벤트로 사후 알림 |
| CHAT-GC-95 | 유비쿼터스 | THE 시스템 SHALL Redis 장애와 무관하게 실시간 전달을 계속한다 | Redis 정지 중 history-writer가 못 쓴 메시지도 SSE로는 전달된다(새 전송은 존재 확인 단계에서 503이지만 이미 produce된 레코드의 전달은 영향 없음) |

### K. history-writer 컨슈머

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-96 | 유비쿼터스 | THE 시스템 SHALL history-writer 역할에서 고정 group id `chat-history-writer`로 `chat-messages`·`chat-control`을 소비한다 | 브로커에 그 그룹 1개, 파드 수와 무관하게 같은 이름. 파드가 늘면 파티션이 재분배될 뿐 그룹이 늘지 않는다 |
| CHAT-GC-97 | 이벤트 | WHEN 메시지 레코드를 받으면, THE 시스템 SHALL `XADD chat:game:{gameId} MAXLEN ~ {max-len} {레코드 offset}-1 <필드>`를 수행하고 이어서 `EXPIREAT chat:game:{gameId} <다음 00:00 KST epoch>`를 건다 | 전송 후 `XRANGE chat:game:{gameId} - +`에 id `{offset}-1`, 필드 `senderId`·`senderNickname`·`teamCode`·`profileImgUrl`·`content`·`sentAt`. `TTL chat:game:{gameId}`가 다음 자정까지의 초. 두 번째 메시지 뒤에도 TTL이 늘어나지 않는다(같은 절대 시각). 같은 파티션을 읽는 순서가 곧 offset 순서라 정상 운영에서 거부가 없다 |
| CHAT-GC-98 | 이벤트 | WHEN blind 툼스톤을 받으면, THE 시스템 SHALL `SADD chat:blind:{gameId} {msgId}`와 같은 `EXPIREAT`를 수행한다 | `SISMEMBER chat:blind:{gameId} 4200` → 1, 키 TTL 다음 자정 |
| CHAT-GC-99 | 예외 | IF `XADD`가 "ID가 스트림 마지막 id 이하"로 거부되면, THEN THE 시스템 SHALL 그 레코드를 건너뛰고 WARN 로그와 함께 카운터 메트릭을 올린다 | 같은 레코드를 두 번 받아도(at-least-once 재전달) 엔트리는 1개, 컨슈머가 멈추지 않는다. 거부 건수가 `GET /chat/actuator/metrics/chat.history.xadd.rejected`로 읽힌다. 정상 운영에서 이 경로는 파티션 수 변경·토픽 재생성으로 방이 더 작은 오프셋 공간으로 옮겨 갔을 때만 밟힌다(제약 7·인프라 선행 조건 2) |
| CHAT-GC-100 | 예외 | IF Redis 명령이 실패하면, THEN THE 시스템 SHALL 오프셋을 커밋하지 않고 재시도해 복구 뒤 밀린 레코드를 순서대로 따라잡는다 | Redis를 30초 내린 뒤 올리면 그 사이 전송된 메시지가 전부 Stream에 생긴다(랙 지표가 올라갔다 내려온다). 건너뛰어 구멍을 내지 않는다 |
| CHAT-GC-101 | 유비쿼터스 | THE 시스템 SHALL history-writer의 컨슈머 랙을 메트릭으로 노출한다 | `GET /chat/actuator/metrics`에서 `chat-history-writer` 그룹의 `kafka.consumer.fetch.manager.records.lag.max` 게이지를 읽을 수 있다 |
| CHAT-GC-102 | 유비쿼터스 | THE 시스템 SHALL history-writer가 멈추거나 밀려도 전송(202)·실시간 전달에 영향을 주지 않는다 | history-writer 역할 파드를 전부 내려도 전송 202·SSE 수신 정상, 히스토리만 갱신되지 않는다 |

### L. 장애 시 동작 요약 (위 항목의 교차 참조 — 새 요구사항 아님)

| 장애 | 전송 | 구독·실시간 | 히스토리·복구 | 근거 |
|---|---|---|---|---|
| Redis 다운 | 존재 확인 503(CHAT-GC-29) / 그 뒤 속도·dedup만 죽으면 fail-open(65) | 실시간 정상(95), 복구는 `reset`(42) | 503(75) | 설계 3-4 |
| Kafka 다운 | 503 + dedup 키 DEL(62) | 구독은 열림, 축출·퇴장 원격 전파만 실패(48) | 정상(Redis 경로) | 설계 3-4 |
| 게이트웨이 파드 재시작 | 영향 없음 | 그 사이 메시지는 SSE로 안 감(86), 클라이언트가 Last-Event-ID로 복구(37) | 정상 | 설계 3-4 |
| history-writer 정지 | 영향 없음(102) | 정상 | 정지 기간 메시지가 히스토리에 없고 신고 404(79), 재가동 시 따라잡음(100) | 설계 1-4 역압 |
| Redis 재시작(AOF everysec) | 최대 1초분 dedup·rate 키 소실(멱등 보장 1초 공백) | 영향 없음 | 최대 1초분 엔트리 소실 — 그 offset은 Stream에 없어 신고 404·복구 누락 | 인프라 선행 조건 3 |
| SSE 30분 타임아웃 | — | 재접속 + Last-Event-ID(33·37) | — | 설계 3-4 |

### M. 기존 quiz 채팅과의 병행

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| CHAT-GC-103 | 유비쿼터스 | THE 시스템 SHALL quiz 앱의 `/rt/chat/**` 7개 엔드포인트와 그 계약(`docs/api/chat.md`)을 이번 변경으로 바꾸지 않는다 | quiz 테스트 전부 그린(`:profanity` 추출 뒤에도 마스킹 결과 문자열 동일), `docs/api/chat.md` 무수정. 두 채팅은 서로의 Redis 키·토픽·테이블을 읽지 않는다 |
| CHAT-GC-104 | 유비쿼터스 | THE 시스템 SHALL chat 모듈이 `quiz` 앱 코드에 컴파일 의존하지 않게 한다 | `chat/build.gradle`에 `project(':quiz')` 없음(web-support 추출과 같은 원칙 — 앱 간 의존 금지). 욕설 탐지는 `project(':profanity')` |

## 선행 리팩터 — `:profanity` 라이브러리 모듈 추출 (chat 구현 전에 끝나야 하는 것)

이 절은 chat 모듈 동작의 요구사항이 아니라 **CHAT-GC-56·104가 성립하기 위한 선행 조건**이다. 요구사항 ID를 붙이지 않는다(quiz 동작 불변이 조건이고, 그 검증은 quiz 기존 테스트 `ProfanityFilterTest` 52·`MaskWordTableTest` 5·`ProfanityDataLoaderTest` 5·`ChatServiceProfanityMaskingTest` 4가 그대로 담당한다).

- **새 Gradle 모듈 `profanity`**(java-library, base package `com.skhynix.profanity`, 부트 플러그인 미적용 — web-support와 같은 형태). 옮기는 것: 판정 장치 `TextNormalizer`·`TracedText`·`KeyboardMapper`·`ProfanityPatterns`·`ProfanityDataLoader`·`ProfanityData`와 리소스 `profanity/` JSON 4종(`banned_words`·`exceptions`·`normalization`·`whitespace_strict`). 탐지 결과(되돌린 원문 구간 목록)를 돌려주는 공개 API 하나를 노출한다 — 이름·시그니처는 `spring-dev` 판단.
- **quiz에 남는 것**: `MaskWordTable`(구단별 치환어, quiz 전용)과 "구간 → 구단 단어" 치환 조립. quiz는 `implementation project(':profanity')`로 바꾸고 저장·201·SSE 문자열이 종전과 바이트 단위로 같아야 한다(QUIZ-CPF 전 항목 불변, `.claude/modules/quiz.md`의 "치환어 목록 순서가 계약" 함정도 그대로).
- **chat이 쓰는 것**: 탐지만. 구간을 같은 길이의 `*`로 바꾼다(CHAT-GC-54).
- **기동 fail-fast 성질 유지**: 리소스 누락·형식 위반 시 빈 생성이 실패해 앱이 안 뜬다(quiz.md). chat에도 같은 성질이 적용된다.
- **동기화 대상은 여전히 두 벌**(`:profanity` ↔ `VictoryFairy_AI/validation/`) — QUIZ-CPF-32·42. 세 번째 사본을 만들지 않는 것이 이 추출의 목적이다.
- 의존 방향: `common` ← `profanity`(common 의존 없음도 가능) ← `{quiz, chat}`. `profanity`는 `domain`·`web-support`를 참조하지 않는다.

## 프론트 영향 (기존 `/rt/chat/**` 대비 — FE 필독)

| 항목 | 기존(quiz) | 신규(chat) |
|---|---|---|
| 경로 접두사 | `/rt/chat/rooms/{roomUid}` | **`/chat/rooms/{gameId}`** — `gameId`는 `GET /api/games`의 `gameId`(=`naver_game_id`)와 같은 값 |
| 방 목록 | 구단 방, `{roomUid, team, name}` | 오늘 경기, `{gameId, homeTeam, homeTeamId, awayTeam, awayTeamId, gameDate, gameState}`. 경기 없는 날 빈 배열 |
| 구단 가드 | 400/403 | **없음** |
| 전송 응답 | 201 + 메시지 본문(`id, content, senderNickname, …`) | **202 + `{gameId, msgId, content}`** — `content`는 마스킹 결과라 보낸 문자열과 비교해 "치환되었습니다" 고지 가능(기존과 같은 방식). 발신자 에코 없음은 동일 |
| 전송 요청 | `{content}` | **`{content, clientMsgId(UUID v4, 메시지마다 새로)}`** — 재시도는 같은 `clientMsgId`로. 120초 안이면 같은 202 본문. **409 `CHAT_MESSAGE_IN_FLIGHT`**는 "첫 요청이 아직 처리 중"이니 잠시 뒤 같은 `clientMsgId`로 재시도 |
| msgId | DB PK, 연속 | **Kafka 오프셋(Long), 띄엄띄엄** — 크기 비교만 할 것 |
| 429 | 없음 | 초당 3건 초과 시 `CHAT_RATE_LIMIT_EXCEEDED` |
| 503 | 없음 | Kafka·Redis 장애 시 `CHAT_BROKER_UNAVAILABLE` — 재시도 대상(같은 `clientMsgId`) |
| SSE 메시지 이벤트 | `event: message`, 단건, `id:` 없음 | **`event: messages`, 배열**, `id:` = 배열 마지막 `msgId`. 항목 `{msgId, content, senderNickname, teamCode, profileImgUrl, sentAt}` |
| SSE 삭제 | 없음 | **`event: deleted`** `{msgId}` — 화면에서 제거 |
| SSE reset | 없음 | **`event: reset`** `{}` — 화면 비우고 히스토리 재조회 |
| 재접속 | 히스토리로 수동 복구 | **`Last-Event-ID`** 헤더에 마지막 `id:` 값. 복구·실시간 중복 가능 → `msgId` ≤ 마지막 처리값은 버릴 것 |
| 히스토리 | `?page=`, `totalElements`·`totalPages` | **`?cursor=<msgId>`**, `{messages, nextCursor, hasNext}`. `messages.length < 30`을 끝으로 보지 말고 `hasNext`만 본다 |
| 신고 | `/messages/{messageId}/report` | `/messages/{msgId}/report`(값은 202·SSE·히스토리의 `msgId`). 202 직후엔 404일 수 있어 짧은 재시도 필요 |
| 시각 | `createdAt` LocalDateTime(오프셋 없음, 파드 UTC라 9시간 어긋남) | `sentAt` **오프셋 포함 ISO-8601**(`+09:00`) — 변환 없이 그대로 표시 |
| 멀티탭 | 금지(last-one-wins) | 동일 |
| 인증 | fetch 폴리필로 `Authorization` 헤더 | 동일 |

## 알려진 결과 (결함이 아니라 알고 택한 것)
1. **msgId가 띄엄띄엄하다.** 파티션을 여러 방이 공유하고(key 해시), 재시도·실패에도 오프셋이 전진한다(전제 8). 클라이언트는 연속성을 가정하면 안 된다.
2. **놓친 `deleted`는 복구되지 않는다.** 끊긴 사이 blind된 메시지는 복구 이벤트에서 빠질 뿐, 이미 그려진 화면에서 지우라는 신호는 오지 않는다(CHAT-GC-91 — `deleted`에 `id:`가 없어 Last-Event-ID로 재생되지 않는다). 재조회(히스토리)로 수렴한다.
3. **202 직후 히스토리·신고에 그 메시지가 없을 수 있다**(CHAT-GC-76·79) — history-writer 랙.
4. **히스토리 창은 최근 50,000건**(`max-len`, 근사 트리밍). 그보다 오래된 메시지는 당일에도 조회·복구·신고가 불가하다.
5. **Redis 재시작 시 최대 1초분이 사라진다**(AOF everysec). 히스토리 엔트리·dedup·rate 키가 대상이며, 사라진 offset은 Stream에 다시 들어오지 않는다(Kafka에는 있으나 재적재 경로가 없다). seq가 없어 "번호가 되돌아가 XADD가 막히는" 종류의 장애는 없다.
6. **게이트웨이 파드 간 전달 시각이 다르다.** 각 파드가 독립적으로 배칭하므로 같은 메시지를 두 사용자가 최대 `batch-interval-ms` 차이로 받는다.
7. **409는 정상 경로의 일부다.** 네트워크 지연으로 클라이언트가 ack 전에 재시도하면 409를 본다 — 에러 토스트가 아니라 재시도 대기로 다뤄야 한다.
8. **히스토리·신고는 방 메타를 만들지 않는다**(CHAT-GC-107). 자정 작업이 실패한 날 상세·구독·전송을 한 번도 안 거친 방에 히스토리를 먼저 부르면 404다. 정상 화면 흐름(목록 → 상세/구독 → 히스토리)에서는 닿지 않는다.

## 미해결 질문
없음. 초안의 11건과 충돌 지점 3건은 2026-10-09 사용자 결정으로 전부 닫혔다(하단 "결정 근거"). 남은 `(가정)`은 CHAT-GC-106의 dedup 확정값 직렬화 형식(JSON `{"msgId","content"}`) 하나이며, 구현자가 다른 형식을 골라도 관측 가능한 계약(같은 202 본문 재반환)은 바뀌지 않는다.

## 결정 근거 (해소된 질문 — 다시 논의하지 않기 위해)
1. **msgId = Kafka 파티션 오프셋.** 초안의 A(건너뜀)/B(writer 버퍼링)/C(Stream 자동 id) 모두 탈락 — 셋 다 "INCR 번호와 도착 순서가 다를 수 있다"는 문제를 뒤에서 수습하는 안이었고, 번호 자체를 도착 순서(오프셋)로 두면 문제가 생기지 않는다. 한 방은 key=gameId로 한 파티션이고 프로듀서가 ack로 오프셋을 받으므로 요청 경로의 Redis 왕복이 하나 줄고(seq), seq 소실·역전 위험이 통째로 사라진다. 대가는 번호가 띄엄띄엄한 것(알려진 결과 1)과 파티션 수 변경 시점 제약(제약 7).
2. **Kafka 503 시 dedup 키 삭제.** 키를 남기면 재시도가 produce된 적 없는 메시지에 대해 409(PENDING) 또는 유령 202를 받는다.
3. **방 메타 지연 생성(B).** py-collector 적재가 자정보다 늦을 수 있어 목록(DB)과 방 단위 경로(Redis)가 어긋나는 창을 첫 요청이 메운다. 매시 재실행(C)은 창을 줄일 뿐 없애지 못한다. 적용 범위는 상세·구독·전송 — 히스토리·신고는 Stream이 전제라 메타만 먼저 생겨도 의미가 없다.
4. **경기 없는 날 아무것도 안 만듦.** 전용 방은 범위 밖.
5. **API+게이트웨이 끄기 조합 기동 거부.** 구독자가 영원히 못 받는 파드가 조용히 생기는 것보다 기동 실패가 낫다.
6. **`sentAt` 오프셋 포함.** 메모리의 "파드 UTC 9시간 어긋남" TODO를 이 모듈에서 닫는다. 기존 chat의 `LocalDateTime`과 다르다는 것을 FE가 안다.
7. **속도 제한 초당 3건.**
8. **방 상세는 DB 현재값.** `gameState`가 실시간이어야 화면이 의미 있다. 요청당 SELECT 1은 상세가 저빈도라 수용.
9. **로컬·dev도 실 Kafka(compose) + Testcontainers.** 인메모리 대체는 prod에서만 검증되는 경로를 만든다.
10. **Redis AOF everysec**(인프라 선행 조건 3). 히스토리 보존 목적. seq가 없어졌으므로 재시작 시 번호 역행 위험은 없다.
11. **기본값 묶음 전부 확정** — 설정 키 표, 그룹 id, 메트릭 이름, 환경변수 필수, 토픽 자동 생성 안 함, 기동 시 1회 실행, 5분 재시도, 48h TTL, ErrorCode 이름·문구, Redis 장애 503, 복구 규칙 4종, 퇴장 발행 실패 200, UUID 검증, 마스킹 실패 500, writer 블로킹 재시도, 목록 필드, `chat-control` key.
12. **충돌 A — `:profanity` 추출.** 복제(세 벌 동기화)·quiz 의존(앱 간 의존) 둘 다 탈락. "선행 리팩터" 절.
13. **충돌 B — 202에 마스킹 `content` 포함.** 발신자가 치환 결과를 즉시 알 수 없는 것(초안 알려진 결과 3)이 기존 대비 후퇴라서. 멱등 재반환도 같은 본문이어야 하므로 dedup 값에 content를 함께 둔다(CHAT-GC-106).
14. **충돌 C — 전송 404→400 유지.** `@Valid`만으로는 못 맞춘다는 제약 1을 알고 택했다.

## 기존 정책과의 충돌 / 계약 성립 제약 (구현 방법 지시가 아니라 지켜야 할 사실)
1. **전송 판정 순서가 quiz와 반대다.** quiz는 `@Valid`가 컨트롤러 진입 전에 돌아 **400 → 404**인데, 이 문서는 **404 → 400**(CHAT-GC-51)이다. 즉 본문 검증을 컨트롤러 진입 전 `@Valid`에만 맡기면 계약 위반이다. 단 본문 **파싱 실패**(깨진 JSON, `HttpMessageNotReadableException`)와 `Content-Type` 불일치(415)는 여전히 컨트롤러 전에 400/415로 끝나며 이 순서 밖이다.
2. **`SseEmitterRegistry`는 파드 로컬이고 팬아웃 주체가 Redis 리스너에서 Kafka assign 컨슈머로 바뀐다.** 그래서 "SSE 구독을 받는 파드 = 게이트웨이 컨슈머가 도는 파드"여야 한다(CHAT-GC-6). API와 게이트웨이를 다른 Deployment로 나누려면 `/subscribe`만 게이트웨이 Deployment로 라우팅하는 Ingress 규칙이 함께 필요하다(인프라 선행 조건 5).
3. **`SubscriptionCloseCommand`의 의미(`originInstanceId` 무시·`allRooms`)는 그대로 쓰되 전송 수단이 Redis 채널 `realtime:events`에서 Kafka `chat-control`로 바뀐다.** 분기를 빠뜨리면 종료 명령이 `data:`로 새는 기존 함정(quiz.md)이 그대로 적용된다(CHAT-GC-92).
4. **욕설 탐지 장치는 `:profanity`로 옮긴 뒤에야 chat이 쓸 수 있다**("선행 리팩터" 절). 추출 전에 chat을 먼저 짜면 복제가 생겨 CHAT-GC-56 위반이다. quiz의 `MaskWordTable`·치환 조립은 quiz에 남고 chat은 쓰지 않는다.
5. **`UserBlockRepository.findRelatedAccountIds`(`:domain`)·`UserAccountRepository`·`GameRepository`·`UserSupportTeamRepository`(응원 구단 code 스냅샷)를 읽는다.** chat은 `:domain`+JPA를 갖춰야 JWT 필터(`UserAccountRepository`)가 조립된다(web-support.md). `games`·`users_account`·`user_support_team`·`user_blocks` 테이블이 그 환경에 있어야 하며, chat의 `ddl-auto`는 **`none`**이다(quiz와 같은 이유 — 새 앱이 공유 스키마를 건드리면 안 된다). user 앱이 테이블을 만든다.
6. **`spring.jpa.open-in-view: false`·`spring.mvc.async.request-timeout: 30m`**은 quiz와 같은 이유로 필요하다(SSE 롱커넥션 중 Hikari 점유 방지·톰캣 30초 조기 종료 방지). `UserSupportTeam.team`은 LAZY라 `teams.code`는 트랜잭션 안에서 읽어야 한다(quiz는 `findWithTeamByUserAccount_IdAndOpposeIsNull` `@EntityGraph`를 쓴다).
7. **`chat-messages` 파티션 수 변경·토픽 재생성은 00:00 KST 방 재생성 직후에만 한다(운영 제약).** 방이 다른 파티션으로 옮겨 가 더 작은 오프셋을 받으면 그날 Stream의 마지막 id보다 작아 **그 방의 XADD가 자정까지 전부 거부**된다(CHAT-GC-99의 메트릭이 이를 드러낸다). 인프라 선행 조건 2에 같은 내용.
8. **`EXPIREAT`는 절대 시각이라 멱등**이지만 "다음 00:00 KST" 계산은 Asia/Seoul 고정이어야 한다(파드 UTC). `LocalDateTime.now()`로 계산하면 15:00 UTC 기준으로 9시간 어긋난다 — quiz의 `kstClock` 함정과 같은 계열. 지연 생성의 `EX {남은 초}`(CHAT-GC-107)도 같은 계산이다.
9. **`chat:dedup:`·`chat:rate:` 키는 자정 정리 대상이 아니다**(TTL 120s·1s로 자연 소멸) — 정리 작업이 이 키들을 찾으려 `SCAN`을 쓰면 CHAT-GC-16 위반이다.
10. **Redis 7.0+ 전제**는 quiz(`EXPIRE NX`)에서 이미 성립했고 이 모듈은 `UNLINK`(4.0+)·`XADD MAXLEN ~`(5.0+)·`SET KEEPTTL`(6.0+)을 쓴다. 기존 EC2 Redis 버전 확인은 인프라 선행 조건.
11. **`AsyncRequestNotUsableException` 재던지기**(web-support catch-all)가 이 모듈에도 그대로 필요하다 — `GlobalExceptionHandler`를 `@Import`하면 따라온다. 빠뜨리면 SSE 끊길 때마다 ERROR 로그.
12. **Kafka 단일 브로커(KRaft 1)**라 `acks=all`·`min.insync.replicas`는 사실상 1이다(CHAT-GC-108). 설계 원문의 "202를 돌려준 메시지는 안 사라진다"는 브로커 디스크(EBS)가 살아 있을 때까지만 참이다 — 요구사항은 그 전제에서 성립하며 다중 브로커로 바뀌어도 문장은 안 바뀐다.
13. **dedup PENDING 창은 Kafka ack 시간만큼이다**(`send-timeout-ms` 최대 3초). 그 안의 재시도는 409이고, 클라이언트 재시도 간격이 이보다 짧으면 409를 반복해서 본다(알려진 결과 7). 프로듀서 `enable.idempotence=true`는 브로커 수준 중복(프로듀서 내부 재전송)을 막는 것이고, 클라이언트 재시도 중복은 이 dedup 키가 막는다 — 두 장치는 다른 층이다.

## 인프라 선행 조건 (앱 범위 밖 — 배포 전 체크리스트)
1. **Kafka: 전용 EC2(별도 EBS) KRaft 단일 브로커.** 9092 리스너의 advertised 주소가 EKS 파드에서 해석·접근 가능해야 한다. **EKS 노드(파드) 보안그룹 → Kafka EC2 보안그룹 9092 인바운드 허용**(dev_infra 소관). 앱은 `KAFKA_BOOTSTRAP_SERVERS=<EC2 사설 IP 또는 DNS>:9092`로 받는다.
2. **토픽 2개 사전 생성**(CHAT-GC-9): `chat-messages`·`chat-control`. 파티션 수는 인프라 결정이되 **`chat-messages`의 파티션 수 변경·토픽 재생성은 00:00 KST 방 재생성 직후에만** 한다(제약 7 — 운영 중 바꾸면 그날 히스토리가 멈춘다). 보존 기간(설계 원문 48h)도 인프라 결정.
3. **Redis: 기존 EC2 Redis(`REDIS_HOST`/`REDIS_PORT`)에 `appendonly yes`·`appendfsync everysec`를 켠다**(히스토리 보존 목적 — 끄면 재시작 시 당일 채팅이 통째로 사라진다). 버전 ≥ 6.0(`SET KEEPTTL`) — quiz가 이미 7.0+를 전제한다. 메모리 상한 점검: 방 5개 × 50,000건 × ~400B ≈ 100MB 추가(설계 1-3) + AOF 리라이트 여유. 같은 박스의 mysqld RSS 사례(2026-08) 때문에 `--maxmemory`·리라이트 정책 재검토.
4. **Deployment·Service·Ingress**: `/chat` path → chat Service 8082. readiness는 `GET /chat/actuator/health/readiness`(Kafka·Redis 인디케이터를 readiness 그룹에 넣지 않는 것은 quiz와 같은 규약).
5. **역할 분리 시**: API와 게이트웨이를 다른 Deployment로 나누면 `/chat/rooms/*/subscribe`(GET)만 게이트웨이 Deployment로 보내는 Ingress 규칙이 필요하다(제약 2). 처음엔 한 Deployment에 셋 다 켠다.
6. **환경변수 추가**: `.env`·k8s Secret/ConfigMap에 `KAFKA_BOOTSTRAP_SERVERS`, 선택적으로 `CHAT_ROLE_*`·`CHAT_*` 설정 덮어쓰기.
7. **DB**: 신규 테이블·컬럼 없음. `user_blocks`·`user_support_team`·`games`가 그 환경에 있어야 한다(user 앱이 생성).
8. **로컬 compose에 Kafka(KRaft 단일) 컨테이너 추가**, CI 러너는 Testcontainers를 돌릴 수 있는 docker 환경이어야 한다(`:chat:test`·`:profanity:test`).
9. **Gradle 모듈 2개 신설**: `:profanity`(선행 리팩터) → `:chat`. `settings.gradle`·CI 매트릭스·`deploy-eks.yml`에 chat 이미지 빌드·배포 단계 추가.
