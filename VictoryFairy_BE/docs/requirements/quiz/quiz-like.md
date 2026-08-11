# 퀴즈 좋아요(추천/취소) 요구사항
> 상태: **승인됨(2026-08-11, 사용자 승인)** · 모듈: quiz(엔드포인트·서비스) + domain(신규 엔티티·리포지토리) · 최종 수정: 2026-08-11
> **미해결 질문 0건** — 초안의 Q1~Q7이 2026-08-11 전부 확정됐다(하단 "결정 근거"). `(가정)` 표시 항목은 이 문서에 없다.
> **문서 이동**: `quiz-recommend.md` → `quiz-like.md`(2026-08-11). ID 계열도 `QUIZ-REC-<n>` → **`QUIZ-LIKE-<n>`**으로 바뀌었고 **번호는 1:1로 보존**했다(옛 `QUIZ-REC-7`은 지금의 `QUIZ-LIKE-7`이다). 승인·구현 전 초안 단계라 참조하는 테스트·커밋이 아직 없다.
> **2026-08-11 개정 2차**: 사용자 확정으로 **어휘를 `like` 하나로 통일**했다 — 테이블 `quizzes_like`, 플래그 컬럼 `liked`, 엔티티 `QuizLike`, 리포지토리 `QuizLikeRepository`, 1회성 DDL `infra/sql/migrate-quiz-like.sql`. 1차 개정에 있던 "외부는 like·내부는 recommend" 이원 어휘는 **철회**됐다.
> **2026-08-11 개정 1차(유효)**: 추천 자격이 "제한 없음"→**"제출한 문제만"**, 거절 응답이 404 은닉→**403 단일 코드**로 뒤집혔다. 뒤집힌 요구사항 5건(QUIZ-LIKE-3·4·15·16·27)은 **폐기 표시로 남기고 번호를 재사용하지 않는다**. 대체 계약은 QUIZ-LIKE-28~37이다.

## 배경 / 목적
문제 품질에 대한 사용자 신호를 남기되, **"몇 명이 좋아했나"가 요청 횟수로 부풀지 않아야** 한다 — 그래서 좋아요를 이력 행으로 쌓지 않고 `(계정, 문제)` 한 행의 플래그로 관리한다. 같은 설계 판단이 이미 응원(`user_support_team.oppose` 토글)과 제출(`uk_quiz_users_submit_account_quiz`)에 적용돼 있고, 이 문서는 그 선례를 따른다.

**신호의 의미를 "풀어본 사람의 평가"로 좁힌 것이 이번 결정의 축이다**(Q5 확정). 제출이 선행조건이 되면서 "존재하지 않는 문제"와 "안 푼 문제"가 요청자 입장에서 같은 상태로 합쳐지고, 그래서 거절 응답도 하나로 합쳐진다(Q5-부속 확정 → QUIZ-LIKE-28).

## 범위
- 포함:
  - 좋아요 토글 엔드포인트 1개 — quiz 모듈
  - **제출 이력 선행조건**과 그 위반의 단일 403 응답 + `:common` `ErrorCode` 1건 신설
  - 신규 엔티티·테이블 `quizzes_like` + 리포지토리 — domain 모듈
  - 제약(UNIQUE·FK CASCADE)과 배포 선행 조건(1회성 DDL 포함)
  - **단건 상세(`GET /rt/quizzes/{quizId}`)와 풀이 이력(`GET /rt/quizzes/submissions`) 응답에 `liked`·`likeCount` 노출**
- 제외:
  - **`GET /rt/quizzes/today` 응답 변경** — 제출이 선행조건인데 `/today`는 이미 푼 문제를 목록에서 빼므로 **그 목록의 모든 항목이 좋아요 불가**다. 집계 비용만 내고 쓰이지 않는다(QUIZ-LIKE-31)
  - **인기 문제 순위·목록 API** — 집계는 위 두 경로와 토글 응답에서만 노출된다
  - **좋아요 이력(언제 켰다 껐나) 조회** — 토글 설계라 이력 행이 쌓이지 않는다. 마지막 상태와 마지막 변경 시각(`updated_at`) 하나만 남는다
  - **싫어요(dislike)** — 컬럼은 `liked` 하나이고 값 영역은 켜짐/꺼짐 둘뿐이다. 3상태(좋아요/무응답/싫어요)는 이번 스키마로 표현하지 않는다
  - **좋아요 수 비정규화 카운터 컬럼(`quizzes.like_count`)** — 같은 사실을 두 곳에 두지 않는다(`Game.winner`를 두지 않은 것과 같은 계열). 필요해지면 별도 요구사항
  - **어드민의 좋아요 데이터 열람·정정** — 경로 자체가 없다
  - **레이팅(`users_bq.bq_score`)·포인트 연동** — 좋아요는 점수와 무관하다. 계정 행을 잠그지도, `users_account.point`를 건드리지도 않는다
  - **거절 사유의 세분화** — 세 가지 거절 경우를 클라이언트가 구분할 수단을 제공하지 않는다(QUIZ-LIKE-30이 그것을 계약으로 못박는다)

## 용어
| 용어 | 뜻 |
|---|---|
| **좋아요(like)** | 어떤 사용자가 **자신이 푼** 문제에 남긴 긍정 신호. 저장 형태는 `quizzes_like` 한 행의 `liked = true` |
| **좋아요 취소** | 같은 행의 `liked`를 `false`로 되돌린 상태. **행 삭제가 아니다**(QUIZ-LIKE-8) |
| **`likeCount`** | 한 문제에 대해 `liked = true`인 행의 개수. `false` 행은 세지 않는다(QUIZ-LIKE-36) |
| **`liked`(응답 필드)** | 요청자 자신의 현재 좋아요 상태. 컬럼 `quizzes_like.liked`와 같은 이름·같은 값이다 |
| **제출 이력** | `quiz_users_submit`에 `(요청자 계정, 그 문제)` 행이 존재함. 이 기능의 선행조건(QUIZ-LIKE-28) |
| **편성된 문제** | `quizzes.quiz_date IS NOT NULL`인 문제. `NULL`은 미편성 풀이며 기존 퀴즈 API가 전부 404로 은닉한다 |
| **요청자** | `@AuthenticationPrincipal Long userAccountId` — JWT에서 해석된 계정. 본문·경로로는 받지 않는다 |

## 대상 엔드포인트

| 메서드 | 경로 | 이 문서가 정의하는 것 |
|---|---|---|
| **POST** | **`/rt/quizzes/{quizId}/like`** | **신규** — 좋아요 토글(없으면 생성해 켬, 켜져 있으면 끔, 꺼져 있으면 켬) |
| GET | `/rt/quizzes/{quizId}` | 기존 — 제출한 문제일 때 `liked`·`likeCount` **필드 추가**(미제출이면 키 부재) |
| GET | `/rt/quizzes/submissions` | 기존 — 이력 각 항목에 `liked`·`likeCount` **필드 추가** |
| GET | `/rt/quizzes/today` | 기존 — **변경 없음**(QUIZ-LIKE-31) |

컨트롤러의 `@RequestMapping`은 접두사 없는 `/quizzes/{quizId}/like`이고 `server.servlet.context-path`(`/rt`)가 앞에 붙는다(기존 퀴즈 경로와 동일). quiz `SecurityConfig`의 `anyRequest().authenticated()`에 그대로 걸려 **인증 필수**이며 SecurityConfig 수정은 필요 없다.

## 요구사항 (EARS)

> ID 규칙: `QUIZ-LIKE-<n>`. 기존 `QUIZ-CHAT-<n>`·`QUIZ-CTAC-<n>`과 별개 계열이며 **번호는 재사용하지 않는다**(폐기된 5건도 자리를 비워 둔다).
> 인수 기준 앞의 `AC-LIKE-<요구사항번호>-<n>`은 `test-writer`가 1:1로 대응시킬 식별자다.

### A. 공통 — 인증·대상 식별

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-1 | 유비쿼터스 | THE 시스템 SHALL 좋아요 요청에 유효한 access 토큰을 요구한다 | `AC-LIKE-1-1` 헤더 없이 `POST /rt/quizzes/1/like` → 401 `{"success":false,"data":null,"message":"인증이 필요합니다."}`. `AC-LIKE-1-2` 만료·위조 토큰도 동일하게 401 |
| QUIZ-LIKE-2 | 유비쿼터스 | THE 시스템 SHALL 좋아요 주체를 토큰에서만 식별한다 | `AC-LIKE-2-1` 요청 본문·경로·쿼리 어디에도 `userAccountId`/`uid`를 받는 자리가 없다. 타인 명의로 좋아요를 남길 입력 경로가 존재하지 않는다 |
| QUIZ-LIKE-3 | ~~예외~~ | ~~IF `quizId`가 존재하지 않는 문제면, THEN 404 `QUIZ_NOT_FOUND`~~ | **폐기(2026-08-11) — QUIZ-LIKE-28로 대체.** 거절 응답이 404에서 403 단일 코드로 통일됐다. 번호 재사용 금지 |
| QUIZ-LIKE-4 | ~~예외~~ | ~~IF `quizId`가 미편성 풀 문제면, THEN QUIZ-LIKE-3과 동일한 응답~~ | **폐기(2026-08-11) — QUIZ-LIKE-28로 대체.** 미편성 은닉은 사라진 것이 아니라 403 쪽으로 흡수됐다(결정 근거 5) |
| QUIZ-LIKE-5 | 유비쿼터스 | THE 시스템 SHALL 한 좋아요 요청의 모든 변경을 단일 트랜잭션으로 처리한다 | `AC-LIKE-5-1` 선행조건 위반(403) 시 어떤 행도 생성·갱신되지 않는다(부분 반영 없음) |

### B. 선행조건과 거절 응답

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-28 | 예외 | IF 요청자에게 대상 문제의 제출 이력이 없으면(문제가 존재하지 않는 경우·미편성 풀 문제인 경우 포함), THEN THE 시스템 SHALL 403 `QUIZ_LIKE_NOT_ALLOWED`를 반환하고 아무 행도 만들거나 갱신하지 않는다 | `AC-LIKE-28-1` 존재하지 않는 `quizId`(예: 999999)로 요청 → 403. `AC-LIKE-28-2` `quiz_date IS NULL`인 실재 문제 id로 요청 → 403. `AC-LIKE-28-3` 편성됐지만 내가 제출하지 않은 문제 id로 요청 → 403. `AC-LIKE-28-4` 세 경우 모두 `quizzes_like` 행 수가 변하지 않는다 |
| QUIZ-LIKE-29 | 유비쿼터스 | THE 시스템 SHALL 위 거절에 `:common` `ErrorCode`의 403 신규 코드 1건을 사용한다 | `AC-LIKE-29-1` `ErrorCode`에 `QUIZ_LIKE_NOT_ALLOWED(403, "좋아요는 직접 푼 문제에만 할 수 있습니다.")`가 존재한다(기존 403 구획 — `SELF_REPORT_NOT_ALLOWED`·`CHATROOM_TEAM_MISMATCH`와 같은 자리). `AC-LIKE-29-2` **메시지가 세 경우를 구분하지 않는다** — 문제의 존재 여부·편성 여부를 문구로 드러내지 않는 포괄 문구여야 한다. `AC-LIKE-29-3` 기존 `QUIZ_NOT_FOUND`(404)는 이 경로에서 사용되지 않는다 |
| QUIZ-LIKE-30 | 유비쿼터스 | THE 시스템 SHALL 세 거절 사유를 외부에서 구분할 수 없게 한다 | `AC-LIKE-30-1` 미존재·미편성·미제출 세 요청의 응답이 **상태코드·본문 문자열까지 동일**하다(바이트 단위 비교로 확인). `AC-LIKE-30-2` 응답 헤더·`data` 필드에도 사유를 시사하는 값이 없다(`data`는 `null`). `AC-LIKE-30-3` **세 조건을 어떤 순서로 검사하든 이 동일성이 유지된다** — 판정 순서는 계약이 아니라 구현 자유이며, 순서가 바깥으로 새지 않는 것만이 계약이다 |

### C. 토글 동작

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-6 | 이벤트 | WHEN 제출 이력이 있고 해당 문제에 대한 좋아요 행이 없는 사용자가 좋아요를 요청하면, THE 시스템 SHALL `liked = true`인 행을 새로 만들고 200을 반환한다 | `AC-LIKE-6-1` 23번 문제를 제출한 계정이 `POST /rt/quizzes/23/like` → 200, `quizzes_like`에 `(user_account_id, quiz_id)=(나, 23)` 행 1개, `liked = 1`. `AC-LIKE-6-2` 응답 `data.liked`가 `true` |
| QUIZ-LIKE-7 | 이벤트 | WHEN `liked = true`인 상태에서 같은 문제에 좋아요를 요청하면, THE 시스템 SHALL 같은 행의 `liked`를 `false`로 전환하고 200을 반환한다 | `AC-LIKE-7-1` 같은 경로를 연속 2회 호출 → 1회차 `data.liked = true`, 2회차 `false`. `AC-LIKE-7-2` 행 수는 1개로 유지된다(2개가 되지 않는다) |
| QUIZ-LIKE-8 | 유비쿼터스 | THE 시스템 SHALL 좋아요 취소를 행 삭제가 아니라 `liked = false` 전환으로 처리한다 | `AC-LIKE-8-1` 좋아요 후 취소한 뒤에도 `(계정, 문제)` 행이 남아 있고 `liked = 0`이다. `AC-LIKE-8-2` 그 행의 `created_at`은 최초 생성 시각 그대로이고 `updated_at`만 갱신된다 |
| QUIZ-LIKE-9 | 이벤트 | WHEN `liked = false`인 상태에서 같은 문제에 좋아요를 요청하면, THE 시스템 SHALL 같은 행의 `liked`를 `true`로 되돌린다 | `AC-LIKE-9-1` 좋아요→취소→좋아요 3회 호출 후 행 수 1개, `liked = 1`, `created_at` 불변. UNIQUE 위반 500이 발생하지 않는다 |
| QUIZ-LIKE-10 | 유비쿼터스 | THE 시스템 SHALL 같은 `(계정, 문제)` 조합에 두 개 이상의 좋아요 행이 존재하지 않도록 보장한다 | `AC-LIKE-10-1` 어떤 호출 순서로도 `select count(*) from quizzes_like where user_account_id=? and quiz_id=?`가 2 이상이 되지 않는다 |
| QUIZ-LIKE-11 | 예외 | IF 동시 요청으로 같은 조합의 삽입이 충돌하면(UNIQUE 위반), THEN THE 시스템 SHALL 500이 아니라 200과 그 시점의 확정 상태를 반환한다 | `AC-LIKE-11-1` 좋아요 이력 없는 같은 계정으로 같은 문제에 동시에 2건을 보내면 두 응답 모두 200이고, 종료 후 행은 1개다. 어느 응답에도 스택트레이스·500이 없다. **첫 좋아요의 동시 충돌에서 최종 상태는 `liked = true`다**(둘 다 "켜기"를 의도했으므로) |
| QUIZ-LIKE-17 | 유비쿼터스 | THE 시스템 SHALL 좋아요 요청이 제출 기록(`quiz_users_submit`)·포인트(`users_account.point`)·레이팅(`users_bq.bq_score`)을 변경하지 않게 한다 | `AC-LIKE-17-1` 좋아요·취소 전후로 위 3곳의 값이 모두 동일하다(제출 이력은 **읽기만** 한다). `AC-LIKE-17-2` 계정 행 비관적 락(`findWithLockById`)을 잡지 않는다 — 조회·좋아요가 서로를 직렬화하지 않는다 |

### D. 응답 계약 (토글)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-12 | 유비쿼터스 | THE 시스템 SHALL 성공 응답을 `ApiResponse<T>` 래퍼로 반환한다 | `AC-LIKE-12-1` 200 본문이 `{"success":true,"data":{...},"message":null}` 형태다(프로젝트 공통 규약) |
| QUIZ-LIKE-13 | 이벤트 | WHEN 좋아요 요청이 성공하면, THE 시스템 SHALL 응답 `data`에 요청자의 확정된 상태(`liked`)와 그 문제의 총 좋아요 수(`likeCount`)를 담는다 | `AC-LIKE-13-1` `{"liked":true,"likeCount":5}` 형태. `AC-LIKE-13-2` `likeCount`는 이번 요청의 변경이 반영된 값이다(좋아요를 켠 직후 응답은 자기 자신을 포함한다) |
| QUIZ-LIKE-36 | 유비쿼터스 | THE 시스템 SHALL `likeCount`를 `liked = true`인 행만 세어 산출한다 | `AC-LIKE-36-1` 같은 문제에 좋아요를 켠 계정 2개, 켰다 끈 계정 3개가 있으면 `likeCount`는 `2`다(행 수 5가 아니다). `AC-LIKE-36-2` 이 규칙은 토글 응답·단건 상세·이력 세 경로 모두에 동일하게 적용된다 |
| QUIZ-LIKE-14 | 유비쿼터스 | THE 시스템 SHALL 좋아요 행의 내부 PK(`id`)를 응답에 노출하지 않는다 | `AC-LIKE-14-1` 응답 `data`에 `id` 키가 없다(domain 컨벤션: PK는 내부 전용) |
| QUIZ-LIKE-37 | 유비쿼터스 | THE 시스템 SHALL 경로·응답 필드·테이블·컬럼·엔티티 전반에 `like` 어휘를 일관되게 쓰되, **플래그 컬럼명만 `liked`로 둔다** | `AC-LIKE-37-1` 경로는 `/like`, 응답 키는 `liked`·`likeCount`, 테이블은 `quizzes_like`, 컬럼은 `liked`, 엔티티는 `QuizLike`(필드 `liked`), 리포지토리는 `QuizLikeRepository`다 — 어느 자리에도 `recommend`가 남지 않는다. `AC-LIKE-37-2` **컬럼명이 `like`가 아니다** — `like`는 MySQL 예약어라 `@Column(name = "`like`")`처럼 백틱이 필요해지고, 백틱이 빠지면 `ddl-auto=update`가 만드는 DDL이 문법 오류로 실패해 **user 앱이 기동하지 못한다**(`quiz_options.option`이 이미 그 함정에 걸려 있다 — domain 컨벤션의 명시된 지뢰. 같은 지뢰를 하나 더 심지 않는 것이 `liked` 채택 근거다). `AC-LIKE-37-3` `is_like`(→`isLike`)도 `is_answer` 선례가 있어 후보였으나 자바 필드명이 어색하고 Lombok `@Getter`가 만드는 접근자와 겹치기 쉬워 택하지 않았다. `AC-LIKE-37-4` 테이블명 `quizzes_like`는 식별자 전체가 예약어가 아니므로 백틱이 필요 없다 |

### E. 조회 경로 노출

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-15 | ~~유비쿼터스~~ | ~~THE 시스템 SHALL 조회 API의 응답 필드 집합을 바꾸지 않는다~~ | **폐기(2026-08-11) — QUIZ-LIKE-31~35로 대체.** `/today`만 불변으로 남고(31), 상세·이력에는 필드가 추가된다. 번호 재사용 금지 |
| QUIZ-LIKE-31 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/today`의 응답 필드 집합을 바꾸지 않는다 | `AC-LIKE-31-1` `/today` 항목 키 집합은 여전히 `id·type·question·difficulty·point·preferred·options`다. `AC-LIKE-31-2` `/today` 처리 중 `quizzes_like`를 읽는 쿼리가 한 건도 발생하지 않는다. **근거: 제출이 선행조건인데 `/today`는 이미 푼 문제를 빼고 내려주므로 그 목록의 모든 항목이 좋아요 불가다** — 집계 비용만 내고 쓰이지 않는다 |
| QUIZ-LIKE-32 | 이벤트 | WHEN 제출한 문제의 단건 상세를 조회하면, THE 시스템 SHALL 응답에 `liked`와 `likeCount`를 포함한다 | `AC-LIKE-32-1` 제출한 문제의 `GET /rt/quizzes/{quizId}` 응답에 `liked`(boolean)·`likeCount`(정수)가 있다. `AC-LIKE-32-2` 좋아요를 누른 적 없으면 `liked = false`, `likeCount`는 다른 사용자의 좋아요 수를 반영한다 |
| QUIZ-LIKE-33 | 예외 | IF 단건 상세의 대상이 요청자가 제출하지 않은 문제면, THEN THE 시스템 SHALL `liked`·`likeCount` **키 자체를 응답에서 내린다** | `AC-LIKE-33-1` 미제출 문제 상세 응답 본문에 `liked`·`likeCount` 키가 **존재하지 않는다**(값이 `null`/`false`/`0`인 것이 아니라 키 부재). `AC-LIKE-33-2` 기존 `answer`·`myOption`·`correct`가 미제출 시 키 자체를 내리는 것과 **같은 방식**이다(`@JsonInclude(NON_NULL)` 패턴 일관성). `AC-LIKE-33-3` 미편성 풀 문제 상세는 종전대로 404 `QUIZ_NOT_FOUND`이며 이 규칙 이전에 걸린다(기존 계약 불변) |
| QUIZ-LIKE-34 | 이벤트 | WHEN 풀이 이력을 조회하면, THE 시스템 SHALL 각 항목에 `liked`와 `likeCount`를 포함한다 | `AC-LIKE-34-1` `GET /rt/quizzes/submissions?page=0`의 `submissions.content[]` 각 항목에 `liked`·`likeCount`가 있다. `AC-LIKE-34-2` 이력 항목은 정의상 전부 제출한 문제이므로 **키 부재 케이스가 없다**(QUIZ-LIKE-33의 예외 조건이 성립하지 않는다). `AC-LIKE-34-3` 이력 0건이면 여전히 200이고 `content`는 빈 배열이다 |
| QUIZ-LIKE-35 | 유비쿼터스 | THE 시스템 SHALL 이력의 `liked`·`likeCount`를 항목 수에 비례하지 않는 고정 쿼리 수로 조립한다 | `AC-LIKE-35-1` 이력 1요청에서 좋아요 관련 SQL은 **정확히 2건**이다 — (a) `quiz_id IN (...)`로 `liked = true` 건수를 `group by quiz_id` 집계 1건, (b) 요청자의 좋아요 여부를 같은 `quiz_id IN (...)`으로 조회 1건. `AC-LIKE-35-2` 페이지 항목이 1건일 때와 20건(고정 페이지 크기)일 때 **SQL 건수가 같다** — 항목별 개별 조회(N+1) 구현은 금지다. `AC-LIKE-35-3` 단건 상세의 좋아요 관련 SQL은 2건 이하다. **선례: `QuizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc`의 `quiz_id IN` 2쿼리 방식과 동일한 방법** |
| QUIZ-LIKE-16 | ~~유비쿼터스~~ | ~~THE 시스템 SHALL 제출 여부와 무관하게 좋아요를 허용한다~~ | **폐기(2026-08-11) — QUIZ-LIKE-28로 대체.** 사용자 확정으로 "제출한 문제만"으로 뒤집혔다. 번호 재사용 금지 |

### F. 데이터 모델 (domain)

> 테이블·컬럼명은 사용자 확정 어휘(`like`)를 따르고 제약은 domain 컨벤션으로 덮는다(`.claude/modules/domain.md`의 확립된 기준).

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-18 | 유비쿼터스 | THE 시스템 SHALL 좋아요를 `quizzes_like` 테이블 한 곳에 보관한다 | `AC-LIKE-18-1` 컬럼은 `id`(BIGINT PK AUTO_INCREMENT) · `quiz_id`(BIGINT NOT NULL) · `user_account_id`(BIGINT NOT NULL) · `liked`(TINYINT NOT NULL) · `created_at`(DATETIME(6) NOT NULL) · `updated_at`(DATETIME(6) NOT NULL) 6개다. `AC-LIKE-18-2` 좋아요 상태를 담는 다른 컬럼·테이블이 추가되지 않는다(`quizzes`에 카운터 컬럼 없음) |
| QUIZ-LIKE-19 | 유비쿼터스 | THE 시스템 SHALL `(user_account_id, quiz_id)`에 이름을 명시한 UNIQUE 제약 `uk_quizzes_like_account_quiz`를 둔다 | `AC-LIKE-19-1` `SHOW CREATE TABLE quizzes_like`에 `UNIQUE KEY uk_quizzes_like_account_quiz (user_account_id, quiz_id)`가 보인다. `AC-LIKE-19-2` Hibernate 자동 생성명(`UK6x04…`)이 아니다 — 자동 생성명이면 1회성 DDL이 "이미 걸렸는지"를 확인할 수 없다 |
| QUIZ-LIKE-20 | 유비쿼터스 | THE 시스템 SHALL `quiz_id`·`user_account_id` FK를 둘 다 `ON DELETE CASCADE`로 건다 | `AC-LIKE-20-1` `quizzes` 행을 지우면 그 문제의 좋아요 행이 함께 사라진다. `AC-LIKE-20-2` `users_account` 행을 지우면 그 계정의 좋아요 행이 함께 사라진다. 근거: 좋아요는 계정·문제에 완전히 종속돼 대상이 사라지면 함께 사라져도 되는 데이터다(domain의 CASCADE 판단 기준, `QuizUserSubmit`과 동일) |
| QUIZ-LIKE-21 | 유비쿼터스 | THE 시스템 SHALL `liked`를 `TINYINT NOT NULL`로 두고 `@ColumnDefault`를 두지 않는다 | `AC-LIKE-21-1` 컬럼 DDL에 `DEFAULT`가 없다. **근거: `@ColumnDefault`는 "이미 행이 있는 테이블에 NOT NULL 컬럼을 추가"할 때만 필요한 장치**인데(`UserAccount.point` 선례) 이 테이블은 신규라 채워야 할 기존 행이 없다. 신규 행의 값은 항상 애플리케이션이 정한다. `AC-LIKE-21-2` 매핑은 `QuizUserSubmit.isAnswer`와 같은 형태다(`columnDefinition = "TINYINT"`, `nullable = false`) |
| QUIZ-LIKE-22 | 유비쿼터스 | THE 시스템 SHALL `created_at`·`updated_at`을 둘 다 두고 애플리케이션이 값을 넘기지 않게 한다 | `AC-LIKE-22-1` 행 생성 시 두 값이 자동으로 채워진다(`@CreationTimestamp`/`@UpdateTimestamp`). `AC-LIKE-22-2` 빌더에 타임스탬프 파라미터가 없다(domain 컨벤션). `AC-LIKE-22-3` 토글로 값이 바뀌면 `updated_at`이 갱신되고 `created_at`은 `updatable = false`라 불변이다 |
| QUIZ-LIKE-23 | 유비쿼터스 | THE 시스템 SHALL 이 테이블에 별도 인덱스를 추가하지 않는다 | `AC-LIKE-23-1` `@Table(indexes = ...)` 선언이 없다. **근거: UNIQUE가 계정 선두 축을 받고 `quiz_id`는 InnoDB의 FK 자동 인덱스가 받는다** — 두 조회 축(문제별 좋아요 수 / 내 좋아요 조회)이 모두 인덱스를 탄다. "FK 컬럼에 인덱스가 없다"는 판단을 내리지 말 것(domain 컨벤션의 명시된 함정) |
| QUIZ-LIKE-24 | 유비쿼터스 | THE 시스템 SHALL 상태 전이를 `@Setter`가 아니라 엔티티의 의도 노출 메서드로 표현한다 | `AC-LIKE-24-1` 엔티티 `QuizLike`에 `@Setter`가 없고, 켜기/끄기/현재 상태 조회가 이름 있는 메서드로 드러난다(`UserSupportTeam.oppose()`/`support()`, `Chat.blind()`/`unblind()`와 같은 계열). 메서드 이름·시그니처는 `spring-dev` 판단 |

### G. 배포·스키마 반영 (계약 성립 조건)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-LIKE-25 | 예외 | IF 대상 환경에 `quizzes_like` 테이블이 없으면, THEN THE 시스템 SHALL 좋아요 API를 제공하지 못한다(배포 선행 조건 미충족) | `AC-LIKE-25-1` 테이블 없는 환경에서 좋아요 요청은 `ApiResponse` 래퍼 없는 500이다. **quiz 앱의 prod `ddl-auto`는 `none`이라 quiz만 배포해서는 테이블이 생기지 않는다** — 생성 주체는 `ddl-auto=update`인 `user` 앱의 기동이다(`user_support_team`·`users_bq`와 같은 처지). 배포 순서에 `user` 앱 재기동이 포함되어야 한다 |
| QUIZ-LIKE-26 | 선택 | WHERE 대상 환경에 `quizzes_like` 테이블이 이미 만들어져 있으면, THE 시스템 SHALL 1회성 DDL(`infra/sql/migrate-quiz-like.sql`)로 UNIQUE 제약을 보정한다 | `AC-LIKE-26-1` **`ddl-auto=update`는 이미 존재하는 테이블에 UNIQUE를 추가하지 않는다**(2026-08-05 실측, 재조사 금지). 적용 전 `SHOW CREATE TABLE quizzes_like`(또는 `information_schema.TABLE_CONSTRAINTS`)로 제약 유무를 환경마다 조회하고, 없으면 적용한다. `AC-LIKE-26-2` 적용 전 중복 조합 0건을 확인한다(`select user_account_id, quiz_id, count(*) ... having count(*) > 1`). `AC-LIKE-26-3` 이 파일을 `spring.sql.init.data-locations`에 넣지 않는다(`migrate-*.sql` 공통 규칙) |
| QUIZ-LIKE-27 | ~~유비쿼터스~~ | ~~THE 시스템 SHALL 새 `ErrorCode`를 추가하지 않는다~~ | **폐기(2026-08-11) — QUIZ-LIKE-29로 대체.** Q5가 "제출한 문제만"으로 확정되며 403 코드 1건 신설이 계약 성립 조건이 됐다. 번호 재사용 금지 |

## 알려진 결과 (택하면 따라오는 것 — 결함이 아니다)
1. **좋아요 버튼이 놓일 자리는 제출 이후 화면뿐이다.** `/today`는 푼 문제를 빼고, 미제출 상세에는 `liked`·`likeCount` 키가 없으며, 토글 요청 자체가 403이다. 따라서 좋아요 UI는 **제출 직후 결과 화면·제출한 문제의 상세·풀이 이력** 세 곳에서만 성립한다. 이는 "풀어본 사람의 평가"라는 신호 정의의 직접적 귀결이다.
2. **클라이언트는 좋아요가 거절된 이유를 알 수 없다.** 미존재·미편성·미제출이 한 응답으로 합쳐지므로(QUIZ-LIKE-30) FE는 "지금은 좋아요할 수 없다"까지만 표시할 수 있다. 정상 흐름(제출한 문제에만 버튼 노출)에서는 이 응답 자체를 만날 일이 없다.
3. **취소한 좋아요도 행으로 남는다.** `liked = false` 행이 계속 쌓이므로 테이블 행 수는 `likeCount`가 아니다. 집계는 반드시 `liked = true` 조건을 걸어야 한다(QUIZ-LIKE-36). 대신 최초 좋아요 시각(`created_at`)이 보존된다.
4. **토글이라 요청이 유실·중복되면 상태가 뒤집힌 채로 남는다.** 클라이언트가 타임아웃 후 재시도하면 서버는 두 번의 토글로 받아 원상 복귀시킨다. **응답의 `liked`를 화면 상태의 정본으로 삼아야 한다.**
5. **계정 탈퇴는 소프트삭제(`exit_at`)라 좋아요 행이 남는다.** FK CASCADE는 물리 삭제에만 작동하므로, 탈퇴 계정의 좋아요도 `likeCount`에 계속 포함된다. 제출 기록·응원 기록과 동일한 기존 성질이다.
6. **보기 편집·문제 삭제 시나리오는 좋아요에 영향이 없다.** 좋아요 행은 `quiz_options`를 참조하지 않으므로 `QuizUserSubmit.submitOption` CASCADE가 안고 있는 알려진 취약함(보기를 지우면 제출이 사라짐)에 걸리지 않는다. 다만 **그 취약함이 발현되면 제출 기록이 사라져 좋아요 선행조건도 함께 무너진다**(이미 누른 좋아요 행은 남지만 새 토글이 403이 된다).

## 미해결 질문
없음. Q1~Q7은 2026-08-11 전부 확정됐다(아래 결정 근거).

## 결정 근거 (해소된 질문 — 다시 논의하지 않기 위해)
1. **Q1 메서드·경로 → `POST /rt/quizzes/{quizId}/like`(본문 없는 서버 토글).** 사용자가 "엔드포인트를 간단하게 recommend → like 로 변환"이라고 명시했고, 이어 **"스키마도 like 로 맞춰줘"로 확정**해 테이블·컬럼·엔티티까지 같은 어휘로 통일했다(QUIZ-LIKE-37). 멱등이 아니라는 대가는 QUIZ-LIKE-11(동시 충돌 흡수)과 알려진 결과 4(응답의 `liked`가 정본)로 관리한다.
2. **Q2 UNIQUE 순서 → `(user_account_id, quiz_id)`, 이름 `uk_quizzes_like_account_quiz`.** 기존 UNIQUE 3건(`uk_quiz_users_submit_account_quiz`·`uk_user_support_team_account_team`·`uk_user_support_player_account_player`)이 전부 계정 선두라는 일관성이 근거다. 성능 차이는 사실상 없다 — 나머지 FK 컬럼(`quiz_id`)에 InnoDB가 인덱스를 자동 생성하고, 집계는 어느 순서든 `liked`가 인덱스에 없어 행 접근이 발생한다.
3. **Q3 초기값·취소 표현 → `true`로 생성, 취소는 `false` 전환(행 보존).** 행이 생기는 계기가 "좋아요를 눌렀다"뿐이라 `true` 생성이 자연스럽고, `created_at`이 최초 좋아요 시각으로 보존된다. 프로젝트 선례(응원 `oppose` 토글)와도 같은 형태다. 대가는 취소 행 누적이며 집계에 `liked = true` 조건이 항상 필요하다(QUIZ-LIKE-36).
4. **Q5 자격 → 제출한 문제만.** `quiz_users_submit`에 `(요청자, 그 문제)` 이력이 있는지 먼저 확인하고, 없으면 이후 로직을 진행하지 않는다(QUIZ-LIKE-28). **부담이 작다는 사실이 이 선행조건을 가능하게 한다** — 존재 확인은 기존 `QuizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id`로 성립하고, 그 조회는 `uk_quiz_users_submit_account_quiz`로 **커버링 인덱스 조회(테이블 접근 0회)**다(domain 실측 기록).
5. **Q5-부속 거절 응답 → 404 은닉이 아니라 403 단일 코드.** 제출이 선행조건이 된 순간 **"존재하지 않음"과 "안 풀었음"이 요청자 입장에서 동일한 상태로 합쳐진다** — 어느 쪽이든 "너는 이 문제에 좋아요할 수 없다"이고, 하나의 403으로 통일하면 퀴즈 존재 여부와 내일 출제분이 새지 않는다(미편성 은닉 정책은 폐기된 것이 아니라 403 쪽으로 흡수됐다).
   - **200 무시(전면 멱등)를 택하지 않은 이유**: 응답 바디가 `{liked, likeCount}`라 `200 + {liked:false, likeCount:0}`이 **"방금 좋아요를 취소함"과 구분되지 않아** FE가 상태를 잘못 렌더링한다. 채팅의 `DELETE /rooms/{roomUid}/subscribe`가 전면 멱등 200인 것과 갈리는 지점이며, 그쪽은 **응답 바디가 없는 정리 요청**이라 성공과 무시를 구분할 필요가 없었다(QUIZ-CTAC-23).
   - **코드·문구**: `:common` `ErrorCode`의 403 구획(`SELF_REPORT_NOT_ALLOWED`·`CHATROOM_TEAM_MISMATCH` 옆)에 `QUIZ_LIKE_NOT_ALLOWED(403, "좋아요는 직접 푼 문제에만 할 수 있습니다.")`를 제안한다. 문구가 **세 경우를 구분하지 않는다**는 것이 필수 조건이다(문제의 존재·편성 여부를 드러내면 은닉이 깨진다).
   - **판정 순서는 계약이 아니다.** 세 조건을 어떤 순서로 검사해도 응답이 동일해야 하며, 그 동일성만이 계약이다(QUIZ-LIKE-30).
6. **Q4 노출 위치 → `/today` 제외, 단건 상세 + 이력에 노출.** `/today`는 이미 푼 문제를 빼고 내려주므로 그 목록의 모든 항목이 좋아요 불가다 — 집계 비용만 내고 쓰이지 않는다(QUIZ-LIKE-31). 상세는 **미제출이면 키 자체를 내려** 기존 `answer` 계열의 `@JsonInclude(NON_NULL)` 패턴과 일관되게 하고(QUIZ-LIKE-33), 이력은 정의상 전부 제출한 문제라 항상 포함한다(QUIZ-LIKE-34). **N+1 금지는 계약이다** — 이력은 페이지 20건 고정이므로 `quiz_id IN (...)` 2쿼리(집계 1 + 내 좋아요 1)로 붙이고 항목별 개별 조회를 금지한다(QUIZ-LIKE-35).
7. **Q6 응답 바디 → `{liked, likeCount}`.** 버튼과 카운터를 한 번의 응답으로 갱신할 수 있고, 토글마다 붙는 집계 쿼리 1건은 감수한다.
8. **Q7 테이블명 → `quizzes_like`.** 초안(사용자 DDL)의 `quizzes_recommend`를 그대로 쓰기로 했다가, 어휘 통일 확정으로 `quizzes_like`가 됐다. 접두사 형태(`quizzes_`)는 초안을 유지해 형제 테이블(`quiz_options`·`quiz_type`·`quiz_users_submit`)의 `quiz_`와 어긋나지만, 사용자가 준 이름 형태를 존중하는 이 프로젝트의 기준을 따른 결과다.
9. **플래그 컬럼명 → `liked`(`like` 아님).** `like`는 MySQL 예약어라 컬럼에 쓰면 `@Column(name = "`like`")` 백틱이 강제되고, 백틱이 빠지는 순간 `ddl-auto=update`의 DDL이 문법 오류로 실패해 **user 앱 기동 자체가 막힌다** — `quiz_options.option`이 이미 그 지뢰를 안고 있어 하나 더 심지 않는다. `is_like`(`is_answer` 선례)도 후보였으나 자바 필드명(`isLike`)이 어색하고 Lombok `@Getter` 접근자와 겹치기 쉬워 `liked`를 택했다(QUIZ-LIKE-37).

## 기존 정책과의 충돌 / 계약 성립 제약 (구현 지시가 아니라 지켜야 할 사실)
1. **기존 승인 요구사항과의 충돌은 없다.** 이 문서가 뒤집는 5건(QUIZ-LIKE-3·4·15·16·27)은 **이 문서 자신의 초안 항목**이며 승인된 적이 없다. 기존 퀴즈 계약(`docs/api/quiz.md`)에 대해서는 경로 1개 신설 + 응답 필드 추가 2곳이고, **기존 필드·상태코드·에러코드를 바꾸는 항목은 없다**. 구현 후 `docs/api/quiz.md`의 상세·이력 응답 표와 엔드포인트 목록이 갱신 대상이다(`api-documenter` 소관).
2. **미편성 풀 은닉 정책은 유지된다 — 다만 이 경로에서는 403으로 흡수된다.** `quiz_date IS NULL` 문제를 감추는 이유는 "id 순회로 내일 출제분을 미리 보는 것"을 막기 위함이다(`.claude/modules/quiz.md`, `ErrorCode.QUIZ_NOT_FOUND` 주석). 좋아요 경로는 404 대신 403을 쓰지만 **미존재와 구분되지 않는다는 성질은 동일**하므로 정책이 약해지지 않는다. **기존 상세·제출 경로의 404는 그대로 둔다**(QUIZ-LIKE-33 AC-3).
3. **새 `ErrorCode` 1건이 계약 성립 조건이다.** `QUIZ_LIKE_NOT_ALLOWED`(403)가 `:common`에 없으면 QUIZ-LIKE-28/29/30이 성립하지 않는다. `common`은 spring 의존 없이 status를 int로 보관하는 enum이므로 추가는 한 줄이다.
4. **제출 이력 확인은 기존 리포지토리로 성립한다 — 새 조회 메서드가 필요 없다.** `QuizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id`가 이미 있고 `uk_quiz_users_submit_account_quiz`로 커버링된다(결정 근거 4). **어떤 메서드를 어떻게 호출할지는 `spring-dev` 판단이며 이 문서는 SQL을 지정하지 않는다** — 다만 "선행조건 검사가 싸다"는 사실이 이 설계를 가능하게 한 전제라 기록해 둔다.
5. **`ddl-auto=update`의 성질이 이 기능의 배포를 좌우한다.** (a) 테이블이 아직 없는 환경이면 엔티티 선언대로 UNIQUE까지 붙는다 — `quiz_type.name`이 "테이블이 없을 때가 1회성 DDL 없이 제약을 거는 유일한 기회"였던 것과 같은 상황이다. (b) 어떤 이유로든 테이블이 먼저 생겨 버린 환경에서는 UNIQUE가 **영원히 자동으로 붙지 않는다**(실측 확정). 그래서 QUIZ-LIKE-26의 1회성 DDL(`infra/sql/migrate-quiz-like.sql`)을 함께 낸다 — **적용 여부는 환경마다 조회해서 판단**해야 하며 "적용됐을 것"을 전제하지 말 것.
6. **스키마를 만드는 앱과 쓰는 앱이 다르다.** quiz 앱의 prod `ddl-auto`는 `none`이고 테이블은 `user` 앱 기동이 만든다. 즉 이 기능의 배포는 **quiz 앱 배포 + user 앱 재기동**이 한 묶음이다(QUIZ-LIKE-25). 로컬에서도 빈 DB에 `:quiz:bootRun`만 하면 시드가 비어 다른 이유로 막히므로 `:user:bootRun`을 먼저 한 번 띄우는 기존 규칙이 그대로 적용된다.
7. **엔티티는 `domain` 모듈 `com.skhynix.domain.quiz.entity.QuizLike`, 리포지토리는 `com.skhynix.domain.quiz.repository.QuizLikeRepository`다** — `record`/`chat`/`support`가 연관 엔티티를 한 패키지로 묶은 선례와 동일하다. `@EntityScan("com.skhynix")` 범위 안이라 별도 배선은 필요 없다.
8. **컬럼명 `liked`는 예약어 회피의 결과다** — 자세한 근거는 결정 근거 9와 QUIZ-LIKE-37. 테이블명 `quizzes_like`는 식별자 전체가 예약어가 아니라 백틱이 필요 없다.
9. **`spring.jpa.open-in-view: false`다.** 상세·이력 응답에 좋아요를 실을 때 필요한 조회는 `@Transactional` 서비스 메서드 안에서 끝나야 한다(quiz 모듈의 명시된 함정 — 컨트롤러에서 LAZY를 건드리면 `LazyInitializationException`).
