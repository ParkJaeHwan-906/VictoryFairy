# chat 모듈

> chat 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, 이 앱은 전 프로파일 `ddl-auto=none` — 스키마는 user 가 만든다)

## 책임
**경기(game) 단위** 채팅 앱(응원 구단 폐쇄 채팅인 quiz `/rt/chat` 과 별개, 구단 가드 없음). 전송 `POST`(202) → Kafka `chat-messages` → (게이트웨이) SSE 팬아웃 + (history-writer) Redis Stream 사본. 계약: `docs/requirements/chat/game-chat.md`(승인) · `docs/api/game-chat.md`. **포트 8082, context-path `/chat`**(컨트롤러·Security matcher 는 `/rooms/**`, 접두사 없음). base package `com.skhynix.chat`. 기존 quiz `/rt/chat` 은 전환 기간 병행 유지 중.

## 역할 플래그 (`chat.role.*`, env `CHAT_ROLE_API` 등, 기본 전부 true)
- `api`(REST·구독·방 수명 스케줄·욕설 탐지) / `gateway`(Kafka assign 컨슈머 + SSE 레지스트리·배처·쓰기 풀) / `history-writer`(Kafka 그룹 컨슈머 → Redis Stream).
- ⚠ **api=true + gateway=false 는 기동 거부**(`ChatProperties.Role`) — `SseEmitterRegistry` 가 파드 로컬이라 구독을 받은 파드에 팬아웃 컨슈머가 없으면 그 구독자는 영원히 아무것도 못 받는다.
- api 꺼진 파드는 `/rooms/**` 핸들러·금지어 데이터 로드(`ChatApiRoleConfig` 의 `@Import(ProfanityConfig)`)·방 수명 스케줄을 갖지 않는다.

## 핵심 클래스 (`chat/src/main/java/com/skhynix/chat/`)
- `global/config/SecurityConfig` — web-support 부품을 `@Import`(좁은 스캔 밖). `JwtAuthenticationFilter` 직접 `new`(생성자 바뀌면 같이 수정). permitAll: `/`, `/error`, `GET /actuator/health/**`, **dispatcher ASYNC·ERROR**.
- `global/config/ChatCoreConfig` — `spring.kafka.bootstrap-servers` 비면 기동 거부. `ChatProperties` — `chat.*` 의 기본값 단일 출처는 `application.yaml`.
- `room/service/RoomLifecycleJob`·`RoomLifecycleScheduler` — 매일 00:00 KST(`zone` 명시 — 안 하면 파드 UTC 라 09:00 KST) + API 파드 기동 직후 1회, 실패 시 5분 재시도. 전부 멱등(UNLINK·SET NX·SADD·EXPIREAT).
- `room/service/ChatRoomGuard` — 방 존재 = Redis 방 메타. `requireOrCreate`(상세·구독·전송, 오늘 경기면 지연 생성) / `requireExisting`(히스토리·신고, 지연 생성 없음 → 404). Redis 실패는 503 `CHAT_BROKER_UNAVAILABLE`.
- `message/service/ChatMessageSendService` — 404(방) → 400(본문, `@Valid` 대신 서비스가 검증) → 욕설 `*` 마스킹 → 429(1초 고정창) → dedup 선점 → Kafka 동기 produce(503) → 202. `SendRateLimiter`·`MessageDedupStore` 가 Redis 키를 쓴다.
- `message/service/ChatHistoryService`·`ChatReportService` — 히스토리(커서=msgId)·신고(→ `BlindTombstone` 을 `chat-control` 로 발행).
- `subscription/*` — `ChatSubscriptionService`(방 확인 → 레지스트리 등록(last-one-wins 축출) → `:connected` → 복구 프레임) · `SubscriptionExceptionHandler`.
- `gateway/service/GatewayConsumer`(그룹 없는 assign) · `RoomBatcher`(방별 큐, 150ms) · `realtime/SseEmitterRegistry`·`SseFrameWriter`·`ChatSseEmitter`.
- `history/service/HistoryWriterListener`·`HistoryStreamWriter`, `history/config/HistoryWriterConfig`(Redis 실패 시 1초 간격 무한 재시도 — 건너뛰어 Stream 에 구멍을 내지 않는다).
- `shared/redis/ChatRedisKeys` — 키·엔트리 id 규칙의 단일 출처. `shared/kafka/*` — 토픽명·코덱·페이로드.

## 엔드포인트 (`/chat` 접두사, 전부 인증 필수)
`GET /rooms`(오늘 방 목록, Redis 안 봄) · `GET /rooms/{gameId}` · `GET|DELETE /rooms/{gameId}/subscribe`(SSE / 명시적 퇴장) · `POST|GET /rooms/{gameId}/messages` · `POST /rooms/{gameId}/messages/{msgId}/report`. 응답은 SSE 를 뺀 나머지 `ApiResponse<T>`.

## 의존
`:common`, `:domain`, `:web-support`, `:profanity`(탐지만 — quiz 에 컴파일 의존하지 않는다, 앱 간 의존 금지). JPA·Security·WebMVC·Validation·Data Redis·Kafka·Actuator, MySQL, dotenv. 테스트: Testcontainers(Redis·Kafka, Docker 없으면 건너뜀).

## 주의 / 컨벤션
- **msgId = `chat-messages` 파티션 offset**(연속 아님, 크기 비교만). Redis Stream 엔트리 id = `{offset}-1` — 시퀀스가 0 이면 offset 0 이 `0-0` 이 되는데 Redis 가 XADD 를 거부해 history-writer 가 영원히 재시도하기 때문. 범위 조회 경계는 **시퀀스 생략 불완전 id**(`ChatRedisKeys.boundary`) — 시작이면 `-0`, 끝이면 최대 시퀀스로 해석돼 그 offset 의 엔트리 전부를 포함한다. 변환은 `ChatRedisKeys` 로만.
- ⚠ **`chat-messages` 파티션 수 변경·토픽 재생성은 00:00 KST 방 재생성 직후에만.** 방이 다른 파티션으로 옮겨 더 작은 offset 을 받으면 그날 Stream 마지막 id 보다 작아 그 방 XADD 가 자정까지 전부 거부된다(메트릭 `chat.history.xadd.rejected`). 앱은 토픽을 만들지 않는다(`auto-create=false`, 인프라가 사전 생성).
- **Redis 키**(전부 TTL): `chat:rooms:{yyyyMMdd}`(Set, 오늘 00:00+48h 절대시각) · `chat:room:{gameId}`(메타, 86400s / 지연 생성은 다음 자정까지) · `chat:game:{gameId}`(Stream, `MAXLEN ~ 50000` + EXPIREAT 다음 00:00 KST) · `chat:blind:{gameId}`(Set) · `chat:dedup:{gameId}:{clientMsgId}`(120s, `PENDING`→확정) · `chat:rate:{userAccountId}`(1s). 자정 정리는 `rooms` 집합을 읽어 방별 키를 **UNLINK** — `KEYS`/`SCAN` 금지. 방 단위 키를 새로 만들면 `RoomLifecycleJob` 정리 목록에도 추가. 지연 생성은 상세·구독·전송만.
- **Kafka 그룹**: `spring.kafka.consumer.group-id` 를 yaml 에 두지 말 것 — 게이트웨이가 그 그룹에 들어가 오프셋을 커밋하게 된다. 게이트웨이는 그룹 없는 `assign`·latest·오프셋 저장 안 함(파드마다 전 레코드를 받아야 한다 — 그룹 구독이면 파티션이 나뉘어 타 파드 구독자 메시지를 못 받는다). 재시작 사이 메시지는 SSE 로 안 보내고 `Last-Event-ID` 복구뿐. history-writer 는 `@KafkaListener` 에서 그룹 `chat-history-writer`·`earliest` 를 직접 지정(새 그룹·오프셋 만료 시 히스토리 공백 방지).
- **스레드 모델**: 컨슈머 스레드는 방별 큐 적재만 → 배처 스레드가 150ms 마다 프레임 구성 → `SseFrameWriter` 쓰기 풀이 소켓에 쓴다. 느린 클라이언트는 watchdog 이 끊고 `complete()` 는 closer 스레드. 소켓 쓰기를 요청·컨슈머·배처 스레드가 하지 않는다.
- **SmartLifecycle 종료 순서**(높은 phase 가 먼저 멈춤): `GatewayConsumer`(MAX-10) → `RoomBatcher`(-20) → `SseFrameWriter`(-30) → `SseEmitterRegistry`(-40). 전부 웹 서버 graceful shutdown(DEFAULT_PHASE-1024)보다 높아야 열린 SSE 가 그 단계를 붙잡지 않는다. **lifecycle 빈은 stop() 뒤 start() 로 다시 살 수 있어야 한다**(테스트 컨텍스트 캐시가 재시작시킴 — 종료된 실행기 재사용 시 `RejectedExecutionException`). `SseFrameWriter` 는 웹 서버가 start() 보다 먼저 떠서 생성자에서도 실행기를 만든다.
- **`ChatSseEmitter.tryComplete`**: Spring 7 `ResponseBodyEmitter` 의 `send()`·`complete()` 가 같은 `writeLock` 을 잡아, 느린 클라이언트에 쓰는 중인 emitter 를 다른 스레드가 `complete()` 하면 그 스레드도 멈춘다. 잠금을 바로 얻을 때만 닫고, 아니면 호출부가 closer 로 넘긴다.
- **SSE 구독 규약**: 첫 프레임은 주석 `:connected`(응답 헤더 flush). 표준 `EventSource` 는 Authorization 헤더를 못 실어 401 → fetch 폴리필. `spring.mvc.async.request-timeout: 30m` 은 SSE 타임아웃(30분)보다 낮추지 말 것. `open-in-view: false`.
- **`SubscriptionExceptionHandler`**(구독 컨트롤러 전용 advice): SSE 클라이언트는 `Accept: text/event-stream` 만 보내서, 공유 핸들러의 `ResponseEntity<ApiResponse>` 가 컨버터 협상에 실패(HttpMediaTypeNotAcceptable)해 404·503 대신 **500** 이 났다. 응답에 JSON 을 직접 쓴다. web-support 를 안 고치려 범위를 구독 컨트롤러로 좁힌 것.
- **SecurityConfig 의 ASYNC·ERROR permitAll 이유**: 서버가 SSE 를 `complete()`(퇴장·축출·타임아웃)하면 같은 요청이 ASYNC 로 재디스패치되는데, 무상태 JWT 라 그 디스패치엔 SecurityContext 가 없어 인가 거부 + ERROR 로그가 남는다. 원 요청은 이미 인가를 통과했다. (quiz 에는 이 허용이 없다 — `quiz.md` 알려진 문제.)
- **actuator**: 노출 `health,metrics`, metrics 는 인증 필요(공개 금지). ALB 헬스체크는 `/chat/actuator/health/readiness`. **`KAFKA_BOOTSTRAP_SERVERS` 는 prod 에서 필수**(기본값 없음, dev 프로파일만 `localhost:29092`). `JWT_SECRET` 은 user 와 동일해야 한다(검증만).
- **로컬 검증**: `.env` 가 원격 DB 를 가리킨다 — chat 은 `ddl-auto=none` 이지만 원격 DB 에 쓰기 경로를 돌리지 말 것. Redis·Kafka 는 compose(`redis`·`kafka`·`kafka-init`, 호스트 `localhost:29092`, 토픽 파티션 3).
- **검증 이력(2026-10-09)**: 테스트 `:profanity` 19 · `:chat` 435 · `:quiz` 497 통과, `:chat:bootRun` 실경로·컨테이너(prod 프로파일) 검증 PASS. **EKS 미검증**(ALB·Ingress·매니페스트 실배포 없음).
