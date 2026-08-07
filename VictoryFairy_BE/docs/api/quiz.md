# 퀴즈(quiz) API 명세

> **도메인** `quiz` — 오늘의 퀴즈 조회.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/quizzes` · **엔드포인트** 1개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/quiz/controller/QuizController.java` (`@RequestMapping("/quizzes")` — `/rt`는 context-path가 붙인다)
> **최종 갱신** 2026-08-08 — 신규 도메인. `GET /rt/quizzes/today`(오늘의 퀴즈 목록) 추가.
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/quizzes/today](#get-rtquizzestoday) | 200 | 오늘(KST) 출제 퀴즈 목록 |

## 이 도메인의 특이사항

**인증 필수.** quiz 모듈의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다 — [chat](chat.md)과 동일한 규칙에 `/quizzes/**`가 자연히 걸린다(SecurityConfig에 quiz 전용 규칙은 없다). 무토큰 요청은 401 `"인증이 필요합니다."`([README](README.md)의 401 정책).

**정답(answer)은 응답에 없다 — 의도된 계약이다.** `Quiz.answer`는 DB에 있지만 `QuizResponse`가 싣지 않는다. 응답에 실리는 순간 클라이언트 개발자 도구로 바로 보이기 때문으로, 채점은 향후 제출 API가 서버에서 수행한다. 같은 이유로 근거(evidence)·정답률 같은 사후 정보도 없다.

**데이터 출처(참고)**: 퀴즈는 AI 파이프라인이 S3 `quiz-candidates/{date}/`에 떨군 후보를 quiz 앱이 매일 10:30 KST 스케줄러 + 기동 시 1회 적재해 채운다(멱등, 읽기 전용). 이 적재 경로는 API 계약이 아니므로 상세는 다루지 않는다 — "출제분이 없는 날"이 생길 수 있다는 사실만 클라이언트 계약에 영향을 준다(아래 200 + 빈 배열 참고).

---

## GET /rt/quizzes/today
> 최종 변경: 2026-08-08 — 신규 추가

오늘(**KST**) 출제분 퀴즈 목록 조회. `QuizController` → `QuizService.getTodayQuizzes()` → `quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(kstClock))`.

**인증 필요** — `Authorization: Bearer <accessToken>`

**요청**: 없음(경로/쿼리 파라미터·본문 없음). 날짜를 지정하는 파라미터는 없다 — "오늘"은 항상 서버가 KST 고정 클록(`kstClock`)으로 판정한다(파드 JVM은 UTC라 기본 클록이면 자정~09시에 하루가 어긋나는 것을 막은 설계). 클라이언트가 다른 날짜의 출제분을 조회할 방법은 없다.

**응답 200 OK** `ApiResponse<List<QuizResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 퀴즈 배열. 페이징 없음 — 오늘 출제분 전체가 단일 배열로 온다. **`quizzes.id` 오름차순** 고정 |
| data[].id | Long | 퀴즈 식별자(`Quiz` PK). 향후 제출 API가 이 값으로 문제를 지목한다 |
| data[].type | String | 유형명 — `"객관식"` \| `"O/X"`(`quiz_type` 코드 테이블의 `name`). FE 렌더링 분기(선택지 목록 vs O/X 토글)용 |
| data[].question | String | 문제 본문(`Quiz.content`) |
| data[].difficulty | String \| null | `EASY` / `MEDIUM` / `HARD` / `EXPERT`(AI 파이프라인 계약값 그대로, UI 난이도 배지용). **사람이 직접 쓴 퀴즈는 null일 수 있다** |
| data[].point | Double \| null | 배점. difficulty와 같은 이유로 **null일 수 있다** — 소비하는 쪽은 null을 다뤄야 한다 |
| data[].options | array | 보기 배열. **`no` 오름차순** 고정 |
| data[].options[].no | int | 보기 번호(0-기반). 표기 순서이자 **향후 제출 시 보낼 번호**. **O/X 유형은 0=`"O"`, 1=`"X"`** |
| data[].options[].text | String | 보기 내용 |
| message | null | 사용되지 않음 |

**정답(answer)·근거(evidence)는 이 응답에 없다** — 위 특이사항 참고. 출제 대상 메타(구단·선수·경기 FK)도 노출되지 않는다.

**출제분이 없는 날은 200 + 빈 배열이다(에러 아님).** 적재 루틴 미실행·실패로 오늘 자 퀴즈가 없으면:
```json
{"success":true,"data":[],"message":null}
```
클라이언트는 이를 "오늘은 퀴즈가 없어요" 상태로 렌더하면 된다 — 404·500으로 구분할 필요가 없게 에러로 만들지 않았다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 토큰 없음/무효(필터·엔트리포인트 단계) — `{"success":false,"data":null,"message":"인증이 필요합니다."}` |

서비스가 던지는 `BusinessException`이 없어 도메인 에러는 없다(빈 결과도 200이므로).

**예시**
```bash
curl -i http://localhost:8081/rt/quizzes/today \
  -H 'Authorization: Bearer eyJ...'
```
응답(2026-08-08 로컬 실측 — 실제 출제분에 따라 내용·건수는 매일 다르다):
```json
{"success":true,"data":[{"id":1,"type":"객관식","question":"강백호가 FA로 새로 합류한 팀은?","difficulty":"HARD","point":80.0,"options":[{"no":0,"text":"한화"},{"no":1,"text":"KT"},{"no":2,"text":"SSG"},{"no":3,"text":"키움"}]}],"message":null}
```

무토큰 실패 예시(401):
```bash
curl -i http://localhost:8081/rt/quizzes/today
```
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## 제출·채점 API는 아직 없다

이 도메인의 코드에 제출(answer 검증)·채점·정답률 엔드포인트는 없다. `QuizUserSubmit` 엔티티는 domain에 준비돼 있으나 어떤 컨트롤러에서도 쓰이지 않는다. 정답 비노출 계약(`options[].no`가 제출 번호라는 것 포함)은 그 후속 API를 전제로 설계된 것이다.

## 관련 문서

- [README.md](README.md) — 응답 래퍼·JWT 인증·401 정책은 quiz 모듈 공통([chat](chat.md)과 동일한 `web-support` 구현 공유).
- [chat.md](chat.md) — 같은 quiz 모듈(포트 8081, context-path `/rt`)의 채팅 도메인.
