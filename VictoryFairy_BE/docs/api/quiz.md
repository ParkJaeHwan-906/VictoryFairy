# 퀴즈(quiz) API 명세

> **도메인** `quiz` — 오늘의 퀴즈 조회·개별 조회·제출(채점)·풀이 이력.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/quizzes` · **엔드포인트** 4개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/quiz/controller/QuizController.java`(조회), `QuizSubmissionController.java`(제출·이력) — `/rt`는 context-path가 붙인다
> **최종 갱신** 2026-08-08 — 제출/이력/개별 조회 추가, `/today`에 선호(응원 구단·선수) 정렬·필터 추가.
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/quizzes/today](#get-rtquizzestoday) | 200 | 오늘(KST) 세트 목록 — 선호 문제 우선 정렬, `preferredOnly` 필터 |
| GET | [/rt/quizzes/{quizId}](#get-rtquizzesquizid) | 200 | 단건 상세 — 제출 전엔 정답 비노출, 제출 후엔 복기 정보 포함 |
| POST | [/rt/quizzes/{quizId}/submit](#post-rtquizzesquizidsubmit) | 200 | 제출·서버 채점 — 정답이면 포인트 적립 |
| GET | [/rt/quizzes/submissions](#get-rtquizzessubmissions) | 200 | 내 풀이 이력(페이지) + 전체 요약(정답률) |

## 이 도메인의 특이사항

**인증 필수.** quiz 모듈의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다. 무토큰 요청은 전부 401 `"인증이 필요합니다."`([README](README.md)의 401 정책).

**전원 동일 데일리 세트.** `quizzes.quiz_date`는 **출제일**이다(생성일 아님). 시효성 없는 문제(역대기록형)는 `quiz_date=NULL` 풀에 쌓였다가 매일 편성 잡이 세트 부족분(기본 10문항, `quiz.serve.daily-count`)을 오래된 것부터 채운다. 경기 문항(gameId 귀속)만 그 경기 날짜에 고정. 모든 사용자가 같은 날 같은 세트를 받는다 — 레이팅(도입 예정)의 점수 비교 전제다. **미편성 풀 문제는 어떤 API로도 보이지 않는다**(단건 조회·제출 모두 404 — id 순회로 내일 출제분을 미리 보는 것을 막는다).

**선호(응원 구단·선수)는 세트 구성이 아니라 노출 방식에 반영된다.** `/today`는 내 응원 구단(문제의 대상·상대 구단 일치) 또는 응원 선수(대상 선수 일치) 문제를 앞에 배치하고 `preferred` 플래그로 표시한다. `preferredOnly=true`는 그것만 남긴다(응원 정보가 하나도 없으면 no-op으로 전체 반환). 세트 자체는 전원 동일하므로 필터를 써도 레이팅 공정성이 깨지지 않는다.

**정답(answer)은 제출 전엔 어떤 응답에도 없다 — 제출 후에만 공개된다.** 조회 응답(`/today`, 미제출 상세)에는 `answer` 키 자체가 없다(클라이언트 개발자 도구 노출 방지, 테스트로 고정). 제출 응답과 제출 후 상세·이력에는 정답이 실린다(복기 화면 전제).

**채점·적립은 서버 트랜잭션 안에서 원자적이다.** 정답이면 `quizzes.score`(배점)만큼 `users_account.point`에 적립한다(비관적 락으로 동시 적립 유실 방지). `users_bq.bq_score`는 레이팅 설계 확정 전이라 건드리지 않는다. 중복 제출은 409 — 동시 요청 race는 `uk_quiz_users_submit_account_quiz` UNIQUE가 최종 중재하고, 그 위반도 500이 아니라 409로 접는다.

---

## GET /rt/quizzes/today
> 최종 변경: 2026-08-08 — 선호 정렬·`preferred` 필드·`preferredOnly` 파라미터 추가

오늘(**KST**) 세트 중 **내가 아직 안 푼 문제만** 반환한다 — **이미 제출한 문제는 목록에서 제외된다(정책: 푼 문제 비노출)**. `QuizService.getTodayQuizzes(userAccountId, preferredOnly)` — "오늘"은 항상 서버가 KST 고정 클록으로 판정한다(파드 JVM은 UTC). 다른 날짜를 조회할 방법은 없다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**요청** — 쿼리 파라미터

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| preferredOnly | boolean | false | `true`면 선호(응원 구단·선수 매칭) 문제만. **응원 구단도 응원 선수도 없으면 무시되고 전체 반환**(취향 미설정과 "오늘 퀴즈 없음"을 구분하기 위함) |

**응답 200 OK** `ApiResponse<List<QuizResponse>>` — 정렬: **선호 먼저, 그 안에서 `id` 오름차순**. 페이징 없음.

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

**실패**: 401 UNAUTHENTICATED 뿐.

```bash
curl http://localhost:8081/rt/quizzes/today?preferredOnly=true -H 'Authorization: Bearer eyJ...'
```

---

## GET /rt/quizzes/{quizId}
> 최종 변경: 2026-08-08 — 신규

단건 상세. `QuizService.getQuiz(userAccountId, quizId)`.

**인증 필요.** **응답 200 OK** `ApiResponse<QuizDetailResponse>` — 공통 필드는 `/today`와 동일(id·type·question·difficulty·point·quizDate·options) + 제출 상태:

| 필드 | 타입 | 설명 |
|---|---|---|
| data.submitted | boolean | 내가 이미 제출했는가 |
| data.myOption | int | **제출한 경우에만 존재.** 내가 낸 보기 번호 |
| data.correct | boolean | **제출한 경우에만 존재.** 정오 |
| data.answer | int | **제출한 경우에만 존재.** 정답 보기 번호 |

미제출이면 위 세 키는 **본문에서 키 자체가 빠진다**(`@JsonInclude(NON_NULL)` — 정답 유출 방지, 테스트로 고정).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 무토큰 |
| 404 | QUIZ_NOT_FOUND | 미존재 **또는 미편성 풀 문제**(구분 불가가 의도 — 존재 은닉) |

---

## POST /rt/quizzes/{quizId}/submit
> 최종 변경: 2026-08-08 — 신규

제출·채점. `QuizSubmitService.submit(userAccountId, quizId, option)` — 검증(404→409→400) 후 채점, 정답이면 계정 행을 비관적 락으로 잠그고 `round(score)`만큼 적립, 제출 기록 저장까지 한 트랜잭션.

**인증 필요.** **요청 본문** `{"option": 0}` — `options[].no`의 값(0-기반). 필수(`@NotNull`).

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
| 400 | QUIZ_OPTION_NOT_FOUND | 그 문제에 없는 보기 번호 |
| 401 | UNAUTHENTICATED | 무토큰 |
| 404 | QUIZ_NOT_FOUND | 미존재·미편성 |
| 409 | QUIZ_ALREADY_SUBMITTED | 이미 제출(동시 제출 race의 UNIQUE 위반 포함) |

```bash
curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":0}'
# {"success":true,"data":{"correct":true,"answer":0,"myOption":0,"earnedPoint":50,"totalPoint":50},"message":null}
```

---

## GET /rt/quizzes/submissions
> 최종 변경: 2026-08-08 — 신규

내 풀이 이력 + 전체 요약. `QuizSubmitService.getHistory(userAccountId, page)` — 최신 제출 먼저, 페이지 크기 서버 고정 20(채팅 이력과 같은 규약).

**인증 필요.** **요청**: `?page=0`(0-기반, 기본 0).

**응답 200 OK** `ApiResponse<QuizSubmissionHistoryResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data.summary.total | long | 전체 제출 수(페이지 아님) |
| data.summary.correctCount | long | 전체 정답 수 |
| data.summary.accuracy | double | 정답률(0.0~1.0). 제출 0건이면 0.0 |
| data.submissions | object | [chat](chat.md)과 동일한 `PageResponse` — content·page·size·totalElements·totalPages·hasNext |
| ...content[] | | quizId · question · type · difficulty · quizDate · myOption · myOptionText · correct · answer · answerText · earnedPoint · submittedAt |

정답 번호·텍스트가 실리는 것은 의도다(제출한 문제의 복기 화면). **실패**: 401뿐 — 이력 0건도 200이다.

---

## 관련 문서

- [README.md](README.md) — 응답 래퍼·JWT 인증·401 정책은 quiz 모듈 공통.
- [chat.md](chat.md) — 같은 quiz 모듈(포트 8081, `/rt`)의 채팅 도메인. `PageResponse` 규약의 원 출처.
