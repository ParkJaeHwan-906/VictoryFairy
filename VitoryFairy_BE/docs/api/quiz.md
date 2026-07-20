# quiz API 명세

> 코드 기준 자동 작성. 포트 **8081**(`quiz/src/main/resources/application.yaml`의 `server.port: 8081`), `server.servlet.context-path` 미설정이므로 base URL은 `http://localhost:8081`.
> 최종 갱신: 2026-07-20 (구단별 채팅 API 신규 문서화)
> 대상 컨트롤러: `quiz/src/main/java/com/skhynix/quiz/chat/controller/ChatController.java` (`@RequestMapping("/api/chat")`) — 현재 quiz 모듈의 유일한 컨트롤러.
> 인증: JWT Bearer (`Authorization: Bearer <accessToken>`). `SecurityConfig`가 `/`, `/error`, `GET /health`만 permitAll이고 그 외 `anyRequest().authenticated()`이므로 **`/api/chat/**` 6개 엔드포인트 전부 인증 필수**다. 인증 방식·401 형식은 `docs/api/user.md`의 "인증 방식" 절과 동일(`RestAuthenticationEntryPoint`가 필터 단계에서 401 `ApiResponse` JSON을 직렬화). `@AuthenticationPrincipal Long userAccountId`는 `JwtAuthenticationFilter`가 토큰 `sub`(uid)를 활성 계정의 내부 PK로 변환해 주입한 값이다.

## 공통 사항

### 응답 포맷
`ChatController`의 6개 엔드포인트 모두 `ResponseEntity<ApiResponse<T>>`를 반환한다(`SseEmitter` 반환인 구독 엔드포인트만 예외 — 아래 참고). `ApiResponse<T>` = `{ success, data, message }`(`:common`).

- 성공: `{ "success": true, "data": <T>, "message": null }`
- 실패(`BusinessException`): `{ "success": false, "data": null, "message": "<ErrorCode 메시지>" }`, 상태코드는 `ErrorCode.getStatus()`.
- 실패(Bean Validation, `MethodArgumentNotValidException`): `{ "success": false, "data": {"필드명":"메시지", ...}, "message": "입력값이 올바르지 않습니다." }`, 상태코드 `400`.

이 변환은 `quiz`의 `SecurityConfig`가 `web-support`의 `GlobalExceptionHandler`(`@RestControllerAdvice`)를 `@Import`로 명시 등록해 이루어진다(quiz 모듈 컨텍스트 참고 — 좁은 컴포넌트 스캔 범위 밖이라 자동 감지되지 않으므로 이 import가 빠지면 `BusinessException`이 스프링 기본 500으로 나간다).

### 외부 식별자
- 채팅방은 `roomUid`(`Chatroom.uid`, UUID)로만 노출된다. 응답 어디에도 순차 PK가 나타나지 않는다.
- 메시지는 **외부 식별자가 없다**. `MessageResponse`/`MessageEvent`(SSE payload)에는 메시지 PK가 실리지 않는다. 신고(`POST .../messages/{messageId}/report`)의 `{messageId}`는 내부 PK를 **room-스코프 경로**로만 노출한 것으로, SSE·히스토리 응답에서 이 값을 얻을 방법은 없다(신고 UI는 별도 컨텍스트로 메시지를 식별해야 함 — 코드상 제약, 클라이언트 설계는 이 문서 범위 밖).
- 발신자/작성자 계정 PK(`user_account_id`)도 응답에 노출되지 않는다. `senderNickname`(`UserAccount.nickname`)만 노출된다.

### 관리자 기능은 범위 밖
blind 해제(unblind), 메시지/방 삭제를 수행하는 엔드포인트는 코드에 없다(`Chat.unblind()`/`Chat.delete()`/`Chatroom.delete()`는 엔티티에 구현돼 있으나 어떤 컨트롤러에서도 호출되지 않는다). 이 문서는 그런 엔드포인트를 다루지 않는다.

---

## GET /api/chat/rooms
채팅방 목록(소프트삭제 제외).

**인증 필요** — `Authorization: Bearer <accessToken>`

**요청**: 파라미터 없음.

**응답 200 OK** `ApiResponse<List<RoomResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자(UUID) |
| team | String | 구단(팀) 이름 |
| name | String | 방 이름 |
| participants | int | 현재 SSE 구독 수(best-effort, 아래 "participants 집계 방식" 참고) |

`Chatroom.deletedAt`이 채워진 방은 목록에서 제외된다(`chatroomRepository.findAllByDeletedAtIsNull()`).

**실패**: 없음(빈 목록도 200).

**예시**
```bash
curl -i http://localhost:8081/api/chat/rooms \
  -H 'Authorization: Bearer eyJ...'
```

---

## GET /api/chat/rooms/{roomUid}
채팅방 상세.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**응답 200 OK** `ApiResponse<RoomResponse>` — 필드는 목록 항목과 동일(`roomUid`/`team`/`name`/`participants`).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성(비삭제) 방이 없음(존재하지 않거나 소프트삭제됨) |

**예시**
```bash
curl -i http://localhost:8081/api/chat/rooms/3f9c2e10-... \
  -H 'Authorization: Bearer eyJ...'
```

---

## GET /api/chat/rooms/{roomUid}/subscribe
방 실시간 구독(SSE). `produces = text/event-stream`. 반환 타입은 `SseEmitter`이며 `ApiResponse`로 감싸지 않는다(다른 5개 엔드포인트와 다름 — 이벤트 스트림이라 JSON 래핑 대상이 아님).

**인증 필요** — `Authorization: Bearer <accessToken>`. **표준 브라우저 `EventSource`는 커스텀 헤더를 실을 수 없으므로 이 엔드포인트를 그대로 쓸 수 없다.** 클라이언트는 fetch 기반 EventSource 폴리필로 `Authorization: Bearer` 헤더를 유지한 채 스트림을 열어야 한다(쿼리 파라미터·쿠키 토큰 방식은 서버가 지원하지 않음).

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**연결 성립 시 동작**: `Content-Type: text/event-stream`으로 200 응답을 열고 연결을 유지한다. 구독이 성립하는 순간 해당 방의 `participants`(=`SseEmitterRegistry`의 구독 수)가 1 증가하고, 연결이 끝나면(완료/타임아웃/오류 콜백) 1 감소한다. 같은 사용자가 여러 탭에서 구독해도 연결 단위로 각각 +1 카운트된다.

**이벤트 계약**
| 이벤트 | `event:` | `data:` | 설명 |
|---|---|---|---|
| 메시지 | `message` | JSON `{content, senderNickname, createdAt, roomUid}`(`MessageEvent`) | 같은 방에 새 메시지가 저장될 때 전달. **메시지 식별자 필드 없음.** `id:` 필드도 없다(Last-Event-ID 미지원 — 재연결 시 놓친 메시지는 `GET .../messages`로 복구할 것) |
| 하트비트 | 없음 | 없음(SSE 주석 `:ping`) | `SseEmitterRegistry.heartbeat()`가 **15초 주기**로 전송. 서버는 전송 실패를 감지하면 그 연결을 죽은 것으로 간주해 즉시 회수하고 `participants`를 보정한다 |

**발신자 에코 없음**: 메시지를 보낸 사용자 본인의 emitter는 fan-out에서 제외된다(서버가 emitter를 `userAccountId`로 식별해 발신자 구독에는 전달하지 않음). 발신자는 `POST .../messages`의 201 응답으로만 자기 메시지를 렌더해야 한다. 발신자의 다른 탭(멀티탭)은 실시간으로 받지 못하며 재접속/히스토리 조회로 보정해야 한다(알려진 한계).

**연결 타임아웃**: `SseEmitter` 타임아웃은 **30분**(`EMITTER_TIMEOUT_MS`). 타임아웃 시 서버가 연결을 `complete()`하고 구독을 해제(participants -1)한다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음(스트림을 열지 않는다) |
| 401 | UNAUTHENTICATED | 인증 헤더 없음/무효(엔트리포인트 단계, 스트림 열기 전) |

**예시(fetch 기반 폴리필 개념 — 실제 라이브러리는 프로젝트마다 다름)**
```bash
curl -i -N http://localhost:8081/api/chat/rooms/3f9c2e10-.../subscribe \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Accept: text/event-stream'
```

---

## POST /api/chat/rooms/{roomUid}/messages
메시지 전송. 저장 후 발신자를 제외한 같은 방 구독자에게 SSE `message` 이벤트로 전달(fire-and-forget)하고, 저장된 메시지를 응답으로 반환한다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**요청** `SendMessageRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| content | String | `@NotBlank` `@Size(max = 500)` | 메시지 내용. `null`·빈 문자열·공백만은 `@NotBlank` 위반. 길이는 `String.length()`(UTF-16 code unit) 기준 — 이모지 surrogate pair는 2로 계수 |

**검증 순서(중요)**: `@Valid`가 컨트롤러 진입 전(인자 바인딩 단계)에 수행되므로, **content 위반(400)이 방 존재 여부 확인(404)보다 먼저 판정**된다. 즉 존재하지 않는 `roomUid`에 빈 content로 요청해도 404가 아니라 400이 난다.

**응답 201 Created** `ApiResponse<MessageResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| content | String | 저장된 메시지 내용 |
| senderNickname | String | 발신자 `UserAccount.nickname` |
| createdAt | LocalDateTime | 생성 시각 |

저장 시 `Chat.blind=false`, `deletedAt=null`로 저장된다. 메시지 식별자는 응답에 없다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `content`가 공백뿐이거나 501자 이상. 저장하지 않는다. content 위반이 아래 404보다 우선 판정됨 |
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음(존재하지 않거나 소프트삭제). 저장하지 않는다 |

실시간 전달(SSE fan-out) 실패는 `ChatService.publishMessage()`가 예외를 삼켜(fire-and-forget) 저장·201 응답에 영향을 주지 않는다.

**예시**
```bash
curl -i -X POST http://localhost:8081/api/chat/rooms/3f9c2e10-.../messages \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"content":"안녕하세요"}'
```

실패 예시(content 공백, 400):
```json
{"success":false,"data":{"content":"공백일 수 없습니다"},"message":"입력값이 올바르지 않습니다."}
```
(실제 `@NotBlank` 기본 메시지는 스프링/Hibernate Validator의 로케일 기본 문구를 그대로 쓴다 — 커스텀 message 속성 미부착.)

---

## GET /api/chat/rooms/{roomUid}/messages
방 히스토리 조회(페이징).

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**쿼리 파라미터**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| page | int | `0` | 0-base 페이지 번호 |

페이지 크기는 서버가 고정한 **30**(`HISTORY_PAGE_SIZE`)이며 쿼리로 바꿀 수 없다. 정렬은 `createdAt` 내림차순(최신순) 고정.

**응답 200 OK** `ApiResponse<PageResponse<MessageResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| content | List\<MessageResponse\> | 현재 페이지 항목(`content`/`senderNickname`/`createdAt`) |
| page | int | 현재 페이지 번호(0-base) |
| size | int | 페이지 크기(30) |
| totalElements | long | 조건을 만족하는 전체 메시지 수 |
| totalPages | int | 전체 페이지 수 |
| hasNext | boolean | 다음 페이지 존재 여부 |

`blind=true`이거나 `deletedAt`이 채워진 메시지는 결과에서 제외된다(`findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc`).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음 |

**예시**
```bash
curl -i "http://localhost:8081/api/chat/rooms/3f9c2e10-.../messages?page=0" \
  -H 'Authorization: Bearer eyJ...'
```

---

## POST /api/chat/rooms/{roomUid}/messages/{messageId}/report
메시지 신고 → 즉시 blind 전환(자동, 관리자 개입 없음, 멱등).

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |
| messageId | Long | 신고 대상 메시지의 내부 PK. **room-스코프 경로로만 지목**하는 구조(`chatRepository.findByIdAndChatroom(messageId, room)`)이며 SSE·히스토리 응답에서는 이 값을 얻을 수 없다 |

**요청**: 본문 없음.

**응답 200 OK** `ApiResponse<Void>` = `{"success":true,"data":null,"message":null}`

**동작**: `Chat.blind()` 호출로 대상 메시지를 즉시 `blind=true`로 전환한다. 이미 `blind=true`인 메시지를 재신고하면 값이 그대로 유지되어 **no-op(멱등)**이며 여전히 200을 반환한다. 신고 이력은 저장되지 않아 재신고 횟수는 추적되지 않는다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음 |
| 404 | CHAT_MESSAGE_NOT_FOUND | `messageId`가 그 방에 없거나(`findByIdAndChatroom` 실패), 존재하지만 `deletedAt`이 채워진(소프트삭제된) 메시지 |
| 403 | SELF_REPORT_NOT_ALLOWED | 신고자(`userAccountId`)가 대상 메시지 작성자(`Chat.userAccount.id`)와 동일 |

검증 순서: 방 존재 → 메시지 존재(`findByIdAndChatroom`) → 메시지 삭제 여부(404) → 자기 신고 여부(403) → blind 적용(코드상 `ChatService.reportMessage()`의 순서 그대로).

**예시**
```bash
curl -i -X POST http://localhost:8081/api/chat/rooms/3f9c2e10-.../messages/42/report \
  -H 'Authorization: Bearer eyJ...'
```

자기 신고 실패 예시(403):
```json
{"success":false,"data":null,"message":"자신의 메시지는 신고할 수 없습니다."}
```

---

## participants 집계 방식 (참고 — 여러 엔드포인트 공통)
`participants`는 DB 컬럼이 아니라 `SseEmitterRegistry`(인메모리)의 현재 SSE 구독 수로 매 요청 서빙된다(`GET /rooms`, `GET /rooms/{roomUid}` 둘 다). connect/disconnect마다 DB write를 피하기 위한 best-effort 방식이며, 앱을 여러 인스턴스로 띄우면 인스턴스별로 다른 값이 나올 수 있다(전역 집계는 이번 범위에 없음). `Chatroom.participants` 컬럼과 `join()`/`leave()` 메서드는 엔티티에 남아 있으나 이 6개 엔드포인트 중 어디서도 쓰이지 않는다.

## 다중 인스턴스 fan-out은 아직 미구현
`RealtimeEventPublisher` 포트 구현체는 `quiz/src/main/java/com/skhynix/quiz/realtime/` 안에 **`InMemoryPublisher` 하나뿐**이다(같은 프로세스의 `SseEmitterRegistry`로 직접 전달). `docs/requirements/quiz/chat.md`가 언급하는 `RedisPubSubPublisher`(다중 인스턴스 운영용)는 **코드에 존재하지 않는다** — `RealtimeEventPublisher.java`의 Javadoc에도 `TODO(다중 인스턴스)`로 명시돼 있다. 즉 현재 API는 **단일 인스턴스 배포에서만** "인스턴스 A로 구독한 클라이언트가 인스턴스 B로 온 전송을 받는다"는 요구사항(QUIZ-CHAT-16)을 충족하며, 다중 인스턴스로 스케일아웃하면 다른 인스턴스로 들어온 메시지는 SSE로 전달되지 않는다(저장·히스토리 조회에는 영향 없음).

## 확인 필요 / 코드 미확인
- `@NotBlank`/`@Size` 위반 시 실제 필드 검증 메시지 문구는 Hibernate Validator 기본 로케일 메시지를 그대로 쓰며(커스텀 `message` 속성 미부착), 이 문서의 예시 문구(`"공백일 수 없습니다"`)는 기본값 추정이다 — 실행 환경(로케일 설정)에 따라 문구가 달라질 수 있어 실측 확인 필요.
