# chat 모듈

> chat 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, 이 앱은 전 프로파일 `ddl-auto=none` — 스키마는 user 가 만든다)

## 책임
**경기(game) 단위** 채팅 앱(응원 구단 폐쇄 채팅인 quiz `/rt/chat` 과 별개, 구단 가드 없음). 전송 `POST`(202) → Kafka `chat-messages` → (게이트웨이) SSE 팬아웃 + (history-writer) Redis Stream 사본. 경기 단위 **좋아요**(응원 애니메이션 신호)는 Kafka 를 안 타고 Redis pub/sub → SSE `likes`. 계약: `docs/requirements/chat/game-chat.md`·`game-chat-likes.md`(승인) · `docs/api/game-chat.md`. **포트 8082, context-path `/chat`**(컨트롤러·Security matcher 는 `/rooms/**`, 접두사 없음). base package `com.skhynix.chat`. 기존 quiz `/rt/chat` 은 전환 기간 병행 유지 중.

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
- `like/*`(api 역할) — `ChatLikeController`(본문 안 읽음, 클래스에 `@Validated` 금지 — 붙이면 gameId 검증 400 이 500 이 된다) → `ChatLikeService`(구단 캐시 → 속도 제한 → 발행, 어느 단계에서 버려도 202) · `SupportTeamCodeCache`(TTL 5분, 첫 조회부터 센다, 빈 결과 미캐시) · `LikeRateLimiter`(메모리 1초 고정창) · `LikePublisher`(비동기 PUBLISH, 미완료 수 상한) · `LikeMetrics`.
- `gateway/service/LikesSubscriber`(`chat:likes` 구독 → 스로틀로 넘김) · `LikesThrottler`(방별 leading-edge 스로틀) · `shared/redis/LikeSignal*`(채널명·JSON 코덱) · `shared/ThrottledWarnLog`(10초 1줄 + 누적 건수).
- `history/service/HistoryWriterListener`·`HistoryStreamWriter`, `history/config/HistoryWriterConfig`(Redis 실패 시 1초 간격 무한 재시도 — 건너뛰어 Stream 에 구멍을 내지 않는다).
- `shared/redis/ChatRedisKeys` — 키·엔트리 id 규칙의 단일 출처. `shared/kafka/*` — 토픽명·코덱·페이로드.

## 엔드포인트 (`/chat` 접두사, 전부 인증 필수)
`GET /rooms`(오늘 방 목록, Redis 안 봄) · `GET /rooms/{gameId}` · `GET|DELETE /rooms/{gameId}/subscribe`(SSE / 명시적 퇴장) · `POST|GET /rooms/{gameId}/messages` · `POST /rooms/{gameId}/messages/{msgId}/report` · `POST /rooms/{gameId}/likes`(202 **본문 없음**, 실패는 401·400 뿐 — 404·429·503 없음). 응답은 SSE·좋아요 202 를 뺀 나머지 `ApiResponse<T>`.

## 의존
`:common`, `:domain`, `:web-support`, `:profanity`(탐지만 — quiz 에 컴파일 의존하지 않는다, 앱 간 의존 금지). JPA·Security·WebMVC·Validation·Data Redis·Kafka·Actuator, MySQL, dotenv. 테스트: Testcontainers(Redis·Kafka, Docker 없으면 건너뜀).

## 주의 / 컨벤션
- **msgId = `chat-messages` 파티션 offset**(연속 아님, 크기 비교만). Redis Stream 엔트리 id = `{offset}-1` — 시퀀스가 0 이면 offset 0 이 `0-0` 이 되는데 Redis 가 XADD 를 거부해 history-writer 가 영원히 재시도하기 때문. 범위 조회 경계는 **시퀀스 생략 불완전 id**(`ChatRedisKeys.boundary`) — 시작이면 `-0`, 끝이면 최대 시퀀스로 해석돼 그 offset 의 엔트리 전부를 포함한다. 변환은 `ChatRedisKeys` 로만.
- ⚠ **`chat-messages` 파티션 수 변경·토픽 재생성은 00:00 KST 방 재생성 직후에만.** 방이 다른 파티션으로 옮겨 더 작은 offset 을 받으면 그날 Stream 마지막 id 보다 작아 그 방 XADD 가 자정까지 전부 거부된다(메트릭 `chat.history.xadd.rejected`). 앱은 토픽을 만들지 않는다(`auto-create=false`, 인프라가 사전 생성).
- **Redis 키**(전부 TTL): `chat:rooms:{yyyyMMdd}`(Set, 오늘 00:00+48h 절대시각) · `chat:room:{gameId}`(메타, 86400s / 지연 생성은 다음 자정까지) · `chat:game:{gameId}`(Stream, `MAXLEN ~ 50000` + EXPIREAT 다음 00:00 KST) · `chat:blind:{gameId}`(Set) · `chat:dedup:{gameId}:{clientMsgId}`(120s, `PENDING`→확정) · `chat:rate:{userAccountId}`(1s). 자정 정리는 `rooms` 집합을 읽어 방별 키를 **UNLINK** — `KEYS`/`SCAN` 금지. 방 단위 키를 새로 만들면 `RoomLifecycleJob` 정리 목록에도 추가. 지연 생성은 상세·구독·전송만 — **좋아요는 의도된 예외**(방 존재 확인·지연 생성 없음, gameId 는 `^[A-Za-z0-9]{1,20}$` 형식만 검사, 없는 gameId 도 202). 좋아요 채널 `chat:likes` 는 키가 아니라 pub/sub 채널이라 TTL·자정 UNLINK 대상이 아니다.
- **Kafka 그룹**: `spring.kafka.consumer.group-id` 를 yaml 에 두지 말 것 — 게이트웨이가 그 그룹에 들어가 오프셋을 커밋하게 된다. 게이트웨이는 그룹 없는 `assign`·latest·오프셋 저장 안 함(파드마다 전 레코드를 받아야 한다 — 그룹 구독이면 파티션이 나뉘어 타 파드 구독자 메시지를 못 받는다). 재시작 사이 메시지는 SSE 로 안 보내고 `Last-Event-ID` 복구뿐. history-writer 는 `@KafkaListener` 에서 그룹 `chat-history-writer`·`earliest` 를 직접 지정(새 그룹·오프셋 만료 시 히스토리 공백 방지).
- **좋아요 흐름**: 401 → 400(형식) → 응원 구단(캐시) → 속도 제한 → 비동기 `PUBLISH chat:likes` `{"gameId","teamCode"}`(클릭마다, 202 는 완료를 안 기다림; 미완료가 `publish-queue-capacity` 넘으면 버림) → 모든 게이트웨이 파드가 구독(발행 파드 자신 포함) → `LikesThrottler`. 속도 제한은 **파드 메모리** 초당 10회(파드 N 개면 실효 10×N), 구단 변경은 캐시 TTL 만큼 늦게 반영(파드별로 다를 수 있음). 응원 구단 없음·조회 실패·속도 초과·Redis 실패는 전부 202 + 버림.
- **좋아요 스로틀(게이트웨이)**: 조용한 방은 즉시 1건 전송 후 `throttle-window-ms`(100) 창을 열고, 창 안 신호는 구단 코드를 중복 없이 모아 창 끝에 1프레임. 구독자 없는 방은 버리고 창도 안 연다. SSE `likes` 는 `data: ["HT",...]`, **`id:` 없음·복구 없음·발신자 제외 없음**(본인에게도 돌아옴), `messages` 와 상대 순서 무보장. 방 상태는 전용 단일 스레드(`chat-likes-throttler`)만 만진다 — 구독 스레드는 `offer` 로 넘기기만 하고 소켓엔 안 쓴다(쓰기 풀 경유).
- ⚠ **`RedisMessageListenerContainer` 를 빈으로 두지 말 것**: `start()` 가 첫 구독 실패 시 예외를 던져 Redis 가 죽어 있으면 앱 기동이 실패하고, 첫 시도에는 자동 복구가 없다. 그래서 `LikesSubscriber` 가 시도마다 새 컨테이너를 만들어 별도 스레드에서 1초→30초 백오프로 재시도한다. 구독이 맺어진 뒤의 끊김은 Lettuce 가 재연결·재구독하며 열린 SSE 는 안 닫는다.
- **좋아요 설정·메트릭**: `chat.likes.{rate-limit.per-second(10), team-cache-ttl-seconds(300), throttle-window-ms(100), publish-queue-capacity(10000)}` — 메시지 배처 `chat.gateway.batch-interval-ms`(150)와 별개 키. **`ChatProperties` 가 아닌 별도 레코드 `ChatLikesProperties`**: 테스트 여러 곳이 `ChatProperties` 정식 생성자를 직접 부르므로 성분을 늘리면 전부 깨진다. 메트릭 `chat.likes.dropped{reason=rate-limit|no-team|team-lookup-failed|queue-full}` · `chat.likes.publish.failed`.
- **인코딩된 세미콜론 gameId**(`%3B`)는 Spring Security 방화벽이 `ApiResponse` 래퍼 없는 400 으로 거부한다(좋아요뿐 아니라 `/rooms/{gameId}/**` 공통).
- **스레드 모델**: 컨슈머 스레드는 방별 큐 적재만 → 배처 스레드가 150ms 마다 프레임 구성 → `SseFrameWriter` 쓰기 풀이 소켓에 쓴다. 느린 클라이언트는 watchdog 이 끊고 `complete()` 는 closer 스레드. 소켓 쓰기를 요청·컨슈머·배처 스레드가 하지 않는다.
- **SmartLifecycle 종료 순서**(높은 phase 가 먼저 멈춤): `GatewayConsumer`·`LikesSubscriber`(MAX-10) → `RoomBatcher`·`LikesThrottler`(-20) → `SseFrameWriter`(-30) → `SseEmitterRegistry`(-40). 전부 웹 서버 graceful shutdown(DEFAULT_PHASE-1024)보다 높아야 열린 SSE 가 그 단계를 붙잡지 않는다. **lifecycle 빈은 stop() 뒤 start() 로 다시 살 수 있어야 한다**(테스트 컨텍스트 캐시가 재시작시킴 — 종료된 실행기 재사용 시 `RejectedExecutionException`). `SseFrameWriter` 는 웹 서버가 start() 보다 먼저 떠서 생성자에서도 실행기를 만든다.
- **`ChatSseEmitter.tryComplete`**: Spring 7 `ResponseBodyEmitter` 의 `send()`·`complete()` 가 같은 `writeLock` 을 잡아, 느린 클라이언트에 쓰는 중인 emitter 를 다른 스레드가 `complete()` 하면 그 스레드도 멈춘다. 잠금을 바로 얻을 때만 닫고, 아니면 호출부가 closer 로 넘긴다.
- **SSE 구독 규약**: 첫 프레임은 주석 `:connected`(응답 헤더 flush). 표준 `EventSource` 는 Authorization 헤더를 못 실어 401 → fetch 폴리필. `spring.mvc.async.request-timeout: 30m` 은 SSE 타임아웃(30분)보다 낮추지 말 것. `open-in-view: false`.
- **`SubscriptionExceptionHandler`**(구독 컨트롤러 전용 advice): SSE 클라이언트는 `Accept: text/event-stream` 만 보내서, 공유 핸들러의 `ResponseEntity<ApiResponse>` 가 컨버터 협상에 실패(HttpMediaTypeNotAcceptable)해 404·503 대신 **500** 이 났다. 응답에 JSON 을 직접 쓴다. web-support 를 안 고치려 범위를 구독 컨트롤러로 좁힌 것.
- **SecurityConfig 의 ASYNC·ERROR permitAll 이유**: 서버가 SSE 를 `complete()`(퇴장·축출·타임아웃)하면 같은 요청이 ASYNC 로 재디스패치되는데, 무상태 JWT 라 그 디스패치엔 SecurityContext 가 없어 인가 거부 + ERROR 로그가 남는다. 원 요청은 이미 인가를 통과했다. (quiz 에는 이 허용이 없다 — `quiz.md` 알려진 문제.)
- **actuator**: 노출 `health,metrics`, metrics 는 인증 필요(공개 금지). ALB 헬스체크는 `/chat/actuator/health/readiness`. **`KAFKA_BOOTSTRAP_SERVERS` 는 prod 에서 필수**(기본값 없음, dev 프로파일만 `localhost:29092`). `JWT_SECRET` 은 user 와 동일해야 한다(검증만).
- **로컬 검증**: `.env` 가 원격 DB 를 가리킨다 — chat 은 `ddl-auto=none` 이지만 원격 DB 에 쓰기 경로를 돌리지 말 것. Redis·Kafka 는 compose(`redis`·`kafka`·`kafka-init`, 호스트 `localhost:29092`, 토픽 파티션 3).
- **검증 이력(2026-10-09)**: 테스트 `:profanity` 19 · `:quiz` 497 통과, `:chat:bootRun` 실경로·컨테이너(prod 프로파일) 검증 PASS(채팅 본체). **EKS 미검증**(ALB·Ingress·매니페스트 실배포 없음).
- **검증 이력(2026-10-09, 좋아요)**: `:chat` 567 통과. 전체 실행 시 기존 `ChatSendFlowIT` [CHAT-GC-101] 랙 게이지 테스트가 간헐 실패, 단독 재실행은 통과 — **플레이키**(좋아요 무관). `bootRun` 실측: 클릭→수신 지연 16~22ms, 연타 스로틀 간격 ~105ms, Redis 정지 중에도 202 즉시, Redis 복구 뒤 재구독 ~4초. 컨테이너(prod, chat 2개 + compose redis·kafka): 파드 간 전달 지연 18~36ms·NUMSUB=2, 두 파드 분산 연타에도 구독자당 ~10프레임/초, Redis 정지 중 202 ≤38ms·SSE 유지·재기동 후 재구독 1.5초, 회귀·축출 ERROR 0. EKS(운영 Redis·HPA·CloudFront) 미검증.
