# 만료 데이터 정리 스케줄러(removeExpiredData) 요구사항
> 상태: **승인됨 (2026-08-18)** · 모듈: user (파급: `:domain`, quiz, prod DB 스키마) · 최종 수정: 2026-08-18
> **ID 접두사 `USER-EDC`** — 기존 접두사(`WD`/`PE`/`ME`/`ATI`/`SP`/`PL`/`PLF`/`GSP`/`GL`/`TM`/`NICK`/`EMV`)와 겹치지 않는다.
> **2026-08-18 1차 개정**: 쟁점 3(채팅방을 소유한 계정) 확정 — `(알수없음)` 더미 계정으로 **소유권 이관** 후 삭제. USER-EDC-12·13 삭제, 30~45 신설.
> **2026-08-18 2차 개정**: 쟁점 9(`quizzes_like` UNIQUE 충돌) 확정 — 후보 A~C가 아닌 **네 번째 길**이다. 퀴즈 추천은 이관하지 않고 **FK를 `ON DELETE SET NULL`로 바꿔 행만 남긴다.** USER-EDC-37·38 삭제, 46~50 신설. 이 결정으로 정리 방식이 **하이브리드**가 됐다(아래 "결정 기록" — 가르는 기준은 "역참조로 사람 이름을 읽는가").
> **2026-08-18 3차 개정(승인)**: 남아 있던 쟁점 10건(1·2·4·5·6·7·8·10·11·12)이 사용자 승인으로 전부 확정됐다. 승인 대기 표시를 걷어내고 상태를 `승인됨`으로 올렸으며, 질문 절은 "결정 기록"으로 흡수했다 — **이 문서에 답을 기다리는 항목은 없다.**
> **2026-08-18 4차(구현 후 정정)**: 요구사항 문장은 그대로 두고 서술만 고쳤다 — ①`(알수없음)` 길이 오기(5자 → **6자**, 괄호 2 + 한글 4. USER-EDC-35의 400 거절 판정은 그대로 성립하며 test-writer가 실제 문자열로 테스트를 짜다 발견) ②제약 5(운영 DB 확인) **완료**로 갱신 ③제약 9(DDL 마이그레이션) **적용 완료**로 갱신(실패했던 첫 시도의 교훈 포함) ④제약 2를 "실재 결함"에서 **"잠재 위험(파드 TZ 의존)"** 으로 정정 — 운영 파드 JVM 기본 존이 이미 `Asia/Seoul`임을 실측 확인했다 ⑤"구현·검증 기록" 절 신설.
> **2026-08-18 6차(상태 정정)**: 제약 2를 실제 상태에 맞게 좁혔다 — `UserAccountService.withdraw`의 `Clock` 정렬이 이번 브랜치에서 **완료**되어 기록·판정이 같은 시계를 쓰게 됐고, 남은 `LocalDateTime.now()` 두 곳(`AuthService`·`SupportService`)의 영향 범위를 코드 근거로 명시해 범위 밖으로 남긴 이유를 적었다. 만료 토큰이 다량 쌓이는 현상이 **설계대로의 누적**임을 "확인된 사실"에 추가했다(시간대 문제로 오해하지 않도록).
> **2026-08-18 5차(확정값 갱신)**: 예약 행의 `uid`를 사람이 지어낸 순차값에서 **실제 생성된 UUID v4**로 교체한 데 따라 USER-EDC-32의 확정값을 갈아끼웠다(요구사항 문장·취지는 그대로 — "닉네임 조회가 아니라 고정 uid로 식별한다"가 계약이고 그 값만 바뀌었다). 배포 순서 의존성으로 **제약 11**(`migrate-reserved-uids-to-uuid.sql` 선행)을 추가했다.
> **표기**: 요구사항 3건에 붙은 **`(구현 재량)`** 은 계약이 결과만 고정하고 수단은 구현자가 고르는 자리라는 뜻이다(설정 키 이름·단계 순서 표현·선행 검사 방식). 그 밖의 모든 요구사항은 확정 계약이다.

## 배경 / 목적

`withdraw.md`는 하드 딜리트를 명시적으로 범위 밖으로 밀어 두고("하드 딜리트 / 개인정보 파기 배치 — `exit_at`은 표식만 남기며 실제 행 삭제는 이번 범위 밖") 탈퇴를 soft delete로 확정했다. **이 스케줄러가 그 유예를 끝낸다** — 저장소 전체에서 사용자 데이터를 물리적으로 지우는 **최초의 경로**다.

그래서 이 작업의 본질은 "배치 하나 추가"가 아니라 **"계정 행은 사라지지 않는다"는 지금까지의 전제를 깨는 것**이다. 그 전제 위에 세워진 결정이 실제로 존재한다.

- `Chatroom.owner`에는 `@OnDelete`가 없고, 그 이유가 코드 주석에 **"계정 삭제는 소프트 삭제라 고아 FK가 안 생김"** 이라고 적혀 있다. 이 문서가 그 근거를 무효화한다.
- `QuizLike`의 `@OnDelete(CASCADE)` 역시 "계정은 안 지워진다"를 전제로 골라진 값이다. 실제로 계정을 지우기 시작하면 그 선택이 **추천 수를 조용히 깎는다**(USER-EDC-46).
- user 모듈에는 **`@EnableScheduling` 자체가 없다**(저장소 전체에서 quiz의 `RealtimeSchedulingConfig` 하나뿐). 이 모듈의 첫 스케줄러다.

두 차례 개정을 거쳐 목적이 하나 더 늘었다: **떠난 사람의 흔적을 지우되, 남은 사람의 콘텐츠와 집계는 지우지 않는다.** 그 방법은 데이터마다 다르다 — 사람 이름을 표시해야 하는 것은 `(알수없음)`으로 **이관**하고, 세기만 하는 것은 소유자를 **비운다**(SET NULL).

## 범위

- 포함
  - 매일 1회(Asia/Seoul 03:00) 도는 정리 작업 1개
  - `(알수없음)` **더미 계정의 존재 보장**(부트스트랩)과 그 계정으로의 **소유권 이관**(`chatrooms`·`chats`)
  - `quizzes_like`의 **소유자 분리**(FK `ON DELETE SET NULL`) 및 그 전제인 **DDL 마이그레이션**
  - 탈퇴(`users_account.exit_at IS NOT NULL`) 후 30일이 지난 계정의 **하드 삭제**(부모 `users` 행부터)
  - `users_refreshtoken`의 **만료된 토큰 행 삭제**
  - 위 작업들의 실패 격리·결과 로깅
  - user 앱에 스케줄링을 활성화하는 것(이 모듈 최초)
- 제외
  - **탈퇴 API(`DELETE /api/users/me`) 동작 변경** — soft delete 계약(`withdraw.md` USER-WD-1~4)은 그대로다
  - **신규 엔드포인트** — 수동 실행 API·관리자 조회 API를 만들지 않는다
  - **`(알수없음)` 계정이나 소유자 없는 추천을 API가 특별 취급하는 것** — 기존 조회 경로에서 다른 데이터와 똑같이 취급된다(USER-EDC-44)
  - **퀴즈 추천 수를 별도 카운터 컬럼으로 비정규화하는 것** — 질문 9의 후보 C였고 채택되지 않았다. `QuizLikeRepository`가 "같은 사실을 두 곳에 두지 않는다"고 이미 결정해 둔 자리다
  - **재가입 정책 변경** — UNIQUE 제약을 손대지 않는다. 다만 삭제된 계정의 email·tel·nickname은 **결과적으로** 재사용 가능해진다(확정 근거: 결정 기록 3차 2)
  - **채팅방·채팅의 soft delete(`deleted_at`) 정리, 오래된 채팅 이력 정리, 퀴즈 제출 이력 보존기간** — 요청 범위 밖
  - **Redis에 남는 이메일 인증 키 정리** — TTL이 이미 회수한다

## 확인된 사실 (요구사항의 전제 — 다시 조사하지 말 것)

### FK 삭제 규칙 (devdb `information_schema.REFERENTIAL_CONSTRAINTS` 실측, 2026-08-18)

| 부모 | 자식 테이블(FK) | 현재 DELETE_RULE | 이 문서가 요구하는 최종 형태 |
|---|---|---|---|
| `users` | `users_account` (유일한 자식) | CASCADE | 그대로 |
| `users_account` | `users_refreshtoken` | CASCADE | 그대로(삭제) |
| `users_account` | `users_bq` | CASCADE | 그대로(삭제) |
| `users_account` | `user_support_team` | CASCADE | 그대로(삭제) |
| `users_account` | `user_support_player` | CASCADE | 그대로(삭제) |
| `users_account` | `quiz_users_submit` | CASCADE | 그대로(삭제) |
| `users_account` | **`quizzes_like`** | CASCADE | **`SET NULL`로 변경 + 컬럼 nullable** ← 2차 개정, DDL 필요 |
| `users_account` | `chats` | CASCADE | 그대로 두되 **삭제 전에 더미 계정으로 이관**(UPDATE) |
| `users_account` | **`chatrooms`(`owner_account_id`)** | **NO ACTION** | 그대로 두되 **삭제 전에 더미 계정으로 이관**(UPDATE) |

- **"`users` 한 줄 지우면 전부 사라진다"는 예상은 `chatrooms` 때문에 성립하지 않는다.** `chatrooms.owner_account_id`는 `NOT NULL`이라 NULL로 비울 수도 없어, 이관하지 않으면 `DELETE FROM users` 자체가 FK 위반으로 실패한다.
- 엔티티 애노테이션과 현재 DB 제약은 어긋난 곳이 없다.
- ⚠ 위 표는 devdb 실측에서 출발했다 — **운영 DB(43.200.82.148) 대조는 2026-08-18에 완료됐고 결과가 동일했다**(제약 5).

### MySQL UNIQUE는 NULL을 서로 다른 값으로 본다 (SET NULL을 고른 근거, devdb 실측)

임시 테이블에 `UNIQUE(acc, quiz)`를 걸고 `(NULL,1)`·`(NULL,1)`·`(NULL,1)`·`(7,1)` 4행을 넣어 **전부 성공**하는 것을 확인했다. 즉 탈퇴자가 몇 명이든 `(NULL, 같은 quiz_id)` 행이 공존한다 — **추천 수가 한 건도 손실되지 않는다.** 더미 계정 이관(구 질문 9-A)은 `uk_quizzes_like_account_quiz` 때문에 한 명분만 남아 카운트가 깎였는데, 이 방식은 그 손실이 원천적으로 없다.

### `quizzes_like`의 소유자를 비워도 코드가 깨지지 않는다 (`QuizLikeRepository` 4개 전수 확인)

| 메서드 | 계정 사용 | NULL 행에서 |
|---|---|---|
| `findByUserAccount_IdAndQuiz_Id` | `user_account_id = ?` | 조건에서 자연히 제외 |
| `findLikedQuizIds` | `l.userAccount.id = :userAccountId` | 자연히 제외 |
| `countByQuiz_IdAndLikedTrue` | 안 씀 | 그대로 집계됨 |
| `countLikesByQuizIds` | 안 씀 | 그대로 집계됨 |

추천 수는 `liked = true` 행 수라 **누가 눌렀는지가 집계에 들어가지 않는다** — 계정 정체성을 지우는 것이 데이터 의미와도 맞는다. 좋아요에서 닉네임을 역참조하는 경로는 없다.

### `chats`에는 같은 방식을 쓸 수 없다 (하이브리드가 된 이유)

`quiz/src/main/java/com/skhynix/quiz/chat/dto/MessageResponse.java`의 `from()`이 `chat.getUserAccount().getNickname()`을 **직접 역참조**한다. 소유자를 NULL로 두면 그 메시지가 섞인 히스토리를 읽을 때마다 NPE다. 그래서 `chats`는 더미 계정 이관이어야 한다. `chatrooms`는 `RoomResponse`가 owner를 쓰지 않지만, 이관이면 **DDL 변경 없이** 끝나므로 그대로 이관으로 둔다.

### 만료 refresh 토큰이 잔뜩 쌓여 있는 것은 결함이 아니라 설계대로다

devdb에서 "112건 중 108건이 만료"로 관측된 것은 토큰이 방치돼 썩은 것이 아니다. `expireValidTokens`가 **"즉시 만료"를 `expired_at = now`로 표현**하고 삭제 경계가 `<=`(USER-EDC-19)라, **로그인·재발급·비밀번호 변경·탈퇴를 할 때마다 이미 만료된 행이 하나씩 남는다.** refresh 1개 정책이 "이전 토큰을 만료시키고 새로 발급"인 이상 이 누적은 정상 경로의 산물이며, **스케줄러가 지우는 대상이 바로 이 누적분**이다.

⚠ 그래서 삭제 건수가 큰 것을 **시간대 문제로 오해하지 말 것.** 존이 어긋나도 경계는 정확히 9시간만 움직이므로, 그 폭으로 신선한 토큰이 무더기로 지워질 수는 없다(제약 2의 `AuthService` 항목 참고 — 그쪽 피해 범위도 잔여 수명 9시간 미만인 토큰으로 한정된다).

### `(알수없음)` 이라는 닉네임 (사칭 불가의 근거)

`NicknamePolicy.REGEX`가 `[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+`라 **괄호가 허용 문자에 없다.** 길이는 6자(괄호 2 + 한글 4)라 1~10 범위에 들어가지만 문자 검사에서 걸린다. 따라서 회원가입·닉네임 변경 **어느 경로로도** 일반 사용자가 이 닉네임을 선점하거나 사칭할 수 없다(`users_account.nickname`에는 DB UNIQUE가 없고 중복 검사는 앱의 `existsByNickname`뿐이라, **사칭을 막는 것은 중복 검사가 아니라 이 문자 정책이다**).

### 더미 계정이 채워야 하는 NOT NULL 컬럼

`users`: `name`·`tel`(UNIQUE)·`email`(UNIQUE)·`gender` 전부 NOT NULL. `users_account`: `uid`(UNIQUE)·`user_id`(NOT NULL UNIQUE)·`nickname`·`password` NOT NULL. 즉 `users` 1행 + `users_account` 1행이 짝으로 있어야 하며, user 앱이 `ddl-auto=update`라 **행은 저절로 생기지 않는다**(선례: `chat-init.sql`의 SYSTEM 계정 시드).

## 요구사항 (EARS)

### 실행 시각과 시계

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-1 | 이벤트 | WHEN Asia/Seoul 기준 매일 03:00이 되면, THE 시스템 SHALL 만료 데이터 정리를 1회 실행한다 | 앱이 기동된 상태에서 KST 03:00 도달 → 시작 로그 1건, 하루 정확히 1회(파드 1개 기준) |
| USER-EDC-2 | 유비쿼터스 | THE 시스템 SHALL 기준 시각을 `global.config.ClockConfig`의 `Clock` 빈(Asia/Seoul)에서 읽는다 | `Clock.fixed`로 UTC 18:00(=KST 익일 03:00)을 고정했을 때 판정 결과가 KST 벽시계 기준과 일치. `LocalDateTime.now()`(시스템 기본 존)를 쓰면 UTC 파드에서 9시간 어긋남 |
| USER-EDC-3 | 유비쿼터스 | THE 시스템 SHALL 한 회차의 모든 판정(30일 경과·토큰 만료)에 같은 기준 시각 하나를 사용한다 | 회차 로그에 기록된 기준 시각이 1개이며, 계정 판정과 토큰 판정이 서로 다른 시각을 읽지 않음 |
| USER-EDC-4 | 선택 **(구현 재량)** | WHERE 실행 주기 설정값(`user.cleanup.expired-data.cron`)이 주어지면, THE 시스템 SHALL 그 값으로 스케줄을 구성한다 | 설정 미지정 시 기본값 `0 0 3 * * *` + `zone = "Asia/Seoul"`(quiz `QuizIngestScheduler`와 동일 형태). **설정 키 이름은 구현자가 정해도 되나, 시각과 존이 코드에 하드코딩되지 않고 설정으로 드러나는 것이 계약이다** |
| USER-EDC-5 | 선택 | WHERE 실행 스위치(`user.cleanup.expired-data.enabled`)가 켜져 있는 경우에만, THE 시스템 SHALL 정리를 실행한다 | **기본값 `false`, prod 프로파일에서만 `true`.** 로컬 bootRun이 원격 devdb를 보는 구성이라 기본값이 `true`면 **로컬에서 앱을 띄워 둔 채 새벽 3시를 넘기는 순간 원격 개발 DB가 실제로 지워진다**(확정 근거: 결정 기록 3차 8) |
| USER-EDC-6 | 예외 | IF 03:00 시점에 앱이 기동돼 있지 않았으면, THEN THE 시스템 SHALL 그 회차를 보정 실행하지 않는다 | 03:30에 기동해도 즉시 실행되지 않음. 그날 대상이던 계정은 다음 날 03:00 회차에 그대로 포함(30일 조건을 여전히 만족하므로 유실 없음) |

### `(알수없음)` 더미 계정

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-30 | 유비쿼터스 | THE 시스템 SHALL 이관 대상 데이터를 넘겨받을 더미 계정 1개를 유지한다 | `users_account`에 이 계정이 정확히 1행 존재. 회차를 여러 번 실행해도 2행이 되지 않음. **예약값: `email = unknown@victoryfairy.internal`, `tel = 00000000001`**(SYSTEM 계정 `system@victoryfairy.internal`/`00000000000`과 같은 방식, 결정 기록 3차 12) |
| USER-EDC-31 | 유비쿼터스 | THE 시스템 SHALL 더미 계정의 닉네임을 `(알수없음)`으로 유지한다 | 그 계정이 노출되는 응답(`MessageResponse.senderNickname` 등)의 값이 문자 그대로 `(알수없음)` |
| USER-EDC-32 | 유비쿼터스 | THE 시스템 SHALL 더미 계정을 고정된 `uid`로 식별한다 | 확정값 `568ee3c3-029f-4514-b87f-9d90e729f755`(`cleanup.policy.UnknownAccountPolicy.UID` 가 단일 출처). 닉네임 문자열 조회로 찾지 않는다(닉네임에 DB UNIQUE가 없어 같은 값의 행이 둘이 되면 어느 쪽을 고를지 비결정적) |
| USER-EDC-33 | 유비쿼터스 | THE 시스템 SHALL 더미 계정의 비밀번호를 어떤 원문으로도 로그인이 성립하지 않는 값으로 유지한다 | 그 계정 이메일 + 임의 비밀번호로 `POST /api/auth/login` → 401 `INVALID_CREDENTIALS`. BCrypt 패턴이 아닌 값이면 `matches()`가 예외 없이 항상 false(SYSTEM 계정 선례와 동일) |
| USER-EDC-34 | 유비쿼터스 | THE 시스템 SHALL 더미 계정을 삭제 대상으로 선정하지 않는다 | 회차를 100번 실행해도 그 계정의 `users`·`users_account` 행이 잔존(`exit_at`이 NULL이라 USER-EDC-8로 자연 제외되지만, 이 요구사항이 그 보장을 별도로 고정한다) |
| USER-EDC-35 | 예외 | IF 일반 사용자가 닉네임으로 `(알수없음)`을 요청하면, THEN THE 시스템 SHALL 문자 위반으로 거절한다 | `PATCH /api/users/me/nickname` `{"nickname":"(알수없음)"}` → 400, `message`가 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."`. **현행 `NicknamePolicy`가 이미 하는 일을 명문화한 것**이며 이것이 사칭 차단의 유일한 근거다 |
| USER-EDC-45 | 이벤트 | WHEN user 앱이 기동되면, THE 시스템 SHALL 더미 계정이 없는 경우에만 생성한다 | 빈 DB에 앱을 3번 기동 → 더미 계정 1행(find-or-create, 재실행 안전). 생성 시점·수단은 결정 기록 3차 11 |

### 삭제 대상 선정

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-7 | 이벤트 | WHEN 정리가 실행되면, THE 시스템 SHALL `exit_at`이 NULL이 아니고 `exit_at + 30일 <= 기준 시각`인 계정을 삭제 대상으로 선정한다 | `exit_at = 기준시각 - 30일 정각`인 계정은 **포함**, `기준시각 - 29일 23시간 59분`인 계정은 **미포함**(확정 근거: 결정 기록 3차 1) |
| USER-EDC-8 | 유비쿼터스 | THE 시스템 SHALL `exit_at`이 NULL인 계정을 삭제 대상으로 선정하지 않는다 | 활성 계정 6건이 있는 DB에서 회차 실행 후 활성 계정 6건 전부 잔존 |
| USER-EDC-11 | 예외 | IF 탈퇴 후 30일이 아직 지나지 않은 계정이 있으면, THEN THE 시스템 SHALL 그 계정을 삭제하지 않는다 | `exit_at = 기준시각 - 29일`인 계정은 회차 실행 후에도 `users_account` 행과 자식 행 전부 잔존 |

### 소유권 이관 — 사람 이름을 표시해야 하는 데이터 (삭제보다 먼저 일어난다)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-36 | 이벤트 | WHEN 삭제 대상 계정이 선정되면, THE 시스템 SHALL 그 계정이 소유한 `chatrooms.owner_account_id`를 더미 계정의 id로 변경한다 | 방 2개를 소유한 탈퇴 30일 경과 계정 → 삭제 후에도 방 2개가 남고 `owner_account_id`가 더미 계정 id. `chatrooms`에는 소유자 UNIQUE가 없어 방이 몇 개든 충돌하지 않는다 |
| USER-EDC-50 | 이벤트 | WHEN 삭제 대상 계정이 선정되면, THE 시스템 SHALL 그 계정이 남긴 `chats.user_account_id`를 더미 계정의 id로 변경한다 | 메시지 10건을 남긴 계정 삭제 후 그 방의 히스토리 조회 → 메시지 10건이 그대로 있고 `senderNickname`이 `(알수없음)`. **SET NULL을 쓸 수 없는 이유는 `MessageResponse.from()`의 닉네임 역참조 NPE**(확정 근거: 결정 기록 3차 10) |
| USER-EDC-39 | 유비쿼터스 | THE 시스템 SHALL 이관·보존 대상이 아닌 자식 데이터를 계정과 함께 삭제한다 | 분류 — **이관**: `chatrooms`·`chats` / **소유자 분리(SET NULL)**: `quizzes_like` / **삭제**: `quiz_users_submit`·`users_refreshtoken`·`users_bq`·`user_support_team`·`user_support_player`(확정 근거: 결정 기록 3차 10) |
| USER-EDC-40 | 유비쿼터스 | THE 시스템 SHALL 한 계정의 이관·정리·삭제를 하나의 트랜잭션으로 처리한다 | 삭제 단계에서 예외가 나면 그 계정의 이관도 되돌아간다 — "소유권만 넘어가고 계정은 남은" 중간 상태가 관측되지 않음 |
| USER-EDC-41 | 예외 | IF 어떤 계정의 이관이 실패하면, THEN THE 시스템 SHALL 그 계정을 삭제하지 않는다 | 이관 UPDATE가 실패한 계정의 `users` 행은 회차 종료 후에도 잔존(fail-closed — 이관 없는 삭제는 공용 데이터 소실이므로 절대 진행하지 않는다) |
| USER-EDC-42 | 예외 | IF 회차 실행 시점에 더미 계정이 존재하지 않으면, THEN THE 시스템 SHALL 계정 삭제 단계를 수행하지 않고 ERROR 로그를 남긴다 | 더미 계정 행을 지운 DB에서 회차 실행 → 삭제 0건, ERROR 1건. 만료 토큰 삭제(USER-EDC-19)는 이관과 무관하므로 계속 수행 |
| USER-EDC-43 | 이벤트 | WHEN 이관이 끝나면, THE 시스템 SHALL 테이블별 이관 행 수를 로그에 남긴다 | 결과 로그에 `chatrooms`·`chats` 각각의 이관 건수가 포함 |
| USER-EDC-44 | 유비쿼터스 | THE 시스템 SHALL 이관된 데이터와 소유자가 비워진 데이터를 기존 조회 경로에서 다른 데이터와 동일하게 취급한다 | 이관된 채팅방·메시지가 목록에서 사라지거나 별도 필드가 붙지 않고, 소유자 없는 추천도 추천 수 집계에 그대로 포함. 노출되는 차이는 닉네임 `(알수없음)` 하나뿐 |

### 퀴즈 추천(`quizzes_like`) — 이관하지 않고 소유자만 비운다 (2차 개정)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-46 | 유비쿼터스 | THE 시스템 SHALL 계정이 삭제될 때 그 계정의 `quizzes_like.user_account_id`를 NULL로 만든다 | FK가 `ON DELETE SET NULL`이고 컬럼이 nullable. 계정 삭제 후 그 계정이 남긴 추천 행이 `user_account_id IS NULL`로 잔존(스케줄러의 UPDATE가 아니라 **DB 제약이 수행한다**) |
| USER-EDC-47 | 이벤트 | WHEN 삭제 대상 계정이 선정되면, THE 시스템 SHALL 그 계정을 삭제하기 **전에** 그 계정의 `liked = false`인 `quizzes_like` 행을 삭제한다 | 추천 2건(`liked=true`)·취소 3건(`liked=false`)을 가진 계정 삭제 → 소유자 NULL 행이 2건만 남는다. **순서가 뒤집히면 취소 이력이 영구히 남는다** — 소유자가 NULL이 된 뒤에는 어느 취소 행이 누구 것인지 가릴 수 없다 |
| USER-EDC-48 | 유비쿼터스 | THE 시스템 SHALL 계정 삭제로 퀴즈별 추천 수(`liked = true` 행 수)가 줄어들지 않게 한다 | 같은 문제를 추천한 탈퇴자가 3명이어도 삭제 후 `countByQuiz_IdAndLikedTrue` 값이 삭제 전과 동일. MySQL UNIQUE가 NULL을 서로 다른 값으로 보므로 `(NULL, 같은 quiz_id)` 행이 공존한다(devdb 실측) |
| USER-EDC-49 | 예외 **(구현 재량)** | IF 회차 실행 시점에 `quizzes_like`의 FK 삭제 규칙이 아직 `SET NULL`이 아니면, THEN THE 시스템 SHALL 계정 삭제 단계를 수행하지 않고 ERROR 로그를 남긴다 | 마이그레이션 미적용 DB에서 회차 실행 → 삭제 0건, ERROR 1건. **선행 검사가 없으면 마이그레이션 전 첫 회차가 CASCADE로 추천 행을 지워 추천 수가 조용히 줄어든다**(되돌릴 수 없다). **검사를 두는 것이 계약이고, 방식(기동 시 1회 조회·회차마다 조회·`information_schema` 대신 다른 신호)은 구현자가 고른다** |
| USER-EDC-37 | — | **(삭제됨 — 2026-08-18 2차)** `quizzes_like`를 더미 계정으로 이관하던 요구사항. USER-EDC-46이 대체 | — |
| USER-EDC-38 | — | **(삭제됨 — 2026-08-18 2차)** 이관 시 UNIQUE 충돌 행을 삭제하던 요구사항. SET NULL 방식에서는 충돌 자체가 없다 | — |

### 하드 삭제

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-9 | 이벤트 | WHEN 이관과 취소 추천 정리가 완료되면, THE 시스템 SHALL 그 계정과 1:1로 연결된 `users` 행을 삭제한다 | 회차 실행 후 해당 `users.id` 행 0건 |
| USER-EDC-10 | 이벤트 | WHEN `users` 행이 삭제되면, THE 시스템 SHALL 그 계정을 참조하는 `users_account`·`users_refreshtoken`·`users_bq`·`user_support_team`·`user_support_player`·`quiz_users_submit` 행이 남지 않게 한다 | 6개 테이블에 각각 1건 이상 행이 있던 계정을 삭제 → 6개 테이블 모두 해당 계정 참조 행 0건. **`chatrooms`·`chats`(이관)·`quizzes_like`(SET NULL)는 이 목록에 없다** |
| USER-EDC-12 | — | **(삭제됨 — 2026-08-18 1차)** 채팅방 소유 계정을 삭제 대상에서 제외하던 요구사항. USER-EDC-36이 대체 | — |
| USER-EDC-13 | — | **(삭제됨 — 2026-08-18 1차)** 위 제외를 ERROR 로그로 남기던 요구사항. USER-EDC-43이 대체 | — |
| USER-EDC-14 | 예외 | IF 한 계정의 처리(이관·정리·삭제)가 예외로 실패하면, THEN THE 시스템 SHALL 그 계정만 건너뛰고 남은 대상 처리를 계속한다 | 대상 3건 중 2번째가 실패 → 1·3번째는 완료, 회차는 정상 종료 |
| USER-EDC-15 | 예외 | IF 한 계정의 처리가 실패하면, THEN THE 시스템 SHALL 같은 회차에서 이미 처리된 다른 계정을 롤백하지 않는다 | 위 시나리오에서 1번째 계정의 행이 되살아나지 않음(= 계정 1건이 트랜잭션 1개, 결정 기록 3차 6) |
| USER-EDC-16 | 유비쿼터스 | THE 시스템 SHALL 한 회차에서 처리하는 삭제 대상 계정 수에 상한을 두지 않는다 | 대상이 1000건이면 1000건 모두 같은 회차에서 처리 시도(확정 근거: 결정 기록 3차 6) |
| USER-EDC-17 | 유비쿼터스 | THE 시스템 SHALL 계정을 삭제하기 전에 그 데이터를 아카이브·감사 테이블로 복사하지 않는다 | 삭제된 계정의 개인정보(이메일·전화번호·닉네임)가 저장소 어디에도 남지 않음. 이관·보존된 데이터는 개인정보를 담지 않으므로 무관(확정 근거: 결정 기록 3차 2) |
| USER-EDC-18 | 예외 | IF 삭제 대상 계정이 0건이면, THEN THE 시스템 SHALL 예외 없이 0건을 기록하고 다음 단계(만료 토큰 삭제)로 진행한다 | 탈퇴 계정이 없는 DB에서 회차 실행 → ERROR 로그 0건, 결과 로그의 계정 삭제 수 0 |

### 만료 refresh 토큰 삭제

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-19 | 이벤트 | WHEN 정리가 실행되면, THE 시스템 SHALL `users_refreshtoken.expired_at <= 기준 시각`인 행을 삭제한다 | 만료 108건·유효 4건인 DB에서 회차 실행 → `users_refreshtoken` 4행만 잔존. 유예 기간 없음(확정 근거: 결정 기록 3차 4) |
| USER-EDC-20 | 유비쿼터스 | THE 시스템 SHALL `expired_at > 기준 시각`인 행을 삭제하지 않는다 | 로그인 직후 받은 refreshToken으로 회차 실행 직후 `POST /api/auth/refresh` → 200 |
| USER-EDC-21 | 이벤트 **(구현 재량)** | WHEN 한 회차에서 두 정리를 모두 수행하면, THE 시스템 SHALL 계정 처리(이관·정리·삭제)를 먼저 수행한 뒤 만료 토큰 삭제를 수행한다 | 로그 순서가 항상 "이관 결과 → 계정 삭제 결과 → 토큰 삭제 결과". **고정되는 것은 이 실행 순서이며, 로그 문구·단계 분해 방식은 구현자가 고른다** |
| USER-EDC-22 | 유비쿼터스 | THE 시스템 SHALL 계정 삭제로 함께 사라진 토큰 행을 만료 토큰 삭제 건수에 포함하지 않는다 | 만료 토큰 5건을 가진 계정이 삭제되는 회차 → 결과 로그의 토큰 삭제 수에 그 5건이 들어가지 않음 |
| USER-EDC-23 | 예외 | IF 만료 토큰 삭제가 실패하면, THEN THE 시스템 SHALL 같은 회차에서 이미 완료된 계정 처리를 롤백하지 않는다 | 토큰 삭제 단계에서 예외 발생 → 앞서 삭제된 계정 행이 되살아나지 않고 ERROR 로그 1건 후 회차 종료 |

### 중복 실행 · 관측

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EDC-24 | 유비쿼터스 | THE 시스템 SHALL 같은 회차가 파드 두 곳에서 동시에 진행되지 않도록 상호배제한다 | **Redis 락**(user 앱에 `spring-boot-starter-data-redis`가 이미 있어 새 의존 없음). 파드 2개(HPA max 2)에서 03:00 도달 → 한 파드만 시작 로그, 다른 파드는 "선점됨" 로그 후 종료. 초안 가정(방치)에서 바뀐 항목이다 — **이관이 들어오며 두 파드가 같은 계정을 처리할 위험이 생겼다**(확정 근거: 결정 기록 3차 5) |
| USER-EDC-25 | 예외 | IF 상호배제가 성립하지 않아 두 파드가 같은 대상을 처리하면, THEN THE 시스템 SHALL 뒤늦은 쪽의 실패를 해당 계정만 건너뛰는 것으로 흡수한다 | 한쪽 성공, 다른 쪽 "이미 없음"·락 경합 실패 → 회차 전체는 정상 종료(USER-EDC-14와 동일 처리). 락을 도입해도 TTL 만료·네트워크 분단에서 겹칠 수 있으므로 이 안전망은 남긴다 |
| USER-EDC-26 | 이벤트 | WHEN 정리가 끝나면, THE 시스템 SHALL 이관 행 수·취소 추천 삭제 수·삭제된 계정 수·실패한 계정 수·삭제된 토큰 행 수를 INFO 로그 한 줄로 남긴다 | 회차마다 정확히 1줄, 다섯 수치가 모두 포함 |
| USER-EDC-27 | 예외 | IF 계정 처리가 실패하면, THEN THE 시스템 SHALL 그 계정의 `uid`와 예외를 ERROR 로그로 남긴다 | 실패 1건당 ERROR 1줄. 내부 PK `id`가 아니라 `uid`를 쓴다(토큰 subject 규약과 같은 이유 — 내부 PK 비노출) |
| USER-EDC-28 | 유비쿼터스 | THE 시스템 SHALL 로그에 이메일·전화번호·닉네임·비밀번호 해시를 남기지 않는다 | 회차 로그 전문에 `@`가 포함된 주소·`010`으로 시작하는 번호·닉네임 문자열·`$2a$`가 없음 |
| USER-EDC-29 | 유비쿼터스 | THE 시스템 SHALL 실패한 대상을 같은 회차 안에서 재시도하지 않는다 | 실패 대상은 다음 날 03:00 회차가 다시 시도(재시도 큐·알림 없음, 결정 기록 3차 7) |

## 결정 기록

### 2026-08-18 (1차) — 채팅방을 소유한 계정 처리 (쟁점 3, 확정)

제시한 A~D 중 어느 것도 아닌 **B(SYSTEM 이관)의 변형**으로 확정됐다. 사용자 원문:

> 더미 계정을 하나 생성합니다. (알수없음) 계정으로 하여 chatroom 의 데이터와, user_like (퀴즈 추천) 과 같은 데이터는 알수없음 계정으로 이관합니다. 닉네임 자체를 (알수없음) 으로 둡니다.

- 시드 SYSTEM 계정을 재사용하지 않는 이유: 그 계정은 "공용 채팅방 소유자"라는 별개 의미를 이미 갖고 있고, 위에 "탈퇴자 콘텐츠 소유자"를 겹치면 두 성격이 한 행에 섞여 나중에 어느 쪽 기준으로도 골라낼 수 없다.
- 이 결정으로 쟁점 2(하드 삭제)의 성격이 바뀌었다 — 공용 데이터는 보존되므로 "탈퇴 30일 뒤 흔적이 전부 사라진다"가 더는 사실이 아니다.

### 2026-08-18 (2차) — 퀴즈 추천은 이관하지 않는다 (쟁점 9, 확정)

`quizzes_like`의 UNIQUE(`user_account_id`, `quiz_id`) 충돌 처리로 제시한 A(충돌 행 삭제)·B(UNIQUE 완화)·C(카운터 분리) 중 어느 것도 아닌 **네 번째 길**로 확정됐다: **FK를 `ON DELETE SET NULL`로 바꾸고 소유자만 비운다.** 1차 결정("`user_like`도 이관")을 **뒤집는 결정**이다.

- MySQL UNIQUE가 NULL을 서로 다른 값으로 보므로 `(NULL, 같은 quiz_id)` 행이 몇 개든 공존한다 → **추천 수 손실 0**(A안은 탈퇴자 중복분만큼 카운트가 깎였다).
- 추천 수는 `liked = true` 행 수라 누가 눌렀는지가 집계에 들어가지 않는다 — 정체성을 지우는 것이 데이터 의미와 맞는다.
- 스케줄러가 `quizzes_like`를 UPDATE할 필요가 사라져 **모듈 경계 확장이 절반으로 줄었다**(제약 4).

**앞으로 붙는 테이블도 이 기준으로 가른다 — "그 데이터를 읽는 코드가 계정을 역참조해 사람 이름을 표시하는가?"**

| 답 | 처리 | 해당 테이블 | 근거 |
|---|---|---|---|
| 예 | `(알수없음)` 더미 계정 **이관** | `chats`(`MessageResponse.from()`이 `getUserAccount().getNickname()` 호출 → NULL이면 NPE), `chatrooms`(역참조는 없지만 FK가 NO ACTION + NOT NULL이라 이관이 DDL 없이 끝나는 유일한 길) | 이름이 필요하므로 소유자 행이 있어야 한다 |
| 아니오(집계·카운트만) | FK **SET NULL** | `quizzes_like` | 소유자 행 자체가 필요 없다. DDL 1회로 끝나고 스케줄러 코드가 늘지 않는다 |
| 개인 이력·인증 | **삭제** | `users_refreshtoken`(이관 시 더미 계정으로 로그인 가능해짐)·`users_bq`·`user_support_*`·`quiz_users_submit` | 남길 이유가 없거나 남기면 위험하다 |

### 2026-08-18 (3차) — 남은 쟁점 10건 일괄 확정 (사용자 승인)

사용자 승인으로 아래 10건이 확정됐다. **번호는 초안의 쟁점 번호이며 요구사항 인수 기준이 이 번호를 참조한다** — 재사용·당김 금지.

| # | 확정 내용 | 해당 요구사항 | 고르지 않은 길과 그 대가 |
|---|---|---|---|
| 1 | 30일 경계는 **`exit_at + 30일 <= 기준 시각`**(시각 단위, 경과 순간 포함) | USER-EDC-7 | 날짜 단위는 03:00 실행 탓에 최대 하루 늦어지고, 31일 기준은 "30일"이라는 사양 문구와 코드가 어긋난다 |
| 2 | 계정 본체는 **하드 삭제**. 아카이브·감사 테이블을 두지 않는다 | USER-EDC-9·10·17 | 익명화는 탈퇴자 수만큼 유령 계정이 쌓인다. **받아들인 대가**: `users` 행이 사라져 email·tel·nickname이 재사용 가능해진다 — `withdraw.md`의 "영구 점유"가 30일 뒤 자동 해제되는 셈이며, 이는 사고가 아니라 알고 택한 결과다 |
| 4 | 만료 토큰은 **`expired_at <= 기준 시각` 즉시 삭제**(유예 없음) | USER-EDC-19·20 | N일 유예는 재사용 시도 추적 근거가 남지만 테이블이 계속 크다(현재 112건 중 108건이 만료) |
| 5 | **파드 간 상호배제를 둔다(Redis 락)** | USER-EDC-24·25 | **초안 가정(방치)에서 뒤집힌 항목이다.** 뒤집은 이유: 1차 개정으로 이관이 들어오면서, 두 파드가 같은 계정을 동시에 처리하면 단순 "이미 없음" 실패를 넘어 **이관 UPDATE와 삭제가 서로 교차**할 수 있게 됐다. user 앱에 `spring-boot-starter-data-redis`가 이미 있어 새 의존이 없다는 점이 ShedLock 대신 이 안을 고른 이유다. 락을 둬도 TTL 만료·네트워크 분단에서 겹칠 수 있으므로 USER-EDC-25(계정 단위 흡수)는 안전망으로 남긴다 |
| 6 | **계정 1건 = 트랜잭션 1개**(이관·정리·삭제 묶음), 회차당 상한 없음 | USER-EDC-15·16·40 | 회차당 상한은 밀린 대상이 이월돼 "30일"이 실질적으로 늘어난다. 전체 한 트랜잭션은 한 계정의 실패가 회차 전체를 되돌린다 |
| 7 | 결과 통지는 **로그만**(INFO 1줄 + 실패당 ERROR) | USER-EDC-26·27·29 | 알림 발송은 채널·시크릿이 필요하고 이 저장소에 선례가 없다. **남는 위험**: 매일 도는 무인 작업이라 아무도 로그를 안 보면 실패가 오래 묻힌다 |
| 8 | **prod 프로파일에서만 실행**(설정 키로 on/off, 기본값 꺼짐) | USER-EDC-5 | **초안 가정(전 프로파일 실행)에서 뒤집힌 항목이다.** 뒤집은 이유: 로컬 bootRun이 `.env`의 원격 IP를 따라 **원격 devdb를 직접 본다** — 전 프로파일 실행이면 로컬에서 앱을 켜 둔 채 새벽 3시를 넘기는 것만으로 원격 개발 DB의 계정이 실제로 삭제된다. 대가는 dev 환경에서 스케줄러가 자동 검증되지 않는다는 점이며, 설정 키를 켜면 필요할 때 dev에서도 돌릴 수 있다 |
| 10 | 테이블별 처리를 **이관 / SET NULL / 삭제 3분류**로 확정 | USER-EDC-39·36·50·46·10 | **이관**: `chatrooms`·`chats` / **SET NULL**: `quizzes_like` / **삭제**: `quiz_users_submit`·`users_refreshtoken`·`users_bq`·`user_support_team`·`user_support_player`. `chats`를 삭제로 두면 다른 사람 대화창에서 메시지가 사라져 맥락이 끊기고, SET NULL은 `MessageResponse.from()`의 닉네임 역참조 NPE라 불가능하다. `quiz_users_submit`을 보존하려면 UNIQUE(계정, 퀴즈) 때문에 `quizzes_like`처럼 SET NULL로 가야 하는데 **컬럼이 NOT NULL이라 DDL이 한 번 더 필요**해 이번에는 삭제로 둔다(통계 모수가 그만큼 줄어드는 것을 감수한 결정) |
| 11 | 더미 계정은 **앱 기동 시 find-or-create** | USER-EDC-45·30 | 시드 SQL은 적용을 잊으면 USER-EDC-42로 삭제가 통째로 멈춘다. **남는 위험**: 완전히 빈 DB에 파드 2개가 동시에 뜨면 UNIQUE 충돌로 한쪽 기동이 실패할 수 있다(기존 시드가 이미 가진 함정 — `application-prod.yaml` 주석, 재시작으로 자가 치유) |
| 12 | 더미 계정 예약값 **`unknown@victoryfairy.internal` / `00000000001`** | USER-EDC-30 | SYSTEM 계정(`system@victoryfairy.internal` / `00000000000`)과 같은 방식이라 "예약 계정은 `.internal` 도메인"이라는 규칙이 유지된다 |

확정된 쟁점 3(1차)·9(2차)는 위 두 절에 있다. **요구사항 결정에 남은 질문은 없다.**(제약 2의 시각 출처 정렬은 계약을 바꾸는 쟁점이 아니라 별개의 정리 작업이다 — "구현·검증 기록" 참고)

## 제약 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)

1. **user 모듈에는 `@EnableScheduling`이 없다.** 저장소에서 스케줄링을 켠 곳은 quiz의 `RealtimeSchedulingConfig` 하나뿐이고 그건 quiz 앱의 컴포넌트 스캔 범위라 user에 적용되지 않는다. 활성화 수단이 없으면 `@Scheduled`가 붙어도 **아무 일도 일어나지 않고 에러도 안 난다**.
2. **시각의 출처는 `Clock` 빈이어야 한다(USER-EDC-2) — 이 기능 범위에서는 해소됐고, 모듈 전체로는 두 곳이 남았다.**
   - **해소(2026-08-18, 이번 브랜치)**: `exit_at`을 **기록하는** `UserAccountService.withdraw`가 `Clock`을 생성자 주입받아 `LocalDateTime.now(clock)`을 쓰도록 정렬됐다(`UserAccountServiceTest`는 `@InjectMocks`를 걷고 `Clock.fixed(..., Asia/Seoul)`로 직접 생성, 케이스 3건 그대로). **기록하는 쪽과 30일 경과를 판정하는 쪽이 이제 같은 `Clock` 빈을 쓴다** — 제약이 지적하던 "출처가 갈린다"는 USER-EDC-7 경로에서 사라졌다.
   - 애초에 이 정렬의 근거는 "지금 틀려서"가 아니었다. 운영 파드의 JVM 기본 존은 **이미 `Asia/Seoul`이라**(2026-08-18 실측: 파드에서 `date` → KST, `TZ=Asia/Seoul`) `LocalDateTime.now()`와 `Clock` 빈이 같은 값을 냈다. 다만 그 정합이 **환경변수 `TZ`에 암묵적으로 의존**해, 설정이 빠지거나 파드마다 달라지는 순간 **경고 없이** 갈라진다. 시각의 출처를 명시화해 그 의존을 없애고 테스트에서 시각을 고정할 수 있게 하는 것이 목적이었다. (저장소 yaml 주석의 "파드도 TZ 미설정(UTC)"은 **과거 사고 시점의 맥락이고 지금 상태가 아니다.**)
   - **남은 두 곳 — 둘 다 이번 작업 범위 밖으로 의도적으로 남겼다**(코드 조사로 판정한 것이지 추측이 아니다):
     - `AuthService` — refresh 만료 판정과 토큰 발급 시각(`users_refreshtoken.expired_at`)에 `LocalDateTime.now()`를 쓴다. **현행 운영에서는 무해하다**: 쓰는 쪽과 읽는 쪽(이 스케줄러)이 둘 다 KST로 귀결된다. 결함이 되는 조건은 파드 `TZ`가 빠지거나 파드마다 다를 때이고, 그때 피해는 **잔여 수명 9시간 미만인 refresh 토큰**(수명 14일 중 약 2.7%)이 일찍 삭제되는 것이다. 반대 방향으로는 비밀번호 변경·탈퇴로 만료시킨 토큰이 최대 9시간 "유효"로 읽힐 수 있으나, 2차 관문(`isWithdrawn`·`acceptsTokenIssuedAt`)이 재발급을 막으므로 **보안 구멍은 아니다.** 인증 경로라 손대면 테스트 44건이 함께 흔들려 별도 작업으로 둔다.
     - `SupportService` — 응원 취소 시각(`oppose`)에 `LocalDateTime.now()`를 쓴다. **사실상 무해하다**: `oppose`는 저장소 어디에서도 다른 시각과 비교되지 않고 널 여부 판정에만 쓰이며 API 응답에도 실리지 않는다. 존이 어긋나도 증상은 DB를 직접 볼 때 시각이 밀려 보이는 것뿐이다.
3. **"30일"을 `NicknameChangeCooldownPolicy.COOLDOWN_DAYS`(닉네임 재변경 30일)와 공유하지 말 것.** 숫자만 같고 정책이 다르다.
4. **모듈 경계가 넓어진다(2차 개정으로 절반 축소).** 이관은 user 모듈의 스케줄러가 **chat 도메인 테이블(`chatrooms`·`chats`)을 UPDATE**한다는 뜻이다. 지금까지 user 모듈은 이 엔티티들을 쓴 적이 없다(quiz 앱이 서빙 중이다). 소유자 일괄 변경 메서드는 `:domain`의 공유 리포지토리(`ChatroomRepository`·`ChatRepository`)에 붙게 되고 **quiz 앱에서도 보인다** — 추가 시 용도를 문서화할 것. **`quizzes_like`는 이 목록에서 빠졌다**(SET NULL이라 스케줄러가 건드리지 않는다). 다만 `QuizLike` **엔티티 매핑**은 바뀐다(제약 9).
5. **운영 DB 스키마 확인 — 완료(2026-08-18).** FK 실측이 devdb 결과뿐이었고 user prod는 `ddl-auto=update`라 스키마가 환경마다 다르게 굳었을 수 있어(`update`는 기존 제약을 고쳐주지 않는다 — `game_statuses` UNIQUE 선례) **배포 전 선행조건**으로 두었던 항목이다. 운영 DB(43.200.82.148)를 실측한 결과 **devdb와 완전히 동일**해 네 항목 모두 해소됐다: ① `users_account`의 자식 6개(`chats`·`quiz_users_submit`·`user_support_player`·`user_support_team`·`users_bq`·`users_refreshtoken`)와 `users_account → users` 전부 `CASCADE` ② `chatrooms.owner_account_id` = `NO ACTION`(이관 설계의 전제가 운영에서도 성립) ③ `quizzes_like` 계정 축(`fk_quizzes_like_user_account`) = **`SET NULL`**, `quiz_id` 축 = `CASCADE`(유지 대상) ④ `uk_quizzes_like_account_quiz` 존재. **다음에 스키마를 건드릴 때는 이 대조를 다시 해야 한다** — 확인이 유효한 시점은 2026-08-18이다.
6. **하드 삭제는 되돌릴 수 없다.** USER-EDC-17이 아카이브를 두지 않는 이상, 잘못된 기준선(확정 근거: 결정 기록 3차 1)으로 한 번 돌면 복구 수단이 DB 백업뿐이다.
7. ⚠ **`NicknamePolicy.REGEX`에서 괄호를 허용하는 순간 `(알수없음)` 사칭이 가능해진다.** `users_account.nickname`에는 DB UNIQUE가 없어 중복 검사만으로는 막지 못하고, 지금 사칭을 막는 유일한 장치는 "괄호는 허용 문자가 아니다"뿐이다. 허용 문자를 넓히는 변경(이모지·특수문자 요청은 흔하다)은 이 보장을 조용히 깬다 — 그때는 더미 계정 식별을 닉네임이 아닌 `uid`로만 하고 있다는 점(USER-EDC-32)이 마지막 방어선이다.
8. **`(알수없음)` 계정의 데이터는 계속 쌓이기만 한다.** 이관은 누적이라 시간이 지날수록 채팅방·메시지가 이 계정 한 행에 몰린다.
9. **`quizzes_like` DDL 마이그레이션이 앱 배포보다 먼저다(2차 개정 신설) — 적용 완료(2026-08-18, devdb·운영 양쪽 실측 확인).** 순서가 왜 강제였는지는 남겨 둔다: 필요한 변경이 ① `user_account_id`를 `NOT NULL` → `NULL` 허용 ② FK를 **DROP 후 `ON DELETE SET NULL`로 재생성** 둘인데, ⚠ **`ddl-auto=update`는 둘 다 해 주지 않는다**(기존 컬럼의 NOT NULL을 완화하지 않고 — `quizzes.quiz_date`·`quiz_users_submit.submit_option_id` 선례 — 기존 FK의 삭제 규칙도 바꾸지 않는다). 적용 전에 스케줄러가 먼저 돌면 계정 삭제가 **CASCADE로 추천 행을 지워 추천 수가 조용히 줄어든다**(되돌릴 수 없다). USER-EDC-49의 선행 검사가 그 창을 막는 장치이며, 실제로 미적용 상태에서 그 검사가 삭제를 막는 것까지 확인했다(아래 "구현·검증 기록").
   - 적용 파일: `infra/sql/migrate-quiz-like-account-set-null.sql`.
   - ⚠ **첫 시도는 실패했다**: 드롭할 FK 이름을 `fk_quizzes_like_user_account`로 **가정해** 적어 뒀는데 실제로는 Hibernate 자동 생성명(`FKfyl3b9pbew3iy58tyfabr605n`)이라 `ERROR 1091`로 죽었다. 지금은 `information_schema`에서 이름을 찾아 동적 실행하며 재실행해도 안전하다. **`ddl-auto=update`가 만든 제약의 이름을 추측해 DDL을 쓰면 같은 함정을 다시 밟는다.**
10. **`QuizLike` 엔티티(`:domain`)가 바뀐다(2차 개정 신설).** `@ManyToOne(optional = false)` → optional 해제, `@JoinColumn(nullable = false)` → nullable 허용, `@OnDelete(action = OnDeleteAction.CASCADE)` → `OnDeleteAction.SET_NULL`. **quiz 앱이 함께 쓰는 엔티티**이며, 엔티티 javadoc에 적힌 CASCADE 근거("좋아요는 계정·문제에 완전히 종속돼 계정이 사라지면 함께 사라져도 됨")도 이 결정으로 더는 유효하지 않으므로 함께 고쳐야 한다. `QuizLike → Quiz` 쪽 CASCADE는 **그대로 둔다**(문제가 사라지면 그 문제의 추천도 사라져야 한다 — 바뀐 것은 계정 축뿐이다).
11. **예약 uid 마이그레이션(`infra/sql/migrate-reserved-uids-to-uuid.sql`)이 앱 배포보다 먼저다 — 제약 9와 같은 계열이지만 실패 모드가 훨씬 험하다.** 예약 행의 `uid`가 사람이 지어낸 순차값(`...0001` 류)에서 **실제 생성된 UUID v4**로 교체되면서, 이미 DB에 들어가 있는 12건(SYSTEM 계정 1 + 구단 채팅방 10 + `(알수없음)` 더미 계정 1)을 제자리 갱신하는 1회성 SQL이 생겼다(`UPDATE ... WHERE uid = '<옛 값>'`, 재실행하면 0행 매칭 no-op).
    - **순서를 뒤집으면 두 가지가 동시에 깨진다**: ① `chat-init.sql`의 멱등성 가드가 `WHERE NOT EXISTS (... uid = '<새 값>')`로 바뀌었으므로, 옛 uid 행만 있는 DB에서 그 시드가 다시 돌면 매칭이 0이라 **SYSTEM 계정과 채팅방 10건이 통째로 중복 생성**된다(구단마다 방이 둘). ② `UnknownAccountBootstrapper`가 `ApplicationRunner`라 더미 계정을 새 uid로 못 찾고 다시 만들려 하다 **email·tel UNIQUE 충돌로 앱 기동 자체가 실패**한다. 제약 9는 "조용히 데이터가 줄어드는" 실패였지만 이쪽은 **기동 실패 + 중복 시드**라 더 시끄럽고 더 아프다.
    - **uid 값을 이 문서에 나열하지 않는다.** 더미 계정 값의 단일 출처는 `cleanup.policy.UnknownAccountPolicy.UID`(USER-EDC-32), SYSTEM 계정·채팅방 값의 단일 출처는 `infra/sql/chat-init.sql`이다 — 값이 또 바뀌면 문서만 낡는다.


## 구현·검증 기록 (2026-08-18)

승인본 기준으로 구현·테스트·검증이 끝났다. **아래는 요구사항이 아니라 그 요구사항이 실제로 성립함을 확인한 기록**이다 — 계약을 바꾸지 않으며, 같은 사실을 다시 조사하지 않기 위해 남긴다.

- **격리된 로컬 스택에서 실제 회차 1건 실행**: 이관 `chatrooms` 3건·`chats` 5건, 취소 추천(`liked=false`) 삭제 2건, 계정 삭제 2건, 실패 0건. **탈퇴 29일째 경계 계정은 삭제되지 않았다**(USER-EDC-11 실증).
- **USER-EDC-46·48 실증**: 계정 삭제 후 `liked=true` 행이 `user_account_id IS NULL`로 잔존했고, **`(NULL, 같은 quiz_id)` 2행이 UNIQUE 충돌 없이 공존**했다. 추천 수 3 → 3으로 **손실 0**.
- **USER-EDC-49 실증**: 마이그레이션 **미적용** 상태로 회차를 돌리면 선행 검사가 계정 삭제를 실제로 막고 ERROR만 남겼다. 같은 회차에서 **만료 토큰 삭제는 그와 무관하게 계속 수행**돼 8건이 지워졌다(USER-EDC-42의 "토큰 삭제는 계속"과 같은 성질).
- **USER-EDC-22 실증**: 계정 삭제로 CASCADE된 토큰은 "만료 토큰 삭제" 수치에 **이중 계상되지 않았다.**
- **USER-EDC-5 실증**: prod 프로파일 + `USER_CLEANUP_EXPIRED_DATA_ENABLED=true`에서 `CleanupSchedulingConfig`·`ExpiredDataCleanupScheduler`가 조건 평가 리포트상 **Positive match**로 뒤집히는 것을 컨테이너에서 확인했다(dev는 Negative match — 기본 꺼짐이 실제로 동작한다).
- **멱등성**: 같은 회차를 3회 반복해도 6개 수치가 모두 0이고 ERROR가 없었다.
- **스키마**: 운영 DB 대조 완료(제약 5), `infra/sql/migrate-quiz-like-account-set-null.sql` 적용 완료(제약 9).
- **시각 출처 정렬**: `UserAccountService.withdraw`가 `Clock` 빈을 쓰도록 정렬돼 `exit_at`을 기록하는 쪽과 30일 경과를 판정하는 쪽이 같은 시계를 쓴다(`UserAccountServiceTest`를 `Clock.fixed`로 조정, `:user:test` 516건 통과).

**이 문서 범위에 남은 항목은 없다.** 모듈 전체로는 `AuthService`·`SupportService`가 아직 `LocalDateTime.now()`를 쓰지만, **둘 다 이 기능의 계약에 영향을 주지 않으며 별도 작업으로 남긴 것**이다(영향 범위와 근거는 제약 2).
