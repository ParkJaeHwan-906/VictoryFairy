# 구단별 채팅 인수 테스트 시나리오
> 상태: 승인됨 (2026-07-20) · 모듈: quiz (호스트 앱) · 최종 수정: 2026-07-20 · 원본: `docs/requirements/quiz/chat.md`

## 읽는 법
- 각 시나리오는 `docs/requirements/quiz/chat.md`의 EARS 요구사항 하나(또는 소수)를 Given-When-Then으로 관찰 가능한 사실까지 펼친 것이다. 이 문서는 **계약(무엇이 참이어야 하는가)**까지이고, 이를 테스트 코드로 옮기는 것은 `test-writer`의 일이다.
- **구분** 열: `정상`=요구사항의 정상 경로 / `예외`=원치 않는 동작(검증 실패·권한 없음·중복·삭제·만료) / `경계`=값의 임계에서 판정이 갈리는 지점.
- 시나리오 ID: `AC-CHAT-<요구사항번호>-<n>`. 요구사항 번호는 `QUIZ-CHAT-<n>`과 대응한다.
- 모든 값은 확정값이다(Q1~Q11 종결, 2026-07-20). 엔드포인트 경로·상태코드·수치: content 최대 500자, 히스토리 페이지 30·최신순, `/api/chat/rooms`·`.../subscribe`·`.../messages`·`.../messages/{messageId}/report`.
- **관리자 기능(unblind/메시지 soft-delete)은 이번 범위에서 제외**되어 관련 시나리오는 삭제했다(하단 "삭제된 시나리오" 참고).

---

## 정상 경로 요약 (한눈에)

| 흐름 | 시작 | 관찰 결과 |
|---|---|---|
| 방 목록 | `GET /api/chat/rooms` (인증) | 200, 삭제 안 된 방만, 정수 PK 없음 |
| 입장 | `GET .../{roomUid}/subscribe` (fetch 폴리필로 Bearer 헤더) | 200 `text/event-stream`, `participants` +1 |
| 전송 | `POST .../{roomUid}/messages {content}` (인증) | 201 저장, **발신자 제외** 구독자 SSE 수신 |
| 히스토리 | `GET .../{roomUid}/messages` (인증) | 200 페이징(30·최신순), blind·삭제 제외 |
| 퇴장 | 구독 연결 종료 | `participants` -1 (0 하한) |
| 신고 | `POST .../messages/{messageId}/report` (인증, 타인 메시지) | 2xx, 대상 `blind=true` 즉시(자동, 관리자 없음) |

---

## A. 채팅방 조회

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-1-1 | QUIZ-CHAT-1 | 정상 | 팀 시드와 함께 생성된 10개 구단 방(owner=시스템 계정), 유효 토큰 | 방 목록·구독·전송 응답을 관찰한다 | 방이 team↔room 1:1로 존재. 응답·경로에 정수 PK가 없고 36자 UUID(`roomUid`)만 나타난다 |
| AC-CHAT-2-1 | QUIZ-CHAT-2 | 정상 | 삭제 안 된 방 N개, 삭제된 방 1개 | `GET /api/chat/rooms` | 200, N개만 반환, 각 항목에 `roomUid`·`team`·`name`·`participants` 포함 |
| AC-CHAT-2-2 | QUIZ-CHAT-2 | 경계 | 삭제 안 된 방이 0개 | `GET /api/chat/rooms` | 200, 빈 배열(404 아님) |
| AC-CHAT-3-1 | QUIZ-CHAT-3 | 예외 | 존재하지 않는 임의 UUID | `GET /api/chat/rooms/{uuid}` | 404 |
| AC-CHAT-3-2 | QUIZ-CHAT-3 | 예외 | `deletedAt`이 채워진 방의 `roomUid` | `GET /api/chat/rooms/{roomUid}` | 404 (삭제된 방도 없는 방과 동일 취급) |
| AC-CHAT-3-3 | QUIZ-CHAT-3 | 경계 | UUID 형식이 아닌 문자열(`abc`) | `GET /api/chat/rooms/abc` | 404 (500이 아니라 정상적 미존재 처리) |

## B. 인증·인가

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-4-1 | QUIZ-CHAT-4 | 예외 | `Authorization` 헤더 없음 | `POST .../{roomUid}/messages` | 401 (`RestAuthenticationEntryPoint` 형식) |
| AC-CHAT-4-2 | QUIZ-CHAT-4 | 예외 | 만료된 액세스 토큰 | `POST .../{roomUid}/messages` | 401 |
| AC-CHAT-4-3 | QUIZ-CHAT-4 | 예외 | 리프레시 토큰을 액세스 자리에 사용 | `POST .../{roomUid}/messages` | 401 (필터가 `isRefreshToken`이면 인증 안 함) |
| AC-CHAT-4-4 | QUIZ-CHAT-4 | 예외 | `Bearer ` 접두사 없는/깨진 헤더 | `POST .../{roomUid}/messages` | 401 |
| AC-CHAT-4-5 | QUIZ-CHAT-4 | 경계 | 서명은 유효하나 **탈퇴 계정**의 uid 토큰 | `POST .../{roomUid}/messages` | 401 (`findActiveIdByUid`가 빈 결과 → SecurityContext 미설정) |
| AC-CHAT-4-6 | QUIZ-CHAT-4 | 정상 | 유효한 액세스 토큰(활성 계정) | `GET /api/chat/rooms` | 200 (인증 통과) |
| AC-CHAT-5-1 | QUIZ-CHAT-5 | 정상 | 인증 사용자, 임의 구단 방(자신의 응원팀과 무관) | 입장·전송 시도 | 접근 허용, 403이 발생하지 않는다(구단 소속 제한 없음) |

## C. 입장(SSE 구독)·퇴장·참여 인원

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-6-1 | QUIZ-CHAT-6 | 정상 | 인증 사용자, 존재하는 방, fetch 폴리필로 `Authorization` 헤더 전달 | `GET .../{roomUid}/subscribe` | 200, `Content-Type: text/event-stream`, 연결 유지(즉시 종료 안 됨) |
| AC-CHAT-6-2 | QUIZ-CHAT-6 | 경계 | 표준 브라우저 `EventSource`(헤더 못 실음) | 구독 시도 | 인증 헤더 부재로 401 — 프론트는 fetch 기반 폴리필을 써야 함(전제 2) |
| AC-CHAT-7-1 | QUIZ-CHAT-7 | 정상 | 구독 전 `participants=k` | 구독이 성립한다 | 구독 직후 `participants=k+1` |
| AC-CHAT-7-2 | QUIZ-CHAT-7 | 경계 | 같은 사용자가 두 탭에서 동시에 구독 | 두 번째 구독 성립 | `participants`가 2 증가(연결 기준 카운트, 사용자 기준 아님) |
| AC-CHAT-8-1 | QUIZ-CHAT-8 | 정상 | 구독 중, `participants=k` | 클라이언트가 연결을 끊는다 | `participants=k-1` |
| AC-CHAT-8-2 | QUIZ-CHAT-8 | 경계 | `participants=0`인 방(정합성 붕괴 가정) | 퇴장 신호 도달 | `participants`가 음수로 내려가지 않고 0 유지(`Chatroom.leave()` 0 하한) |
| AC-CHAT-26-1 | QUIZ-CHAT-26 | 정상 | 유휴(메시지 없는) 구독 연결 | 시간이 흐른다 | 주기적 `:ping` 주석 프레임이 스트림에 관찰된다 |
| AC-CHAT-26-2 | QUIZ-CHAT-26 | 예외 | 구독 중 클라이언트가 leave 신호 없이 비정상 종료 | 하트비트 전송이 실패해 죽은 연결로 감지됨 | 서버가 연결을 회수하고 `participants`를 1 감소(best-effort 근사 보정) |
| AC-CHAT-9-1 | QUIZ-CHAT-9 | 예외 | 소프트삭제된 방 | `GET .../{roomUid}/subscribe` | 404, 스트림을 열지 않는다 |
| AC-CHAT-9-2 | QUIZ-CHAT-9 | 경계 | 구독 중인 방이 도중에 소프트삭제됨 | 삭제 후 신규 구독 시도 | 신규 구독은 404. 기존 열린 스트림 종료 방식은 범위 밖 |

## D. 메시지 전송

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-10-1 | QUIZ-CHAT-10 | 정상 | 인증 사용자, 존재하는 방, `content="안녕"` | `POST .../{roomUid}/messages` | 201, 응답에 `content`·`senderNickname`(발신자 `UserAccount.nickname`)·`createdAt`, `chats` 1행 증가, 저장값 `blind=false`·`deletedAt=null` |
| AC-CHAT-11-1 | QUIZ-CHAT-11 | 정상 | 같은 방에 다른 사용자 B가 구독 중 | 클라이언트 A가 메시지 전송 | B가 저장된 메시지를 SSE로 수신 |
| AC-CHAT-11-2 | QUIZ-CHAT-11 | 정상 | 발신자 A 자신도 같은 방 구독 중 | A가 전송 | **A는 SSE로 받지 않는다**(emitter를 `userAccountId`로 식별해 제외). A 본인 메시지는 201 POST 응답으로만 렌더 |
| AC-CHAT-11-3 | QUIZ-CHAT-11 | 경계 | 방에 구독자가 아무도 없음 | 전송 | 201 저장 성공(전달 대상 0명이어도 전송은 성공), 히스토리로 조회됨 |
| AC-CHAT-11-4 | QUIZ-CHAT-11 | 경계 | 발신자 A가 두 탭에서 구독 중(멀티탭) | A가 한 탭에서 전송 | 나머지 탭도 SSE 미수신(발신자 `userAccountId` 전체 제외). 다른 탭은 재접속 시 히스토리로 보정(알려진 한계) |
| AC-CHAT-12-1 | QUIZ-CHAT-12 | 예외 | `content=null`(필드 누락) | `POST .../messages` | 400, `chats` 증가 없음 |
| AC-CHAT-12-2 | QUIZ-CHAT-12 | 예외 | `content=""`(빈 문자열) | `POST .../messages` | 400, 저장 없음 |
| AC-CHAT-12-3 | QUIZ-CHAT-12 | 예외 | `content="   "`(공백 3칸) | `POST .../messages` | 400(공백만은 빈 값으로 취급), 저장 없음 |
| AC-CHAT-12-4 | QUIZ-CHAT-12 | 경계 | `content="\n\t"`(개행·탭만) | `POST .../messages` | 400(trim 후 빈 값), 저장 없음 |
| AC-CHAT-12-5 | QUIZ-CHAT-12 | 경계 | `content="a"`(공백 아닌 1자) | `POST .../messages` | 201(최소 유효), 저장됨 |
| AC-CHAT-13-1 | QUIZ-CHAT-13 | 경계 | `content` 정확히 500자 | `POST .../messages` | 201(상한 포함 통과) |
| AC-CHAT-13-2 | QUIZ-CHAT-13 | 예외 | `content` 501자 | `POST .../messages` | 400, 저장 없음 |
| AC-CHAT-13-3 | QUIZ-CHAT-13 | 경계 | 이모지(surrogate pair) 250개 = `String.length()` 500 | `POST .../messages` | 201(길이는 UTF-16 code unit 기준). 251개(502)면 400 |
| AC-CHAT-14-1 | QUIZ-CHAT-14 | 예외 | 존재하지 않는 `roomUid` | `POST .../{roomUid}/messages` | 404, 저장 없음 |
| AC-CHAT-14-2 | QUIZ-CHAT-14 | 예외 | 소프트삭제된 방 | `POST .../{roomUid}/messages` | 404, 저장 없음 |
| AC-CHAT-14-3 | QUIZ-CHAT-14 | 경계 | 유효 방 + content 검증도 동시 실패(501자를 없는 방에 전송) | `POST .../messages` | 검증 순서 계약: 방 미존재 404 vs content 400 중 무엇이 우선인지 확정 필요(관찰: 하나의 상태코드만) — test-writer 이관 전 확정 권장 |

## E. 실시간 전달(SSE·다중 인스턴스)

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-15-1 | QUIZ-CHAT-15 | 정상 | 구독 중인 클라이언트 | 메시지가 전달됨 | SSE 프레임 `event: message`, `data:`가 `{content, senderNickname, createdAt, roomUid}` JSON. **메시지 id 필드 없음** |
| AC-CHAT-15-2 | QUIZ-CHAT-15 | 경계 | 구독 유휴 상태 | 하트비트 발생 | 하트비트는 `data:` 이벤트가 아니라 별도 `:ping` 주석 프레임으로 구분된다 |
| AC-CHAT-16-1 | QUIZ-CHAT-16 | 정상 | 인스턴스 A에 구독, 인스턴스 B로 POST(운영, Redis pub/sub) | B로 전송 | A의 구독자가 수신(인스턴스 경계 넘어 전달, 발신자 제외 규칙 유지) |
| AC-CHAT-16-2 | QUIZ-CHAT-16 | 정상 | 단일 인스턴스(로컬/테스트, `InMemoryPublisher`) | 같은 인스턴스로 전송 | 같은 인스턴스 구독자 수신 |
| AC-CHAT-17-1 | QUIZ-CHAT-17 | 예외 | pub/sub 발행이 실패하는 상태 | 메시지 전송 | `POST .../messages`는 여전히 201이고 `chats`에 저장 → 히스토리로 조회 가능(fire-and-forget) |
| AC-CHAT-17-2 | QUIZ-CHAT-17 | 경계 | 전달은 실패했으나 저장은 성공 | 구독자가 재연결 후 히스토리 조회 | 놓친 메시지가 히스토리에 존재(전달 실패가 데이터 유실로 이어지지 않음) |

## F. 히스토리 조회

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-18-1 | QUIZ-CHAT-18 | 정상 | 방에 메시지 50개 | `GET .../{roomUid}/messages`(첫 페이지) | 200, 30개, 최신순(`createdAt` desc), 페이지 메타 포함 |
| AC-CHAT-18-2 | QUIZ-CHAT-18 | 경계 | 방에 메시지 0개 | 히스토리 조회 | 200, 빈 목록 |
| AC-CHAT-18-3 | QUIZ-CHAT-18 | 경계 | 메시지가 정확히 30개 | 첫 페이지 조회 | 30개 반환, 다음 페이지 없음 표시 |
| AC-CHAT-18-4 | QUIZ-CHAT-18 | 예외 | 없는/삭제된 방 | 히스토리 조회 | 404(QUIZ-CHAT-24와 정합) |
| AC-CHAT-19-1 | QUIZ-CHAT-19 | 예외 | 방에 정상 3건 + `blind=true` 1건 | 히스토리 조회 | 정상 3건만, blind 1건 제외 |
| AC-CHAT-19-2 | QUIZ-CHAT-19 | 예외 | 방에 정상 3건 + `deletedAt` 채워진 1건 | 히스토리 조회 | 정상 3건만, 삭제 1건 제외 |
| AC-CHAT-19-3 | QUIZ-CHAT-19 | 경계 | 페이지 크기 30, blind/삭제가 섞여 유효 메시지가 페이지 경계에 걸림 | 페이지 조회 | 응답은 **유효 메시지 기준**으로 채워지고 blind/삭제가 빈 슬롯으로 새지 않는다 |

## G. 신고·블라인드 (자동, 관리자 개입 없음)

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-20-1 | QUIZ-CHAT-20 | 정상 | 인증 사용자, 타인의 정상 메시지(`blind=false`) | `POST .../messages/{messageId}/report` | 2xx, 대상 메시지 `blind=true`(신고 1건으로 즉시, 관리자 승인 없음) |
| AC-CHAT-20-2 | QUIZ-CHAT-20 | 예외 | 존재하지 않는 `messageId` | 신고 요청 | 404 |
| AC-CHAT-20-3 | QUIZ-CHAT-20 | 예외 | 미인증 요청 | 신고 요청 | 401(B 영역과 정합) |
| AC-CHAT-27-1 | QUIZ-CHAT-27 | 예외 | 신고자 = 메시지 작성자(자기 신고) | 신고 요청 | 403, 대상 `blind=false` 유지(자기 메시지 신고 금지) |
| AC-CHAT-28-1 | QUIZ-CHAT-28 | 예외 | 이미 `blind=true`인 메시지 | 재신고 | 멱등: 2xx, 여전히 `blind=true`. 신고 이력 미저장이라 재신고 횟수 추적 안 됨 |
| AC-CHAT-29-1 | QUIZ-CHAT-29 | 예외 | 이미 소프트삭제된 메시지(`deletedAt` 채워짐) | 신고 요청 | 404 |
| AC-CHAT-21-1 | QUIZ-CHAT-21 | 예외 | 메시지가 `blind=true`로 전환됨 | 이후 신규 구독자 입장·히스토리 조회 | 신규 구독자·히스토리 모두 그 메시지 미수신 |
| AC-CHAT-21-2 | QUIZ-CHAT-21 | 경계 | blind 전에 이미 그 메시지를 SSE로 받은 클라이언트 | blind 처리됨 | 이미 전달된 화면의 소급 회수는 범위 밖 — 서버 계약은 "이후 조회·전달 제외"까지 |
| AC-CHAT-21-3 | QUIZ-CHAT-21 | 경계 | blind 처리된 메시지 | blind 해제를 시도할 수단을 찾는다 | **해제 경로가 없다**(이번 범위 관리자 기능 제외). 단 `content`는 `chats`에 보존되어 데이터 유실 아님 |

## H. 소프트삭제(채팅방) — 상태 기반 동작만

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-24-1 | QUIZ-CHAT-24 | 예외 | 소프트삭제된 방 | `GET /api/chat/rooms` | 목록에 없음 |
| AC-CHAT-24-2 | QUIZ-CHAT-24 | 예외 | 소프트삭제된 방 | `GET .../{roomUid}/subscribe` | 404 |
| AC-CHAT-24-3 | QUIZ-CHAT-24 | 예외 | 소프트삭제된 방 | `POST .../{roomUid}/messages` | 404 |
| AC-CHAT-24-4 | QUIZ-CHAT-24 | 예외 | 소프트삭제된 방 | `GET .../{roomUid}/messages`(히스토리) | 404(삭제 전 메시지도 조회 불가) |
| AC-CHAT-24-5 | QUIZ-CHAT-24 | 경계 | 방 삭제 실행 트리거를 찾는다 | 삭제 엔드포인트 탐색 | **방을 삭제하는 엔드포인트가 없다**(후속 과제). 후속에선 `room.owner == 현재 userAccountId` 비교로 인가 예정(QUIZ-CHAT-31 owner 모델 위). 이 요구사항은 이미 삭제된 상태의 동작만 정의 |

## I. 재연결·놓친 메시지 복구

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-25-1 | QUIZ-CHAT-25 | 정상 | 구독 중 연결이 끊김 | 재연결 + 히스토리 조회 | SSE 프레임에 `id:` 없음, 재연결 클라이언트는 히스토리로 공백 복구 |

## J. 채팅방 소유권 (선제 모델링 — 활성 엔드포인트 없음)

| 시나리오 ID | 요구사항 | 구분 | Given | When | Then |
|---|---|---|---|---|---|
| AC-CHAT-31-1 | QUIZ-CHAT-31 | 정상 | 시드된 10개 구단 방 | owner를 확인한다(스키마·시드 수준) | 각 방의 `owner_account_id`가 시스템 계정으로 non-null. 일반 사용자가 삭제할 수 없다(삭제 엔드포인트 자체가 없음) |
| AC-CHAT-31-2 | QUIZ-CHAT-31 | 경계 | 어떤 방의 owner 계정이 소프트삭제(`withdraw`)됨 | 방 존재를 확인한다 | 방과 `owner_account_id`가 그대로 보존된다(비-CASCADE, 계정 row는 exit_at만 기록되어 남음) |
| AC-CHAT-31-3 | QUIZ-CHAT-31 | 경계 | owner를 읽거나 쓰는 API를 찾는다 | 방 생성·삭제·owner 노출 엔드포인트 탐색 | **없음** — `owner_account_id`는 선제 모델링이라 이번 범위에 활성 동작이 없다. 사용자 생성 방 + 삭제 기능 도입 시 실효 |

---

## 경계 조건 커버리지 체크리스트 (예외 경로 집중)
사용자가 특히 요구한 4개 영역이 빠짐없이 다뤄졌는지 확인용.

| 영역 | 다룬 경계 | 시나리오 |
|---|---|---|
| **content 검증** | null / "" / 공백만 / 개행·탭만 / 1자(min) / 500자(max) / 501자 / 이모지 code unit 계수 | AC-CHAT-12-1~5, 13-1~3 |
| **신고→블라인드** | 정상 신고 / 없는 메시지 404 / 미인증 401 / 자기 신고 403 / 이미 blind 멱등 / 삭제된 메시지 신고 404 / blind 후 조회·전달 제외 / 기전달분 소급 없음 / 해제 경로 없음 | AC-CHAT-20-1~3, 27-1, 28-1, 29-1, 21-1~3 |
| **소프트삭제** | 메시지 blind/삭제 히스토리 제외 / 방 삭제 시 목록·구독·전송·히스토리 404 / 삭제 실행 트리거 없음 | AC-CHAT-19-1~2, 24-1~5 |
| **권한 없음** | 미인증 401 / 만료 / 리프레시 오용 / 깨진 헤더 / 탈퇴 계정 / 표준 EventSource 헤더 부재 401 / 자기 신고 403 | AC-CHAT-4-1~5, 6-2, 27-1 |

## 삭제된 시나리오 (관리자 기능 제외로 무효)
- **구 AC-CHAT-5-2**(소속 아닌 방 접근 403): Q1이 "전원 허용"으로 확정 — 방 접근 제한 자체가 없어 무효.
- **구 AC-CHAT-22-1/22-2**(관리자 unblind, 비관리자 403): 관리자 기능 전면 제외로 무효. blind 해제 경로 없음(AC-CHAT-21-3으로 대체).
- **구 AC-CHAT-23-1~3**(관리자 메시지 soft-delete): 관리자 기능 전면 제외로 무효.
- **구 AC-CHAT-25-2**(Last-Event-ID 재전송): Q6 "미지원" 확정으로 무효.
- 대응 요구사항 QUIZ-CHAT-22/23은 원본 문서 "제외 범위(후속 과제)"로 이관됨. 번호 재사용 금지.

## test-writer 이관 전 확정 권장 사항
- **AC-CHAT-14-3**: 방 미존재(404)와 content 위반(400)이 동시 발생할 때의 검증 우선순위. 하나의 상태코드만 관찰되므로 계약을 못 박아야 테스트가 결정적이다. (Q1~Q11 밖의 세부 사항 — 구현 착수 전 확정 권장)
