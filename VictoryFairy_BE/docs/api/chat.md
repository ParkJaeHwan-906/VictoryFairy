# 채팅(chat) API 명세

> **도메인** `chat` — 구단별 채팅방, 메시지 전송·히스토리·신고, SSE 실시간 구독.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/chat` · **엔드포인트** 7개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/chat/controller/ChatController.java` (`@RequestMapping("/chat")` — `/rt`는 context-path가 붙인다) — 현재 quiz 모듈의 유일한 컨트롤러.
> **최종 갱신** 2026-09-03 — **메시지 전송 경로에 욕설 마스킹 필터 도입.** 엔드포인트·요청/응답 스키마·상태코드·`ErrorCode`는 **하나도 바뀌지 않았고**, 바뀐 것은 `content` **값의 성질**이다: `POST .../messages`로 보낸 content는 서버에서 마스킹을 거친 뒤 저장되며 **저장값·201 응답 `data.content`·SSE `message` 이벤트의 `content` 세 곳이 모두 같은 문자열**이다. 치환 문자는 `***`가 아니라 **발신자가 응원하는 구단을 연상시키는 단어**(두산 팬 → `두산`·`망곰`·`철웅이`·`곰돌이` 중 하나)이며 같은 구단·같은 욕설이면 항상 같은 단어다(결정적). **금지어가 있다고 전송이 거절되지 않는다 — 400이 아니라 201이다.** ⚠ **원문은 저장되지 않고 마스킹 여부를 알리는 필드도 없다** — 발신자는 SSE 에코를 받지 않으므로 201 응답의 `content`가 자기 메시지의 최종 형태를 확인하는 유일한 지점이다(아래 "욕설 마스킹 필터" 절 — 프론트 필독). 계약 원본 `docs/requirements/quiz/chat-profanity-filter.md`(승인됨 2026-09-03, QUIZ-CPF-1~44). (직전: 2026-08-20 — **`MessageResponse`(전송 응답·히스토리)와 SSE `message` 이벤트 payload(`MessageEvent`)에 `profileImgUrl` 필드 추가**(발신자 `users_account.profile_img_url`, 없으면 `null` — 값의 형태는 [account](account.md#profileimgurl-값의-의미-프론트-필독)와 동일한 BaseURL 없는 EP). 발신자 계정이 이미 로딩돼 있어 SELECT는 늘지 않는다. 탈퇴자 메시지는 `(알수없음)` 더미 계정으로 이관되고 그 계정은 프로필 이미지가 없어 자연히 `null`이다(별도 분기 없음). 엔드포인트 7개·필드 개수 외 나머지 계약은 불변. 계약 원본 `docs/requirements/user/profile-image.md`(승인됨 2026-08-20). (직전: 2026-08-17 **비밀번호 변경 이전에 발급된 토큰이 이 도메인 7개 엔드포인트 전부에서 401로 거절되게 됨**(user 모듈의 `PATCH /api/users/me/password`, `main` 84f6f4a 머지 완료 — 공유 인증 필터라 chat 쪽 코드 변경 없이 적용됨). 응답·요청 계약은 그 외 불변.)) 그 이전 이력은 각 엔드포인트 섹션의 `최종 변경` 줄에 남아 있다.
> **요구사항** `docs/requirements/quiz/chat.md`(QUIZ-CHAT, 도입 시점 계약) · `docs/requirements/quiz/chat-team-access-control.md`(QUIZ-CTAC-1~29, 구단 접근 제어) · `docs/requirements/user/profile-image.md`(승인됨 2026-08-20 — `profileImgUrl` 필드의 출처) · `docs/requirements/quiz/chat-profanity-filter.md`(승인됨 2026-09-03, QUIZ-CPF-1~44 — 욕설 마스킹)
> 공통 규약(응답 래퍼·JWT payload·401 정책·**시스템 예외 래핑**)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/chat/rooms](#get-rtchatrooms) | 200 | 채팅방 목록(응원 구단 기준, `teamId` 선택) |
| GET | [/rt/chat/rooms/{roomUid}](#get-rtchatroomsroomuid) | 200 | 채팅방 상세 |
| GET | [/rt/chat/rooms/{roomUid}/subscribe](#get-rtchatroomsroomuidsubscribe) | 200 (SSE) | 방 실시간 구독 |
| **DELETE** | [/rt/chat/rooms/{roomUid}/subscribe](#delete-rtchatroomsroomuidsubscribe) | 200 | **명시적 퇴장(구독 종료)** — 신규 |
| POST | [/rt/chat/rooms/{roomUid}/messages](#post-rtchatroomsroomuidmessages) | 201 | 메시지 전송(**보낸 content에 욕설 마스킹이 적용된 값**이 저장·응답·SSE로 나간다) |
| GET | [/rt/chat/rooms/{roomUid}/messages](#get-rtchatroomsroomuidmessages) | 200 | 히스토리 조회(페이징) |
| POST | [/rt/chat/rooms/{roomUid}/messages/{messageId}/report](#post-rtchatroomsroomuidmessagesmessageidreport) | 200 | 메시지 신고 → 즉시 blind |

## 이 도메인의 특이사항

**7개 전부 인증 필수.** quiz의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다(과거 기록, 정정됨 — 이전 버전 문서는 `GET /health`로 적었으나 그 경로엔 핸들러가 없어 항상 404였다; 이 permitAll 범위 자체는 바뀐 적 없고 표기만 틀려 있었다). user 모듈처럼 GET 한정으로 열린 공개 경로가 없다. 신규 `DELETE .../subscribe`도 이 규칙 그대로 걸린다 — `SecurityConfig` 수정 없이 기존 `anyRequest().authenticated()`에 자연히 포함된다.

**응답 래퍼는 7개 모두 `ApiResponse<T>`이나 SSE 구독(GET)만 예외**로 `SseEmitter`를 반환한다(이벤트 스트림이라 JSON 래핑 대상이 아님).

**비밀번호 변경 이전에 발급된 토큰도 401로 거절된다(2026-08-17부터).** user 모듈과 같은 `JwtAuthenticationFilter`(`web-support` 공유)를 물려받아, `PATCH /api/users/me/password`(user, 8080) 이전에 발급된 access 토큰은 이 도메인 요청에서도 401 `UNAUTHENTICATED`(토큰이 아예 없을 때와 응답 동일)로 거절된다. chat 쪽 코드 변경은 없다 — 자세한 내용은 [README.md](README.md#2-인증-방식-jwt) 참고.

### 채팅방은 응원 구단 단위 폐쇄 공간이다 (2026-08-04 도입)

`docs/requirements/quiz/chat.md`의 QUIZ-CHAT-5("구단 소속에 따른 접근 제한 없음")는 **철회**되고 이 절이 대체한다. 판정 기준 구단은 요청자의 `user_support_team`에서 `oppose IS NULL`인 행의 구단이며(계정당 최대 1개), 캐시 없이 요청마다 다시 읽는다.

**판정 순서는 고정이다: ①404(방 없음·소프트삭제) → ②400 `SUPPORT_TEAM_REQUIRED`(응원 구단 없음) → ③403 `CHATROOM_TEAM_MISMATCH`(구단 불일치).** 방 존재를 먼저 보고, 그다음 비교 기준(내 응원 구단) 자체가 있는지, 마지막에 그 기준과 방의 구단이 같은지를 본다. 존재하지 않는 `roomUid`·삭제된 방은 요청자의 응원 구단과 무관하게 항상 404다.

이 판정은 목록(`GET /rooms`)과 방 단위 5개 경로(상세·구독·전송·히스토리·신고) 전부에 적용된다. **명시적 퇴장(`DELETE .../subscribe`)만 예외**다 — 아래 해당 절 참고.

`GET /rooms`에 `teamId`를 생략하면 응원 구단으로 간주하고, 값을 주더라도 응원 구단과 다르면 403이다(존재하지 않는 구단 id 포함). **결과적으로 이 파라미터에 넣을 수 있는 유효 값은 "내 응원 구단 id" 하나뿐이지만, 잘못된 값을 조용히 무시하지 않고 403으로 드러내기 위해 파라미터 자체는 유지한다.**

### 전송한 content는 서버가 치환한다 — 욕설 마스킹 필터 (2026-09-03 도입, 프론트 필독)

`POST /rt/chat/rooms/{roomUid}/messages`로 보낸 `content`는 **저장 직전에 욕설 마스킹을 거친다**(`ChatService.sendMessage`가 엔티티를 만들기 전에 `chat/profanity/ProfanityFilter.mask(content, teams.code)`를 호출한다). **엔드포인트·요청 스키마·응답 키 집합·상태코드·`ErrorCode`는 하나도 바뀌지 않았다** — 바뀐 것은 `content` **값의 성질**뿐이다.

**1. 결과 문자열은 세 곳이 모두 같다.** `chats.content` 저장값 · 201 응답 `data.content` · SSE `message` 이벤트의 `content`가 문자 단위로 동일하다(QUIZ-CPF-1/2/3). 금지어 매칭이 하나도 없으면 저장값은 원문과 완전히 동일하다(공백·이모지·구두점·대소문자 보존, QUIZ-CPF-8).

**2. 치환 문자는 `***`가 아니라 구단 연상 단어다.** 매칭 구간 하나를 **치환어 한 개로 통째 교체**한다(길이에 맞춰 채우거나 반복하지 않는다 — `"개새끼"`(3자)도 `"ㅗ"`(1자)도 각각 단어 하나). 후보 목록은 **발신자의 현재 응원 구단**(`user_support_team`에서 `oppose IS NULL`인 행) `teams.code`로 고른다 — 방의 구단이 아니라 발신자 기준이지만, 전송 경로는 구단 가드 때문에 둘이 항상 같다.

| 구단 | `teams.code` | 치환어 후보 |
|---|---|---|
| 두산 | OB | 두산 · 망곰 · 철웅이 · 곰돌이 |
| LG | LG | 엘지 · 럭키 · 스타 · 쌍둥이 |
| 삼성 | SS | 삼성 · 블레오 · 사자 |
| KT | KT | 케이티 · 위즈 · 마법사 |
| 키움 | WO | 키움 · 턱돌이 · 히어로 |
| KIA | HT | 기아 · 호랑이 · 호걸이 |
| 한화 | HH | 한화 · 수리 · 위니 · 독수리 |
| NC | NC | 엔씨 · 단디 · 쎄리 · 공룡 |
| 롯데 | LT | 롯데 · 누리 · 아라 · 거인 |
| SSG | SK | 에스에스지 · 랜디 · 쓱 |
| (폴백) | 표에 없는 code·`null` | 야구 · 직관 · 응원 |

**선택은 결정적이다.** 후보는 되돌린 원문 구간 문자열의 `String.hashCode`로 고르므로(`MaskWordTable.pick`), 같은 구단·같은 매칭 문자열이면 요청을 다시 보내도·앱을 재기동해도·다른 파드가 처리해도 **항상 같은 단어**다(무작위·시각·계정 id가 개입하지 않는다). 반대로 **구단이 다르면 같은 욕설이라도 다른 단어**가 되고, 한 메시지 안에서도 매칭 문자열이 다르면 다른 단어가 될 수 있다(`"시발 병신"` → 두 단어가 서로 다를 수 있음). `"시발"`과 `"시 발"`은 되돌린 원문 문자열이 달라 서로 다른 후보가 뽑힐 수 있다(둘 다 치환은 된다).

**3. 금지어가 있다고 전송이 거절되지 않는다.** content 전체가 금지어뿐인 요청(`{"content":"시발"}`)도 **201**이고 `chats`에 1행이 늘어난다. 마스킹 판정 결과가 4xx로 나가는 경로는 없으며 새 `ErrorCode`도 추가되지 않았다(QUIZ-CPF-6/35).

**4. ⚠ 원문은 저장되지 않고, 마스킹 여부를 알리는 필드도 없다.** 201 응답·SSE payload의 키 집합은 종전과 동일하고(`id`·`content`·`senderNickname`·`profileImgUrl`·`createdAt`(+SSE `roomUid`)) `masked` 같은 키가 없다. `chats`에 새 컬럼도 없다(DDL 없음). **발신자는 SSE 에코를 받지 않으므로 201 응답의 `content`가 자기 메시지의 최종 형태를 확인하는 유일한 지점**이며, "치환되었습니다" 같은 고지를 하려면 **프론트가 보낸 문자열과 응답 문자열을 직접 비교**해야 한다(서버가 알려 주지 않는다). 치환된 원문은 서버에도 남지 않아 복구할 수 없다.

**5. `@Size(max = 500)`은 원문에만 적용된다.** `@Valid`가 컨트롤러 진입 전에 원문을 보고 판정하므로, 치환으로 길이가 늘어 결과가 500자를 넘어도 **400이 아니라 201**이며 그 길이 그대로 저장된다(`chats.content`는 `TEXT` 컬럼이라 저장 제약도 없다). 마스킹 후 길이를 다시 검증해 400을 내는 동작은 없다.

**6. 검증 순서는 종전 그대로다.** content 검증(400) → 방 없음(404) → 응원 구단 없음(400) → 구단 불일치(403). 마스킹은 이 4단계를 전부 통과한 뒤 **저장 직전**에 수행되므로, 404·403으로 끝난 요청에서는 마스킹도 저장도 일어나지 않는다.

**7. 어떤 문자열이 걸리는가(요약).** 판정 로직은 `VictoryFairy_AI/validation/`의 파이썬 구현을 Java로 이식한 것이며, 런타임에 그 앱을 HTTP로 호출하지 않는다. 관측되는 성질만 적으면:

- **회피 표기가 함께 사라진다.** 정규화가 공백·특수문자를 지우므로 `"시 발"`·`"씨@발"`·`"시!!발"`은 사이 글자까지 삼켜 치환어 하나만 남는다. 단, 삼키는 대상은 정규화가 지우는 문자(공백·특수문자)뿐이라 `"시(진짜)발"`처럼 사이에 정상 단어가 낀 문장은 **아예 매칭되지 않는다**.
- 전각·볼드·원문자(`ｓｉｂａｌ`·`𝘀𝗶𝗯𝗮𝗹`·`ⓢⓘⓑⓐⓛ`), 초성(`ㅅㅂ`), 두벌식 자판 입력(`tlqkf` → `시발`), 숫자·기호 치환(`시1발`류)도 같은 파이프라인에서 걸린다.
- **오탐 방지 예외 표현**이 있어 `"보지도 못했다"`·`"결정장애 온다"`·`"수십년 만이다"`·`"새끼손가락 다쳤대"`·`"샤갈 전시회 갔다왔다"`·`"싸갈겼다"` 등은 치환되지 않는다. 반면 `"새끼"`·`"개새끼"`는 여전히 치환된다.
- **짧은 변형어 `샤갈`·`싸갈`·`야발`은 원문에서 붙어 있을 때만** 매칭한다(되돌린 구간에 공백이 있으면 버린다). 그래서 `"야발"`은 치환되지만 `"야 발표 준비하자"`·`"이야 발이 빠르네"`는 원문 그대로다. 다른 금지어(`시발` 등)의 공백 우회 탐지는 그대로 유지된다.
- 치환어 34개는 그 자체로 어떤 금지어·예외 패턴에도 걸리지 않는다(치환 결과를 다시 넣어도 추가 치환이 없다).
- 금지어·예외·정규화·공백 엄격 목록은 코드가 아니라 데이터다(`quiz/src/main/resources/profanity/*.json`).

**8. 마스킹 실패는 삼키지 않는다.** 필터가 예상치 못한 예외를 던지면 그 요청은 201이 아니며 `chats` 행도 늘지 않는다(원문을 그대로 저장하는 fallback 없음 — QUIZ-CPF-36). 필터는 상태 없는 순수 컴포넌트이고 데이터 로딩·패턴 컴파일은 기동 시 1회라, 이 경로가 실제로 발생하면 그 자체가 버그다. 응답은 공통 catch-all인 500 `INTERNAL_SERVER_ERROR`(`ApiResponse` 래퍼)이며, 이 도메인 전용 에러 코드는 없다([README.md](README.md#1-1-시스템-예외도-래퍼를-탄다-2026-08-20부터--quiz-fe-영향-필독) 참고).

**9. 적용 범위는 전송 하나뿐이다.** 히스토리 조회(`GET .../messages`)는 저장된 값을 그대로 돌려주고 조회 시점에 다시 필터를 돌리지 않으며, **필터 도입 이전에 저장된 메시지에는 소급 적용이 없다**(소급 배치도 없다). 신고→blind 경로도 마스킹과 무관하게 종전 그대로다. 닉네임·방 이름 등 채팅 전송 외의 사용자 입력에도 이 필터는 적용되지 않는다.

### 외부 식별자

- 채팅방은 `roomUid`(`Chatroom.uid`, UUID)로만 노출된다. 응답 어디에도 방의 순차 PK가 나타나지 않는다.
- 메시지 식별자는 `id`(=`Chat` 내부 PK)이며 `MessageResponse`(전송 응답·히스토리)와 `MessageEvent`(SSE payload) 양쪽에 같은 값이 실린다. 신고 경로의 `{messageId}`가 이 값이다. 클라이언트는 이 `id`로 (1) SSE로 이미 그린 메시지를 히스토리 재조회 때 중복 렌더하지 않도록 걸러내고 (2) 신고를 호출한다. 메시지는 순차 PK가 노출되므로 **방 식별자는 계속 uid(UUID)** 를 쓴다(열거 방지는 방 단위에서 유지).
- 발신자/작성자 계정 PK(`user_account_id`)·`uid`도 응답에 노출되지 않는다. `senderNickname`(`UserAccount.nickname`)과 `profileImgUrl`(`UserAccount.profileImgUrl`, 2026-08-20 신규 — BaseURL 없는 EP, [account](account.md#profileimgurl-값의-의미-프론트-필독)와 같은 형태)만 노출된다.

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
| 400 | (`ApiResponse` 래퍼, ErrorCode 없음) | `teamId`가 정수로 변환되지 않음(예: `?teamId=abc`). 컨트롤러 진입 전 바인딩 단계 실패지만 2026-08-20부터 `GlobalExceptionHandler.handleTypeMismatch`(공유 컴포넌트 신설)가 잡아 `ApiResponse` 래퍼를 붙인다(종전엔 래퍼 없이 스프링 기본 처리 — player·game의 `?teamId=`·`?date=`도 같은 방식으로 정정됨, [README.md](README.md#1-응답-래퍼--도메인엔드포인트마다-다르다) 참고) |
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
> 최종 변경: 2026-09-03 — **`message` 이벤트의 `content`가 마스킹된 값**이 된다(발신자가 보낸 원문이 아니라 `chats.content` 저장값·201 응답 `data.content`와 같은 문자열). payload 키 집합·이벤트 이름·구독 계약 자체는 불변이며 마스킹 여부를 알리는 필드는 없다. (직전: 2026-08-20 — `message` 이벤트 payload(`MessageEvent`)에 `profileImgUrl` 추가(발신자 프로필 이미지 EP, 없으면 `null`). 히스토리에만 실으면 SSE로 방금 도착한 메시지는 아바타가 비었다가 새로고침해야 채워지므로 함께 싣는다 — 전송 트랜잭션이 이미 로딩해 둔 발신자 계정이라 SELECT 증가 없음. (직전: 2026-08-04 구독 시점 1회 구단 일치 검사 추가(스트림을 열기 전, 트랜잭션 안에서 완결) + 같은 사용자의 기존 구독을 축출(last-one-wins)))

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
| 메시지 | `message` | JSON `{id, content, senderNickname, profileImgUrl, createdAt, roomUid}`(`MessageEvent`, `profileImgUrl`은 2026-08-20 신규·nullable). **`content`는 2026-09-03부터 마스킹된 값**(저장값·전송 201 응답과 동일한 문자열, 발신자 원문이 아님 — [욕설 마스킹 필터](#전송한-content는-서버가-치환한다--욕설-마스킹-필터-2026-09-03-도입-프론트-필독) 참고) | 같은 방에 **커밋된** 새 메시지가 저장될 때 전달(커밋 이후 발행이라 전달된 메시지는 반드시 DB에 있다). SSE 프레임의 `id:` 필드는 여전히 없다(Last-Event-ID 미지원 — 재연결 시 놓친 메시지는 `GET .../messages`로 복구하고, payload 의 `id` 로 중복을 걸러낼 것) |
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
| 401 | UNAUTHENTICATED | 인증 헤더 없음/무효/비밀번호 변경 이전에 발급된 토큰(2026-08-17부터 — 응답 동일, 위 "이 도메인의 특이사항" 참고, 엔트리포인트 단계, 스트림 열기 전) |

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
> 최종 변경: 2026-09-03 — **욕설 마스킹 필터가 저장 직전에 끼어들었다.** 경로·요청 스키마·응답 키 집합·상태코드·`ErrorCode`는 불변이고, 저장값·201 응답 `data.content`·SSE `content`가 **마스킹된 같은 문자열**이 된다. 금지어가 있어도 400이 아니라 201이며, 원문도 마스킹 여부 플래그도 남지 않는다. `@Size(max=500)`은 원문에만 적용돼 치환 후 500자를 넘어도 201이다. (직전: 2026-08-20 — 응답에 `profileImgUrl` 추가(발신자 프로필 이미지 EP, 없으면 `null`. 발신자 계정이 이미 로딩돼 있어 SELECT 증가 없음). 나머지 계약 불변. (직전: 2026-08-04 구단 일치 검사 추가(내 응원 구단 방이 아니면 403, 저장하지 않음). 판정 순서: content 검증(400) → 방 존재(404) → 응원 구단 없음(400) → 구단 불일치(403)))

메시지 전송. **content에 욕설 마스킹을 적용한 뒤** 저장하고, 발신자를 제외한 같은 방 구독자에게 SSE `message` 이벤트로 전달(fire-and-forget)한 다음, 저장된(=마스킹된) 메시지를 응답으로 반환한다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| roomUid | String | 방 외부 식별자 |

**요청** `SendMessageRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| content | String | `@NotBlank` `@Size(max = 500)` | 메시지 내용. `null`·빈 문자열·공백만은 `@NotBlank` 위반. 길이는 `String.length()`(UTF-16 code unit) 기준 — 이모지 surrogate pair는 2로 계수. **두 제약은 마스킹 이전의 원문에만 적용된다**(2026-09-03) |

**검증 순서(중요)**: `@Valid`가 컨트롤러 진입 전(인자 바인딩 단계)에 수행되므로, **content 위반(400)이 방 존재 여부·구단 일치 확인보다 먼저 판정**된다. 즉 존재하지 않는 `roomUid`나 타 구단 방에 빈 content로 요청해도 404·403이 아니라 400이 난다. content가 유효하면 그다음은 404(방 없음) → 400(응원 구단 없음) → 403(구단 불일치) 순서이고, **욕설 마스킹은 이 4단계를 전부 통과한 뒤 저장 직전**에 수행된다(2026-09-03 — 마스킹이 판정 순서에 끼어들지 않으며 어떤 4xx도 새로 만들지 않는다).

**보낸 content와 저장·응답되는 content는 다를 수 있다(2026-09-03).** 금지어가 포함되면 서버가 그 구간을 발신자 응원 구단의 연상 단어로 치환한다 — 요청은 여전히 **201**이고 거절되지 않는다. `@Size(max=500)`을 통과한 원문이 치환으로 길어져 500자를 넘어도 400이 아니다(`chats.content`는 `TEXT`). 규칙·구단별 치환어 표·프론트 영향은 위 [욕설 마스킹 필터](#전송한-content는-서버가-치환한다--욕설-마스킹-필터-2026-09-03-도입-프론트-필독) 절 참고.

**응답 201 Created** `ApiResponse<MessageResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 메시지 식별자(`Chat` PK). 신고 경로의 `{messageId}`이자 SSE payload `id`와 같은 값 |
| content | String | **저장된(=마스킹된) 메시지 내용**(2026-09-03부터 요청 값과 다를 수 있음). 같은 방 구독자에게 나가는 SSE `content`·`chats.content` 저장값과 문자 단위로 동일하다. 발신자는 SSE 에코를 받지 않으므로 **이 값이 자기 메시지의 최종 형태를 확인할 수 있는 유일한 지점**이며, 치환 여부를 알리는 별도 필드는 없다(보낸 문자열과 비교해야 안다) |
| senderNickname | String | 발신자 `UserAccount.nickname` |
| profileImgUrl | String \| null | 발신자의 프로필 이미지 **EP**(BaseURL을 뺀 오브젝트 키, 2026-08-20 신규 — [account](account.md#profileimgurl-값의-의미-프론트-필독)와 같은 형태). 이미지가 없으면 `null`. 탈퇴자의 메시지는 `(알수없음)` 더미 계정으로 소유권이 이관되고(`ChatRepository.reassignSender`) 그 계정은 프로필 이미지가 없어 자연히 `null`이다 |
| createdAt | LocalDateTime | 생성 시각 |

저장 시 `Chat.blind=false`, `deletedAt=null`로 저장된다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증 실패, ErrorCode 없음) | `content`가 공백뿐이거나 501자 이상. 저장하지 않는다. 아래 모든 실패보다 우선 판정됨 |
| 404 | CHATROOM_NOT_FOUND | `roomUid`에 해당하는 활성 방이 없음(존재하지 않거나 소프트삭제). 저장하지 않는다 |
| 400 | SUPPORT_TEAM_REQUIRED | 방은 존재하지만 요청자에게 현재 응원 중인 구단이 없음. 저장하지 않는다 |
| 403 | CHATROOM_TEAM_MISMATCH | 방의 구단이 요청자의 응원 구단과 다름. 저장하지 않으며 해당 방 구독자에게 SSE 전달도 없다 |
| 500 | INTERNAL_SERVER_ERROR | 마스킹 처리가 예상치 못한 예외로 실패(2026-09-03 — 원문을 그대로 저장하는 fallback을 두지 않는다). 저장되지 않으며 SSE 전달도 없다. 필터는 상태 없는 순수 컴포넌트라 실제로 발생하면 버그다 |

**금지어는 실패 사유가 아니다(2026-09-03).** content에 어떤 금지어가 들어 있어도 400·403이 아니라 201이며, 마스킹 판정 때문에 추가되는 `ErrorCode`는 없다.

실시간 전달(SSE fan-out) 실패는 `ChatService.publishMessage()`가 예외를 삼켜(fire-and-forget) 저장·201 응답에 영향을 주지 않는다.

**예시**
```bash
curl -i -X POST http://localhost:8081/rt/chat/rooms/3f9c2e10-.../messages \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"content":"안녕하세요"}'
```
```json
{"success":true,"data":{"id":128,"content":"안녕하세요","senderNickname":"gildong","profileImgUrl":"user-profile-img/9f1c4e2a-....jpg","createdAt":"2026-08-20T14:03:21"},"message":null}
```

마스킹이 일어난 예시(두산(`OB`) 팬, 요청 `{"content":"시발 오늘 왜 저럼"}` → **201**):
```json
{"success":true,"data":{"id":129,"content":"망곰 오늘 왜 저럼","senderNickname":"gildong","profileImgUrl":null,"createdAt":"2026-09-03T14:03:21"},"message":null}
```
(치환어는 구단·매칭 문자열에 따라 결정되며 위 값은 예시다 — 실제로 어느 후보가 뽑히는지는 [치환어 표](#전송한-content는-서버가-치환한다--욕설-마스킹-필터-2026-09-03-도입-프론트-필독)의 해시 규칙이 정한다. 같은 입력이면 항상 같은 결과다.)

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
> 최종 변경: 2026-08-20 — 항목(`MessageResponse`)에 `profileImgUrl` 추가(발신자 프로필 이미지 EP, 없으면 `null`). 히스토리는 fetch join으로 이미 발신자 계정을 함께 로딩하므로 SELECT 증가 없음. (직전: 2026-08-04 구단 일치 검사 추가(내 응원 구단 방이 아니면 403, 메시지가 실리지 않음))

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
| content | List\<MessageResponse\> | 현재 페이지 항목(`id`/`content`/`senderNickname`/`profileImgUrl`(2026-08-20 신규, nullable)/`createdAt`) |
| page | int | 현재 페이지 번호(0-base) |
| size | int | 페이지 크기(30) |
| totalElements | long | 조건을 만족하는 전체 메시지 수 |
| totalPages | int | 전체 페이지 수 |
| hasNext | boolean | 다음 페이지 존재 여부 |

`blind=true`이거나 `deletedAt`이 채워진 메시지는 결과에서 제외된다(`findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc`).

**욕설 마스킹은 이 경로의 동작을 바꾸지 않는다(계약 불변).** 히스토리는 저장된 값을 그대로 돌려주고 조회 시점에 필터를 다시 돌리지 않는다 — 2026-09-03 이후 전송된 메시지는 저장 시점에 이미 마스킹된 값이고, **그 이전에 저장된 메시지는 원문 그대로 조회된다(소급 치환·소급 배치 없음)**.

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
- 요구사항: `docs/requirements/quiz/chat.md`(도입 시점 계약), `docs/requirements/quiz/chat-team-access-control.md`(구단 접근 제어 + 구독 수명 계약, QUIZ-CTAC-1~29), `docs/requirements/quiz/chat-profanity-filter.md`(욕설 마스킹, QUIZ-CPF-1~44 — 승인됨 2026-09-03)
