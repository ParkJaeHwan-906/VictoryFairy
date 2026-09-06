# 퀴즈 배점 분리(point / bq)와 두 축 적립 요구사항
> 상태: **승인됨 (2026-09-03, 사용자 승인 완료)** · 모듈: quiz(적재·제출·응답) + domain(엔티티 `Quiz`) · 최종 수정: 2026-09-03
> **미해결 질문 0건** — 초안의 Q1~Q3이 2026-09-03 전부 확정됐다(하단 "결정 근거"). **`(가정)` 표시 항목은 이 문서에 남아 있지 않다.**
> 확정된 셋: ① `users_bq` 행이 없으면 **적립 트랜잭션이 행을 만든다** ② 제출 응답의 누적 bq 필드명은 **`totalBq`** ③ 이력의 **경기 전체 `summary`에만 `earnedBq`를 추가**하고 이닝별 요약은 손대지 않는다.
> ID 규칙: `QUIZ-PBQ-<n>` — 인수 기준은 `AC-PBQ-<요구사항번호>-<n>`. 기존 quiz 계열(`QUIZ-INN-*`·`QUIZ-VOTE*`·`QUIZ-CPF-*`)과 번호 공간이 다르다.
> 선행 계약: `docs/requirements/user/me-profile.md`(승인됨, `USER-ME-3~6`·`USER-ME-18~30` — `users_bq` 스키마·행 생성·백필). **이 문서는 그 스키마를 한 줄도 바꾸지 않고 `bq_score`를 증가시키는 첫 주체가 된다.**
> 선행 계약(앱 밖): **AI 파이프라인 후보 JSON v3** — `bqReward`가 필수 필드가 됐고 업로드 직전 게이트(`VictoryFairy_AI`의 `validate_candidates.py`)가 부재·난이도 불일치를 exit 1 로 막는다. 아래 "적재" 절이 그 계약을 받는 쪽이다.

## 배경 / 목적
`quizzes.score` 하나가 두 가지 뜻을 겸하고 있었다 — 응답 JSON 은 이미 `point`(재화 축, `users_account.point` → 캐릭터 상점 소비처)로 내보내는데 컬럼 이름은 `score`(점수)여서 코드와 프론트 계약이 서로 다른 단어를 쓴다.

동시에 레이팅 축(`users_bq.bq_score`)은 **아무 경로에서도 증가하지 않아** 전 계정이 영구히 0이었다(`docs/api/quiz.md`: "레이팅 설계 확정 전이라 건드리지 않는다"). 이번 변경은 그 둘을 한 번에 닫는다 — 컬럼 이름을 뜻에 맞추고(`point`), 난이도에 연동된 별도 배점 축(`bq`)을 신설해 정답 시 두 원장에 각각 적립한다.

## 범위
- 포함
  - `quizzes.score` → `quizzes.point` **이름 변경**(타입·널 허용 불변) 및 `Quiz.score` → `Quiz.point`
  - `quizzes.bq`(정수, 널 허용) **신설** + 난이도 → bq 매핑표
  - 적재(`QuizIngestService`)가 후보 JSON 의 `bqReward`를 받고, 없으면 `difficulty`로 계산해 채우는 규칙
  - 정답 시 `users_bq.bq_score` 적립 — 기존 포인트 적립과 **같은 트랜잭션·같은 중복 판정**
  - `GET /rt/quizzes/today`·`GET /rt/quizzes/{quizId}` 응답에 `bq` 노출
  - `POST /rt/quizzes/{quizId}/submit` 응답에 `earnedBq`·누적 bq(`totalBq`) 노출
  - `GET /rt/quizzes/submissions` 의 문제 항목에 `earnedBq` 노출 + **경기 전체 `summary` 에 `earnedBq` 추가**(이닝별 요약은 불변)
  - 1회성 마이그레이션 스크립트 `infra/sql/migrate-quiz-point-bq.sql`(rename · 컬럼 추가 · 기존 행 백필)과 그 **적용 순서 제약**
- 제외
  - **`point` 필드의 관측 가능한 변화** — JSON 필드명·타입·값이 전부 그대로다. 이번 변경은 그 사실을 **회귀로 고정**할 뿐이다
  - **`Quiz.point` 타입을 정수로 바꾸는 것** — `Double`을 유지한다. 값이 정수여도 `30.0` 표기를 바꾸면 프론트 계약이 함께 흔들린다(알고 택한 결정)
  - **bq 를 소비하는 화면·랭킹·레이팅 산식** — 이번엔 적립과 노출까지다
  - **`GET /rt/quizzes/submissions` 의 이닝별 요약(`innings[].summary`) 변경** — 이닝 단위 bq 합계는 넣지 않는다(확정, Q3)
  - **`GET /api/users/me` 응답 변경**(user 모듈) — `bqScore`가 이미 그 값을 읽고 있어 손댈 것이 없다
  - **`GET /rt/quizzes/{quizId}/vote-count` 응답 변경** — 불변
  - **과거 제출분의 bq 소급 적립** — 이 변경 이전에 맞힌 문제는 `bq_score`에 반영하지 않는다
  - **`docs/api/quiz.md` 갱신** — 구현 후 `api-documenter` 소관

## 용어
| 용어 | 뜻 |
|---|---|
| **point** | 재화 축 배점. `quizzes.point`(널 허용) → 정답 시 `users_account.point` 적립. AI 후보 JSON 의 `pointReward` |
| **bq** | 레이팅 축 배점. `quizzes.bq`(널 허용) → 정답 시 `users_bq.bq_score` 적립. AI 후보 JSON 의 `bqReward` |
| **난이도 매핑표** | `EASY=1` · `MEDIUM=2` · `HARD=3` · `EXPERT=4`. **5는 예약값** — 현재 어떤 난이도에도 부여되지 않지만 상위 난이도가 신설되면 생길 수 있다 |
| **적립 트랜잭션** | `QuizSubmitService.submit` 의 `@Transactional` 경계. 계정 행 락(`findWithLockById`) → 적립 → 조건부 UPDATE(`fillAnswer`) → 커밋이 이 안에서 일어난다 |
| **후보 JSON v3** | `bqReward` 가 필수가 된 계약 판본. 업로드 직전 게이트(`validate_candidates.py`)가 부재·난이도 불일치를 exit 1 로 막아, **이 변경 배포 이후 올라오는 파티션에는 항상 실려 있다** |

**파이프라인 정본(참고)** — AI 쪽 `scoring.yaml` 의 난이도별 값이다. 서버는 이 표로 `point` 를 **계산하지 않는다**(후보 JSON 의 `pointReward` 를 그대로 저장한다 — QUIZ-PBQ-12). 서버가 이 표로 계산하는 것은 `bq` 폴백(QUIZ-PBQ-10)뿐이다.

| 난이도 | pointReward | bqReward |
|---|---|---|
| EASY | 30 | 1 |
| MEDIUM | 50 | 2 |
| HARD | 80 | 3 |
| EXPERT | 120 | 4 |
| *(미할당)* | — | 5(예약) |

## 요구사항 (EARS)

### 스키마 · 엔티티
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-PBQ-1 | 유비쿼터스 | THE 시스템 SHALL `quizzes` 테이블에 배점 컬럼 `point`(널 허용)를 보유한다 | `SHOW COLUMNS FROM quizzes LIKE 'point'` → 1행, `Null=YES`. `Type`은 변경 이전 `score` 컬럼의 `Type`과 문자열이 같다 |
| QUIZ-PBQ-2 | 유비쿼터스 | THE 시스템 SHALL `quizzes` 테이블에 `score` 컬럼을 보유하지 않는다 | `SHOW COLUMNS FROM quizzes LIKE 'score'` → 0행 |
| QUIZ-PBQ-3 | 유비쿼터스 | THE 시스템 SHALL `quizzes` 테이블에 `bq`(INT, 널 허용) 컬럼을 보유한다 | `SHOW COLUMNS FROM quizzes LIKE 'bq'` → 1행, `Type=int`, `Null=YES` |
| QUIZ-PBQ-4 | 유비쿼터스 | THE 시스템 SHALL `Quiz` 엔티티의 배점 필드를 `point`(`Double`)로 하여 `quizzes.point`에 매핑한다 | `Quiz.getPoint()`가 존재하고 `Quiz.getScore()`는 존재하지 않는다(저장소 전역에서 `getScore()`·`.score(` 참조 0건) |
| QUIZ-PBQ-5 | 유비쿼터스 | THE 시스템 SHALL `Quiz` 엔티티에 `bq`(`Integer`) 필드를 두어 `quizzes.bq`에 매핑한다 | `Quiz.builder().bq(3)...build().getBq()` → `3`. 값을 주지 않고 만든 `Quiz`의 `getBq()` → `null` |
| QUIZ-PBQ-6 | 유비쿼터스 | THE 시스템 SHALL 난이도 문자열을 bq 값으로 옮길 때 `EASY→1` · `MEDIUM→2` · `HARD→3` · `EXPERT→4` 매핑을 사용한다 | 네 값 각각에 대해 매핑 결과가 1·2·3·4 |
| QUIZ-PBQ-7 | 유비쿼터스 | THE 시스템 SHALL 어떤 난이도에도 bq 값 5를 부여하지 않는다 | 매핑표의 치역이 `{1,2,3,4}`. 난이도로부터 5가 나오는 입력이 없다 |
| QUIZ-PBQ-8 | 예외 | IF 난이도가 NULL 이거나 매핑표에 없는 값이면, THEN THE 시스템 SHALL bq 를 NULL 로 둔다 | `difficulty=null` · `difficulty="LEGEND"` 각각에 대해 매핑 결과 `null`(예외를 던지지 않는다) |

### 적재 — `QuizIngestService`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-PBQ-9 | 이벤트 | WHEN 후보 JSON 에 `bqReward`가 있으면, THE 시스템 SHALL 그 값을 `quizzes.bq`에 그대로 저장한다 | `{"bqReward":3,"difficulty":"EASY"}` 후보 적재 → 저장된 행의 `bq`=3 (난이도 매핑값 1이 아니다 — 후보 값이 우선). **v3 이후 파티션의 정상 경로다**(게이트가 부재를 막으므로 실릴 것이 보장된다) |
| QUIZ-PBQ-10 | 예외 | IF 후보 JSON 에 `bqReward`가 없으면, THEN THE 시스템 SHALL 그 후보의 `difficulty`에 매핑표를 적용해 `quizzes.bq`를 채운다 | `bqReward` 키 없음 + `{"difficulty":"HARD"}` → 저장된 행의 `bq`=3. **이론적 방어가 아니다** — S3 에 이미 쌓인 v3 이전 파티션에는 `bqReward`가 없고 적재기가 그 과거분을 재처리할 수 있어 실제로 발동한다 |
| QUIZ-PBQ-11 | 예외 | IF 후보 JSON 에 `bqReward`가 없고 `difficulty`도 매핑 불가하면, THEN THE 시스템 SHALL `bq`를 NULL 로 저장하고 적재를 성공시킨다 | `bqReward`·`difficulty` 둘 다 없는 후보 → 적재 결과 `LOADED`, 저장된 행의 `bq` IS NULL, 다른 컬럼은 정상 |
| QUIZ-PBQ-12 | 이벤트 | WHEN 후보 JSON 에 `pointReward`가 있으면, THE 시스템 SHALL 그 값을 `quizzes.point`에 저장한다 | `{"pointReward":30}` → 저장된 행의 `point`=30.0 (종전 동작, 컬럼 이름만 바뀜) |
| QUIZ-PBQ-13 | 예외 | IF 후보 JSON 에 `pointReward`가 없으면, THEN THE 시스템 SHALL `point`를 NULL 로 저장한다 | `pointReward` 키 없는 후보 → 저장된 행의 `point` IS NULL |
| QUIZ-PBQ-14 | 유비쿼터스 | THE 시스템 SHALL 난이도 매핑을 단일 정의로 두어 적재 경로와 마이그레이션 백필이 같은 결과를 낸다 | 같은 `difficulty` 값 집합에 대해 앱 적재로 만든 행의 `bq`와 백필 SQL 이 채운 행의 `bq`가 전부 일치 |

### 정답 적립 — `POST /rt/quizzes/{quizId}/submit`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-PBQ-15 | 이벤트 | WHEN 제출이 정답으로 확정되면, THE 시스템 SHALL 그 문제의 `point`를 반올림한 값만큼 `users_account.point`를 증가시킨다 | `point=30.0` 문제 정답 → `users_account.point`가 30 증가(종전 동작의 회귀 고정) |
| QUIZ-PBQ-16 | 이벤트 | WHEN 제출이 정답으로 확정되면, THE 시스템 SHALL 그 문제의 `bq`만큼 `users_bq.bq_score`를 증가시킨다 | `bq=3` 문제 정답 → 그 계정의 `users_bq.bq_score`가 3 증가 |
| QUIZ-PBQ-17 | 예외 | IF 그 문제의 `bq`가 NULL 이면, THEN THE 시스템 SHALL `users_bq.bq_score`를 변경하지 않는다 | `bq IS NULL` 문제 정답 → 200, `bq_score` 불변, `users_account.point`는 정상 적립 |
| QUIZ-PBQ-18 | 예외 | IF 그 문제의 `point`가 NULL 이면, THEN THE 시스템 SHALL `users_account.point`를 변경하지 않는다 | `point IS NULL` 문제 정답 → 200, `point` 불변, `bq_score`는 정상 적립(두 축이 서로를 막지 않는다) |
| QUIZ-PBQ-19 | 예외 | IF 적립할 bq 값이 0 이하이면, THEN THE 시스템 SHALL `users_bq` 행을 갱신하지 않는다 | `bq=0` 문제 정답 → `bq_score`·`users_bq.updated_at` 불변 |
| QUIZ-PBQ-20 | 예외 | IF 제출이 오답이면, THEN THE 시스템 SHALL `point`·`bq_score` 어느 쪽도 증가시키지 않는다 | 오답 제출 → 두 값 모두 불변, 응답 `earnedPoint`=0·`earnedBq`=0 |
| QUIZ-PBQ-21 | 유비쿼터스 | THE 시스템 SHALL bq 적립을 포인트 적립과 **같은 트랜잭션**에서 수행한다 | 제출 1건으로 `users_account.point`·`users_bq.bq_score`가 함께 커밋된다. 별도 요청·별도 커밋·커밋 후 훅이 필요하지 않다 |
| QUIZ-PBQ-22 | 예외 | IF 중복 제출 판정(`fillAnswer` 영향 0행)으로 409 를 반환하면, THEN THE 시스템 SHALL 그 요청의 bq 적립을 되돌린다 | 같은 문제에 두 번째 제출 → 409, `bq_score`가 첫 제출 직후 값과 동일(두 번 더해지지 않음) |
| QUIZ-PBQ-23 | 예외 | IF 제출이 404·403·400 으로 거절되면, THEN THE 시스템 SHALL `users_bq.bq_score`를 변경하지 않는다 | 미편성 문제(404) · `/today` 미경유(403) · 없는 보기(400) 각각에서 `bq_score`·`updated_at` 불변 |
| QUIZ-PBQ-24 | 복합 | WHILE 적립 트랜잭션이 진행되는 동안, WHEN 그 계정의 `users_bq` 행을 갱신하면, THE 시스템 SHALL 그 행을 행 단위 배타 락 아래에서 읽고 쓴다 | 같은 계정으로 서로 다른 15개 문제(각 `bq=2`)를 동시 제출 → `bq_score` 증가분이 정확히 30(갱신 유실 0). 계정 행 락을 얻은 뒤에 잡아 두 락의 획득 순서가 항상 같다 |
| QUIZ-PBQ-25 | 예외 | IF 적립 대상 계정에 `users_bq` 행이 없으면, THEN THE 시스템 SHALL 같은 트랜잭션에서 `bq_score`가 적립액인 행을 1건 생성한다 | `users_bq` 행이 없는 계정이 `bq=3` 문제를 맞힘 → 200, 그 계정의 `users_bq` 행이 1건 생기고 `bq_score`=3. 이후 `GET /api/users/me` 의 `bqScore`=3 |

### 응답 노출
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-PBQ-26 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/today` 응답의 각 문제 항목에 `bq`를 JSON 정수로 담는다 | `bq=2` 문제 → `data[].bq` = `2`. 기존 키(`id·type·question·difficulty·point·preferred·options`)는 하나도 빠지지 않는다 |
| QUIZ-PBQ-27 | 예외 | IF `/today` 응답에 실은 문제의 `bq`가 NULL 이면, THEN THE 시스템 SHALL 그 항목의 `bq`를 `null` 로 담는다 | `bq IS NULL` 문제 → `data[].bq` 키가 존재하고 값이 `null`(키 생략 아님 — 같은 응답의 `point`가 NULL 일 때와 같은 규칙) |
| QUIZ-PBQ-28 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/{quizId}` 응답에 `bq`를 JSON 정수로 담는다 | `bq=4` 문제 상세 → `data.bq` = `4` |
| QUIZ-PBQ-29 | 예외 | IF 상세 응답 대상 문제의 `bq`가 NULL 이면, THEN THE 시스템 SHALL `bq` 키를 응답에서 생략한다 | `bq IS NULL` 문제 상세 → `data` 에 `bq` 키 자체가 없다(이 응답의 `@JsonInclude(NON_NULL)` 규칙 — `point`가 NULL 일 때와 동일) |
| QUIZ-PBQ-30 | 유비쿼터스 | THE 시스템 SHALL `POST /rt/quizzes/{quizId}/submit` 응답에 이번 제출로 적립된 bq 를 `earnedBq`로 담는다 | `bq=3` 문제 정답 → `data.earnedBq` = `3`. 오답·`bq` NULL·`bq<=0` 이면 `0` |
| QUIZ-PBQ-31 | 유비쿼터스 | THE 시스템 SHALL 같은 응답에 적립 후 누적 bq 를 `totalBq`로 담는다 | 누적 10 인 계정이 `bq=3` 문제를 맞힘 → `data.totalBq` = `13`. 오답이면 적립 전 값 그대로 |
| QUIZ-PBQ-32 | 유비쿼터스 | THE 시스템 SHALL 제출 응답의 기존 키 `correct·answer·myOption·earnedPoint·totalPoint` 를 이름·타입·값 그대로 유지한다 | 종전 계약 그대로의 응답에 키 2개만 늘어난다(5개 → 7개) |
| QUIZ-PBQ-33 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/submissions` 의 각 문제 항목에 `earnedBq`를 담는다 | 정답 행이고 `bq=2` 면 `innings[].quizzes[].earnedBq` = `2`. 오답·미답·`bq` NULL 이면 `0` |
| QUIZ-PBQ-34 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/submissions` 의 이닝별 요약(`innings[].summary`) 키 집합을 변경하지 않는다 | `innings[].summary` 키가 정확히 `{correctCount,total,accuracy}` — `earnedBq`·`earnedPoint` 어느 쪽도 없다 |
| QUIZ-PBQ-46 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/submissions` 의 경기 전체 요약(`summary`)에 그 경기에서 적립된 bq 합계를 `earnedBq`로 담는다 | `summary` 키가 정확히 `{correctCount,total,accuracy,earnedPoint,earnedBq}`(4개 → 5개). 값은 그 경기 문제 항목들의 `earnedBq` 합과 같다. 기록이 없는 경기(`innings:[]`)면 `0` |
| QUIZ-PBQ-35 | 유비쿼터스 | THE 시스템 SHALL 네 응답(`/today`·상세·제출·이력)의 `point`·`earnedPoint` 필드를 이번 변경 전후로 동일하게 유지한다 | 같은 데이터에 대해 변경 전후 응답의 `point`·`earnedPoint` 값이 문자 단위로 같다(컬럼 rename 이 프론트로 새는 지점 0) |
| QUIZ-PBQ-36 | 유비쿼터스 | THE 시스템 SHALL `GET /rt/quizzes/{quizId}/vote-count` 응답을 변경하지 않는다 | 응답 항목이 `{no, text, voteCount}` 그대로, `bq` 계열 키 없음 |

### 마이그레이션 — `infra/sql/migrate-quiz-point-bq.sql`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| QUIZ-PBQ-37 | 유비쿼터스 | THE 시스템 SHALL 1회성 수동 적용 스크립트 `infra/sql/migrate-quiz-point-bq.sql` 을 보유한다 | 파일이 존재하고 다른 `migrate-*.sql` 과 같은 형식이다(머리 주석에 대상 환경·적용 순서·검증 쿼리, `spring.sql.init` 미배선) |
| QUIZ-PBQ-38 | 유비쿼터스 | THE 시스템 SHALL 그 스크립트로 `quizzes.score` 를 값 손실 없이 `point` 로 이름 변경한다 | 적용 전 `score`가 30.0 이던 행이 적용 후 `point`=30.0. `SELECT COUNT(*) FROM quizzes WHERE point IS NOT NULL` 이 적용 전 `score IS NOT NULL` 건수와 같다 |
| QUIZ-PBQ-39 | 유비쿼터스 | THE 시스템 SHALL 그 스크립트로 `quizzes.bq` 컬럼을 추가한다 | 적용 후 `SHOW COLUMNS FROM quizzes LIKE 'bq'` → `int`, `Null=YES` |
| QUIZ-PBQ-40 | 유비쿼터스 | THE 시스템 SHALL 그 스크립트로 기존 행의 `bq` 를 `difficulty` 기준 매핑표대로 백필한다 | 적용 후 `SELECT difficulty, bq, COUNT(*) FROM quizzes GROUP BY 1,2` 가 EASY→1 · MEDIUM→2 · HARD→3 · EXPERT→4 로만 나온다 |
| QUIZ-PBQ-41 | 예외 | IF 백필 대상 행의 `difficulty` 가 NULL 이거나 매핑표에 없으면, THEN THE 시스템 SHALL 그 행의 `bq` 를 NULL 로 남긴다 | 적용 후 `SELECT COUNT(*) FROM quizzes WHERE (difficulty IS NULL OR difficulty NOT IN ('EASY','MEDIUM','HARD','EXPERT')) AND bq IS NOT NULL` = 0 |
| QUIZ-PBQ-42 | 유비쿼터스 | THE 시스템 SHALL 백필이 이미 `bq` 값이 있는 행을 덮어쓰지 않도록 한다 | 난이도가 EASY 인 행의 `bq` 를 4로 바꾼 뒤 백필을 재실행 → 그 행의 `bq` 가 여전히 4(재실행 멱등) |
| QUIZ-PBQ-43 | 예외 | IF 스크립트 적용 시점에 `quizzes.point` 컬럼이 이미 존재하면(= `ddl-auto=update` 인 앱이 먼저 기동해 컬럼을 만든 경우), THEN THE 시스템 SHALL `score` 의 값을 `point` 로 옮긴 뒤 `score` 를 제거하는 경로를 스크립트 안에 제공한다 | `score`·`point` 가 함께 있고 `point` 가 전부 NULL 인 DB 에 그 경로를 적용 → 모든 행의 `point` 가 옛 `score` 값과 같아지고 `score` 컬럼이 사라진다 |
| QUIZ-PBQ-44 | 예외 | IF 스크립트를 적용하지 않은 채 quiz 앱을 배포하면, THEN THE 시스템(quiz 앱) SHALL 퀴즈 조회·제출 요청을 500 으로 실패시킨다 | 미적용 DB 에서 `GET /rt/quizzes/today` → 500(`Unknown column ... point`). 스크립트 적용 후 **재시작 없이** 200 으로 정상화 |
| QUIZ-PBQ-45 | 유비쿼터스 | THE 시스템 SHALL 이 스크립트를 배포 파이프라인이 아니라 운영자가 손으로 적용하는 선행 조건으로 둔다 | `deploy-eks.yml` 에 이 SQL 적용 단계가 없다. 배포 체크리스트(모듈 문서)에 선행 SQL 로 기재된다 |

## 알려진 결과 (설계상 받아들인 것)
1. **적재기는 `bqReward` 값을 검증하지 않는다(QUIZ-PBQ-9).** 후보 JSON 이 5나 0, 음수를 보내면 그대로 저장된다 — 값의 정합은 **상류 게이트**(`validate_candidates.py`의 난이도 일치 검사)가 책임지고, BE 는 받은 값을 보존하는 쪽을 택했다. 두 곳에서 같은 판정을 하면 반드시 어긋난다. 대신 0 이하는 적립 시점에 무시된다(QUIZ-PBQ-19).
2. **과거 제출분에 소급 적립은 없다.** 백필(QUIZ-PBQ-40)은 문제의 배점만 채우며, 이미 그 문제를 맞힌 사람의 `bq_score` 는 0에서 시작한다. `quiz_users_submit` 에 적립 원장이 없어 소급 계산의 근거가 없다.
3. **`GET /rt/quizzes/submissions` 의 `earnedBq` 는 표시용 근사치다.** 기존 `earnedPoint` 와 같은 성질로 `quizzes.bq` 의 현재 값을 읽으므로, 배점이 사후 수정되면 실제 적립액과 어긋난다.
4. **`/today` 의 `voteCount` 노출이 이제 레이팅에 영향을 준다.** `docs/requirements/quiz/quiz-vote-exposure.md` 는 미제출 문제에 투표 분포를 싣는 대가(다수결 정답 힌트)를 "현재 `bq_score` 미연동이라 점수엔 미반영"으로 명시해 두었는데, 이 문서가 그 전제를 없앤다 — 남의 선택을 보고 맞힌 정답이 레이팅 축을 올린다. 이번 범위에서 대응하지 않고 기록만 남긴다.

## 기존 정책과의 충돌 (모듈 컨텍스트 대조)
| 대상 | 현재 기록 | 이 문서의 변경 |
|---|---|---|
| `.claude/modules/quiz.md`(`QuizSubmitService` 항목) | "**bq_score 는 안 건드림**(레이팅 설계 확정 전)" | 뒤집힌다. 구현 후 `context-keeper` 가 갱신해야 한다 |
| `docs/api/quiz.md`(제출 절 · 이력 절) | "`users_bq.bq_score` 는 레이팅 설계 확정 전이라 건드리지 않는다" · `summary.earnedPoint` = "`quizzes.score` 합" | 구현 후 `api-documenter` 소관 |
| `docs/requirements/user/me-profile.md` `USER-ME-23` | `users_bq` 행을 만드는 주체는 **회원가입 트랜잭션**이다 | QUIZ-PBQ-25 가 **두 번째 생성 주체**를 추가한다(확정). `USER-ME-23` 자체는 유효하며 이 문서가 그 조항을 부정하는 것이 아니다 — 생성 주체가 하나에서 둘로 **늘어난 것**이다 |
| 같은 문서 `USER-ME-19`/`USER-ME-20` | 행이 없으면 `bqScore:0` 으로 200, **조회는 행을 만들지 않는다** | 충돌 없음 — 그 조항은 `GET /api/users/me`(읽기 경로) 한정이고 이 문서가 쓰는 곳은 제출(쓰기) 경로다 |
| `.claude/modules/quiz.md`(배포 전제) | quiz prod `ddl-auto=none` / user prod `ddl-auto=update` + `@EntityScan("com.skhynix")` | ⚠ **user 앱이 먼저 재기동하면 `point`·`bq` 컬럼을 스스로 만들고 `score` 는 고아로 남아 전 문제의 배점이 NULL(적립 0)이 된다.** QUIZ-PBQ-43·44 가 이 함정을 다룬다 |

## 결정 근거 (2026-09-03 확정)

### Q1 — `users_bq` 행이 없는 계정의 적립: **적립 트랜잭션이 행을 만든다** (A안)
- **스킵하면 그 적립분이 영구히 사라진다.** 보정 배치가 없고, `GET /api/users/me` 의 `bqScore:0`(`USER-ME-19` 안전망)이 "점수를 얻은 적 없다"와 같은 값이라 **유실 사실까지 덮어 감춘다.** 사용자도 운영자도 잃었다는 것을 알 수 없는 손실이라 받아들이지 않는다.
- **UNIQUE 충돌 창이 없다.** 이 경로는 이미 `findWithLockById` 로 계정 행을 잠근 뒤에 진행되므로 같은 계정의 동시 제출이 직렬화된다. `users_bq.user_account_id` 의 UNIQUE(`USER-ME-5`)를 두 요청이 동시에 때릴 수 있는 지점이 생기지 않는다. 다른 생성 주체(가입 트랜잭션)는 정의상 **신규 계정**만 만들어 이 경로와 같은 행을 노리지 않는다.
- ⚠ **`USER-ME-23`("`users_bq` 행은 회원가입 트랜잭션이 만든다")을 뒤집는 것이 아니라 늘리는 것이다.** 그 조항만 읽으면 "행 생성 주체는 가입 하나"로 읽히지만, 이 결정 이후 주체는 **가입 트랜잭션 + 정답 적립 트랜잭션 둘**이다. 백필(`USER-ME-26`)까지 세면 셋이며, 셋 다 `bq_score` 초기값 규칙이 다르다(가입·백필=0, 적립=적립액).

### Q2 — 제출 응답의 누적 bq 필드명: **`totalBq`** (A안)
- 같은 응답 안의 `earnedPoint`/`totalPoint` 쌍과 대칭이 맞는다. 한 응답에서 두 축을 나란히 읽는 소비자에게는 **응답 내부의 일관성**이 더 강한 단서다.
- **`GET /api/users/me` 의 `bqScore` 와 이름이 다른 것은 알고 택한 것이다** — 같은 값(`users_bq.bq_score`)을 두 엔드포인트가 다른 이름으로 내보낸다. 통일하려면 `earnedBq`/`bqScore` 라는 짝이 안 맞는 조합을 이 응답에 넣어야 해서 그쪽을 버렸다.

### Q3 — 이력 요약의 bq 합계: **경기 전체 `summary`에만 추가** (B안)
- **기존 비대칭을 그대로 따른 것이다.** 지금도 `earnedPoint` 는 경기 전체 `Summary` 에만 있고 `InningSummary`(`correctCount`/`total`/`accuracy`)에는 없다. 두 축이 같은 자리에 놓여야 나중에 한쪽만 옮기는 실수가 안 생긴다.
- 이닝별 bq 합계가 필요해지면 FE 가 그 이닝의 문제 항목 `earnedBq`(QUIZ-PBQ-33)를 더할 수 있다 — 서버가 계산해 줘야만 얻을 수 있는 값이 아니다.
