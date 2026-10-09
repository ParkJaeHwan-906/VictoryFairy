# 경기별 채팅(game-chat) API 명세

> 소속 모듈 `chat` (포트 8082) · 경로 접두사 `/chat` (`server.servlet.context-path`) · 엔드포인트 7개
> 최종 갱신: 2026-10-09 (신규 문서 — chat 모듈 최초 명세)
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md) 참고.
> 대상 컨트롤러: `chat/src/main/java/com/skhynix/chat/room/controller/ChatRoomController.java`, `message/controller/ChatMessageController.java`, `subscription/controller/ChatSubscriptionController.java` (+ `SubscriptionExceptionHandler`)
> 계약 원본: `docs/requirements/chat/game-chat.md` (CHAT-GC-*, 승인 2026-10-09)
> 파일명 주의: `chat.md`는 기존 quiz 모듈의 `/rt/chat/**` 문서가 이미 쓰고 있어(병행 운영 중, 수정 금지) 이 문서는 `game-chat.md`로 둔다.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/chat/rooms](#get-chatrooms) | 200 | 오늘 경기 = 오늘 채팅방 목록 |
| GET | [/chat/rooms/{gameId}](#get-chatroomsgameid) | 200 | 채팅방 상세 |
| GET | [/chat/rooms/{gameId}/subscribe](#get-chatroomsgameidsubscribe-sse) | 200 (SSE) | 실시간 구독 |
| DELETE | [/chat/rooms/{gameId}/subscribe](#delete-chatroomsgameidsubscribe) | 200 | 명시적 퇴장 |
| POST | [/chat/rooms/{gameId}/messages](#post-chatroomsgameidmessages) | **202** | 메시지 전송 |
| GET | [/chat/rooms/{gameId}/messages](#get-chatroomsgameidmessages) | 200 | 히스토리(커서) |
| POST | [/chat/rooms/{gameId}/messages/{msgId}/report](#post-chatroomsgameidmessagesmsgidreport) | 200 | 신고 → 즉시 blind |

## 이 도메인의 특이사항

- **7개 전부 인증 필수.** `SecurityConfig` permitAll은 `/`, `/error`, `GET /actuator/health/**`뿐이고 나머지는 `anyRequest().authenticated()`. JWT Bearer, user와 같은 secret·같은 `JwtAuthenticationFilter`(비밀번호 변경 이전 발급 토큰 401 규칙도 동일). 인증 실패는 401 `UNAUTHENTICATED`.
- **응답은 SSE 구독(GET)을 뺀 6개가 `ApiResponse<T>`** (`{success,data,message}`).
- `gameId`는 `GET /api/games`의 `gameId`(= `naver_game_id`)와 같은 값이다. **응원 구단 검사는 어디에도 없다**(구단 가드 없음).
- **방의 존재 = Redis 방 메타.** 메타가 없어도 상세·구독·전송은 "오늘 경기"면 그 자리에서 방을 만든다. 히스토리·신고는 만들지 않아 메타가 없으면 404다. 경기가 오늘이 아니거나 없으면 404.
- **Redis 장애 시 방 존재 확인 단계에서 503** `CHAT_BROKER_UNAVAILABLE`. (방 목록은 Redis를 보지 않아 200)
- **`msgId` = Kafka 오프셋(Long)**: 연속이 아니다(띄엄띄엄). 크기 비교로만 쓸 것.
- 시각 `sentAt`: 오프셋 포함 ISO-8601 밀리초 3자리, 예 `2026-10-09T19:03:21.482+09:00`(Asia/Seoul). 변환 없이 표시.
- 표준 `EventSource`는 `Authorization` 헤더를 못 실어 401이다 → fetch 기반 SSE 폴리필로 구독.
- 400(Bean Validation) 본문은 공유 `GlobalExceptionHandler` 형식: `{"success":false,"data":{"<필드>":"<메시지>"},"message":"입력값이 올바르지 않습니다."}`.

### 공통 객체 `ChatMessageView` (히스토리 항목 · SSE `messages` 배열 항목)
| 필드 | 타입 | 설명 |
|---|---|---|
| msgId | long | Kafka 오프셋 |
| content | String | 마스킹 결과(금지어는 `*`로 치환) |
| senderNickname | String | **전송 시점 스냅샷** (이후 닉네임 변경 소급 안 됨) |
| teamCode | String? | 발신자 응원 구단 코드(없으면 null, 전송 시점 스냅샷) |
| profileImgUrl | String? | 프로필 이미지(없으면 null, 전송 시점 스냅샷) |
| sentAt | String | 위 형식 |

계정 id는 싣지 않는다.

---

## GET /chat/rooms
> 최종 변경: 2026-10-09 — 신규 추가

오늘 경기 목록 = 오늘 채팅방 목록. `games.game_date`가 오늘(KST)인 경기를 시각 오름차순으로, `naver_game_id`가 없는 경기는 제외. 경기가 없는 날은 빈 배열. Redis를 보지 않아 Redis 장애에도 200.

**인증** 필요

**응답 200** `ApiResponse<List<RoomResponse>>`
| 필드 | 타입 | 설명 |
|---|---|---|
| gameId | String | `naver_game_id` — 이후 모든 경로의 `{gameId}` |
| homeTeam | String | 홈 구단명 |
| homeTeamId | Long | |
| awayTeam | String | 원정 구단명 |
| awayTeamId | Long | |
| gameDate | LocalDateTime | 경기 시작 시각(오프셋 없는 KST 벽시계) |
| gameState | String | 경기 상태명 |

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 토큰 없음·무효 |

**예시**
```bash
curl http://localhost:8082/chat/rooms -H 'Authorization: Bearer <accessToken>'
```

## GET /chat/rooms/{gameId}
> 최종 변경: 2026-10-09 — 신규 추가

채팅방 상세. 응답 형태는 목록 항목(`RoomResponse`)과 같고 값은 `games`의 현재 값이다(방 메타는 경기 상태를 들고 있지 않다).

**인증** 필요 · **경로 변수** `gameId`(String)

**응답 200** `ApiResponse<RoomResponse>` (필드는 위와 동일)

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | CHATROOM_NOT_FOUND ("존재하지 않는 채팅방입니다.") | 메타 없음 + 경기가 없거나 오늘이 아님 |
| 503 | CHAT_BROKER_UNAVAILABLE | 방 메타 조회/지연 생성 중 Redis 실패 |

**예시**
```bash
curl http://localhost:8082/chat/rooms/20261009HTLG0 -H 'Authorization: Bearer <accessToken>'
```

## GET /chat/rooms/{gameId}/subscribe (SSE)
> 최종 변경: 2026-10-09 — 신규 추가

방 실시간 구독. `text/event-stream`. 프레임 규약은 아래 "SSE 이벤트" 절.

**인증** 필요 (fetch 폴리필 + `Authorization` 헤더) · **경로 변수** `gameId` · **요청 헤더** `Last-Event-ID`(선택): 마지막으로 처리한 `id:` 값. 0 이상의 정수가 아니면 **무시**(복구 없이 실시간만).

**응답 200** `text/event-stream` (`ApiResponse` 래퍼 아님)

**실패** (구독 전용 핸들러가 `Accept: text/event-stream`에서도 JSON 본문 `{"success":false,"data":null,"message":...}`로 직접 쓴다 — 500으로 둔갑하지 않는다)
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | CHATROOM_NOT_FOUND | 방 없음(이때 구독은 등록되지 않음) |
| 503 | CHAT_BROKER_UNAVAILABLE | Redis 실패 |

**예시**
```bash
curl -N http://localhost:8082/chat/rooms/20261009HTLG0/subscribe \
  -H 'Authorization: Bearer <accessToken>' -H 'Accept: text/event-stream' -H 'Last-Event-ID: 7100'
```

## DELETE /chat/rooms/{gameId}/subscribe
> 최종 변경: 2026-10-09 — 신규 추가

명시적 퇴장. 이 사용자의 해당 방 구독을 닫고, 다른 파드의 구독에도 종료 명령을 발행한다. **방 존재를 확인하지 않는다**(없는 방·구독 없음이어도 200). 종료 명령 발행이 실패해도 200(다른 파드 구독은 타임아웃·하트비트로 회수).

**인증** 필요 · **응답 200** `ApiResponse<Void>` (`data: null`)

**실패** 401 UNAUTHENTICATED 외 없음.

**예시**
```bash
curl -X DELETE http://localhost:8082/chat/rooms/20261009HTLG0/subscribe -H 'Authorization: Bearer <accessToken>'
```

## POST /chat/rooms/{gameId}/messages
> 최종 변경: 2026-10-09 — 신규 추가

메시지를 전송한다. Kafka `chat-messages`에 produce하고 **202**를 돌려준다(히스토리·다른 구독자 전달은 비동기). **발신자 본인에게는 SSE로 되돌려 주지 않으니** 202 응답의 `msgId`·`content`로 렌더한다.

**인증** 필요

**요청** `SendMessageRequest` (컨트롤러에 `@Valid` 없음 — 서비스가 방 확인 뒤 검증)
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| content | String | @NotBlank @Size(max=500) | 본문(UTF-16 code unit 500자) |
| clientMsgId | String | @NotNull @Pattern(UUID 36자 `8-4-4-4-12` hex) | 메시지마다 새로 만드는 UUID. **재시도는 같은 값으로** |

**응답 202** `ApiResponse<SendMessageResponse>`
| 필드 | 타입 | 설명 |
|---|---|---|
| gameId | String | |
| msgId | long | Kafka 오프셋(연속 아님) |
| content | String | **마스킹 결과** — 보낸 문자열과 비교해 치환 고지 가능. 금지어가 있어도 거절하지 않는다(202) |

**판정 순서(계약)**: 404(방, 지연 생성 포함) → 400(본문 검증) → 마스킹 → 429(속도) → dedup 선점(409 또는 재반환) → Kafka produce(503) → dedup 확정 → 202.
- 같은 `clientMsgId` 재요청이 120초 안이고 첫 요청이 확정됐으면 **새로 produce하지 않고 같은 202 본문**(같은 msgId)을 돌려준다.
- dedup 재반환·409 요청도 속도 제한에 계수된다(429가 dedup 판정보다 먼저).
- produce 실패(503) 시 dedup 선점은 해제되므로 같은 `clientMsgId`로 재시도하면 다시 produce를 시도한다.

**실패**
| 상태 | ErrorCode | 문구 | 조건 |
|---|---|---|---|
| 400 | - | 입력값이 올바르지 않습니다. (`data.content`/`data.clientMsgId`에 위반 메시지) | 빈·공백 content, 501자 이상, clientMsgId 누락·형식 불일치. 깨진 JSON도 400, Content-Type 불일치는 415 |
| 401 | UNAUTHENTICATED | 인증이 필요합니다. | 인증 실패(발신자 계정 조회 실패 포함) |
| 404 | CHATROOM_NOT_FOUND | 존재하지 않는 채팅방입니다. | 방 없음 (빈 content여도 404가 먼저) |
| 409 | CHAT_MESSAGE_IN_FLIGHT | 같은 메시지를 처리하고 있습니다. 잠시 후 다시 시도해 주세요. | 같은 clientMsgId의 첫 요청이 아직 처리 중 — **에러 토스트가 아니라 잠시 뒤 같은 clientMsgId로 재시도** |
| 429 | CHAT_RATE_LIMIT_EXCEEDED | 메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요. | 사용자 단위 1초 창에서 3건 초과(방 불문 공유) |
| 503 | CHAT_BROKER_UNAVAILABLE | 채팅 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요. | Kafka ack 3초 내 실패, 또는 방 존재 확인의 Redis 실패 — 재시도 대상(같은 clientMsgId) |
| 500 | INTERNAL_SERVER_ERROR | 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. | 마스킹 필터 예외 — 원문을 내보내지 않고 실패 처리 |

(속도 제한·dedup의 Redis 명령 실패는 건너뛰고 진행한다 — fail-open.)

**예시**
```bash
curl -X POST http://localhost:8082/chat/rooms/20261009HTLG0/messages \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"content":"오늘 이긴다","clientMsgId":"3f9c2e10-5b7a-4c1d-9e2f-0a1b2c3d4e5f"}'
# 202 {"success":true,"data":{"gameId":"20261009HTLG0","msgId":7123,"content":"오늘 이긴다"},"message":null}
```

## GET /chat/rooms/{gameId}/messages
> 최종 변경: 2026-10-09 — 신규 추가

히스토리 조회(최신순). 한 페이지 30건 고정. history-writer가 적재한 Redis Stream만 읽으며 창은 최근 50,000건이다. **202 직후의 메시지는 아직 없을 수 있다.**

**인증** 필요 · **쿼리** `cursor`(Long, 선택): 없으면 최신부터, 있으면 그 `msgId` **미포함** 더 오래된 것부터.

**응답 200** `ApiResponse<HistoryResponse>`
| 필드 | 타입 | 설명 |
|---|---|---|
| messages | List&lt;ChatMessageView&gt; | 최신순. blind 처리된 것과 **요청자와 차단 관계(양방향)인 발신자의 메시지는 조회 시점에 제외** |
| nextCursor | Long? | 다음 요청의 `cursor`. 빈 페이지면 null. **필터 이전 원본 기준** |
| hasNext | boolean | 원본 페이지가 30건이면 true. `messages.length < 30`을 끝으로 보지 말고 **`hasNext`만** 볼 것 |

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `cursor`가 숫자가 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | CHATROOM_NOT_FOUND | 방 메타 없음(**지연 생성하지 않음**) |
| 503 | CHAT_BROKER_UNAVAILABLE | Redis 실패(빈 배열 200으로 덮지 않음) |

**예시**
```bash
curl 'http://localhost:8082/chat/rooms/20261009HTLG0/messages?cursor=7100' -H 'Authorization: Bearer <accessToken>'
```

## POST /chat/rooms/{gameId}/messages/{msgId}/report
> 최종 변경: 2026-10-09 — 신규 추가

메시지 신고 → 즉시 blind. 신고자·사유·횟수는 저장하지 않는다. 이미 blind된 메시지를 다시 신고해도 200(멱등, 툼스톤 재발행). blind되면 구독자에게 `deleted` 이벤트가 나간다. 본문 없음.

**인증** 필요 · **경로 변수** `gameId`, `msgId`(Long — 202·SSE·히스토리의 `msgId`)

**응답 200** `ApiResponse<Void>` (`data: null`)

**판정 순서**: 404(방 메타, 지연 생성 없음) → 404(Stream 엔트리) → 403(본인) → 툼스톤 발행.

**실패**
| 상태 | ErrorCode | 문구 | 조건 |
|---|---|---|---|
| 401 | UNAUTHENTICATED | | 인증 실패 |
| 403 | SELF_REPORT_NOT_ALLOWED | 자신의 메시지는 신고할 수 없습니다. | 본인 메시지 |
| 404 | CHATROOM_NOT_FOUND | 존재하지 않는 채팅방입니다. | 방 메타 없음 |
| 404 | CHAT_MESSAGE_NOT_FOUND | 존재하지 않는 메시지입니다. | Stream에 없음 — **202 직후에는 적재 지연으로 404일 수 있어 짧은 재시도 필요**, 50,000건 창 밖도 404 |
| 503 | CHAT_BROKER_UNAVAILABLE | | Redis 조회 또는 툼스톤 발행 실패 (200으로 삼키지 않음) |

**예시**
```bash
curl -X POST http://localhost:8082/chat/rooms/20261009HTLG0/messages/7100/report -H 'Authorization: Bearer <accessToken>'
```

---

## SSE 이벤트 (`GET .../subscribe`)

| 프레임 | 형태 | `id:` | 설명 |
|---|---|---|---|
| 주석 `:connected` | 주석(핸들러에 안 잡힘) | - | 구독 직후 첫 프레임. 응답 헤더를 바로 내보내는 용도 |
| `event: messages` | `data:` = `ChatMessageView[]`(msgId 오름차순) | **배열 마지막 msgId** | 새 메시지. 실시간은 최대 150ms 배칭 |
| `event: deleted` | `data: {"msgId":n}` | **없음** | blind된 메시지 → 화면에서 제거. `id:`가 없어 Last-Event-ID로 재생되지 않는다(끊긴 사이의 deleted는 복구 불가, 히스토리 재조회로 수렴) |
| `event: reset` | `data: {}` | 없음 | 화면을 비우고 히스토리 재조회 |
| 주석 `:ping` | 주석 | - | 15초마다 하트비트 |

```
:connected

id: 7123
event: messages
data: [{"msgId":7120,"content":"가자","senderNickname":"곰","teamCode":"OB","profileImgUrl":null,"sentAt":"2026-10-09T19:03:21.482+09:00"},{"msgId":7123,...}]

event: deleted
data: {"msgId":7120}

:ping
```

**Last-Event-ID 복구**
- 헤더가 유효하면 그 msgId **초과**분을 Stream에서 읽어 `messages` 이벤트로 보낸다. 한 페이지(최대 500건)가 이벤트 하나이고 `id:`는 그 페이지의 마지막 msgId. 최대 5페이지 = **최대 2,500건**.
- 2,500건을 넘겨 더 남았거나, 요청한 id가 Stream의 가장 오래된 엔트리보다 작거나(트리밍·재생성), 복구 중 오류가 나면 **`reset`** 을 보내고 실시간에 합류한다.
- 복구에서는 blind된 메시지와 **본인이 보낸 메시지**를 거른다(걸러서 빈 페이지는 이벤트 없음). 실시간에는 blind 필터가 없고 사후 `deleted`로 알린다.
- **클라이언트 계약: 복구와 실시간이 겹쳐 중복 전달될 수 있다. 마지막으로 처리한 `msgId` 이하는 버릴 것.**

**연결 수명**
- 서버 타임아웃 **30분**(이후 재접속, `Last-Event-ID` 사용).
- **같은 계정 last-one-wins**: 새 구독이 생기면 같은 계정의 기존 구독은 방 불문, 다른 파드 것까지 서버가 닫는다(멀티탭 불가).
- `DELETE .../subscribe`로 명시적으로 닫을 수 있다.

## 기존 quiz `/rt/chat/**`와의 차이 (병행 운영 중)

| 항목 | 기존(quiz, 8081 `/rt/chat/**`) | 신규(chat, 8082 `/chat/**`) |
|---|---|---|
| 경로 | `/rt/chat/rooms/{roomUid}` | `/chat/rooms/{gameId}` (`gameId` = `GET /api/games`의 `gameId`) |
| 방 목록 | 구단 방 `{roomUid, team, name}` | 오늘 경기 `{gameId, homeTeam, homeTeamId, awayTeam, awayTeamId, gameDate, gameState}`, 경기 없는 날 `[]` |
| 구단 가드 | 400/403 | 없음 |
| 전송 응답 | 201 + 메시지 본문 | **202 + `{gameId, msgId, content}`** |
| 전송 요청 | `{content}` | `{content, clientMsgId(UUID)}` |
| msgId | DB PK, 연속 | Kafka 오프셋, 띄엄띄엄 |
| 429 / 409 / 503 | 없음 | `CHAT_RATE_LIMIT_EXCEEDED`(초당 3건) / `CHAT_MESSAGE_IN_FLIGHT` / `CHAT_BROKER_UNAVAILABLE` |
| 전송 판정 순서 | 400 → 404 | **404 → 400** → 429 → 409/재반환 → 503 |
| 욕설 마스킹 | 구단 연상 단어로 치환 | `*`로 치환(금지어로 거절하지 않음은 동일) |
| SSE 메시지 | `event: message` 단건, `id:` 없음 | `event: messages` 배열, `id:` = 마지막 msgId |
| SSE 삭제/리셋 | 없음 | `deleted` `{msgId}` / `reset` `{}` |
| 재접속 | 히스토리 수동 복구 | `Last-Event-ID` 복구(최대 2,500건, 초과 시 reset) + 클라이언트 중복 제거 |
| 히스토리 | `?page=`, `totalElements`·`totalPages` | `?cursor=<msgId>`, `{messages, nextCursor, hasNext}` |
| 신고 | `/messages/{messageId}/report` | `/messages/{msgId}/report` |
| 시각 | `createdAt` 오프셋 없음(파드 UTC) | `sentAt` 오프셋 포함 `+09:00` |
| 멀티탭 / 인증 | last-one-wins / fetch 폴리필 | 동일 |
