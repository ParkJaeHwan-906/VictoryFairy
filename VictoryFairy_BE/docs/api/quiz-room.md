# 퀴즈방(quiz-room) API 명세

> 소속 모듈 `quiz` · 경로 접두사 `/rt/quiz-rooms` (`server.servlet.context-path` = `/rt`) · 엔드포인트 9개 (SSE 1개 포함)
> 최종 갱신: 2026-10-14 (신규 도메인 — 사용자 정의 퀴즈방 9개 엔드포인트 신설)
> 운영 base URL: `https://victoryfairy.com/rt`
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md) 참고.
> 대상 컨트롤러: `quiz/src/main/java/com/skhynix/quiz/quizroom/controller/QuizRoomController.java` (+ `dto/` 패키지)
> 계약 원본: `docs/requirements/quiz/custom-quiz-room.md` (QUIZ-CQR-*, 개정 1차 2026-10-14) — 참고용이며 이 문서는 코드 기준이다. 어긋난 점은 아래 "요구사항과 어긋난 점" 참고.
> 도메인 이름: 코드 패키지 `com.skhynix.quiz.quizroom`에 맞춰 파일명을 `quiz-room.md`로 둔다. 기존 오늘의 퀴즈(`/rt/quizzes`, [quiz.md](quiz.md))와는 **별개 도메인**이다(문항·풀이 기록·보상 모두 분리).
> 상태: 2026-10-14 기준 **미커밋 worktree(`be-quiz-room`) 코드**를 기준으로 쓴 명세다. 머지·배포 전이다.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| POST | [/rt/quiz-rooms](#post-rtquiz-rooms) | **201** | 퀴즈방 생성 |
| GET | [/rt/quiz-rooms](#get-rtquiz-rooms) | 200 | 전체 목록(페이지, 최신순) |
| GET | [/rt/quiz-rooms/popular](#get-rtquiz-roomspopular) | 200 | 인기 top5 = 가장 최근 확정된 주간 베스트 |
| GET | [/rt/quiz-rooms/{roomId}](#get-rtquiz-roomsroomid) | 200 | 상세 |
| GET | [/rt/quiz-rooms/{roomId}/quizzes](#get-rtquiz-roomsroomidquizzes) | 200 | 풀이용 문항 목록(정답 미노출) |
| POST | [/rt/quiz-rooms/{roomId}/quizzes/{quizId}/answer](#post-rtquiz-roomsroomidquizzesquizidanswer) | 200 | 문항별 답 제출(마지막 답에 결과 동봉) |
| PUT | [/rt/quiz-rooms/{roomId}/like](#put-rtquiz-roomsroomidlike) | 200 | 추천 상태 지정(멱등) |
| GET | [/rt/quiz-rooms/{roomId}/subscribe](#get-rtquiz-roomsroomidsubscribe-sse) | 200 (SSE) | 실시간 구독(presence·votes) |
| DELETE | [/rt/quiz-rooms/{roomId}/subscribe](#delete-rtquiz-roomsroomidsubscribe) | 200 | 명시적 퇴장 |

## 이 도메인의 특이사항

- **9개 전부 인증 필수.** `SecurityConfig` permitAll은 `/`, `/error`, `GET /actuator/health/**`뿐이고 나머지는 `anyRequest().authenticated()`. 인증 실패는 401 `UNAUTHENTICATED`([README](README.md) 401 정책). 이번 변경으로 `SecurityConfig`에 `DispatcherType.ASYNC`·`ERROR` permitAll이 추가됐지만(서버가 SSE를 종료할 때의 재디스패치 인가 오류 방지) 외부 계약과는 무관하다.
- **응답은 SSE 구독(GET)을 뺀 8개가 `ApiResponse<T>`**(`{success,data,message}`)다. 생성만 상태코드가 **201**, 나머지 성공은 200.
- **풀이는 첫 답을 제출하면서 시작된다.** 상세·퀴즈 리스트·구독은 어떤 행도 만들지 않는다. 풀이 이력 조회 API는 없다 — 마지막 답의 응답(`result`)이 결과를 받는 **유일한 기회**다(재조회 불가).
- **본인 방 제약 없음**: 작성자도 자기 방을 풀 수 있고 추천할 수 있다(응답 `isAuthor`로 구분만). 단 주간 베스트 점수에서는 작성자 본인의 추천·풀이가 빠진다(아래 "주간 베스트").
- **식별자**: 작성자는 `{nickname, profileImgUrl}` 두 키로만 나간다(계정 id·uid 미노출). `profileImgUrl`은 BaseURL 없는 EP([README](README.md) 규칙).
- **`createdAt`**: 오프셋 없는 `LocalDateTime`(KST 기준 벽시계), 예 `2026-10-14T14:03:21`.
- **`solvingCount`(풀이 중 인원)**: SSE `presence`와 같은 Redis 집계(전 파드 합산, 고유 계정 수)를 읽는다. **Redis 장애 시 0으로 채워 200을 유지**하므로 "0명"과 "못 읽음"을 응답으로 구별할 수 없다.
- **`likeCount`는 누적 추천 수**다. 퀴즈방 풀이는 `quiz_users_submit`·`quizzes_like`·Redis `quiz:votes:*`·point·bq를 건드리지 않고, `/rt/quizzes/today`에도 나오지 않는다.
- 400 본문 형식은 공유 `GlobalExceptionHandler`를 따른다.
  - 본문 검증(`@Valid`) 실패: `{"success":false,"data":{"<위반 키>":"<메시지>"},"message":"입력값이 올바르지 않습니다."}`. 키당 메시지는 하나(같은 키에 위반이 겹치면 하나만 남는다). **위반한 키만** 담긴다.
  - `page`·`size` 위반: 같은 모양, 키가 파라미터명.
  - 깨진 JSON·본문 없음 → 400 `"요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."`, `roomId` 등 경로 변수에 숫자가 아닌 값 → 400 `"요청 파라미터 형식이 올바르지 않습니다: <이름>"`. ([README 1-1](README.md))
- 컨트롤러 안에 `BusinessException` 전용 핸들러가 있어, **구독(`Accept: text/event-stream`)에서도 오류가 JSON 래퍼 본문**(`Content-Type: application/json`)으로 나간다(공유 핸들러는 내용 협상에서 걸려 500이 될 수 있어 이 컨트롤러가 직접 막는다).

### 공통 객체

**`AuthorResponse`**
| 필드 | 타입 | 설명 |
|---|---|---|
| nickname | String | 작성자 닉네임 |
| profileImgUrl | String? | EP. 없으면 `null`(키는 실림) |

**`RoomSummaryResponse`** (전체 목록·인기 목록 항목)
| 필드 | 타입 | 설명 |
|---|---|---|
| roomId | Long | 퀴즈방 id |
| title | String | 제목 |
| description | String | 소개 |
| author | AuthorResponse | 작성자 |
| quizCount | int | 문항 수 |
| likeCount | long | 추천 수(응답 시점 값) |
| solvingCount | long | 풀이 중 인원(응답 시점 값) |
| createdAt | LocalDateTime | 생성 시각 |

**`RoomDetailResponse`** = `RoomSummaryResponse` 8필드 + 요청자 기준 2필드
| 필드 | 타입 | 설명 |
|---|---|---|
| liked | boolean | 내가 추천 중인가 |
| isAuthor | boolean | 내가 작성자인가(JSON 키는 정확히 `isAuthor`) |

**`QuizRoomOptionResponse`** `{no: int, text: String}` — `no`는 **0부터** 시작하는 보기 번호.

---

## POST /rt/quiz-rooms
> 최종 변경: 2026-10-14 — 신규 추가

퀴즈방을 만든다. 방·문항·보기를 **한 트랜잭션**에 저장한다(하나라도 실패하면 아무것도 남지 않는다). 값은 다듬지 않고(앞뒤 공백 포함) 받은 그대로 저장하며, 길이는 `String.length()` 기준이다. 문항의 표시 순서는 요청 배열 순서다.

**인증** 필요

**요청 본문** `CreateQuizRoomRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| title | String | `@NotBlank` `@Size(max=50)` | 제목 |
| description | String | `@NotBlank` `@Size(max=500)` | 소개 |
| quizzes | List\<QuizItem\> | `@NotEmpty` `@Size(max=20)`, 원소 `@NotNull` `@Valid` | 문항 1~20개 |

`QuizItem`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| type | String | `@NotNull` `@Pattern("객관식\|O/X")` | 문항 유형. 정확히 `객관식` 또는 `O/X` |
| question | String | `@NotBlank` `@Size(max=200)` | 문제 |
| options | List\<String\>? | 유형별 검사(아래) | 객관식 보기. **`O/X`는 이 값을 무시**하고 `O`·`X` 두 개로 저장한다 |
| answer | Integer | `@NotNull` + 범위 검사(아래) | 정답 보기 **번호(0부터)** |

유형별 검사(`@AssertTrue`, 위반 키는 `quizzes[i].optionsValid` / `quizzes[i].answerInRange`):
- `optionsValid` — 객관식일 때만: `options`가 null이 아니고 **2~4개**, 각 항목이 비어 있지 않고(공백만 불가) **100자 이하**. 메시지 `"객관식 보기는 2~4개, 각 1~100자여야 합니다."`
- `answerInRange` — `0 <= answer < 보기 수`(객관식은 `options.size()`, O/X는 2 → 0 또는 1). 메시지 `"정답은 보기 번호 범위 안이어야 합니다."` 유형·정답이 null이거나 객관식 보기가 이미 틀렸으면 이 키는 **잡지 않는다**(각각 `type`·`answer`·`optionsValid` 키가 잡는다).

**응답 201** `ApiResponse<CreateQuizRoomResponse>`
| 필드 | 타입 | 설명 |
|---|---|---|
| roomId | Long | 생성된 방 id |

```json
{"success":true,"data":{"roomId":12},"message":null}
```

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | 본문 검증 실패. `data` 키 예: `title`, `description`, `quizzes`(개수 위반·비어 있음), `quizzes[0]`(null 원소, `"문항이 비어 있습니다."`), `quizzes[0].type`, `quizzes[0].question`, `quizzes[0].answer`, `quizzes[0].optionsValid`, `quizzes[0].answerInRange` |
| 400 | - | 깨진 JSON·본문 없음(`message`가 "요청 본문을 읽을 수 없습니다…", `data:null`) |
| 401 | UNAUTHENTICATED | 인증 실패 |

400 예(21개 문항 중 첫 문항 보기 1개, 둘째 문항 정답이 범위 밖):
```json
{"success":false,"data":{"quizzes[0].optionsValid":"객관식 보기는 2~4개, 각 1~100자여야 합니다.","quizzes[1].answerInRange":"정답은 보기 번호 범위 안이어야 합니다."},"message":"입력값이 올바르지 않습니다."}
```

**예시**
```bash
curl -X POST https://victoryfairy.com/rt/quiz-rooms \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"title":"KBO 상식","description":"가볍게 풀어 보세요","quizzes":[
        {"type":"객관식","question":"2024 우승팀은?","options":["KIA","삼성","LG"],"answer":0},
        {"type":"O/X","question":"한 팀은 9명이 뛴다","answer":0}]}'
```

---

## GET /rt/quiz-rooms
> 최종 변경: 2026-10-14 — 신규 추가

전체 퀴즈방 목록. **최신순**(id 내림차순). 인기 방도 여기서 빠지지 않는다(중복 제거는 프론트 몫).

**인증** 필요

**쿼리 파라미터**
| 이름 | 타입 | 기본값 | 제약 | 설명 |
|---|---|---|---|---|
| page | int | 0 | `@Min(0)` | 0부터 시작하는 페이지 |
| size | int | 20 | `@Min(1)` `@Max(50)` | 페이지 크기 |

**응답 200** `ApiResponse<PageResponse<RoomSummaryResponse>>`
| 필드 | 타입 | 설명 |
|---|---|---|
| content | RoomSummaryResponse[] | 항목([공통 객체](#공통-객체)) |
| page | int | 현재 페이지(0부터) |
| size | int | 요청한 페이지 크기 |
| totalElements | long | 전체 방 수 |
| totalPages | int | 전체 페이지 수 |
| hasNext | boolean | 다음 페이지 존재 |

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `page<0`·`size<1`·`size>50`. `data` = `{"page":"page는 0 이상이어야 합니다."}` / `{"size":"size는 1 이상이어야 합니다."}` / `{"size":"size는 50 이하여야 합니다."}` |
| 400 | - | `page=abc` 등 타입 불일치 |
| 401 | UNAUTHENTICATED | 인증 실패 |

**예시**
```bash
curl 'https://victoryfairy.com/rt/quiz-rooms?page=0&size=20' -H 'Authorization: Bearer <accessToken>'
```

---

## GET /rt/quiz-rooms/popular
> 최종 변경: 2026-10-14 — 신규 추가

인기 퀴즈방 top5. **요청마다 계산하지 않는다** — 가장 최근에 **확정된 주간 베스트**를 확정 순위 순서로 그대로 돌려준다([주간 베스트](#주간-베스트-확정과-보상) 참고).

- 다음 확정 전까지 **구성과 순서는 고정**이다. `likeCount`·`solvingCount`만 응답 시점 값이라, 숫자 크기와 순서가 어긋나 보일 수 있다(계약).
- **확정된 주가 한 번도 없거나, 확정된 주에 순위 대상 방이 없으면 `data: []`**(200). 페이징·순위/주차 필드 없음.
- 항목 수는 0~5.

**인증** 필요 · 파라미터 없음

**응답 200** `ApiResponse<RoomSummaryResponse[]>` (항목은 [공통 객체](#공통-객체)와 같다)

**실패** 401 UNAUTHENTICATED 외 없음.

**예시**
```bash
curl https://victoryfairy.com/rt/quiz-rooms/popular -H 'Authorization: Bearer <accessToken>'
```

---

## GET /rt/quiz-rooms/{roomId}
> 최종 변경: 2026-10-14 — 신규 추가

퀴즈방 상세. 요청자 기준의 `liked`·`isAuthor`가 포함된다.

**인증** 필요

**경로 변수** `roomId`(long)

**응답 200** `ApiResponse<RoomDetailResponse>` ([공통 객체](#공통-객체))
```json
{"success":true,"data":{"roomId":12,"title":"KBO 상식","description":"가볍게 풀어 보세요","author":{"nickname":"곰","profileImgUrl":null},"quizCount":2,"likeCount":3,"solvingCount":1,"createdAt":"2026-10-14T14:03:21","liked":false,"isAuthor":true},"message":null}
```

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `roomId` 숫자 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | QUIZ_ROOM_NOT_FOUND | 방 없음 (`"존재하지 않는 퀴즈방입니다."`) |

**예시**
```bash
curl https://victoryfairy.com/rt/quiz-rooms/12 -H 'Authorization: Bearer <accessToken>'
```

---

## GET /rt/quiz-rooms/{roomId}/quizzes
> 최종 변경: 2026-10-14 — 신규 추가

풀이용 문항 목록. **정답과 투표 분포 키가 없다** — 이미 다 푼 계정이 요청해도 같다. 문항은 생성(= 요청 배열) 순서, 보기는 `no` 오름차순이다. 내가 어디까지 풀었는지도 알려 주지 않는다(`answeredCount`는 답 제출 응답에서만).

**인증** 필요 · **경로 변수** `roomId`(long)

**응답 200** `ApiResponse<RoomQuizResponse[]>`
| 필드 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 문항 id(답 제출 경로의 `{quizId}`) |
| type | String | `객관식` 또는 `O/X` |
| question | String | 문제 |
| options | QuizRoomOptionResponse[] | `{no, text}`. O/X는 `[{no:0,text:"O"},{no:1,text:"X"}]` |

```json
{"success":true,"data":[{"quizId":31,"type":"객관식","question":"2024 우승팀은?","options":[{"no":0,"text":"KIA"},{"no":1,"text":"삼성"},{"no":2,"text":"LG"}]},{"quizId":32,"type":"O/X","question":"한 팀은 9명이 뛴다","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}]}],"message":null}
```

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `roomId` 숫자 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | QUIZ_ROOM_NOT_FOUND | 방 없음 |

**예시**
```bash
curl https://victoryfairy.com/rt/quiz-rooms/12/quizzes -H 'Authorization: Bearer <accessToken>'
```

---

## POST /rt/quiz-rooms/{roomId}/quizzes/{quizId}/answer
> 최종 변경: 2026-10-14 — 신규 추가

한 문항의 답을 제출한다. 문항당 **1회**(정정·재제출 불가, 같은 문항 재시도는 409). 문항 순서·건너뛰기 제한은 없다. 이 방의 문항 수만큼 답이 쌓이는 **마지막 제출의 응답에만 `result`가 채워지고**, 그 전에는 `result: null`(키는 실림)이다. 제출 성공마다 그 방의 **모든 SSE 구독자**에게 해당 문항의 `votes` 이벤트가 나간다(제출자 본인·아직 안 푼 구독자 포함, 전달 실패는 응답에 영향 없음).

**인증** 필요

**경로 변수** `roomId`(long), `quizId`(long)

**요청 본문** `AnswerRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| option | Integer | (검증 애너테이션 없음) | 고른 보기 번호(0부터). 누락·`null`·존재하지 않는 번호는 모두 400 `QUIZ_OPTION_NOT_FOUND` |

> `@NotNull`을 일부러 쓰지 않는다 — 검사 순서가 **404(방) → 404(문항) → 400(보기) → 409(중복)** 로 고정이라, 없는 방에 빈 본문을 보내면 400이 아니라 404가 나온다. 본문 자체가 없으면(`Content-Type` 있는 빈 본문 포함) 이보다 앞서 400(본문 읽기 실패)이다.

**응답 200** `ApiResponse<AnswerResponse>`
| 필드 | 타입 | 설명 |
|---|---|---|
| answeredCount | long | 이 계정이 이 방에서 답한 문항 수(이번 제출 포함) |
| total | int | 방의 문항 수 |
| result | QuizRoomResultResponse? | 마지막 답(`answeredCount >= total`)일 때만. 그 전엔 `null` |

`QuizRoomResultResponse`
| 필드 | 타입 | 설명 |
|---|---|---|
| correctCount | int | 정답 문항 수 |
| total | int | 문항 수 |
| quizzes | ResultQuizResponse[] | 문항 생성 순서 |

`ResultQuizResponse`
| 필드 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 문항 id |
| type | String | `객관식`/`O/X` |
| question | String | 문제 |
| options | QuizRoomOptionResponse[] | `{no, text}` |
| myOption | int | 내가 고른 번호 |
| answer | int | 정답 번호 |
| correct | boolean | `myOption == answer` |

중간 제출 응답:
```json
{"success":true,"data":{"answeredCount":1,"total":2,"result":null},"message":null}
```
마지막 제출 응답:
```json
{"success":true,"data":{"answeredCount":2,"total":2,"result":{"correctCount":1,"total":2,"quizzes":[{"quizId":31,"type":"객관식","question":"2024 우승팀은?","options":[{"no":0,"text":"KIA"},{"no":1,"text":"삼성"},{"no":2,"text":"LG"}],"myOption":0,"answer":0,"correct":true},{"quizId":32,"type":"O/X","question":"한 팀은 9명이 뛴다","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],"myOption":1,"answer":0,"correct":false}]}},"message":null}
```

**부수 효과 없음**: point·bq 적립, `quiz_users_submit`·`quizzes_like` 기록, Redis `quiz:votes:*` 갱신은 일어나지 않는다. 같은 계정의 동시 제출은 서버가 계정 행 락으로 줄 세우므로, 마지막 두 문항을 동시에 내도 어느 한 응답에는 반드시 `result`가 실린다.

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | QUIZ_OPTION_NOT_FOUND | `option` 누락/`null`/그 문항에 없는 번호 (`"존재하지 않는 보기 번호입니다."`) |
| 400 | - | 본문 없음·깨진 JSON·`option`이 정수 아님, `roomId`/`quizId` 숫자 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | QUIZ_ROOM_NOT_FOUND | 방 없음 |
| 404 | QUIZ_NOT_FOUND | 그 방의 문항이 아님(다른 방의 quizId 포함) (`"존재하지 않는 퀴즈입니다."`) |
| 409 | QUIZ_ALREADY_SUBMITTED | 이미 이 문항에 답함 (`"이미 제출한 퀴즈입니다."`) |

**예시**
```bash
curl -X POST https://victoryfairy.com/rt/quiz-rooms/12/quizzes/31/answer \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"option":0}'
```

---

## PUT /rt/quiz-rooms/{roomId}/like
> 최종 변경: 2026-10-14 — 신규 추가

추천 상태를 **지정**한다(토글이 아님 — 같은 값을 다시 보내도 아무것도 바뀌지 않는다). 풀이 여부·작성자 여부는 보지 않는다. 같은 방의 추천 변경은 방 행 락으로 직렬화되므로 동시 추천이 유실되거나 카운터가 이중 증가하지 않는다.

**인증** 필요 · **경로 변수** `roomId`(long)

**요청 본문** `LikeRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| liked | Boolean | `@NotNull` | `true` 추천, `false` 해제 |

**응답 200** `ApiResponse<QuizRoomLikeResponse>`
| 필드 | 타입 | 설명 |
|---|---|---|
| liked | boolean | 요청한 상태(= 처리 후 상태) |
| likeCount | long | 처리 후 방의 추천 수 |

```json
{"success":true,"data":{"liked":true,"likeCount":4},"message":null}
```

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `liked` 누락 → `data` = `{"liked":"liked는 필수입니다."}`; 본문 없음·`liked`가 불리언 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | QUIZ_ROOM_NOT_FOUND | 방 없음 |

**예시**
```bash
curl -X PUT https://victoryfairy.com/rt/quiz-rooms/12/like \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"liked":true}'
```

---

## GET /rt/quiz-rooms/{roomId}/subscribe (SSE)
> 최종 변경: 2026-10-14 — 신규 추가

방 실시간 구독. `text/event-stream`. 프레임 규약은 아래 "SSE 이벤트" 절.

**인증** 필요 (표준 `EventSource`는 `Authorization` 헤더를 못 실어 401이다 → fetch 기반 SSE 폴리필 사용) · **경로 변수** `roomId`(long)

**응답 200** `text/event-stream` (`ApiResponse` 래퍼 아님). 연결 타임아웃 **30분**(서버가 종료) — 이후 재구독.

**구독 규칙**
- **같은 계정의 퀴즈방 구독은 동시에 하나**(last-one-wins): 새로 구독하면 이 계정의 **다른 퀴즈방(같은 방의 이전 구독 포함) 구독은 서버가 닫는다**(다른 파드 포함). 구단 채팅(`/rt/chat`) 구독과는 서로 영향이 없다.
- 구독 직후 서버가 `presence` 1건 → 문항마다 `votes` 1건을 순서대로 보낸다. `Last-Event-ID` 복구는 **없다**(어느 프레임에도 `id:`가 없음) — 재접속하면 위 스냅샷이 다시 와서 상태가 수렴한다.

**실패** (스트림이 열리기 **전**에 JSON 본문 `{"success":false,"data":null,"message":...}`로 나간다. `Accept: text/event-stream`이어도 500으로 둔갑하지 않는다)
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | `roomId` 숫자 아님 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 404 | QUIZ_ROOM_NOT_FOUND | 방 없음(구독은 등록되지 않는다) |

**예시**
```bash
curl -N https://victoryfairy.com/rt/quiz-rooms/12/subscribe \
  -H 'Authorization: Bearer <accessToken>' -H 'Accept: text/event-stream'
```

---

## DELETE /rt/quiz-rooms/{roomId}/subscribe
> 최종 변경: 2026-10-14 — 신규 추가

명시적 퇴장. 이 계정의 **그 방** 구독을 닫고(풀이 중 인원에서 빠짐) 다른 파드의 구독에도 종료 명령을 발행한다. **전면 멱등** — 구독이 없어도, 방이 없어도 200이다. 명령 발행이 실패해도 200(다른 파드 구독은 하트비트 만료 창이 회수한다).

**인증** 필요 · **경로 변수** `roomId`(long)

**응답 200** `ApiResponse<Void>` — `{"success":true,"data":null,"message":null}`

**실패** 401 UNAUTHENTICATED, 400(경로 변수 형식) 외 없음.

**예시**
```bash
curl -X DELETE https://victoryfairy.com/rt/quiz-rooms/12/subscribe -H 'Authorization: Bearer <accessToken>'
```

---

## SSE 이벤트 (`GET .../subscribe`)

| 프레임 | 형태 | `id:` | 설명 |
|---|---|---|---|
| `event: presence` | `data: {"count":N}` (N = long) | 없음 | 풀이 중 인원 |
| `event: votes` | `data: {"quizId":n,"options":[{"no":0,"count":3},...]}` | 없음 | 한 문항의 보기별 투표 수 |
| 주석 `:ping` | 주석 | - | 15초마다 하트비트 |

```
event: presence
data: {"count":3}

event: votes
data: {"quizId":31,"options":[{"no":0,"count":2},{"no":1,"count":1},{"no":2,"count":0}]}

event: votes
data: {"quizId":32,"options":[{"no":0,"count":0},{"no":1,"count":0}]}

:ping
```

**`presence`**
- `count` = 그 방을 구독 중인 **고유 계정 수**(같은 계정이 여러 탭·여러 파드여도 1, 본인 포함). Redis 공유 집계라 전 파드 합산이며, REST의 `solvingCount`와 같은 정의다.
- 구독 성립 직후 **1회**, 이후 **값이 바뀐 것을 감지했을 때만** 보낸다(기본 2초 주기 점검, 같은 값이면 프레임 없음).
- 퇴장 요청 없이 끊긴 구독은 **하트비트 갱신이 45초 이상 끊기면** 인원에서 빠진다.
- **Redis 장애 시** 그 주기의 전송을 건너뛴다(대체 값 없음, 스트림도 닫지 않음). 구독 직후 첫 값을 못 보냈다면 복구 후 첫 점검에서 보낸다.

**`votes`**
- `options`는 **0표 보기까지 전부**, `no` 오름차순. 값은 DB에 저장된 답을 센 것이다(오늘의 퀴즈 Redis 집계와 무관).
- 구독 직후 **문항마다 1건씩**(문항 생성 순서) 스냅샷이 오고, 이후 누구든 그 방의 어떤 문항에 답을 **커밋하면 그 문항 1건**이 방의 모든 구독자에게 온다(아직 안 푼 구독자 포함 — 풀이 화면에서는 분포를 숨기는 것은 프론트 몫).
- REST 응답(목록·상세·퀴즈 리스트·답 제출)에는 분포가 실리지 않는다. SSE가 유일한 경로다.

---

## 주간 베스트 확정과 보상

`GET /rt/quiz-rooms/popular`가 돌려주는 목록의 출처이자, 방 소유자에게 point를 주는 정책이다. 호출하는 API는 없고 서버 스케줄러가 한다.

- **주 경계**: KST 월요일 00:00:00 ~ 다음 월요일 00:00:00 직전. 일요일 23:59:59 KST의 추천·풀이는 지난주, 월요일 00:00:00 KST의 것은 새 주다.
- **갱신(확정) 시점**: 매주 **월요일 KST**. 크론 `0 1 * * * MON`(zone `Asia/Seoul`) = 월요일 **00:01을 시작으로 매시 01분**마다 실행한다(첫 실행이 정상 경로이고 이후 실행은 DB 장애 등으로 롤백됐을 때의 재시도). 서버 **기동 시에도 1회** 실행해 파드가 없던 주를 메운다. 설정으로 바꿀 수 있다(`QUIZ_ROOM_WEEKLY_BEST_CRON`, `QUIZ_ROOM_WEEKLY_BEST_ON_STARTUP`).
- **확정 대상은 언제나 "바로 앞 주" 하나**다. 2주 이상 밀린 주는 소급 확정하지 않는다. 이미 확정된 주면 아무것도 하지 않는다(멱등). 여러 인스턴스가 동시에 돌아도 주 단위 UNIQUE로 하나만 남고 나머지는 지급까지 롤백된다.
- **점수** = 그 주 안에 생긴 **추천 수**(확정 시점에 취소되지 않은 것) + 그 주 안에 그 방에 **처음 답한 고유 계정 수**(이전 주에 이미 푼 적 있는 계정은 제외). **작성자 본인의 추천·풀이는 점수에서 제외**된다. 점수 0인 방은 제외, 점수 내림차순·동점은 `roomId` 내림차순(최신 방 우선), **최대 5개**.
- **보상**: 순위 방마다 **방 소유자에게 `users_account.point` 500**을 지급한다(방 단위 — 한 사람이 여러 방이 들면 방 수만큼). **bq는 지급하지 않는다.** 확정 시점에 소유자가 **탈퇴 계정**이거나 **`(알수없음)` 더미 계정**이면 보상하지 않는다(그 방은 순위에는 남는다). 확정과 지급은 **한 트랜잭션**이라 지급 도중 실패하면 확정도 남지 않고 다음 실행이 그 주를 처음부터 다시 한다.
- **노출**: 확정된 순위·주차·점수·보상 이력을 조회하는 API는 없다. 인기 목록에 순위 필드도 없다(배열 순서가 곧 순위).
- 확정된 적이 없으면(배포 직후 첫 확정 전) `popular`는 `[]`이다.

## 요구사항과 어긋난 점

- **확정 시각**: 요구사항(QUIZ-CQR-138)은 "월요일 00:10(KST) 안에 확정"인데, 코드 크론은 `0 1 * * * MON` — 월요일 **00:01부터 매시 01분**(재시도 포함)이다. 문서는 코드대로 썼다. 요구사항 쪽 갱신 또는 코드 확인 필요.
- 요구사항 본문 전체를 대조하지는 않았다(주요 한도·정책 수치는 일치: title 50, description 500, 문항 1~20, 보기 2~4·100자, 보상 500).

## 확인 필요
- `createdAt` 직렬화 포맷은 이 모듈의 다른 `LocalDateTime` 응답(채팅)과 같은 Jackson 설정을 따른다고 보고 오프셋 없는 ISO로 적었다. 실제 응답 샘플로 확정하지는 않았다.
