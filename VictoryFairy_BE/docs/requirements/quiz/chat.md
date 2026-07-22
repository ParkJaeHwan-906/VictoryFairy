# 구단별 채팅 요구사항
> 상태: 승인됨 (2026-07-20) · 모듈: quiz (호스트 앱) · 최종 수정: 2026-07-20

## 배경 / 목적
KBO 구단(team)별 실시간 채팅을 제공한다. 아키텍처는 사용자와 합의 완료(재논의 대상 아님): **전송=HTTP POST / 실시간 전달=SSE**(WebSocket 아님), 다중 인스턴스 fan-out=**Redis pub/sub**(`RealtimeEventPublisher` 포트 + `RedisPubSubPublisher`(운영)/`InMemoryPublisher`(로컬·테스트) 두 구현). 히스토리는 `chats` DB 페이징, 실시간 fan-out만 pub/sub(fire-and-forget). 호스트 모듈은 별도 chat 앱을 만들지 않고 **기존 `quiz` 앱**이며, 엔티티(`Chatroom`/`Chat`)와 리포지토리 뼈대는 `domain` 모듈에 **이미 구현되어 있다**.
이 문서는 그 위에서 "무엇이 참이어야 하는가"(계약)만 정의한다. 초기 11개 미해결 질문(Q1~Q11)은 2026-07-20 전부 확정되었다(하단 "결정 근거" 참고). **관리자 처리 기능(unblind/메시지 soft-delete)은 이번 범위에서 제외**되었다(하단 "제외 범위(후속 과제)").

## 범위
- 포함:
  - 채팅방 조회(구단별 방 존재), 입장(SSE 구독 시작)·퇴장(구독 종료)과 `participants` 카운트 증감
  - 메시지 전송(인증 사용자 POST → `chats` 저장 → 같은 방 구독자 SSE 전달), content 검증
  - SSE 스트림 계약(이벤트 포맷·하트비트)과 다중 인스턴스 전달 보장
  - 히스토리 페이징 조회(정렬·페이지 크기·blind/삭제 제외)
  - 신고 → 즉시 blind(자동, 관리자 개입 없음)와 blind 동안의 조회·전달 제외
  - 채팅방/메시지가 소프트삭제된 **상태일 때의** 조회·전달 동작(삭제 실행 주체는 범위 밖)
- 제외: 하단 "제외 범위(후속 과제)" 절 참고 — 관리자/Role 체계, unblind·메시지 soft-delete·방 삭제 실행 트리거, 신고 이력, quiz 정답 집계, prod DDL·시드 마이그레이션.

## 확정 전제 (코드에서 확인 + Q 확정 반영 — 요구사항의 바탕)
1. **인증 principal = `Long userAccountId`.** `JwtAuthenticationFilter`가 토큰 subject(`uid`)를 활성 계정의 내부 PK로 변환해 SecurityContext에 넣는다. 컨트롤러는 이 값으로 발신자를 식별한다.
2. **JWT는 `Authorization: Bearer` 헤더에서만 읽는다(Q7 확정).** SSE 인증도 **헤더 방식을 유지**하며, 표준 브라우저 `EventSource`는 헤더를 실을 수 없으므로 **fetch 기반 EventSource 폴리필로 `Authorization: Bearer`를 유지**하는 것을 전제한다. 쿼리 파라미터·쿠키 토큰 방식은 채택하지 않는다. SecurityConfig·JWT 필터는 변경하지 않는다.
3. **`/api/chat/**`는 자동으로 인증 필수.** quiz `SecurityConfig`가 `/`, `/error`, `GET /health` 외 `anyRequest().authenticated()`이며 미인증은 user의 `RestAuthenticationEntryPoint`로 **401**을 낸다. chat 경로용 SecurityConfig 수정은 불필요.
4. **방 접근에는 구단 소속 제한이 없다(Q1 확정).** 모든 인증 사용자가 모든 구단 방을 조회·입장·전송할 수 있다. user↔team 소속 개념은 도입하지 않는다.
5. **관리자 개념이 없다(Q4 재확정).** 이번 범위엔 관리자/Role 체계도, admin 허용목록도 두지 않는다. 신고→blind는 **완전 자동**이며 사람이 승인·해제하는 경로가 없다. unblind/메시지 soft-delete는 후속 과제(하단 제외 범위).
6. **`Chat`에는 외부 식별자(`uid`)가 없고, 추가하지 않는다(Q5 확정).** `chats`는 고write 테이블이라 랜덤 UUID 유니크 인덱스는 삽입 지역성을 해쳐 채택하지 않는다. 메시지를 지목해야 하는 유일한 경로인 신고는 **내부 PK를 room-스코프 경로로 지목**한다: `POST /api/chat/rooms/{roomUid}/messages/{messageId}/report`. 열거 방어는 인가(방 접근 사용자)·신고 규칙·blind 가역성으로 충당한다. **SSE·히스토리 응답 payload에는 메시지 식별자를 싣지 않는다.**
7. **발신자에게는 SSE 에코를 하지 않는다(Q8 확정).** 서버는 emitter를 `userAccountId`로 식별해 발신자 구독을 fan-out에서 제외한다. 발신자 본인 메시지는 POST 응답으로 렌더한다. 발신자의 다른 탭은 실시간 수신 대신 재접속 시 히스토리로 보정된다(알려진 한계).
8. **quiz 스캔 범위 안에 `GlobalExceptionHandler`가 없다.** `BusinessException`을 던지면 `ApiResponse.fail`이 아니라 스프링 기본 500이 나간다(모듈 컨텍스트 quiz.md). 아래 표의 4xx 오류 응답이 `ApiResponse` 형태로 나오려면 quiz에 별도 `@RestControllerAdvice` 추가가 **제약**이다(구현 지시가 아니라 계약 성립 조건, 하단 제약 절 1).
9. **채팅방에 소유자(`owner_account_id`)를 둔다(승인 후 확정, 선제 모델링).** 사용자 직접 방 생성 기능을 로드맵에 두기 위한 준비로, `Chatroom`에 `owner_account_id` FK(→`users_account`, **non-null·비-CASCADE**)를 추가한다. 계정은 소프트삭제(exit_at)이고 FK가 비-CASCADE이므로 소유자 계정이 탈퇴해도 방은 보존된다. **이번 범위엔 방 생성·삭제 엔드포인트가 없어 `owner_account_id`는 아직 활성 동작이 없는 선제 모델링이다**(사용자 생성 방 + 삭제 기능 도입 시 실효). 방 삭제 인가(후속)는 Role 체계가 아니라 `room.owner == 현재 userAccountId` 단순 비교로 한다.

## 요구사항 (EARS)

> ID 규칙: `QUIZ-CHAT-<n>`. 번호는 재사용하지 않는다. 관리자 관련이던 QUIZ-CHAT-22/23은 이번 범위에서 제외되어 아래 표에 없다(번호는 후속을 위해 비워 둠 — 재사용 금지). QUIZ-CHAT-26~29·31은 개정에서 뒤 번호로 추가된 항목이다. 아래 엔드포인트 경로·상태코드·수치는 전부 확정값이다.

### A. 채팅방 조회

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-1 | 유비쿼터스 | THE 시스템 SHALL 각 KBO 구단(team)에 1:1로 대응하는 채팅방을 제공하고, 외부에는 순차 PK가 아니라 `Chatroom.uid`로만 노출한다 | 10개 구단 방이 team↔room 1:1로 존재(팀 시드와 함께 미리 생성, owner는 시스템 계정, 방 생성 API 없음). 방 목록·구독·전송 응답/경로에 정수 PK가 없고 36자 UUID(`uid`)만 나타난다 |
| QUIZ-CHAT-2 | 이벤트 | WHEN 인증된 사용자가 채팅방 목록을 요청하면, THE 시스템 SHALL 소프트삭제되지 않은 방 목록을 200으로 반환한다 | `GET /api/chat/rooms` → 200, 각 항목에 `roomUid`·`team`·`name`·`participants` 포함, `deletedAt`이 채워진 방은 목록에 없다 |
| QUIZ-CHAT-3 | 예외 | IF 존재하지 않거나 소프트삭제된 `roomUid`로 접근하면, THEN THE 시스템 SHALL 404를 반환한다 | 임의 UUID로 `GET /api/chat/rooms/{roomUid}` → 404. 삭제된 방도 404 |

### B. 인증·인가

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-4 | 예외 | IF `/api/chat/**` 요청에 유효한 액세스 토큰이 없으면, THEN THE 시스템 SHALL 401을 반환한다 | `Authorization` 헤더 없이 `POST /api/chat/rooms/{roomUid}/messages` → 401(`RestAuthenticationEntryPoint` 형식). 만료·리프레시 토큰도 401 |
| QUIZ-CHAT-5 | 유비쿼터스 | THE 시스템 SHALL 인증된 모든 사용자에게 모든 구단 방의 조회·입장·전송을 허용한다(구단 소속에 따른 접근 제한 없음) | 임의 인증 사용자가 임의 구단 방에 입장·전송 → 403이 발생하지 않는다. user↔team 소속 개념 없음 |

### C. 입장(SSE 구독)·퇴장·참여 인원

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-6 | 이벤트 | WHEN 인증 사용자가 방 구독을 요청하면, THE 시스템 SHALL `text/event-stream` 스트림을 200으로 열어 유지한다 | `GET /api/chat/rooms/{roomUid}/subscribe`(fetch 기반 폴리필로 `Authorization` 헤더 유지) → 200, `Content-Type: text/event-stream`, 연결이 즉시 끊기지 않는다 |
| QUIZ-CHAT-7 | 이벤트 | WHEN 방 구독이 성립하면, THE 시스템 SHALL 해당 방 `participants`를 1 증가시킨다 | 구독 직후 `GET /api/chat/rooms/{roomUid}` 의 `participants`가 구독 전보다 1 크다. 같은 사용자의 두 번째 구독(멀티탭)도 +1(연결 기준 카운트) |
| QUIZ-CHAT-8 | 이벤트 | WHEN 구독 연결이 종료되면(클라이언트 종료 또는 타임아웃), THE 시스템 SHALL 해당 방 `participants`를 1 감소시킨다 | 구독 종료 후 `participants`가 1 줄어든다. best-effort 카운트이며 `Chatroom.leave()`로 0 미만으로 내려가지 않는다 |
| QUIZ-CHAT-26 | 상태 | WHILE SSE 연결이 열려 있는 동안, THE 시스템 SHALL 주기적으로 하트비트(SSE 주석 `:ping`)를 전송하고 타임아웃·전송 실패로 감지된 죽은 연결을 회수해 `participants`를 보정한다 | 유휴 연결에서 주기적 `:ping` 주석 프레임이 관찰된다. 비정상 종료로 leave 신호 없이 끊긴 연결이 하트비트 실패로 정리되면 `participants`가 그만큼 감소한다(정확성 보장이 아닌 근사 정확도) |
| QUIZ-CHAT-9 | 상태 | WHILE 방이 소프트삭제된 상태이면, THE 시스템 SHALL 그 방의 신규 구독 요청을 404로 거부한다 | 삭제된 방 `.../subscribe` → 404, 스트림을 열지 않는다 |

### D. 메시지 전송

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-10 | 이벤트 | WHEN 인증 사용자가 유효한 content로 메시지 전송을 요청하면, THE 시스템 SHALL `Chat`을 `blind=false`·`deletedAt=null`로 저장하고 저장된 메시지를 201로 반환한다 | `POST /api/chat/rooms/{roomUid}/messages` `{"content":"안녕"}` → 201, 응답에 `content`·`senderNickname`(발신자 `UserAccount.nickname`)·`createdAt`, `chats`에 1행 증가 |
| QUIZ-CHAT-11 | 이벤트 | WHEN 메시지가 저장되면, THE 시스템 SHALL 그 메시지를 발신자를 제외한 같은 방 구독자에게 SSE 이벤트로 전달한다 | 같은 방 구독 중인 다른 사용자 B가 메시지를 SSE로 수신한다. **발신자 A 자신은 SSE로 받지 않고**(emitter를 `userAccountId`로 식별해 제외) POST 응답으로만 렌더한다 |
| QUIZ-CHAT-12 | 예외 | IF content가 `null`·빈 문자열·공백만이면, THEN THE 시스템 SHALL 400을 반환하고 메시지를 저장하지 않는다 | `{"content":"   "}` → 400, `chats` 행 증가 없음(4xx가 `ApiResponse` 형태로 나가려면 제약 절 1 충족) |
| QUIZ-CHAT-13 | 예외 | IF content가 최대 길이 500자를 초과하면, THEN THE 시스템 SHALL 400을 반환하고 저장하지 않는다 | 501자 content → 400. 경계: 500자 통과, 501자 거부. 길이는 `String.length()` UTF-16 code unit 기준(닉네임 정책과 동일 컨벤션 — 이모지 surrogate pair는 2로 계수) |
| QUIZ-CHAT-14 | 예외 | IF 존재하지 않거나 소프트삭제된 방으로 전송하면, THEN THE 시스템 SHALL 404를 반환하고 저장하지 않는다 | 삭제된/없는 `roomUid`로 전송 → 404, 저장 없음 |

### E. 실시간 전달(SSE·다중 인스턴스)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-15 | 유비쿼터스 | THE 시스템 SHALL SSE 메시지 이벤트를 `event: message`, `data:` = JSON `{content, senderNickname, createdAt, roomUid}`로 전달한다 | 전달 이벤트의 `event:`가 `message`이고 `data:`가 해당 4필드 JSON. **메시지 id 필드 없음**(전제 6·7 일관). `senderNickname`은 발신자 `UserAccount.nickname`. 하트비트는 별도 `:ping` 주석 프레임 |
| QUIZ-CHAT-16 | 선택 | WHERE 앱 인스턴스가 다중화된 환경이면, THE 시스템 SHALL 어느 인스턴스로 들어온 전송이든 모든 인스턴스의 해당 방 구독자(발신자 제외)에게 전달한다 | 인스턴스 A에 구독한 클라이언트가, 인스턴스 B로 POST된 메시지를 수신한다(Redis pub/sub fan-out). 로컬/테스트에선 `InMemoryPublisher`로 단일 인스턴스 내 전달 |
| QUIZ-CHAT-17 | 유비쿼터스 | THE 시스템 SHALL 실시간 fan-out을 fire-and-forget으로 처리해, 전달 실패가 저장·전송 응답의 성공을 되돌리지 않게 한다 | pub/sub 발행이 실패해도 `POST .../messages`는 201이고 메시지는 `chats`에 저장되어 히스토리로 조회된다 |

### F. 히스토리 조회

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-18 | 이벤트 | WHEN 인증 사용자가 방의 과거 메시지를 요청하면, THE 시스템 SHALL 페이지 크기 30·최신순(`createdAt` desc)으로 페이징된 메시지 목록을 200으로 반환한다 | `GET /api/chat/rooms/{roomUid}/messages?...` → 200, 한 페이지 최대 30건, 최신 메시지가 먼저 |
| QUIZ-CHAT-19 | 유비쿼터스 | THE 시스템 SHALL 히스토리 응답에서 `blind=true`이거나 `deletedAt`이 채워진 메시지를 제외한다 | blind 처리된 메시지와 소프트삭제된 메시지가 히스토리 결과에 나타나지 않는다 |

### G. 신고·블라인드 (자동, 관리자 개입 없음)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-20 | 이벤트 | WHEN 인증 사용자가 방 접근 권한 내에서 타인의 메시지를 신고하면, THE 시스템 SHALL 대상 `Chat`을 즉시 `blind=true`로 전환한다(신고 1건으로 즉시 숨김, 임계치·관리자 승인 없음) | `POST /api/chat/rooms/{roomUid}/messages/{messageId}/report` → 2xx, 대상 메시지 `blind=true` |
| QUIZ-CHAT-27 | 예외 | IF 신고자가 대상 메시지의 작성자와 동일하면, THEN THE 시스템 SHALL 403을 반환하고 blind하지 않는다 | 자기 메시지 신고(`Chat.userAccount.id` == 요청자 `userAccountId`) → 403, 대상 `blind=false` 유지 |
| QUIZ-CHAT-28 | 예외 | IF 이미 `blind=true`인 메시지를 신고하면, THEN THE 시스템 SHALL 상태를 바꾸지 않고(no-op) 2xx를 반환한다 | 이미 blind된 메시지 재신고 → 2xx, 여전히 `blind=true`(멱등). 신고 이력을 저장하지 않아 재신고 횟수는 추적되지 않는다 |
| QUIZ-CHAT-29 | 예외 | IF 이미 소프트삭제된 메시지를 신고하면, THEN THE 시스템 SHALL 404를 반환한다 | `deletedAt`이 채워진 메시지 신고 → 404 |
| QUIZ-CHAT-21 | 상태 | WHILE 메시지가 `blind=true`인 동안, THE 시스템 SHALL 그 메시지를 실시간 전달과 히스토리 조회 양쪽에서 제외한다 | blind 이후 신규 구독자·히스토리 모두 그 메시지를 받지 못한다. 이미 전달된 클라이언트의 표시 갱신은 범위 밖. **이번 범위엔 blind 해제 경로가 없다**(후속 과제) — content는 `chats`에 보존됨 |

### H. 소프트삭제(채팅방) — 상태 기반 동작만

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-24 | 상태 | WHILE 채팅방이 소프트삭제된 상태이면, THE 시스템 SHALL 목록·조회·구독·전송·히스토리 전부에서 그 방을 404로 취급한다 | 삭제된 방은 `GET /api/chat/rooms` 목록에 없고, 구독·전송·히스토리 요청은 404. **방을 삭제하는 사용자·엔드포인트 트리거는 이번 범위 밖**(관리자 없음) — "삭제된 상태이면 이렇게 동작한다"는 계약만 정의 |

### I. 재연결·놓친 메시지 복구

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-25 | 유비쿼터스 | THE 시스템 SHALL 재연결 시 놓친 메시지 복구를 SSE Last-Event-ID로 제공하지 않고 히스토리 API로 복구하게 한다 | SSE 이벤트에 `id:` 필드를 싣지 않으며, 재연결 클라이언트는 히스토리 조회로 공백을 메운다 |

### J. 채팅방 소유권 (선제 모델링 — 이번 범위 활성 엔드포인트 없음)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-CHAT-31 | 유비쿼터스 | THE 시스템 SHALL 모든 채팅방에 소유자 계정(`owner_account_id`)을 non-null로 두고, 소유자 계정이 소프트삭제(탈퇴)되어도 방을 보존한다(비-CASCADE) | 시드된 10개 구단 방은 owner가 **시스템 계정**이다. 소유자 계정이 `withdraw()`(exit_at 기록)돼도 해당 방과 그 `owner_account_id`는 그대로 남는다(방 삭제 안 됨). **선제 모델링**: 이번 범위엔 방 생성·삭제 엔드포인트가 없어 owner를 읽거나 쓰는 API 동작은 아직 없다(스키마·시드 수준 계약) |

## 미해결 질문
없음 — Q1~Q11 전부 확정(2026-07-20, 사용자 결정). Q4는 "관리자 기능 전면 제외 + 자동 blind만"으로 재확정. 아래 "결정 근거" 참고.

## 제외 범위 (후속 과제)
이번 범위 밖 — 향후 관리자/Role 체계와 함께 별도 요구사항으로 다룬다.
- **관리자 처리**: blind 메시지 unblind(해제·복구), 메시지 soft-delete. 앞서 검토했던 "경량 config admin 허용목록"도 **채택하지 않음**(admin `userAccountId` 설정 없음). 활성 요구사항에 관리자 항목은 하나도 없다. (구 QUIZ-CHAT-22/23은 이 절로 이관, 번호 재사용 금지.)
- **사용자 직접 방 생성·삭제 엔드포인트**: 사용자가 방을 만들고(owner=생성자), 자신이 owner인 방을 소프트삭제로 만드는 경로. 삭제 인가는 `room.owner == 현재 userAccountId` 단순 비교(Role 체계 아님). `owner_account_id`(QUIZ-CHAT-31)는 이 후속 기능을 위한 선제 모델링이며, QUIZ-CHAT-24는 "이미 삭제된 상태의 동작"만 정의한다. 시드된 구단 방은 owner가 시스템 계정이라 일반 사용자가 삭제할 수 없다.
- **신고 이력·집계**: 신고 사유·신고자 추적·임계치 기반 처리를 위한 엔티티. 이번엔 신고 1건=즉시 blind 멱등 토글만.
- **quiz 정답 집계**: `RealtimeEventPublisher` transport만 재사용, 기능 자체는 별도.
- **prod DDL·시드 마이그레이션**: `chatrooms`/`chats` 테이블과 팀당 1행 방 시드(제약 절 5).

## 결정 근거 (해소된 질문 — 조사를 반복하지 않기 위해)
1. **Q1 방 접근**: 모든 인증 사용자가 모든 구단 방 접근. user↔team 소속 개념 도입 안 함(QUIZ-CHAT-5).
2. **Q2 길이/페이징**: content 최대 500자(`String.length()` UTF-16 code unit), 히스토리 페이지 30·최신순(`createdAt` desc)(QUIZ-CHAT-13/18).
3. **Q3 participants**: best-effort. 구독 +1 / 종료·타임아웃 −1 / 0 하한. 크래시 시 일시적 과다 카운트 허용. SSE 타임아웃 + 하트비트(`:ping`)로 죽은 연결을 감지·회수해 근사 정확도 유지(정확성 보장 아님)(QUIZ-CHAT-8/26).
4. **Q4 관리자**: 관리자/Role 체계·admin 허용목록 **전면 제외**(재확정). 신고→blind는 완전 자동, unblind/메시지 soft-delete·방 삭제 트리거는 후속(제외 범위 절).
5. **Q5 메시지 식별자**: `Chat`에 uid 추가 안 함(고write 테이블에 랜덤 UUID 유니크 인덱스는 삽입 지역성·저장 부담 문제, 신고는 저빈도). 신고는 room-스코프 경로 `.../rooms/{roomUid}/messages/{messageId}/report`로 내부 PK 지목. SSE·히스토리 payload엔 식별자 미노출(전제 6).
6. **Q6 Last-Event-ID**: 미지원. SSE에 `id:` 없음, 재연결은 히스토리로 보정(QUIZ-CHAT-25).
7. **Q7 SSE 인증**: fetch 기반 EventSource 폴리필로 `Authorization: Bearer` 헤더 유지. 서버·시큐리티 변경 없음, 쿼리파라미터·쿠키 미채택(전제 2).
8. **Q8 발신자 에코**: 발신자에게 SSE 미전달. 본인 메시지는 POST 응답으로 렌더, emitter를 `userAccountId`로 식별해 fan-out에서 제외. 멀티탭은 히스토리로 보정(알려진 한계)(전제 7, QUIZ-CHAT-11).
9. **Q9 SSE 포맷**: `event: message`, `data:` = `{content, senderNickname, createdAt, roomUid}`, 메시지 id 없음, `senderNickname`=발신자 `UserAccount.nickname`, 하트비트 `:ping`(QUIZ-CHAT-15).
10. **Q10 신고 규칙**: 신고 1건=즉시 blind, 멱등(이미 blind면 no-op), 자기 메시지 신고 금지(403), 삭제 메시지 신고 404, 신고 이력 엔티티 없음(임계치·집계·추적 미지원, 후속 과제)(QUIZ-CHAT-20/27/28/29).
11. **Q11 방 생성**: 팀 시드와 함께 10개 구단 방 미리 생성(team↔room 1:1), 방 생성 API·온디맨드 없음. **방 owner는 시스템 계정**으로 두어 일반 사용자가 삭제할 수 없게 한다(승인 후 확정). chatrooms 시드가 배포 선행 조건이며 시스템 계정 시드가 그에 선행한다(QUIZ-CHAT-1/31, 제약 절 5).

## 계약 성립 제약 / 배포 선행 조건 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)
1. **quiz에 `GlobalExceptionHandler`가 없다(quiz.md).** 위 4xx 오류 계약(QUIZ-CHAT-12/13/14/27/29 등)이 `ApiResponse.fail` 형태로 성립하려면 quiz에 별도 `@RestControllerAdvice` 추가가 필요하다. 없으면 스프링 기본 500이 나가 계약과 어긋난다.
2. **`Chat.unblind()`·`Chat.delete()`는 이미 구현돼 있으나 이번엔 어떤 엔드포인트에도 연결하지 않는다.** blind 해제·메시지 삭제 경로가 없으므로 이 두 메서드는 **호출되지 않은 채 후속 관리자 기능용으로 남겨둔다**. 마찬가지로 `Chatroom.delete()`도 이번 범위엔 트리거가 없다(QUIZ-CHAT-24는 이미 삭제된 상태를 가정한 동작만).
3. **신고 대상 지정은 내부 PK를 room-스코프 경로로만 노출한다.** SSE·히스토리 응답에 메시지 식별자를 넣지 않는다(전제 6). PK 열거 방어는 인가·신고 규칙·blind 가역성으로 충당.
4. **SSE 인증은 헤더 방식이며 표준 `EventSource`로는 동작하지 않는다.** 프론트가 fetch 기반 폴리필로 `Authorization` 헤더를 실어야 QUIZ-CHAT-4/6이 SSE 경로에서 성립한다.
5. **배포 선행 조건(시드 순서 포함)**: `chatrooms`/`chats` 테이블이 prod DDL에 없고(domain.md), `chatrooms.owner_account_id` FK도 아직 스키마에 없다. 팀당 1행 방 시드와 그 owner로 쓸 시스템 계정 시드도 없다. 배포 전 순서: **① `chatrooms`/`chats` 테이블 마이그레이션(+`owner_account_id` FK 비-CASCADE·non-null) → ② 시스템 계정 1행 삽입 → ③ 그 계정 id를 owner로 10개 구단 방 시드**. `owner_account_id`가 non-null이므로 시스템 계정 시드 없이는 방 시드가 불가능하다(순서 강제).
6. **`Chatroom.owner → UserAccount`는 의도적으로 비-CASCADE다(domain의 `Chat → UserAccount` CASCADE와 다름).** 메시지(`Chat`)는 작성자 계정에 종속돼 함께 사라져도 되지만, 채팅방은 소유자 계정 삭제와 무관하게 보존되어야 하므로(대화 이력·다중 사용자 공유) `@OnDelete`를 걸지 않는다. domain.md의 CASCADE 판단 기준("삭제되면 함께 사라져도 되는 종속 데이터인가")에 따른 결정으로, 새 FK 추가 시 domain 컨텍스트에 이 근거를 반영할 필요가 있다(context-keeper 소관).
