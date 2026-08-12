# 퀴즈(quiz) API 명세

> **도메인** `quiz` — 오늘의 퀴즈 조회·개별 조회·제출(채점)·풀이 이력·좋아요.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/quizzes` · **엔드포인트** 5개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/quiz/controller/QuizController.java`(조회·좋아요 토글), `QuizSubmissionController.java`(제출·이력) — `/rt`는 context-path가 붙인다
> **최종 갱신** 2026-08-12 — `POST /{quizId}/submit`에 403 `QUIZ_SUBMIT_NOT_ALLOWED` 신설(제출 자격 증명 도입, 실패 3종→4종) + `GET /today` 응답 상한 20건 신설(응답 필드는 불변). 계약 원본 `docs/requirements/quiz/quiz-inning-tracking.md`(승인됨 2026-08-12). (직전: 같은 날 경로 변수/쿼리 파라미터/요청 본문 표기를 chat.md 형식으로 통일(계약 변경 없음). 직전: 2026-08-11 좋아요 기능 신설(`POST /{quizId}/like` 신규, 단건 상세·풀이 이력 응답에 `liked`·`likeCount` 필드 추가)).
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/quizzes/today](#get-rtquizzestoday) | 200 | 오늘(KST) 세트 목록 — 선호 문제 우선 정렬, `preferredOnly` 필터 |
| GET | [/rt/quizzes/{quizId}](#get-rtquizzesquizid) | 200 | 단건 상세 — 제출 전엔 정답 비노출, 제출 후엔 복기 정보 + 좋아요 상태 포함 |
| POST | [/rt/quizzes/{quizId}/submit](#post-rtquizzesquizidsubmit) | 200 | 제출·서버 채점 — 정답이면 포인트 적립 |
| GET | [/rt/quizzes/submissions](#get-rtquizzessubmissions) | 200 | 내 풀이 이력(페이지) + 전체 요약(정답률), 각 항목 좋아요 상태 포함 |
| POST | [/rt/quizzes/{quizId}/like](#post-rtquizzesquizidlike) | 200 | 좋아요 토글 — 내가 제출한 문제에만 허용 |

## 이 도메인의 특이사항

**인증 필수.** quiz 모듈의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다. 무토큰 요청은 전부 401 `"인증이 필요합니다."`([README](README.md)의 401 정책).

**전원 동일 데일리 세트.** `quizzes.quiz_date`는 **출제일**이다(생성일 아님). 시효성 없는 문제(역대기록형)는 `quiz_date=NULL` 풀에 쌓였다가 매일 편성 잡이 세트 부족분(기본 10문항, `quiz.serve.daily-count`)을 오래된 것부터 채운다. 경기 문항(gameId 귀속)만 그 경기 날짜에 고정. 모든 사용자가 같은 날 같은 세트를 받는다 — 레이팅(도입 예정)의 점수 비교 전제다. **미편성 풀 문제는 어떤 API로도 보이지 않는다**(단건 조회·제출 모두 404 — id 순회로 내일 출제분을 미리 보는 것을 막는다).

**선호(응원 구단·선수)는 세트 구성이 아니라 노출 방식에 반영된다.** `/today`는 내 응원 구단(문제의 대상·상대 구단 일치) 또는 응원 선수(대상 선수 일치) 문제를 앞에 배치하고 `preferred` 플래그로 표시한다. `preferredOnly=true`는 그것만 남긴다(응원 정보가 하나도 없으면 no-op으로 전체 반환). 세트 자체는 전원 동일하므로 필터를 써도 레이팅 공정성이 깨지지 않는다.

**정답(answer)은 제출 전엔 어떤 응답에도 없다 — 제출 후에만 공개된다.** 조회 응답(`/today`, 미제출 상세)에는 `answer` 키 자체가 없다(클라이언트 개발자 도구 노출 방지, 테스트로 고정). 제출 응답과 제출 후 상세·이력에는 정답이 실린다(복기 화면 전제).

**채점·적립은 서버 트랜잭션 안에서 원자적이다.** 정답이면 `quizzes.score`(배점)만큼 `users_account.point`에 적립한다(비관적 락으로 동시 적립 유실 방지). `users_bq.bq_score`는 레이팅 설계 확정 전이라 건드리지 않는다. 중복 제출은 409 — 동시 요청 race는 `uk_quiz_users_submit_account_quiz` UNIQUE가 최종 중재하고, 그 위반도 500이 아니라 409로 접는다.

**`/today`로 받은 문제는 8분 안에 제출해야 한다(제출 자격 증명, 2026-08-12 신설).** `/today` 응답에 실린 문제마다 서버가 내부적으로 Redis 티켓(TTL 8분)을 발급하고, 제출 시점에 그 티켓이 없으면(=`/today`를 거치지 않았거나 8분이 지났으면) 403 `QUIZ_SUBMIT_NOT_ALLOWED`로 거절한다. **이 티켓·이닝 값은 어떤 응답 필드에도 노출되지 않는다** — 서버 내부 통계·자격 판정용이다. 자세한 판정 순서·복구 경로는 [POST 제출](#post-rtquizzesquizidsubmit) 절 참고.

**좋아요는 "풀어본 사람의 평가"로 좁혀져 있다.** 신호로 이력 행을 쌓지 않고 `(계정, 문제)` 한 행의 플래그로만 관리한다(응원 `oppose` 토글·제출 UNIQUE와 같은 설계 계열). **제출 이력이 좋아요의 선행조건**이라 "존재하지 않는 문제"·"미편성 풀 문제"·"편성됐지만 안 푼 문제" 셋이 요청자 입장에서 같은 상태로 합쳐지고, 거절 응답도 하나(403 `QUIZ_LIKE_NOT_ALLOWED`)로 합쳐진다 — 404가 아닌 이유가 이것이다. 토글이라 멱등이 아니며, 동시 충돌은 500이 아니라 200 + 확정 상태로 흡수된다. `likeCount`는 취소된 행을 제외한 **현재 `liked = true`인 행 수**다.

---

## GET /rt/quizzes/today
> 최종 변경: 2026-08-12 — 응답 상한 20건 신설(`quiz.serve.max-today-count`) + 응답에 실린 문제마다 제출 자격 티켓(TTL 8분) 발급 시작. 응답 필드·상태코드·에러코드는 불변(직전: 2026-08-10 정렬 방식 변경(선호 그룹 안에서 id ASC → 사용자별 고정 랜덤). 요청·응답 필드·상태코드·에러코드는 불변)

오늘(**KST**) 세트 중 **내가 아직 안 푼 문제만** 반환한다 — **이미 제출한 문제는 목록에서 제외된다(정책: 푼 문제 비노출)**. `QuizService.getTodayQuizzes(userAccountId, preferredOnly)` — "오늘"은 항상 서버가 KST 고정 클록으로 판정한다(파드 JVM은 UTC). 다른 날짜를 조회할 방법은 없다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**쿼리 파라미터**

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| preferredOnly | boolean | false | `true`면 선호(응원 구단·선수 매칭) 문제만. **응원 구단도 응원 선수도 없으면 무시되고 전체 반환**(취향 미설정과 "오늘 퀴즈 없음"을 구분하기 위함) |

**응답 200 OK** `ApiResponse<List<QuizResponse>>` — 페이징 없음.

**정렬 규칙**: **선호(preferred) 문제가 항상 비선호보다 먼저 온다.** 랜덤은 각 그룹(선호/비선호) **안에서만** 일어난다 — 선호 문제가 비선호보다 뒤로 밀리는 일은 없다. 그룹 내부 순서는 **사용자별로 고정된 랜덤**이다(계정 id와 퀴즈 id로 결정되는 해시 기반 — 같은 사용자는 몇 번을 호출해도 항상 같은 순서를 받고, 새로고침해도 재배치되지 않는다. 계정이 다르면 순서도 다르다). 해시 충돌(드묾) 시에만 `id` 오름차순으로 보정한다. 문제를 풀어 목록에서 빠져도 남은 문제들의 상대 순서는 보존된다. **클라이언트는 서버가 준 순서를 그대로 표시할 것** — 별도 정렬을 하면 이 의도가 사라진다.

| 필드 | 타입 | 설명 |
|---|---|---|
| data[].id | Long | 퀴즈 식별자. 상세·제출이 이 값으로 지목 |
| data[].type | String | `"객관식"` \| `"O/X"` — FE 렌더링 분기용 |
| data[].question | String | 문제 본문 |
| data[].difficulty | String \| null | `EASY`/`MEDIUM`/`HARD`/`EXPERT`. 사람이 쓴 퀴즈는 null 가능 |
| data[].point | Double \| null | 배점(정답 시 적립될 포인트). null 가능 |
| data[].preferred | boolean | 내 응원 구단·선수 매칭 여부(정렬 근거 그대로) |
| data[].options | array | 보기 배열, `no` 오름차순. `no`(0-기반, **제출 시 보낼 번호**, O/X는 0=`"O"` 1=`"X"`) · `text` |

**정답·근거·대상 FK는 응답에 없다.** 빈 배열은 "오늘 세트 없음"과 "오늘 세트를 다 품" **둘 다**를 뜻한다(에러 아님) — 구분이나 진행률("10문제 중 7개 완료")이 필요하면 [풀이 이력](#get-rtquizzessubmissions)을 병용한다.

**이 응답에는 `liked`·`likeCount`가 없다(2026-08-11 좋아요 기능 추가 후에도 불변).** 좋아요는 제출한 문제에만 허용되는데 `/today`는 이미 제출한 문제를 목록에서 빼고 내려주므로, 이 목록의 모든 항목이 애초에 좋아요 대상이 아니다 — 집계 쿼리를 붙여도 쓰이지 않아 아예 실행하지 않는다.

**응답 개수 상한 20건(2026-08-12 신설).** 위 정렬(선호 우선 + 사용자별 고정 랜덤)이 끝난 목록을 앞에서 잘라 최대 `quiz.serve.max-today-count`(기본 20, env `QUIZ_SERVE_MAX_TODAY_COUNT`)건만 반환한다 — 편성 수(`quiz.serve.daily-count`, 기본 10)와는 별개 설정이며 응답 **필드 집합은 바뀌지 않는다**(20건 이하이면 상한이 아무것도 바꾸지 않는다, `hasMore` 같은 표식도 없다).

**⚠ 이 응답에 실린 문제는 8분 안에 제출해야 한다.** 서버가 각 문제에 대해 내부적으로 제출 자격 티켓(TTL 8분, 응답에는 노출 안 됨)을 발급한다 — 8분을 넘기면 [제출](#post-rtquizzesquizidsubmit)이 403 `QUIZ_SUBMIT_NOT_ALLOWED`로 거절되고 그 문제는 미제출로 남아 다음 `/today` 응답에 다시 실린다. **만료 전에 `/today`를 다시 호출하면 그 문제의 시한이 8분으로 갱신된다** — 이것이 유일한 연장 수단이다(재발급 API·유예 시간 없음). 한 번에 20문제를 받아 오래 붙들면 뒷부분 문제가 대량 403이 될 수 있다 — 화면을 주기적으로 `/today`로 갱신하는 구조를 권장한다.

**실패**: 401 UNAUTHENTICATED 뿐. (Redis 장애 시 티켓 발급이 실패하면 500 — `ApiResponse` 래퍼 없음, 확인 필요)

```bash
curl http://localhost:8081/rt/quizzes/today?preferredOnly=true -H 'Authorization: Bearer eyJ...'
```

---

## GET /rt/quizzes/{quizId}
> 최종 변경: 2026-08-11 — 제출한 문제일 때 `liked`·`likeCount` 필드 추가(미제출이면 두 키 모두 부재). 상태코드·에러코드 변화 없음

단건 상세. `QuizService.getQuiz(userAccountId, quizId)`.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK. `/today` 응답의 `data[].id`(또는 이력의 `quizId`)로 얻는다 |

**응답 200 OK** `ApiResponse<QuizDetailResponse>` — 공통 필드는 `/today`와 동일(id·type·question·difficulty·point·quizDate·options) + 제출 상태:

| 필드 | 타입 | 설명 |
|---|---|---|
| data.submitted | boolean | 내가 이미 제출했는가 |
| data.myOption | int | **제출한 경우에만 존재.** 내가 낸 보기 번호 |
| data.correct | boolean | **제출한 경우에만 존재.** 정오 |
| data.answer | int | **제출한 경우에만 존재.** 정답 보기 번호 |
| data.liked | boolean | **제출한 경우에만 존재.** 내 현재 좋아요 상태 |
| data.likeCount | long | **제출한 경우에만 존재.** 그 문제에 대해 `liked = true`인 행 수(취소한 좋아요는 세지 않음) |

미제출이면 위 다섯 키는 **본문에서 키 자체가 빠진다**(`@JsonInclude(NON_NULL)` — 정답 유출 방지, 테스트로 고정). 좋아요 자체가 제출한 문제에만 허용되므로 미제출 시엔 좋아요 관련 조회조차 하지 않는다(키 부재와 쿼리 부재가 같은 분기).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 무토큰 |
| 404 | QUIZ_NOT_FOUND | 미존재 **또는 미편성 풀 문제**(구분 불가가 의도 — 존재 은닉) |

**예시 — 미제출**

```bash
curl http://localhost:8081/rt/quizzes/23 -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"id":23,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "quizDate":"2026-08-10","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#   "submitted":false},"message":null}
# (myOption·correct·answer·liked·likeCount 키 자체가 없음)
```

**예시 — 제출함**

```bash
curl http://localhost:8081/rt/quizzes/23 -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"id":23,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "quizDate":"2026-08-10","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#   "submitted":true,"myOption":0,"correct":true,"answer":0,
#   "liked":false,"likeCount":5},"message":null}
```

---

## POST /rt/quizzes/{quizId}/submit
> 최종 변경: 2026-08-12 — 403 `QUIZ_SUBMIT_NOT_ALLOWED` 신설(제출 자격 티켓 검사). 판정 순서 404→409→**403**→400으로 확장. 기존 404/409/400의 조건·문구는 불변. 요청·성공 응답 필드는 불변(직전: 2026-08-08 신규)

제출·채점. `QuizSubmitService.submit(userAccountId, quizId, option)` — 검증(404→409→**403**→400, 순서 고정) 후 채점, 정답이면 계정 행을 비관적 락으로 잠그고 `round(score)`만큼 적립, 제출 기록 저장까지 한 트랜잭션.

**⚠ 제출 자격 티켓(2026-08-12 신설, 응답에는 노출 안 됨).** `/today`가 응답에 실은 문제마다 서버 내부에 TTL 8분짜리 제출 자격을 발급해 둔다. 제출 시점에 그 자격이 없으면(=`/today`를 거치지 않고 바로 제출했거나, `/today`로 받은 뒤 8분이 지났으면) 403으로 거절한다 — **두 경우를 응답으로 구분하지 않는다(의도된 설계, 상태코드·본문 문자열 완전히 동일)**. 403을 받은 문제는 제출되지 않은 상태로 남아 다음 `/today` 응답에 다시 실려 나온다 — 다시 받아서 제출하면 된다(재발급 API·유예 시간 없음, 이것이 유일한 복구 경로).

**판정 순서는 404 → 409 → 403 → 400으로 고정이다.** 특히 **이미 제출한 문제를 다시 제출하면 403이 아니라 409다** — 최초 제출 시 서버가 그 티켓을 삭제하므로 재제출 시점엔 티켓이 이미 없지만, 409(선제출) 검사가 403(자격)보다 앞서 있어 응답이 바뀌지 않는다. 클라이언트가 "아직 안 풀었는데 시한을 넘김"(403)과 "이미 풀었음"(409)을 다르게 처리해야 하므로 이 순서가 중요하다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK. `/today` 응답의 `data[].id`(또는 이력의 `quizId`)로 얻는다 |

**요청 본문** `{"option": 0}` — `options[].no`의 값(0-기반). 필수(`@NotNull`).

**응답 200 OK** `ApiResponse<QuizSubmitResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data.correct | boolean | 정오 |
| data.answer | int | 정답 보기 번호(제출했으므로 공개) |
| data.myOption | int | 내가 낸 번호(에코) |
| data.earnedPoint | long | 이번에 적립된 포인트(오답·배점 null이면 0) |
| data.totalPoint | long | 적립 후 보유 포인트 잔액 |

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증) | `option` 누락 — `data`에 필드 오류 맵 |
| 400 | QUIZ_OPTION_NOT_FOUND | 그 문제에 없는 보기 번호(판정 순서상 403 다음) |
| 401 | UNAUTHENTICATED | 무토큰 |
| 403 | **QUIZ_SUBMIT_NOT_ALLOWED**(신규) | 제출 자격 티켓 없음 — `/today` 미경유 또는 발급 후 8분 경과(둘 다 같은 응답, 판정 순서상 409 다음·400 이전) |
| 404 | QUIZ_NOT_FOUND | 미존재·미편성(판정 순서상 가장 먼저) |
| 409 | QUIZ_ALREADY_SUBMITTED | 이미 제출(동시 제출 race의 UNIQUE 위반 포함) — **재제출은 티켓이 없어도 403이 아니라 409**(판정 순서상 404 다음·403 이전) |

```bash
curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":0}'
# {"success":true,"data":{"correct":true,"answer":0,"myOption":0,"earnedPoint":50,"totalPoint":50},"message":null}

curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":0}'
# /today 를 거치지 않았거나 발급된 티켓이 8분을 넘긴 경우
# {"success":false,"data":null,"message":"오늘의 퀴즈로 받은 문제만 제한 시간 안에 제출할 수 있습니다."}
```

---

## GET /rt/quizzes/submissions
> 최종 변경: 2026-08-11 — 각 이력 항목에 `liked`·`likeCount` 필드 추가(항상 포함, 키 부재 케이스 없음). 상태코드·에러코드 변화 없음

내 풀이 이력 + 전체 요약. `QuizSubmitService.getHistory(userAccountId, page)` — 최신 제출 먼저, 페이지 크기 서버 고정 20(채팅 이력과 같은 규약).

**인증 필요.**

**쿼리 파라미터**

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| page | int | 0 | 0-기반 페이지 번호 |

**응답 200 OK** `ApiResponse<QuizSubmissionHistoryResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data.summary.total | long | 전체 제출 수(페이지 아님) |
| data.summary.correctCount | long | 전체 정답 수 |
| data.summary.accuracy | double | 정답률(0.0~1.0). 제출 0건이면 0.0 |
| data.submissions | object | [chat](chat.md)과 동일한 `PageResponse` — content·page·size·totalElements·totalPages·hasNext |
| ...content[] | | quizId · question · type · difficulty · quizDate · myOption · myOptionText · correct · answer · answerText · earnedPoint · submittedAt · **liked** · **likeCount** |

정답 번호·텍스트가 실리는 것은 의도다(제출한 문제의 복기 화면). **`liked`·`likeCount`는 이력 항목 전부에 항상 포함된다**(키 부재 케이스 없음) — 이력 항목은 정의상 전부 제출한 문제라 좋아요가 불가능한 항목이 섞이지 않는다(`/{quizId}` 상세와 달리 `NON_NULL` 처리가 필요 없어 원시 타입). 좋아요 상태 조립은 페이지 항목 수와 무관하게 고정 쿼리 2건(집계 1 + 내 좋아요 1)으로 끝난다(N+1 금지). **실패**: 401뿐 — 이력 0건도 200이다.

```bash
curl "http://localhost:8081/rt/quizzes/submissions?page=0" -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"summary":{"total":12,"correctCount":9,"accuracy":0.75},
#   "submissions":{"content":[{"quizId":23,"question":"...","type":"O/X","difficulty":"EASY",
#     "quizDate":"2026-08-10","myOption":0,"myOptionText":"O","correct":true,"answer":0,
#     "answerText":"O","earnedPoint":50,"submittedAt":"2026-08-10T09:12:03",
#     "liked":true,"likeCount":5}, "..."],
#     "page":0,"size":20,"totalElements":12,"totalPages":1,"hasNext":false}},"message":null}
```

---

## POST /rt/quizzes/{quizId}/like
> 최종 변경: 2026-08-11 — 신규

좋아요 토글. `QuizLikeService.toggle(userAccountId, quizId)` → `QuizLikeToggler.toggleOnce`. **내가 제출한 문제에만 허용**된다 — 대상 계정은 오직 토큰(`@AuthenticationPrincipal Long userAccountId`)에서만 해석하며 요청 본문·경로·쿼리 어디에도 계정 식별자를 받는 자리가 없다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK. `/today` 응답의 `data[].id`(또는 이력의 `quizId`)로 얻는다 |

**요청 본문 없음.**

**동작(토글, 멱등 아님)**: 같은 `(계정, 문제)`에 좋아요 행이 없으면 새로 만들어 `liked=true`로 켠다. 있으면 그 행의 `liked`만 뒤집는다(`true↔false`). **취소해도 행은 삭제되지 않고 `liked=false`로 남는다.** 같은 경로를 연속 호출하면 `liked`가 매번 왕복한다 — 클라이언트는 낙관적으로 뒤집지 말고 **응답의 `liked`를 화면 상태의 정본**으로 삼아야 한다(재시도로 두 번 토글되면 원상 복귀함).

**동시성**: 같은 계정·같은 문제로 최초 좋아요가 동시에 들어와 `uk_quizzes_like_account_quiz` UNIQUE가 충돌해도 **500이 아니라 200 + 그 시점의 확정 상태**를 반환한다(둘 다 "켜기"를 의도했으므로 최종 상태는 `liked=true`). 진 쪽 요청이 다시 토글하지 않고 확정 상태만 읽어 반환한다.

**응답 200 OK** `ApiResponse<QuizLikeResponse>` — 토글 후 확정 상태.

| 필드 | 타입 | 설명 |
|---|---|---|
| data.liked | boolean | 토글 후 요청자의 확정 좋아요 상태 |
| data.likeCount | long | 토글 반영 후 그 문제의 `liked=true` 행 수(취소된 좋아요는 세지 않음, 자기 자신의 이번 변경 포함) |

내부 PK(`id`)는 응답에 노출하지 않는다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 무토큰 |
| 403 | QUIZ_LIKE_NOT_ALLOWED | 요청자에게 그 `quizId`의 제출 이력이 없음 |

**⚠ 403은 미존재·미편성 풀·미제출 세 경우를 구분하지 않는 단일 응답이다(404가 아니다).** 제출이 좋아요의 선행조건이 되는 순간, 요청자 입장에서 "그 문제가 존재하지 않음"과 "존재하지만 아직 안 풀었음"이 같은 상태로 합쳐진다 — 어느 쪽이든 "너는 이 문제에 좋아요할 수 없다"이므로 하나의 403으로 통일해 퀴즈 존재 여부와 내일 출제분(미편성 풀)이 새어 나가지 않게 한다. 세 경우 모두 상태코드·에러코드·메시지·응답 본문 문자열이 완전히 동일하며(테스트로 바이트 단위 고정), `data`는 항상 `null`이다. 판정 순서(문제 존재→편성→제출 이력)는 계약이 아니며 구현은 제출 이력 존재 확인 하나로 세 경우를 한 번에 가른다.

메시지: `"좋아요는 직접 푼 문제에만 할 수 있습니다."`

```bash
curl -X POST http://localhost:8081/rt/quizzes/23/like -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"liked":true,"likeCount":5},"message":null}

curl -X POST http://localhost:8081/rt/quizzes/23/like -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"liked":false,"likeCount":4},"message":null}

# 제출 이력이 없는(또는 존재하지 않는/미편성) quizId
curl -X POST http://localhost:8081/rt/quizzes/999999/like -H 'Authorization: Bearer eyJ...'
# {"success":false,"data":null,"message":"좋아요는 직접 푼 문제에만 할 수 있습니다."}
```

---

## 관련 문서

- [README.md](README.md) — 응답 래퍼·JWT 인증·401 정책은 quiz 모듈 공통.
- [chat.md](chat.md) — 같은 quiz 모듈(포트 8081, `/rt`)의 채팅 도메인. `PageResponse` 규약의 원 출처.
