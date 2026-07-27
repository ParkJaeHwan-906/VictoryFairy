# quiz 모듈

> quiz 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto`는 앱마다 다름 — user=`update`, quiz=`none`)

## 책임
Quiz 도메인 REST API 서버. **구단별 채팅 기능 구현 완료**: `chat`(방 목록/상세·전송·히스토리·신고 REST) + `realtime`(SSE 실시간 전달). **포트 8081.**

## 핵심 클래스 (`quiz/src/main/java/com/skhynix/quiz/`)
- `QuizApplication` — 메인 진입점. `scanBasePackages = "com.skhynix.quiz"`로 **좁게** 스캔 (user는 `com.skhynix` 전체). `@EntityScan`/`@EnableJpaRepositories`만 `com.skhynix` 전역.
- `SecurityConfig` — stateless, `@Import({JwtVerificationConfig.class, GlobalExceptionHandler.class})`로 `web-support`의 JWT 검증 부품 + 예외 어드바이스를 명시적으로 끌어옴(좁은 스캔이 `com.skhynix.websupport.*`를 놓쳐 자동 감지 안 됨). `securityFilterChain`이 `JwtAuthenticationFilter`를 직접 `new`로 생성하며 `UserAccountRepository`(`:domain`, uid→id 조회)도 함께 넘김 — `web-support`의 필터 생성자가 바뀌면 여기도 같이 고쳐야 함. `/`, `/error`, `GET /health` 외 전부 인증 필수. 미인증 401도 `web-support`의 `RestAuthenticationEntryPoint`를 그대로 import해 등록(동일 커플링 — 생성자 바뀌면 같이 고칠 것)
- `chat/controller/ChatController` — `/api/game/chat/**` REST+SSE. principal은 `@AuthenticationPrincipal Long userAccountId`(`JwtAuthenticationFilter`가 주입)
- `chat/service/ChatService` — 방 조회/구독/전송/히스토리/신고 로직. 전송 성공 후 SSE 발행은 fire-and-forget(예외 삼킴, 저장·응답은 되돌리지 않음)
- `realtime/RealtimeEventPublisher` — 실시간 전송 포트(토픽=roomUid). **quiz 정답 집계 등 향후 기능도 재사용할 일반 설계**. 구현은 프로파일로 갈린다: `prod`=`RedisPubSubPublisher`(단일 채널 `realtime:events` 로 발행) + `RealtimeEventSubscriber`/`RealtimeRedisConfig`(리스너 컨테이너가 전 파드에서 수신 → 자기 레지스트리로 전달), `!prod`=`InMemoryPublisher`. 발행 파드도 자기 구독으로 되받으므로 Redis 구현은 로컬 레지스트리로 직접 전달하지 않는다(이중 전달 방지 — 테스트로 고정). quiz-app HPA maxReplicas=4 이므로 이 경로가 없으면 스케일아웃 시 메시지가 조용히 유실된다
- **로컬 자동 시드(dev 전용)**: 기동 시 `infra/sql/teams-init.sql` → `chat-init.sql` 을 순서대로 실행해 구단 10개·SYSTEM 계정·구단별 채팅방 10개를 채운다(`application-dev.yaml` 의 `spring.sql.init`, 파일은 `build.gradle` 의 `processResources` 가 `classpath:sql/` 로 복사 — 시드의 단일 출처는 `infra/sql`). 전부 `INSERT ... WHERE NOT EXISTS` 라 매 기동 재실행해도 중복이 없다. `defer-datasource-initialization: true` 로 Hibernate 초기화 뒤에 돈다. **prod 는 해당 없음**(ddl-auto=none + 수동 적용 규칙 유지). dev `ddl-auto=update` 와 맞물려 **빈 DB 에서 `:quiz:bootRun` 한 번이면 스키마 15테이블 + 시드까지 완성**된다(실측 확인)
- `realtime/SseEmitterRegistry` — 방별 SSE 구독 관리. 타임아웃 30분·하트비트 15초(`:ping` 주석 프레임). **participants는 DB 컬럼이 아니라 이 레지스트리의 현재 구독 수로 서빙**(connect/disconnect마다 DB write가 폭주하는 걸 피함). ⚠ 파드별 카운트라 **다중 파드에서는 실제보다 작게 나온다** — 메시지 fan-out은 Redis pub/sub로 해결됐지만 participants 전역 집계는 여전히 후속 과제. `Chatroom.participants`/`join()`/`leave()`는 domain에 존재하나 이번 범위에서 미사용
- `realtime/RealtimeSchedulingConfig` — `@EnableScheduling`으로 하트비트 구동(quiz 좁은 스캔 범위 안이라 자동 등록)

## 엔드포인트 (`/api/game/chat`)
- `GET /rooms` → 방 목록(소프트삭제 제외, participants=구독 수)
- `GET /rooms/{roomUid}` → 방 상세. 없거나 삭제된 방 404
- `GET /rooms/{roomUid}/subscribe` → SSE 구독(`text/event-stream`). 표준 `EventSource`는 헤더를 못 실어 인증 실패(401) — fetch 기반 폴리필로 `Authorization` 헤더를 실어야 함
- `POST /rooms/{roomUid}/messages` → 전송(`@Valid` content 검증). 저장 후 발신자 제외 구독자에게 SSE 전달, 201
- `GET /rooms/{roomUid}/messages?page=` → 히스토리(최신순 30건 페이징, blind·삭제 제외)
- `POST /rooms/{roomUid}/messages/{messageId}/report` → 신고 → 즉시 blind(멱등). 자기신고 403, 삭제 메시지 404

## 의존
- 모듈: `:common`, `:domain`, `:web-support`(JWT 검증 부품·예외 핸들러·401 엔트리포인트 재사용 — 상세는 `.claude/modules/web-support.md`). **user 앱 자체에는 더 이상 의존하지 않음**(이전엔 `:user`를 직접 참조해 앱간 의존이었으나 web-support 추출로 해소)
- 라이브러리: JPA, Security, WebMVC, **WebSocket**(빌드 의존은 있으나 미사용 — 채팅 실시간 전달은 WebSocket이 아니라 Spring MVC `SseEmitter` 기반 SSE로 구현됨), **Validation**(`@Valid`/`@Size`, 채팅 content 검증), DevTools, MySQL, dotenv. JJWT는 web-support가 전이 제공

## 주의 / 컨벤션
- **JWT_SECRET을 user와 동일하게** (.env 공유) — 불일치 시 토큰 검증 실패
- 응답 `ApiResponse<T>`, 예외 `BusinessException`+`ErrorCode`(common). `GlobalExceptionHandler`는 `com.skhynix.websupport.error`에 있어 quiz의 좁은 스캔 범위 밖이지만 `SecurityConfig`가 `@Import`로 명시 등록해 quiz에도 활성화되어 있다 — `BusinessException`을 던지면 `ApiResponse.fail`로 정상 변환된다(예전엔 미등록이라 스프링 기본 500이 나가는 열린 항목이었으나 해소됨)
- domain 엔티티는 패키지 스캔으로 자동 로드
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=update`(2026-07-27 `validate`에서 변경 — 로컬을 기동 한 번으로 세우기 위함. additive 라 컬럼 삭제·타입 변경은 반영 안 됨), show-sql ON
- Redis 환경변수: `REDIS_HOST`/`REDIS_PORT`(`spring.data.redis.*`) — prod 실시간 fan-out 전용. dev 는 연결을 맺는 빈이 없어 불필요하며, 액추에이터 redis 인디케이터도 dev 에서 꺼둔다
- `application.yaml`(base)에 SSE용 설정 2개가 전 프로파일 공통 고정: `spring.mvc.async.request-timeout: 30m`(톰캣 기본 30초에 SSE가 조기 종료되는 걸 막는 안전망 — `SseEmitterRegistry`의 SSE 자체 타임아웃보다 낮추지 말 것) · `spring.jpa.open-in-view: false`(SSE 롱커넥션이 응답 끝까지 JPA 커넥션을 잡으면 Hikari 풀이 고갈될 위험 — LAZY 접근은 반드시 `@Transactional` 서비스 메서드 안에서 끝낼 것)
- `SecurityConfig`가 user와 90% 동일해 중복이지만 **의도적으로 유지하기로 결정**: 사례가 user·quiz 둘뿐이라 공통 모듈로 뽑을 만큼 반복되지 않고, permitAll 규칙이 서로 달라 공통화하면 어색한 훅이 필요해진다. 다시 논의할 필요 없음
- 테스트 현황: chat 기능 74개 그린(controller 슬라이스 33 · service 19 · `SseEmitterRegistry` 15 · `InMemoryPublisher` 1 · `RedisPubSubPublisher` 3 · `RealtimeEventSubscriber` 3). 인증 슬라이스는 `@WithMockUser` 대신 `SecurityMockMvcRequestPostProcessors.authentication(...)`으로 실제 필터가 만드는 것과 동일한 `Long` principal을 주입(`@AuthenticationPrincipal Long`은 타입이 정확히 일치해야 바인딩되고 `@WithMockUser`는 `User`/문자열 principal을 만들어 못 씀). 401 미인증 배선도 이 슬라이스로 검증됨(이전 "quiz는 테스트 소스셋이 없어 401이 미검증"이던 열린 항목 해소). 미커버: DB 시드 기반 라운드트립, 실 JWT 토큰 파싱, Redis 계열(환경 한계 — `domain`/`web-support`와 동일한 제약)
