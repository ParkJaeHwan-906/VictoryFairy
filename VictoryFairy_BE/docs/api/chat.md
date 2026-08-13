# 채팅(chat) API 명세

> **도메인** `chat` — 구단별 채팅방, 메시지 전송·히스토리·신고, SSE 실시간 구독.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/chat` · **엔드포인트** 7개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/chat/controller/ChatController.java` (`@RequestMapping("/chat")` — `/rt`는 context-path가 붙인다) — 현재 quiz 모듈의 유일한 컨트롤러.
> **최종 갱신** 2026-08-04 — **구단 접근 제어 도입**: 방 목록 `teamId` 필터·403 구단 가드(신규 `CHATROOM_TEAM_MISMATCH`)를 6개 기존 경로에 추가하고, 명시적 퇴장 `DELETE /rooms/{roomUid}/subscribe`를 신설(직전 변경: 2026-08-01 `RoomResponse`에서 `participants` 필드 제거)
> **요구사항** `docs/requirements/quiz/chat.md`(QUIZ-CHAT, 도입 시점 계약) · `docs/requirements/quiz/chat-team-access-control.md`(QUIZ-CTAC-1~29, 이번 변경의 단일 출처)
> 공통 규약(응답 래퍼·JWT payload·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/chat/rooms](#get-rtchatrooms) | 200 | 채팅방 목록(응원 구단 기준, `teamId` 선택) |
| GET | [/rt/chat/rooms/{roomUid}](#get-rtchatroomsroomuid) | 200 | 채팅방 상세 |
| GET | [/rt/chat/rooms/{roomUid}/subscribe](#get-rtchatroomsroomuidsubscribe) | 200 (SSE) | 방 실시간 구독 |
| **DELETE** | [/rt/chat/rooms/{roomUid}/subscribe](#delete-rtchatroomsroomuidsubscribe) | 200 | **명시적 퇴장(구독 종료)** — 신규 |
| POST | [/rt/chat/rooms/{roomUid}/messages](#post-rtchatroomsroomuidmessages) | 201 | 메시지 전송 |
| GET | [/rt/chat/rooms/{roomUid}/messages](#get-rtchatroomsroomuidmessages) | 200 | 히스토리 조회(페이징) |
| POST | [/rt/chat/rooms/{roomUid}/messages/{messageId}/report](#post-rtchatroomsroomuidmessagesmessageidreport) | 200 | 메시지 신고 → 즉시 blind |

## 이 도메인의 특이사항

**7개 전부 인증 필수.** quiz의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다(과거 기록, 정정됨 — 이전 버전 문서는 `GET /health`로 적었으나 그 경로엔 핸들러가 없어 항상 404였다; 이 permitAll 범위 자체는 바뀐 적 없고 표기만 틀려 있었다). user 모듈처럼 GET 한정으로 열린 공개 경로가 없다. 신규 `DELETE .../subscribe`도 이 규칙 그대로 걸린다 — `SecurityConfig` 수정 없이 기존 `anyRequest().authenticated()`에 자연히 포함된다.

**응답 래퍼는 7개 모두 `ApiResponse<T>`이나 SSE 구독(GET)만 예외**로 `SseEmitter`를 반환한다(이벤트 스트림이라 JSON 래핑 대상이 아님).

### 채팅방은 응원 구단 단위 폐쇄 공간이다 (2026-08-04 도입)

`docs/requirements/quiz/chat.md`의 QUIZ-CHAT-5("구단 소속에 따른 접근 제한 없음")는 **철회**되고 이 절이 대체한다. 판정 기준 구단은 요청자의 `user_support_team`에서 `oppose IS NULL`인 행의 구단이며(계정당 최대 1개), 캐시 없이 요청마다 다시 읽는다.

**판정 순서는 고정이다: ①404(방 없음·소프트삭제) → ②400 `SUPPORT_TEAM_REQUIRED`(응원 구단 없음) → ③403 `CHATROOM_TEAM_MISMATCH`(구단 불일치).** 방 존재를 먼저 보고, 그다음 비교 기준(내 응원 구단) 자체가 있는지, 마지막에 그 기준과 방의 구단이 같은지를 본다. 존재하지 않는 `roomUid`·삭제된 방은 요청자의 응원 구단과 무관하게 항상 404다.

이 판정은 목록(`GET /rooms`)과 방 단위 5개 경로(상세·구독·전송·히스토리·신고) 전부에 적용된다. **명시적 퇴장(`DELETE .../subscribe`)만 예외**다 — 아래 해당 절 참고.

`GET /rooms`에 `teamId`를 생략하면 응원 구단으로 간주하고, 값을 주더라도 응원 구단과 다르면 403이다(존재하지 않는 구단 id 포함). **결과적으로 이 파라미터에 넣을 수 있는 유효 값은 "내 응원 구단 id" 하나뿐이지만, 잘못된 값을 조용히 무시하지 않고 403으로 드러내기 위해 파라미터 자체는 유지한다.**

### 외부 식별자

- 채팅방은 `roomUid`(`Chatroom.uid`, UUID)로만 노출된다. 응답 어디에도 방의 순차 PK가 나타나지 않는다.
- 메시지 식별자는 `id`(=`Chat` 내부 PK)이며 `MessageResponse`(전송 응답·히스토리)와 `MessageEvent`(SSE payload) 양쪽에 같은 값이 실린다. 신고 경로의 `{messageId}`가 이 값이다. 클라이언트는 이 `id`로 (1) SSE로 이미 그린 메시지를 히스토리 재조회 때 중복 렌더하지 않도록 걸러내고 (2) 신고를 호출한다. 메시지는 순차 PK가 노출되므로 **방 식별자는 계속 uid(UUID)** 를 쓴다(열거 방지는 방 단위에서 유지).
- 발신자/작성자 계정 PK(`user_account_id`)도 응답에 노출되지 않는다. `senderNickname`(`UserAccount.nickname`)만 노출된다.

### 로컬에서 띄우기

`docker compose up -d mysql` 로 DB만 올린 뒤 `./gradlew :quiz:bootRun` 한 번이면 끝난다 — dev 프로파일이 `ddl-auto: update`로 스키마를 만들고, 이어서 `spring.sql.init`이 `infra/sql/teams-init.sql` → `chat-init.sql`을 실행해 **구단 10개 · SYSTEM 계정 · 구단별 채팅방 10개**를 채운다. 시드는 `INSERT ... WHERE NOT EXISTS`라 재기동해도 중복이 생기지 않는다. Redis는 dev에서 쓰지 않으므로 띄우지 않아도 된다(실시간 전달은 `InMemoryPublisher`). 이번 변경으로 접근 판정을 쓰려면 `user_support_team`이 채워져 있어야 한다 — user 앱을 먼저 띄워 응원 구단을 선택해 둘 것.

⚠ `user` 앱도 같은 로컬 DB를 본다. dev `ddl-auto`가 `create`였을 때는 user를 띄우는 순간 이 시드가 전부 사라졌다 — 현재는 둘 다 `update`라 안전하다.

### 관리자 기능은 범위 밖

blind 해제(unblind), 메시지/방 삭제를 수행하는 엔드포인트는 코드에 없다(`Chat.unblind()`/`Chat.delete()`/`Chatroom.delete()`는 엔티티에 구현돼 있으나 어떤 컨트롤러에서도 호출되지 않는다). 이 문서는 그런 엔드포인트를 다루지 않는다.

---

## GET /rt/chat/rooms
> 최종 변경: 2026-08-04 — `teamId` 쿼리 파라미터(선택) + 응원 구단 필터링 추가. 생략 시 응원 구단으로 간주, 불일치 시 403(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

채팅방 목록(소프트삭제 제외, 요청자의 응원 구단 기준).

**인증 필요** — `Authorization: Bearer <accessToken>`

**요청**: 쿼리 파라미터 1개(선택).

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| teamId | Long | 아니오 | 조회할 구단 id. 생략하면 요청자의 현재 응원 구단으로 간주한다. 값을 주면 응원 구단과 **같아야만** 통과한다(달라도, 존재하지 않아도 403) |

**응답 200 OK** `ApiResponse<List<RoomResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자(UUID) |
| team | String | 구단(팀) 이름 |
| name | String | 방 이름 |

참여 인원(participants)은 이 응답에 없다 — `RoomResponse`에서 해당 필드가 제거됐다(이전 문서에는 있었다). 이번 접근 제어 도입으로도 필드 집합은 바뀌지 않았다(QUIZ-CTAC-18) — 접근 가능 여부를 나타내는 새 필드도 추가되지 않았다.

`Chatroom.deletedAt`이 채워진 방은 목록에서 제외된다(`chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(supportTeamId)`).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | SUPPORT_TEAM_REQUIRED | 요청자에게 현재 응원 중인 구단이 없음. `teamId`를 실었어도(비교 기준 자체가 없어) 마찬가지로 400 |
| 400 | (래퍼 없음) | `teamId`가 정수로 변환되지 않음(예: `?teamId=abc`). 컨트롤러 진입 전 바인딩 단계 실패라 `ApiResponse` 래퍼가 아니다(기존 공통 규약의 예외 — player·game의 `?teamId=`·`?date=`와 동일) |
| 403 | CHATROOM_TEAM_MISMATCH | `teamId`가 응원 구단과 다름(존재하지 않는 구단 id 포함). 방 목록이 실리지 않는다(`data:null`) |

**예시**
```bash
# 생략 — 내 응원 구단 방
curl -i http://localhost:8081/rt/chat/rooms \
  -H 'Authorization: Bearer eyJ...'
# 명시 — 응원 구단과 같아야 통과
curl -i "http://localhost:8081/rt/chat/rooms?teamId=6" \
  -H 'Authorization: Bearer eyJ...'
```
구단 불일치 실패 예시(403):
```json
{"success":false,"data":null,"message":"응원하는 구단의 채팅방만 이용할 수 있습니다."}
```

---

## GET /rt/chat/rooms/{roomUid}
> 최종 변경: 2026-08-04 — 구단 일치 검사 추가(내 응원 구단 방이 아니면 403). 판정 순서: 404 → 400(응원 구단 없음) → 403(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

채팅방 상세.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**응답 200 OK** `ApiResponse<RoomResponse>` — 필드는 목록 항목과 동일(`roomUid`/`team`/`name`).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성(비삭제) 방이 없음(존재하지 않거나 소프트삭제됨). 요청자의 응원 구단 유무와 무관하게 가장 먼저 판정 |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음 |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름 |

**예시**
```bash
curl -i http://localhost:8081/rt/chat/rooms/3f9c2e10-... \
  -H 'Authorization: Bearer eyJ...'
```

---

## GET /rt/chat/rooms/{roomUid}/subscribe
> 최종 변경: 2026-08-04 — 구독 시점 1회 구단 일치 검사 추가(스트림을 열기 전, 트랜잭션 안에서 완결) + 같은 사용자의 기존 구독을 축출(last-one-wins)(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

방 실시간 구독(SSE). `produces = text/event-stream`. 반환 타입은 `SseEmitter`이며 `ApiResponse`로 감싸지 않는다(다른 6개 엔드포인트와 다름 — 이벤트 스트림이라 JSON 래핑 대상이 아님).

**인증 필요** — `Authorization: Bearer <accessToken>`. **표준 브라우저 `EventSource`는 커스텀 헤더를 실을 수 없으므로 이 엔드포인트를 그대로 쓸 수 없다.** 클라이언트는 fetch 기반 EventSource 폴리필로 `Authorization: Bearer` 헤더를 유지한 채 스트림을 열어야 한다(쿼리 파라미터·쿠키 토큰 방식은 서버가 지원하지 않음).

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**구단 일치 검사(연결 시점 1회)**: 방이 요청자의 응원 구단 소속이 아니면 스트림을 **아예 열지 않고** 403을 반환한다(방 존재 확인과 함께 트랜잭션 안에서 끝난다 — `Chatroom.team`이 LAZY이고 `open-in-view: false`라 스트림을 연 뒤에 팀을 읽으면 SSE가 살아 있는 최대 30분 동안 JPA 커넥션이 묶여 Hikari 풀이 고갈되기 때문). **연결이 유지되는 동안에는 구단을 재검사하지 않는다** — 응원 구단을 바꿔도 이미 열린 스트림은 그대로 유지되고, 제한은 **다음 연결(재구독)부터** 적용된다. 구독이 유지된 채 구단이 바뀌는 시나리오는 성립하지 않는다는 전제(화면 진입 시 구독, 이탈 시 해제) 위에 있다.

**같은 사용자의 기존 구독은 방을 가리지 않고 전부 종료된다(last-one-wins).** 새 구독이 성립하는 순간 그 사용자가 어느 방에 들고 있던 구독이든 서버가 `complete()`로 끊어 클라이언트 스트림을 닫는다. 재구독 전후로 그 사용자의 총 구독 수는 늘지 않는다(1→1). **멀티탭은 사실상 금지된다** — 같은 계정으로 두 탭을 열면 먼저 연 탭이 끊긴다. 끊긴 탭은 재구독하면 되고, 놓친 메시지는 히스토리 조회로 복구한다.

**연결 성립 시 동작**: `Content-Type: text/event-stream`으로 200 응답을 열고 연결을 유지한다. 구독이 성립하는 순간 `SseEmitterRegistry`에 연결이 등록되어 발신자 제외 fan-out 대상이 되고, 연결이 끝나면(완료/타임아웃/오류 콜백, 퇴장, 축출) 레지스트리에서 제거된다. (참여 인원 수는 어떤 API 응답에도 노출되지 않는다.)

**이벤트 계약**

| 이벤트 | `event:` | `data:` | 설명 |
|---|---|---|---|
| 메시지 | `message` | JSON `{id, content, senderNickname, createdAt, roomUid}`(`MessageEvent`) | 같은 방에 **커밋된** 새 메시지가 저장될 때 전달(커밋 이후 발행이라 전달된 메시지는 반드시 DB에 있다). SSE 프레임의 `id:` 필드는 여전히 없다(Last-Event-ID 미지원 — 재연결 시 놓친 메시지는 `GET .../messages`로 복구하고, payload 의 `id` 로 중복을 걸러낼 것) |
| 하트비트 | 없음 | 없음(SSE 주석 `:ping`) | `SseEmitterRegistry.heartbeat()`가 **15초 주기**로 전송. 서버는 전송 실패를 감지하면 그 연결을 죽은 것으로 간주해 즉시 레지스트리에서 회수한다 |

퇴장·축출로 종료된 연결에는 별도의 이벤트 프레임이 오지 않는다 — 연결 자체가 서버 쪽에서 `complete()`되어 스트림이 끝난다(클라이언트는 스트림 종료로 이를 인지한다).

**발신자 에코 없음**: 메시지를 보낸 사용자 본인의 emitter는 fan-out에서 제외된다(서버가 emitter를 `userAccountId`로 식별해 발신자 구독에는 전달하지 않음). 발신자는 `POST .../messages`의 201 응답으로만 자기 메시지를 렌더해야 한다.

**연결 타임아웃**: `SseEmitter` 타임아웃은 **30분**(`EMITTER_TIMEOUT_MS`). 타임아웃 시 서버가 연결을 `complete()`하고 구독을 레지스트리에서 해제한다. 명시적 퇴장(`DELETE .../subscribe`)이나 축출을 호출하지 않는 연결도 이 안전망(하트비트 실패·`onCompletion`·타임아웃)이 계속 회수한다 — 신규 F·G절 계약은 이 안전망을 대체하지 않고 그 위에 얹힌다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음(스트림을 열지 않는다) |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음(스트림을 열지 않는다) |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름(스트림을 열지 않는다). 응답 `Content-Type`이 `text/event-stream`이 아니며 `SseEmitterRegistry`에 등록되지 않는다 |
| 401 | UNAUTHENTICATED | 인증 헤더 없음/무효(엔트리포인트 단계, 스트림 열기 전) |

**예시(fetch 기반 폴리필 개념 — 실제 라이브러리는 프로젝트마다 다름)**
```bash
curl -i -N http://localhost:8081/rt/chat/rooms/3f9c2e10-.../subscribe \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Accept: text/event-stream'
```

---

## DELETE /rt/chat/rooms/{roomUid}/subscribe
> 최종 변경: 2026-08-04 — 신규 추가

명시적 퇴장 — 요청자의 이 방에 대한 SSE 구독을 즉시 끊는다. **협조적 정리(cooperative cleanup)이지 보안 통제가 아니다.** 클라이언트가 이 경로를 호출하지 않아도 종전 안전망(하트비트 실패·`onCompletion`·30분 타임아웃)이 그대로 회수한다 — 이 경로는 그 회수를 더 빠르게 만드는 협조 수단일 뿐, 이 경로로 막히는 접근 통제는 없다(접근 차단은 구단 가드가 담당).

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**요청**: 본문 없음.

**응답 200 OK** `ApiResponse<Void>` = `{"success":true,"data":null,"message":null}`

**동작**: `ChatService.unsubscribe()` → `SseEmitterRegistry.closeSubscriptions(roomUid, userAccountId)` — 요청자의 그 방 구독을 찾아 `complete()`로 종료한다(클라이언트 스트림이 닫힌다). 종료된 연결은 즉시 fan-out 대상에서 빠진다. `chatrooms.participants` 값은 건드리지 않는다(퇴장·축출 모두 이 컬럼에 UPDATE를 내지 않는다).

**이 경로는 전면 멱등이며 구단 가드를 걸지 않는 것이 계약이다** — 다음 어느 경우에도 예외 없이 200이다:

| 상황 | 응답 |
|---|---|
| 끊을 구독이 없음(구독한 적 없음, 이미 종료됨) | 200, 상태 변화 없음 |
| 요청자에게 응원 구단이 없음 | 200 (다른 방 단위 5개 경로는 400 `SUPPORT_TEAM_REQUIRED`지만 이 경로만 예외) |
| `roomUid`가 존재하지 않거나 소프트삭제됨 | 200 (다른 방 단위 5개 경로는 404 `CHATROOM_NOT_FOUND`지만 이 경로만 예외 — 판정 순서 "404가 항상 먼저"의 유일한 예외) |
| 방이 있지만 요청자의 응원 구단과 다름(구단을 바꾼 뒤 예전 방에 대한 퇴장 요청 포함) | 200, 남아 있던 구독이 있으면 종료됨 |

정리 요청을 403/400/404로 막으면 구단을 바꾼 사용자나 삭제된 방에 남은 사용자가 자기 낡은 연결을 닫지 못해 그 연결이 최대 30분(타임아웃) 살아남는다는 것이 이 예외들의 근거다.

**같은 요청을 연속 2회 보내면 둘 다 200이고, 2회차는 레지스트리 상태 변화가 없다.**

**다중 인스턴스(운영) 전파**: quiz-app은 HPA(maxReplicas=4)로 여러 파드가 뜨고 ALB가 요청을 아무 파드로나 보낸다. `SseEmitterRegistry`는 파드 로컬 인메모리 맵이라, 퇴장 요청이 그 구독을 들고 있지 않은 파드에 떨어져도 실제 연결이 있는 파드에서 종료되도록 기존 `RealtimeEventPublisher` 버스(prod=Redis 채널 `realtime:events`)로 종료 명령을 전파한다. 로컬(`InMemoryPublisher`, 단일 인스턴스)에서는 항상 로컬에서 바로 처리된다.

**실패**: 없음. 이 경로에 정의된 실패 응답이 없다(401 미인증만 예외 — 필터 단계에서 걸린다).

**예시**
```bash
curl -i -X DELETE http://localhost:8081/rt/chat/rooms/3f9c2e10-.../subscribe \
  -H 'Authorization: Bearer eyJ...'
```
```json
{"success":true,"data":null,"message":null}
```

---

## POST /rt/chat/rooms/{roomUid}/messages
> 최종 변경: 2026-08-04 — 구단 일치 검사 추가(내 응원 구단 방이 아니면 403, 저장하지 않음). 판정 순서: content 검증(400) → 방 존재(404) → 응원 구단 없음(400) → 구단 불일치(403)(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

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

**검증 순서(중요)**: `@Valid`가 컨트롤러 진입 전(인자 바인딩 단계)에 수행되므로, **content 위반(400)이 방 존재 여부·구단 일치 확인보다 먼저 판정**된다. 즉 존재하지 않는 `roomUid`나 타 구단 방에 빈 content로 요청해도 404·403이 아니라 400이 난다. content가 유효하면 그다음은 404(방 없음) → 400(응원 구단 없음) → 403(구단 불일치) 순서다.

**응답 201 Created** `ApiResponse<MessageResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 메시지 식별자(`Chat` PK). 신고 경로의 `{messageId}`이자 SSE payload `id`와 같은 값 |
| content | String | 저장된 메시지 내용 |
| senderNickname | String | 발신자 `UserAccount.nickname` |
| createdAt | LocalDateTime | 생성 시각 |

저장 시 `Chat.blind=false`, `deletedAt=null`로 저장된다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `content`가 공백뿐이거나 501자 이상. 저장하지 않는다. 아래 모든 실패보다 우선 판정됨 |
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음(존재하지 않거나 소프트삭제). 저장하지 않는다 |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음. 저장하지 않는다 |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름. 저장하지 않으며 해당 방 구독자에게 SSE 전달도 없다 |

실시간 전달(SSE fan-out) 실패는 `ChatService.publishMessage()`가 예외를 삼켜(fire-and-forget) 저장·201 응답에 영향을 주지 않는다.

**예시**
```bash
curl -i -X POST http://localhost:8081/rt/chat/rooms/3f9c2e10-.../messages \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"content":"안녕하세요"}'
```

실패 예시(content 공백, 400):
```json
{"success":false,"data":{"content":"공백일 수 없습니다"},"message":"입력값이 올바르지 않습니다."}
```
(실제 `@NotBlank` 기본 메시지는 스프링/Hibernate Validator의 로케일 기본 문구를 그대로 쓴다 — 커스텀 message 속성 미부착.)

타 구단 방 실패 예시(403):
```json
{"success":false,"data":null,"message":"응원하는 구단의 채팅방만 이용할 수 있습니다."}
```

---

## GET /rt/chat/rooms/{roomUid}/messages
> 최종 변경: 2026-08-04 — 구단 일치 검사 추가(내 응원 구단 방이 아니면 403, 메시지가 실리지 않음)(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

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
| content | List\<MessageResponse\> | 현재 페이지 항목(`id`/`content`/`senderNickname`/`createdAt`) |
| page | int | 현재 페이지 번호(0-base) |
| size | int | 페이지 크기(30) |
| totalElements | long | 조건을 만족하는 전체 메시지 수 |
| totalPages | int | 전체 페이지 수 |
| hasNext | boolean | 다음 페이지 존재 여부 |

`blind=true`이거나 `deletedAt`이 채워진 메시지는 결과에서 제외된다(`findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc`).

**응원 구단을 바꾸면 이전 구단 방의 히스토리도 다시 볼 수 없다.** 자기가 쓴 메시지도 예외 없이 403으로 막힌다(메시지 자체는 삭제·익명화되지 않고 `chats`에 그대로 보존된다).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음 |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음 |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름 |

**예시**
```bash
curl -i "http://localhost:8081/rt/chat/rooms/3f9c2e10-.../messages?page=0" \
  -H 'Authorization: Bearer eyJ...'
```

---

## POST /rt/chat/rooms/{roomUid}/messages/{messageId}/report
> 최종 변경: 2026-08-04 — 구단 일치 검사 추가(내 응원 구단 방이 아니면 403, blind 미적용). 신규 403은 기존 자기신고 403과 `message` 문구로 구분됨(직전: 2026-08-01(추정) `ChatController` 마지막 커밋)

메시지 신고 → 즉시 blind 전환(자동, 관리자 개입 없음, 멱등).

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |
| messageId | Long | 신고 대상 메시지의 내부 PK. **room-스코프 경로로만 지목**하는 구조(`chatRepository.findByIdAndChatroom(messageId, room)`). 값은 SSE payload·히스토리 응답의 `id` 필드에서 얻는다 |

**요청**: 본문 없음.

**응답 200 OK** `ApiResponse<Void>` = `{"success":true,"data":null,"message":null}`

**동작**: `Chat.blind()` 호출로 대상 메시지를 즉시 `blind=true`로 전환한다. 이미 `blind=true`인 메시지를 재신고하면 값이 그대로 유지되어 **no-op(멱등)**이며 여전히 200을 반환한다. 신고 이력은 저장되지 않아 재신고 횟수는 추적되지 않는다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음 |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음 |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름 |
| 404 | CHAT_MESSAGE_NOT_FOUND | `messageId`가 그 방에 없거나(`findByIdAndChatroom` 실패), 존재하지만 `deletedAt`이 채워진(소프트삭제된) 메시지 |
| 403 | SELF_REPORT_NOT_ALLOWED | 신고자(`userAccountId`)가 대상 메시지 작성자(`Chat.userAccount.id`)와 동일 |

검증 순서: 방 존재(404) → 응원 구단 없음(400) → 구단 불일치(403) → 메시지 존재(`findByIdAndChatroom`, 404) → 메시지 삭제 여부(404) → 자기 신고 여부(403) → blind 적용(코드상 `findAccessibleRoom()` 이후 `ChatService.reportMessage()`의 순서 그대로).

**예시**
```bash
curl -i -X POST http://localhost:8081/rt/chat/rooms/3f9c2e10-.../messages/42/report \
  -H 'Authorization: Bearer eyJ...'
```

자기 신고 실패 예시(403):
```json
{"success":false,"data":null,"message":"자신의 메시지는 신고할 수 없습니다."}
```

구단 불일치 실패 예시(403, 자기신고와 `message`로 구분됨):
```json
{"success":false,"data":null,"message":"응원하는 구단의 채팅방만 이용할 수 있습니다."}
```

---

## 참여 인원(participants)은 노출하지 않음
방 목록·상세 응답(`RoomResponse`)에는 참여 인원 수 필드가 없다 — 노출하지 않기로 결정됐다. `Chatroom.participants` 컬럼과 `join()`/`leave()` 메서드는 엔티티에 남아 있으나 이 7개 엔드포인트 중 어디서도 쓰이지 않는다. 명시적 퇴장·축출도 이 컬럼을 건드리지 않는다.

## 다중 인스턴스 fan-out(메시지 전달 + 종료 신호 전파)
`RealtimeEventPublisher` 포트 구현체는 프로파일로 갈린다(`quiz/src/main/java/com/skhynix/quiz/realtime/`).

| 프로파일 | 구현 | 전달 범위 |
|---|---|---|
| `prod` | `RedisPubSubPublisher` + `RealtimeEventSubscriber`(`RealtimeRedisConfig`가 등록한 리스너 컨테이너) | Redis 채널 `realtime:events` 로 발행 → **모든 파드**의 구독자. 메시지 fan-out(QUIZ-CHAT-16)과 퇴장·축출 종료 신호(QUIZ-CTAC-28/29) 둘 다 이 경로를 공유한다 |
| `!prod`(dev/test) | `InMemoryPublisher` | 같은 프로세스의 `SseEmitterRegistry`만. 로컬 개발에 Redis 불필요 |

발행 파드도 자기 구독으로 되받으므로 `RedisPubSubPublisher`는 로컬 레지스트리로 직접 전달하지 않는다(하면 같은 파드 구독자에게 이중 전달). 종료 신호(퇴장·축출)도 발행 파드는 이미 로컬을 동기적으로 정리했으므로, 되받은 자기 명령은 발신 인스턴스 식별자로 걸러 무시한다. 수신 측(`RealtimeEventSubscriber`/`InMemoryPublisher`)은 이벤트 이름으로 "전송"과 "연결 종료"를 분기한다 — 종료 신호는 구독자에게 `data:`로 전달되지 않고 대상 사용자의 연결을 끊는 데만 쓰인다.

## 확인 필요 / 코드 미확인
- `@NotBlank`/`@Size` 위반 시 실제 필드 검증 메시지 문구는 Hibernate Validator 기본 로케일 메시지를 그대로 쓰며(커스텀 `message` 속성 미부착), 이 문서의 예시 문구(`"공백일 수 없습니다"`)는 기본값 추정이다 — 실행 환경(로케일 설정)에 따라 문구가 달라질 수 있어 실측 확인 필요.

## 관련 문서

- [README.md](README.md) — 인증·401 정책은 user 모듈과 동일한 `web-support` 구현을 공유한다.
- 요구사항: `docs/requirements/quiz/chat.md`(도입 시점 계약), `docs/requirements/quiz/chat-team-access-control.md`(구단 접근 제어 + 구독 수명 계약, QUIZ-CTAC-1~29)
