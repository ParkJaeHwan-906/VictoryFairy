# 퀴즈(quiz) API 명세

> **도메인** `quiz` — 오늘의 퀴즈 조회·개별 조회·제출(채점)·풀이 이력·좋아요.
> **모듈** quiz (포트 8081) · **경로 접두사** `/rt/quizzes` · **엔드포인트** 6개
> **컨트롤러** `quiz/src/main/java/com/skhynix/quiz/quiz/controller/QuizController.java`(조회·좋아요 토글), `QuizSubmissionController.java`(제출·이력) — `/rt`는 context-path가 붙인다
> **최종 갱신** 2026-08-26 — **`GET /rt/quizzes/{quizId}/vote-count` 신설**(엔드포인트 5개 → 6개). 아직 답하지 않은 문제의 보기별 투표 수를 **폴링으로 다시 받을 수 있는 유일한 경로**다 — 종전에는 `/today` 응답 한 번이 분포를 전달하는 유일한 기회였고 갱신 수단이 없었다. 응답 항목은 `/today` 와 **같은 타입**(`{no, text, voteCount}`)이고 서버가 백분율을 계산해 주지 않는다(값은 비율이 아니라 개수 그대로다 — 경로 이름도 그에 맞춰 `vote-count` 다). **자격이 없으면 404·403 이 아니라 200 + `data:null`** 이다(응답 코드로 '그 문제를 받았는지'가 드러나지 않게 함). 기존 5개 엔드포인트의 요청·응답·상태코드는 전부 불변. (직전: 2026-08-20 — **공통 시스템 예외가 이제 `ApiResponse` 래퍼를 탄다**(415 미지원 Content-Type·405 잘못된 메서드·400 깨진 JSON·400 경로변수/쿼리 타입 불일치·500 미처리 예외 전부 — `web-support`의 `GlobalExceptionHandler` 신설 핸들러, 공유 컴포넌트라 quiz 쪽 코드 변경 없이 적용됨). 실제로 형태가 바뀌는 사례: `Content-Type` 없이 `POST /{quizId}/submit` 호출(415), `GET /{quizId}/submit`처럼 POST 전용 경로에 GET(405), 깨진 JSON 본문(400), `/rt/quizzes/abc/submit`처럼 `quizId`가 숫자가 아님(400). 이 도메인의 `BusinessException` 매핑(403·404·409 등)·401 엔트리포인트·SSE는 전부 불변. 자세한 내용은 [README.md](README.md#1-응답-래퍼--도메인엔드포인트마다-다르다) 참고. (직전: 2026-08-19 **`GET /rt/quizzes/today` 응답의 보기 항목에 투표 수 필드 `voteCount` 신설**(각 `options[]` 원소에 0 이상 JSON 정수, 항상 존재 — Redis 장애·키 부재·TTL 만료·값 파싱 실패 시에도 0으로 채워 200 유지). 서빙 시점 근사 스냅샷이며 갱신 경로(SSE·폴링) 없음, 총합·비율 필드 없음, 미제출 상태에서도 노출(다수결 정답 힌트 수용). 상세(`GET /{quizId}`)·제출(`POST /submit`)·이력(`GET /submissions`) 세 응답은 **불변**(voteCount 없음). 엔드포인트 5개 그대로, 신규 경로 없음. 계약 원본 `docs/requirements/quiz/quiz-vote-exposure.md`(승인됨 2026-08-19, QUIZ-VOTEVIEW-1~30).)) 그 이전 이력은 각 엔드포인트 섹션의 `최종 변경` 줄에 남아 있다.
> 공통 규약(응답 래퍼·인증·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/rt/quizzes/today](#get-rtquizzestoday) | 200 | `gameId`(내 응원 구단의 오늘 `IN_PROGRESS` 경기)가 지목한 이닝의 세트 목록 — 선호 문제 우선 정렬, `preferredOnly` 필터. 서빙과 동시에 미답 행 생성(쓰기 트랜잭션), 그 이닝에 이미 받았으면 409. 보기마다 투표 수(`voteCount`, 서빙 시점 근사 스냅샷, 2026-08-19 신설) 포함 |
| GET | [/rt/quizzes/{quizId}](#get-rtquizzesquizid) | 200 | 단건 상세 — 답하기 전엔 정답 비노출, 답한 후엔 복기 정보 + 좋아요 상태 포함. `(submitted, expired)`로 진행 중/답함/시한 초과 구분 |
| POST | [/rt/quizzes/{quizId}/submit](#post-rtquizzesquizidsubmit) | 200 | 제출·서버 채점 — 정답이면 포인트 적립 |
| GET | [/rt/quizzes/submissions](#get-rtquizzessubmissions) | 200 | `gameId`(필수, `games.naver_game_id`)로 지목한 **경기 한 건**의 이닝별 풀이 결산 — 이닝 배열(`summary`+`quizzes[]`) + 경기 전체 요약(정답률·획득 포인트). `page` 폐지(페이징 없음), 항목에 보기(`options`)·좋아요 상태 포함 |
| POST | [/rt/quizzes/{quizId}/like](#post-rtquizzesquizidlike) | 200 | 좋아요 토글 — 내가 제출한 문제에만 허용 |
| GET | [/rt/quizzes/{quizId}/vote-count](#get-rtquizzesquizidvote-count) | 200 | 아직 답하지 않은 문제의 보기별 투표 수 — 폴링용. 자격 없으면 200 + `data:null` |

## 이 도메인의 특이사항

**인증 필수.** quiz 모듈의 `SecurityConfig`는 `/`, `/error`, `GET /actuator/health/**`만 permitAll이고 그 외 `anyRequest().authenticated()`다. 무토큰 요청은 전부 401 `"인증이 필요합니다."`([README](README.md)의 401 정책).

**비밀번호 변경 이전에 발급된 토큰도 이 6개 엔드포인트 전부에서 같은 401로 거절된다(2026-08-17부터).** 인증 필터(`JwtAuthenticationFilter`, `web-support` 공유 컴포넌트)가 user(8080)·quiz(8081) 양쪽에서 동일하게 동작하므로, user 쪽 `PATCH /api/users/me/password`로 비밀번호를 바꾸면 그 이전에 발급된 access 토큰은 quiz의 `/rt/**` 요청에서도 그 순간부터 401 `UNAUTHENTICATED`가 된다(토큰이 아예 없을 때와 응답 완전히 동일 — 전용 코드 없음). quiz는 이 판정을 위한 별도 코드가 없다 — `UserAccountRepository`(공유 `:domain`)를 그대로 물려받아 자동으로 적용된다. 자세한 계약은 [README.md](README.md#2-인증-방식-jwt) 참고.

**전원 동일 데일리 세트.** `quizzes.quiz_date`는 **출제일**이다(생성일 아님). 시효성 없는 문제(역대기록형)는 `quiz_date=NULL` 풀에 쌓였다가 매일 편성 잡이 세트 부족분(기본 10문항, `quiz.serve.daily-count`)을 오래된 것부터 채운다. 경기 문항(gameId 귀속)만 그 경기 날짜에 고정. 모든 사용자가 같은 날 같은 세트를 받는다 — 레이팅(도입 예정)의 점수 비교 전제다. **미편성 풀 문제는 어떤 API로도 보이지 않는다**(단건 조회·제출 모두 404 — id 순회로 내일 출제분을 미리 보는 것을 막는다).

**`/today`는 이제 경기 진행 중에만 열리는 창구다(2026-08-12 신설).** 요청은 **`gameId`**(내부 PK가 아니라 `games.naver_game_id` 문자열 — [경기 목록](game.md) 응답의 `gameId`와 같은 값)로 "지금 보고 있는 경기"를 지목해야 하고, 서버는 그 경기가 **존재·오늘(KST)·내 응원 구단·`IN_PROGRESS`**인지 검증한다(하나라도 아니면 403 `QUIZ_NOT_SERVABLE`, 사유 구분 없음). 통과하면 그 경기의 `current_inning`을 읽어 **그 이닝에 아직 세트를 안 받았는지**(같은 `(경기, 이닝)`으로 이미 받았으면 409 `QUIZ_ALREADY_SERVED_IN_INNING`)까지 확인한 뒤에야 세트를 내려준다. **`gameId`는 문제를 고르는 값이 아니다** — 세트 자체는 여전히 `quiz_date = 오늘`인 문제 전체이고, `gameId`의 역할은 제공 여부 검증과 받는 행에 찍을 이닝 값 확보 둘뿐이다(이닝 값 자체는 어떤 응답 필드에도 노출되지 않는다).

**선호(응원 구단·선수)는 세트 구성이 아니라 노출 방식에 반영된다.** `/today`는 내 응원 구단(문제의 대상·상대 구단 일치) 또는 응원 선수(대상 선수 일치) 문제를 앞에 배치하고 `preferred` 플래그로 표시한다. `preferredOnly=true`는 그것만 남긴다. ⚠ **"응원 구단·선수가 하나도 없으면 no-op으로 전체 반환"이라는 분기는 2026-08-12(5차 개정)부터 도달 불가능한 죽은 코드다** — `gameId` 검증(`servableGame`)이 요청자의 응원 구단을 먼저 확인해 구단이 없으면 이 필터에 이르기 전에 403 `QUIZ_NOT_SERVABLE`로 끝나므로, 이 필터에 도달한 시점엔 응원 구단이 항상 존재한다(응원 선수 유무는 무관). 즉 지금 클라이언트가 실제로 받는 동작은 **`preferredOnly=true`가 항상 실제로 필터링한다**는 것이고, 매칭되는 문제가 하나도 없으면 그 결과 그대로 빈 배열이 응답된다(그런 경우와 "오늘 세트 자체가 없음"을 이 응답만으로는 구분할 수 없다). 세트 자체는 전원 동일하므로 필터를 써도 레이팅 공정성이 깨지지 않는다.

**정답(answer)은 답하기 전엔 어떤 응답에도 없다 — 답한 후에만 공개된다.** 조회 응답(`/today`, 미답 상세)에는 `answer` 키 자체가 없다(클라이언트 개발자 도구 노출 방지, 테스트로 고정). 제출 응답과 답한 후 상세·이력에는 정답이 실린다(복기 화면 전제).

**보기별 투표 수(`voteCount`)는 `/today` 와 `/{quizId}/vote-count` 둘에만 있다(2026-08-26 정정 — 종전에는 `/today` 하나뿐이었다).** 상세(`GET /{quizId}`)·제출(`POST /submit`)·이력(`GET /submissions`) 세 응답은 이번 변경으로 필드 집합이 **불변**이고 `voteCount`가 없다. `/today`의 값은 서빙 시점에 Redis 집계 키(`quiz:votes:{quizId}`, `options[].no`와 같은 0-based 축)를 초기화(`HSETNX`) 이후 읽은 **근사 스냅샷**이며, 그 문제는 (경기, 이닝)당 1회만 서빙되므로 분포도 그 순간 딱 한 번 전달된다 — 이후 갱신은 **`GET /{quizId}/vote-count` 폴링으로만** 받는다(2026-08-26 신설 — 그 전에는 갱신 경로가 아예 없었다. 같은 이닝 재조회는 여전히 409라 `/today` 로는 되받을 수 없다). 합계가 그 문제를 받은 사람 수·`quiz_users_submit` 행 수와 일치한다는 보장이 없다(받고 아직 안 푼 사람은 빠짐, Redis 장애 중 들어온 표는 영구 유실). **미제출 상태에서도 분포가 노출된다 — 사용자가 대가(다수결 정답 힌트)를 알고 택한 동작**이다(`/today` 목록은 정의상 전부 미제출 문제라 노출 자격을 따로 검사하지 않는다). Redis 장애·키 부재·TTL 만료·필드 결손·값 파싱 실패는 전부 해당 보기 `voteCount:0`으로 채워 200이 그대로 나간다 — **`voteCount:0`은 "아무도 안 골랐다"와 "집계를 못 읽었다"를 응답만으로 구분하지 못한다**(구분은 서버 WARN 로그로만 가능, 문제당 1건). 총합·비율 필드는 없다(필요하면 클라이언트가 `voteCount`를 더한다). 자세한 내용은 [GET /today](#get-rtquizzestoday) 절 참고.

**채점·적립은 서버 트랜잭션 안에서 원자적이다.** 정답이면 `quizzes.score`(배점)만큼 `users_account.point`에 적립한다(비관적 락으로 동시 적립 유실 방지). `users_bq.bq_score`는 레이팅 설계 확정 전이라 건드리지 않는다. **중복 제출(409)의 판정 방식이 2026-08-12부터 바뀌었다** — 예전엔 선제 `existsBy` 검사(친절한 409) + `uk_quiz_users_submit_account_quiz` UNIQUE(동시 요청 race의 최종 중재)로 이중이었으나, 이제는 미답 행을 채우는 **조건부 UPDATE 한 방의 영향 행 수(0=중복)**가 유일한 판정 근거다. 이 때문에 409 검사가 검증 순서의 **맨 뒤**로 밀렸다(자세한 내용은 [POST 제출](#post-rtquizzesquizidsubmit) 절 참고).

**`/today`가 응답에 실은 문제마다 그 즉시 `quiz_users_submit`에 미답 행을 만드는 것이 곧 제출 자격이다.** 그 즉시 `quiz_users_submit`에 **미답 행**(`submit_option_id IS NULL`)을 만든다(같은 트랜잭션 — 실패하면 목록도 안 준다). 그 행의 **존재가 제출 자격**이고 **`created_at`(받은 시각) + 8분이 제출 시한**이다 — **⚠ 이 8분은 이제 "받은 문제에 답을 낼 수 있는 시간"으로 [제출](#post-rtquizzesquizidsubmit) 경로에만 적용되고, 목록 재조회 판정과는 무관하다(2026-08-12 5차 개정).** `/today`를 다시 호출해도 이미 있는 행은 어떤 필드도 바뀌지 않는다 — 시한이 갱신되지 않는다.

**⚠ 재조회가 폐지됐다(2026-08-12 5차 개정) — FE 구현에 가장 큰 영향을 준다.** 종전엔 "미답이고 시한이 남은 문제는 다시 호출해도 계속 응답에 실린다"였으나, 이제는 **행이 있는 문제는 답 여부·시한과 무관하게 그 즉시 목록에서 영구히 빠진다.** 즉 **FE가 받은 세트를 화면에서 잃으면 다시 받을 방법이 없다** — 새로고침·탭 이동·앱 재시작으로 목록을 놓치면, 아직 답을 못 낸 문제는 8분 시한이 지나는 순간 미제출(오답)로 그대로 확정되고, 같은 이닝으로 `/today`를 다시 불러도 그 문제는 돌아오지 않는다(그 요청 자체가 409 `QUIZ_ALREADY_SERVED_IN_INNING`으로 막힌다). FE는 받은 세트를 클라이언트 쪽에서 계속 들고 있어야 한다. 시한을 넘긴 미답 문제의 제출은 영구히 403 `QUIZ_SUBMIT_NOT_ALLOWED`다 — 복구 경로가 없다. 이닝 값은 어떤 응답 필드에도 노출되지 않는다(서버 내부 통계·자격/회차 판정용). 이 변화로 **퀴즈 조회·제출 경로가 Redis 장애와 무관해졌다**(폐기된 Redis 티켓 방식은 Redis 장애 시 500이었다). 자세한 판정 순서·복구 경로는 [GET /today](#get-rtquizzestoday)·[POST 제출](#post-rtquizzesquizidsubmit) 절 참고.

**좋아요는 "풀어본 사람의 평가"로 좁혀져 있다.** 신호로 이력 행을 쌓지 않고 `(계정, 문제)` 한 행의 플래그로만 관리한다(응원 `oppose` 토글·제출 UNIQUE와 같은 설계 계열). **제출 이력이 좋아요의 선행조건**이라 "존재하지 않는 문제"·"미편성 풀 문제"·"편성됐지만 안 푼 문제" 셋이 요청자 입장에서 같은 상태로 합쳐지고, 거절 응답도 하나(403 `QUIZ_LIKE_NOT_ALLOWED`)로 합쳐진다 — 404가 아닌 이유가 이것이다. 토글이라 멱등이 아니며, 동시 충돌은 500이 아니라 200 + 확정 상태로 흡수된다. `likeCount`는 취소된 행을 제외한 **현재 `liked = true`인 행 수**다. (이 절은 2026-08-12 시점에 코드 변경이 없다.)

---

## GET /rt/quizzes/today
> 최종 변경: 2026-08-19 — **보기 항목에 투표 수 필드 `voteCount`(long, 0 이상) 신설.** `options[]` 원소가 `{no, text}` → `{no, text, voteCount}`로 넓어짐. 값은 응답 조립 시점에 Redis 집계 키(`quiz:votes:{quizId}`)를 0 초기화(`HSETNX`) 이후 읽은 **근사 스냅샷**이고 항상 존재(생략·null 없음), 갱신 경로 없음, 총합·비율 필드 없음, 미제출 상태에서도 노출(정답 유출 수용), Redis 장애·키 부재·TTL 만료·값 파싱 실패는 전부 0으로 채워 200 유지. 나머지 필드·상태코드·에러코드·엔드포인트 5개 구성은 불변. 상세·제출·이력 세 응답에는 `voteCount`가 없다(불변). 계약 원본 `docs/requirements/quiz/quiz-vote-exposure.md`(승인됨 2026-08-19, QUIZ-VOTEVIEW-1~30). (직전: 2026-08-12(5차 개정) — **`gameId` 필수 쿼리 파라미터 신설**(값은 내부 PK가 아니라 `games.naver_game_id` 문자열) + **응원 구단 경기가 `IN_PROGRESS`일 때만 세트 제공**(그 외 사유는 전부 403 `QUIZ_NOT_SERVABLE`로 합쳐짐) + **"한 이닝에 한 세트" 회차 제한 신설**(같은 이닝 재요청은 409 `QUIZ_ALREADY_SERVED_IN_INNING`). **⚠ 재조회가 폐지됐다** — 종전엔 "미답이고 시한이 남은 문제는 다시 호출해도 계속 응답에 실린다"였으나, 이제 **행이 있는 문제는 답 여부·시한과 무관하게 전부 제외된다**(FE가 받은 세트를 잃으면 되받을 수 없다는 뜻 — 가장 큰 FE 영향). 8분 시한은 이제 **제출 경로 전용**(목록 재조회 판정과 무관). 빈 배열의 뜻이 좁아짐 — "지금은 줄 수 없다"가 전부 403·409로 빠지고 "줄 수 있는데 줄 게 없다"만 남는다. **응답 필드·정렬·성공 상태코드(200)는 불변**(이닝은 여전히 응답에 없다). 계약 원본 `docs/requirements/quiz/quiz-inning-tracking.md`(5차 개정, QUIZ-INN-83~113).)

오늘(**KST**) 세트 중 **내가 아직 안 받은 문제만** 반환한다 — **행(`quiz_users_submit`)이 있는 문제는 답 여부·시한과 무관하게 전부 제외된다**(2026-08-12부터 — 재조회 폐지, 아래 참고). `QuizService.getTodayQuizzes(userAccountId, gameId, preferredOnly)` — "오늘"은 항상 서버가 KST 고정 클록으로 판정한다(파드 JVM은 UTC). 다른 날짜를 조회할 방법은 없다.

**⚠ 이 엔드포인트는 읽기 전용이 아니다.** 응답에 실을 문제마다 `quiz_users_submit`에 답이 빈 행(`submit_option_id IS NULL`)을 함께 만든다 — 선조회 후 차집합만 한 문장으로 INSERT하며, **이미 행이 있는 문제는 어떤 필드도 건드리지 않는다**(같은 세트를 다시 받는 재호출은 쓰기 SQL이 0건). 그 행 하나가 네 역할을 겸한다: **존재 = 제출 자격**, **`created_at`(받은 시각) + 8분 = 제출 시한**, **`inning` = 받은 시점의 기준 경기(=`gameId`) 이닝**(응답에는 노출 안 됨), **`(game_id, inning)` = 회차 판정 키**. 행 생성이 실패하면 목록도 주지 않는다(같은 트랜잭션, 부분 성공 없음).

**인증 필요** — `Authorization: Bearer <accessToken>`

**⚠ `gameId`는 문제를 고르는 값이 아니라 "지금 세트를 줘도 되는지"를 검증하는 값이다.** 세트 자체는 여전히 `quiz_date = 오늘`인 문제 전체이며 경기 문항으로 좁혀지지 않는다.

**쿼리 파라미터**

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| gameId | String | 없음(필수) | **내부 PK가 아니라 `games.naver_game_id` 문자열**(예: `20260812SSHT02026`) — [경기 목록](game.md) 응답의 `data[].gameId`와 같은 값. 요청자가 지금 보고 있는 **자기 응원 구단의 오늘 경기**를 지목해야 한다. 누락 시 400 |
| preferredOnly | boolean | false | `true`면 선호(응원 구단·선수 매칭) 문제만. 이 요청이 여기까지 오려면 위 세트 제공 검증에서 이미 요청자의 응원 구단이 그 경기에 참여함이 확인된 뒤다 — **"응원 정보가 하나도 없으면 무시하고 전체 반환"은 2026-08-12부터 도달 불가능하다**(가능한 요청 중 응원 구단이 없는 경우가 없다). 필터는 항상 실제로 적용되며 매칭이 없으면 빈 배열이 그대로 응답이다 |

**세트 제공 검증(2026-08-12 신설, 판정 순서 고정)**: ① `gameId`가 가리키는 경기가 **존재** ② 그 경기가 **오늘(KST)** ③ 요청자의 **응원 구단**(홈·원정 양쪽 확인)이 그 경기에 참여 ④ 그 경기 상태가 **`IN_PROGRESS`**(`game_statuses.name` 문자열로 판정 — id는 환경마다 다를 수 있어 판정 근거로 안 씀) — **넷 중 하나라도 아니면 403 `QUIZ_NOT_SERVABLE`이고 어느 사유인지 응답으로 구분되지 않는다**(경기 없음·오늘 경기 아님·내 응원 구단 경기 아님·진행 중 아님이 전부 같은 응답). 통과하면 ⑤ 그 경기의 `current_inning`을 읽는다 — 값이 없으면(원천 미구현 등) 역시 403 `QUIZ_NOT_SERVABLE`. ⑥ 마지막으로 **그 `(경기, 이닝)`에 이미 세트를 받았는지** 본다 — 받았으면 **409 `QUIZ_ALREADY_SERVED_IN_INNING`**(경기가 안 되는 상태라서가 아니라 "이 이닝엔 이미 줬다"는 별개 사유라 403과 분리). 판정 키는 문제 단위가 아니라 **요청 단위**다 — 한 요청으로 만들어지는 행은 전부 같은 `(game_id, inning)`을 갖는다.

**응답 200 OK** `ApiResponse<List<QuizResponse>>` — 페이징 없음.

**정렬 규칙**: **선호(preferred) 문제가 항상 비선호보다 먼저 온다.** 랜덤은 각 그룹(선호/비선호) **안에서만** 일어난다 — 선호 문제가 비선호보다 뒤로 밀리는 일은 없다. 그룹 내부 순서는 **사용자별로 고정된 랜덤**이다(계정 id와 퀴즈 id로 결정되는 해시 기반 — 같은 사용자는 몇 번을 호출해도 항상 같은 순서를 받고, 새로고침해도 재배치되지 않는다. 계정이 다르면 순서도 다르다). 해시 충돌(드묾) 시에만 `id` 오름차순으로 보정한다. 문제를 풀어(또는 이 세트 자체가 다시 서빙되지 않아) 목록에서 빠져도 남은 문제들의 상대 순서는 보존된다. **클라이언트는 서버가 준 순서를 그대로 표시할 것** — 별도 정렬을 하면 이 의도가 사라진다.

| 필드 | 타입 | 설명 |
|---|---|---|
| data[].id | Long | 퀴즈 식별자. 상세·제출이 이 값으로 지목 |
| data[].type | String | `"객관식"` \| `"O/X"` — FE 렌더링 분기용 |
| data[].question | String | 문제 본문 |
| data[].difficulty | String \| null | `EASY`/`MEDIUM`/`HARD`/`EXPERT`. 사람이 쓴 퀴즈는 null 가능 |
| data[].point | Double \| null | 배점(정답 시 적립될 포인트). null 가능 |
| data[].preferred | boolean | 내 응원 구단·선수 매칭 여부(정렬 근거 그대로) |
| data[].options | array | 보기 배열, `no` 오름차순. `no`(0-기반, **제출 시 보낼 번호**, O/X는 0=`"O"` 1=`"X"`) · `text` · `voteCount`(신규 2026-08-19, long, 0 이상) — 그 보기를 고른 사람 수. **서빙 시점 근사 스냅샷**(응답 조립 시 Redis 집계 키 `quiz:votes:{quizId}`를 0 초기화 이후 읽은 값), 항상 존재(생략·null 없음). Redis 장애·키 부재·TTL 만료·값 파싱 실패 시에도 0으로 채워짐 — **`voteCount:0`은 "아무도 안 골랐다"와 "집계를 못 읽었다"를 구분하지 않는다.** 이후 갱신되지 않고(응답은 문제당 1회뿐), 합계가 참여자 수와 일치할 보장 없음. `GET /{quizId}`·`GET /submissions`의 `options`에는 이 필드가 없다(불변) |

**정답·근거·대상 FK·이닝·경기는 응답에 없다.** ⚠ **빈 배열의 뜻이 좁아졌다(2026-08-12).** 이제 **"줄 수 있는데 줄 게 없다"**(오늘 세트 없음 · 이 이닝에 줄 수 있는 나머지 문제를 이미 다 받음)만 뜻한다(에러 아님) — "지금은 줄 수 없다"에 해당하는 경우(경기 미진행 등)는 전부 403·409로 빠진다. 구분이나 진행률("10문제 중 7개 완료")이 필요하면 [풀이 이력](#get-rtquizzessubmissions)을 병용한다.

**이 응답에는 `liked`·`likeCount`가 없다(2026-08-11 좋아요 기능 추가 후에도 불변).** 좋아요는 답한 문제에만 허용되는데 `/today`는 받은 적 있는 문제 전부를 목록에서 빼고 내려주므로, 이 목록의 모든 항목이 애초에 좋아요 대상이 아니다 — 집계 쿼리를 붙여도 쓰이지 않아 아예 실행하지 않는다.

**응답 개수 상한 20건.** 위 정렬(선호 우선 + 사용자별 고정 랜덤)이 끝난 목록을 앞에서 잘라 최대 `quiz.serve.max-today-count`(기본 20, env `QUIZ_SERVE_MAX_TODAY_COUNT`)건만 반환한다 — 편성 수(`quiz.serve.daily-count`, 기본 10)와는 별개 설정이며 응답 **필드 집합은 바뀌지 않는다**(20건 이하이면 상한이 아무것도 바꾸지 않는다, `hasMore` 같은 표식도 없다). **상한에 잘려 나간 문제에는 미답 행이 생기지 않는다** — 못 받은 문제이므로 그 문제의 제출은 403이다.

**⚠ 재조회가 폐지됐다(2026-08-12) — 이게 FE 구현에 가장 큰 영향을 준다.** 종전엔 "미답이고 시한이 남은 문제는 다시 호출해도 계속 응답에 실린다"였으나, 이제 **행(`quiz_users_submit`)이 있는 문제는 답 여부·시한과 무관하게 그 즉시 목록에서 영구히 빠진다.** 즉 **FE가 한 번 받은 세트를 화면에서 잃으면 다시 받을 방법이 없다** — 새로고침·탭 이동·앱 재시작으로 목록을 놓치면, 아직 답을 못 낸 문제는 8분 시한이 지나는 순간 미제출(오답)로 그대로 확정된다. 같은 이닝으로 `/today`를 다시 불러 그 문제를 되받으려 해도 그 요청 자체가 409 `QUIZ_ALREADY_SERVED_IN_INNING`으로 막힌다. **FE는 받은 세트를 클라이언트 쪽에서 계속 들고 있어야 한다.**

**8분 시한의 뜻이 좁아졌다.** 이제 "**받은 문제에 답을 낼 수 있는 시간**"(제출 경로 전용)일 뿐이고, 이 목록 재조회 판정과는 무관하다 — 시한이 남았다고 문제가 다시 실리지도, 시한이 지났다고 목록 판정이 달라지지도 않는다(둘 다 "행이 있으면 제외"라는 같은 규칙으로 이미 끝난다). 시한이 지나면 [제출](#post-rtquizzesquizidsubmit)만 영구히 403 `QUIZ_SUBMIT_NOT_ALLOWED`가 된다 — 복구 경로가 없다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증) | `gameId` 쿼리 파라미터 누락 — 공통 래퍼로 응답, `data`는 `null` |
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰, 2026-08-17부터 — 위 "이 도메인의 특이사항" 참고) |
| 403 | QUIZ_NOT_SERVABLE | 지목한 경기 없음 · 오늘(KST) 경기 아님 · 내 응원 구단 경기 아님 · `IN_PROGRESS` 아님(경기 전·종료·취소) · 이닝 값 확보 실패 — **다섯 사유 모두 이 하나의 응답으로 합쳐지고 구분되지 않는다** |
| 409 | QUIZ_ALREADY_SERVED_IN_INNING | 그 `(경기, 이닝)`에 이미 세트를 받음(같은 이닝 재요청) |

이 경로는 Redis를 쓰지 않는다(2026-08-12부터 — 폐기된 Redis 티켓 방식은 Redis 장애 시 500이었다). DB 장애 시 500 — 2026-08-20부터 `GlobalExceptionHandler.handleUnexpected`(catch-all 신설)가 잡아 `ApiResponse` 래퍼가 붙지만(`{"success":false,"data":null,"message":"서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}`, `INTERNAL_SERVER_ERROR`), 실제 DB 장애를 재현한 실측은 없다(코드 근거로만 확인, 종전 "래퍼 없음"은 정정됨).

```bash
curl "http://localhost:8081/rt/quizzes/today?gameId=20260812SSHT02026&preferredOnly=true" \
  -H 'Authorization: Bearer eyJ...'
# 성공 (구성 예시 — voteCount 신설 반영, 2026-08-19)
# {"success":true,"data":[{"id":31,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "preferred":true,"options":[{"no":0,"text":"O","voteCount":3},{"no":1,"text":"X","voteCount":1}]}],"message":null}

# gameId 누락 (실측)
curl http://localhost:8081/rt/quizzes/today -H 'Authorization: Bearer eyJ...'
# 400 {"success":false,"data":null,"message":"필수 요청 파라미터가 누락되었습니다: gameId"}

# 없는 경기 / 남의 팀 경기 / 어제 경기 / SCHEDULED 경기 등 — 전부 이 하나의 응답 (실측)
curl "http://localhost:8081/rt/quizzes/today?gameId=20260101XXYY01234" -H 'Authorization: Bearer eyJ...'
# 403 {"success":false,"data":null,"message":"경기가 진행 중일 때만 문제를 받을 수 있습니다."}

# 같은 (경기, 이닝)에 재요청 (실측)
curl "http://localhost:8081/rt/quizzes/today?gameId=20260812SSHT02026" -H 'Authorization: Bearer eyJ...'
# 409 {"success":false,"data":null,"message":"이번 이닝에는 이미 문제를 받았습니다."}
```

---

## GET /rt/quizzes/{quizId}
> 최종 변경: 2026-08-12 — `expired`(boolean) 필드 신설. **`submitted`의 의미가 "받았는가"에서 "답했는가"로 재정의됨**(행이 이제 받는 순간 생기므로 행 존재만으로는 제출을 뜻하지 않는다). FE는 `(submitted, expired)` 조합으로 세 상태를 구분한다: 진행 중 `(false,false)` · 답함 `(true,*)` · 시한 초과 `(false,true)`. `myOption`·`correct`·`answer`·`liked`·`likeCount`는 여전히 **답한 경우에만** 존재(의미는 "제출한 경우"와 동일). 상태코드·에러코드 변화 없음. (직전: 2026-08-11 제출한 문제일 때 `liked`·`likeCount` 필드 추가)

단건 상세. `QuizService.getQuiz(userAccountId, quizId)`.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK. `/today` 응답의 `data[].id`(또는 이력의 `quizId`)로 얻는다 |

**응답 200 OK** `ApiResponse<QuizDetailResponse>` — 공통 필드는 `/today`와 동일(id·type·question·difficulty·point·quizDate·options) + 제출 상태. **단 `options` 항목은 `{no, text}`뿐이다** — `/today`가 2026-08-19부터 싣는 `voteCount`는 이 응답에 없다(불변, 아래 "이 도메인의 특이사항" 참고):

| 필드 | 타입 | 설명 |
|---|---|---|
| data.submitted | boolean | **내가 답을 냈는가**(행을 받았는지가 아니다). 답 없는 행(진행 중이거나 시한 초과)은 false, 받은 적 없는 문제도 false |
| data.expired | boolean(신규 2026-08-12) | 받아 놓고 **시한(받은 시각+8분)을 넘겼는데 아직 안 냈는가**. 답한 문제는 항상 false, 받은 적 없는 문제도 false(둘의 구분은 이 응답의 몫이 아니라 [/today](#get-rtquizzestoday) 목록의 몫) — 저장된 플래그가 아니라 **조회 시각 기준 계산**이라 같은 문제가 8분 전후로 다르게 나올 수 있다 |
| data.myOption | int | **답한 경우에만 존재.** 내가 낸 보기 번호 |
| data.correct | boolean | **답한 경우에만 존재.** 정오 |
| data.answer | int | **답한 경우에만 존재.** 정답 보기 번호 |
| data.liked | boolean | **답한 경우에만 존재.** 내 현재 좋아요 상태 |
| data.likeCount | long | **답한 경우에만 존재.** 그 문제에 대해 `liked = true`인 행 수(취소한 좋아요는 세지 않음) |

미답이면(진행 중이든 시한 초과든, 받은 적이 없든) 위 다섯 키는 **본문에서 키 자체가 빠진다**(`@JsonInclude(NON_NULL)` — 정답 유출 방지, 테스트로 고정). 좋아요 자체가 답한 문제에만 허용되므로 미답 시엔 좋아요 관련 조회조차 하지 않는다(키 부재와 쿼리 부재가 같은 분기).

**`(submitted, expired)` 조합이 곧 화면 상태다**:

| submitted | expired | 뜻 |
|---|---|---|
| false | false | 진행 중(지금 답할 수 있음) 또는 애초에 받은 적 없는 문제(둘의 구분은 이 응답의 몫이 아니다) |
| true | 항상 false | 답함(복기 가능) |
| false | true | 시한 초과(더 이상 제출 불가 — `/today`에도 다시 안 실린다) |

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰, 2026-08-17부터 — 위 "이 도메인의 특이사항" 참고) |
| 404 | QUIZ_NOT_FOUND | 미존재 **또는 미편성 풀 문제**(구분 불가가 의도 — 존재 은닉) |

**예시 — 미제출(진행 중)**

```bash
curl http://localhost:8081/rt/quizzes/23 -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"id":23,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "quizDate":"2026-08-10","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#   "submitted":false,"expired":false},"message":null}
# (myOption·correct·answer·liked·likeCount 키 자체가 없음)
```

**예시 — 시한 초과(2026-08-12 신설 상태)**

```bash
curl http://localhost:8081/rt/quizzes/24 -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"id":24,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "quizDate":"2026-08-12","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#   "submitted":false,"expired":true},"message":null}
# (myOption·correct·answer·liked·likeCount 키 자체가 없음 — 이 문제는 /today 에도 다시 안 실리고 제출도 403)
```

**예시 — 답함**

```bash
curl http://localhost:8081/rt/quizzes/23 -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"id":23,"type":"O/X","question":"...","difficulty":"EASY","point":50.0,
#   "quizDate":"2026-08-10","options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#   "submitted":true,"expired":false,"myOption":0,"correct":true,"answer":0,
#   "liked":false,"likeCount":5},"message":null}
```

---

## POST /rt/quizzes/{quizId}/submit
> 최종 변경: 2026-08-12 — 내부 구현이 "신규 행 INSERT"에서 "미답 행의 조건부 UPDATE"로 전환됨(제출 자격의 근거가 Redis 티켓 → `quiz_users_submit` DB 행). **판정 순서가 404→409→403→400에서 404→403→400→409로 바뀌었다** — 중복 판정이 선검사가 아니라 조건부 UPDATE의 영향 행 수(0=중복)로만 나오기 때문. **⚠ 관측 가능한 계약 변화: 이미 답한 문제에 없는 보기 번호를 보내면 종전 409였는데 이제 400이다.** 403(`QUIZ_SUBMIT_NOT_ALLOWED`)의 판정 근거도 Redis 키 존재에서 DB 행 존재+시한으로 바뀌었으나 조건·문구는 그대로다. **메서드·경로·요청 본문·성공 응답 필드는 전부 불변.**

제출·채점. `QuizSubmitService.submit(userAccountId, quizId, option)` — 검증(404→403→400→계정 락·적립→409, 순서 고정) 후 채점, **이미 있는 미답 행의 답을 조건부 UPDATE로 채운다**(제출은 새 행을 만드는 일이 아니다 — 행은 `/today`가 서빙 시점에 미리 만들어 둔다).

**⚠ 제출 자격은 DB 행이다(2026-08-12부터, Redis 폐기 — 응답에는 노출 안 됨).** `/today`가 응답에 실은 문제마다 미리 만들어 둔 미답 행이 있어야 하고, 그 행의 시한(받은 시각 + 8분)이 남아 있어야 한다. **둘 중 하나라도 아니면 403이다** — 행 자체가 없음(`/today` 미경유 또는 상한 절삭)과, 행은 있으나 시한 초과(제출하지 않은 채 8분 경과) **두 경우를 응답으로 구분하지 않는다**(상태코드·본문 문자열 완전히 동일, 의도된 설계). 403을 받은 문제는 제출되지 않은 상태로 남는다 — `/today` 미경유·상한 절삭으로 인한 403은 흔적이 없어 다음 `/today`에서 상한 안에 들면 다시 받을 수 있지만, **시한 초과로 인한 403은 그 문제가 이후 어떤 `/today` 응답에도 다시 실리지 않아 사실상 복구 불가능하다**(재발급 API·유예 시간 없음, [/today](#get-rtquizzestoday) 절 참고).

**판정 순서는 404 → 403 → 400 → (계정 락·적립) → 409로 고정이다(2026-08-12 변경 — 종전 404→409→403→400).** 중복 제출 판정이 선검사(`existsBy`)가 아니라 조건부 UPDATE(`submit_option_id IS NULL AND created_at`이 시한 안)의 **영향 행 수**로만 나오게 되면서 409 검사가 검증 순서의 맨 뒤로 밀렸다. **⚠ 관측 가능한 계약 변화**: 이미 답한 문제(그 행의 `submit_option_id`가 이미 채워짐)에 **존재하지 않는 보기 번호**를 보내면 종전에는 409(선검사가 먼저 잡음)였으나 **이제는 400**이다 — 보기 조회가 조건부 UPDATE보다 먼저 실행되고, 없는 보기 번호는 UPDATE 시도 자체 없이 400으로 끝난다. 반대로 **답한 문제에 실재하는 보기 번호로 재제출하면 여전히 409**다(조건부 UPDATE 영향 행 0).

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
| 400 | QUIZ_OPTION_NOT_FOUND | 존재하지 않는 보기 번호(판정 순서상 403 다음·409 이전. **⚠ 2026-08-12부터: 이미 답한 문제에 없는 보기 번호를 보낸 경우도 이 400이다** — 종전엔 409였다) |
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰, 2026-08-17부터 — 위 "이 도메인의 특이사항" 참고) |
| 403 | QUIZ_SUBMIT_NOT_ALLOWED | 그 문제를 `/today`로 받은 적이 없거나(행 없음), 받았지만 제한 시간(8분)이 지남(행은 있으나 만료) — **두 경우 응답 동일**(판정 순서상 404 다음·400 이전, 2026-08-12부터 409보다 앞) |
| 404 | QUIZ_NOT_FOUND | 미존재·미편성(판정 순서상 가장 먼저) |
| 409 | QUIZ_ALREADY_SUBMITTED | 이미 답한 문제 재제출(동시 제출 race 포함) — **판정 순서상 가장 마지막**(계정 락·적립 이후, 2026-08-12부터. 종전엔 가장 먼저였다) |

```bash
curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":0}'
# {"success":true,"data":{"correct":true,"answer":0,"myOption":0,"earnedPoint":50,"totalPoint":50},"message":null}

curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":0}'
# /today 를 거치지 않았거나, 거쳤지만 받은 지 8분이 지난 경우
# {"success":false,"data":null,"message":"오늘의 퀴즈로 받은 문제만 제한 시간 안에 제출할 수 있습니다."}

curl -X POST http://localhost:8081/rt/quizzes/23/submit \
  -H 'Authorization: Bearer eyJ...' -H 'Content-Type: application/json' -d '{"option":99}'
# 23번을 이미 답했고, 99번은 그 문제에 없는 보기 번호인 경우 (2026-08-12부터: 종전 409 → 이제 400)
# {"success":false,"data":null,"message":"존재하지 않는 보기 번호입니다."}
```

---

## GET /rt/quizzes/submissions
> 최종 변경: 2026-08-13 — **요청·응답 계약 전면 교체.** `page` 파라미터 **폐지**, `gameId`(필수, `games.naver_game_id` 문자열)로 조회 단위가 계정 전체에서 **경기 한 건**으로 좁혀졌다. 응답이 페이징 구조(`summary`+`PageResponse<submissions.content>`)에서 **`summary`(전체 요약, `earnedPoint` 신규) + `innings[]`(이닝별 `summary`+`quizzes[]`)**로 전면 교체됐다 — FE 파괴적 변경(BE·FE 동시 배포 전제), 계정 전체 누적 정답률을 보여주던 경로가 사라졌다(대체 없음, 후속 과제). 항목에 `options`(보기 배열) 신설, `quizDate`·`myOptionText`·`answerText` 삭제(`liked`/`likeCount`는 유지). 403 `GAME_NOT_STARTED`(신규 `ErrorCode`, 예정 경기 거절) 추가, `GAME_NOT_FOUND`(기존 코드)는 유지. 계약 원본 `docs/requirements/quiz/quiz-submission-by-inning.md`(승인됨 2026-08-13, QUIZ-SUB-1~73). (직전: 2026-08-12 `expired`(boolean) 필드 신설. `myOption`·`myOptionText`가 nullable로 완화되고 답 없는 항목도 목록에 실림, `submittedAt`이 `updated_at`(답을 낸 시각) 기준으로 재정의, 요약이 미답 문제를 오답으로 집계. 정렬 축은 `id DESC`였다 — **이번 개정으로 폐기**(아래 이닝 축으로 대체).)

**지목한 경기 한 건**의 이닝별 풀이 결산 — 이닝 배열 + 이닝별 요약 + 경기 전체 요약. `QuizSubmitService.getHistory(userAccountId, gameId)`. "경기 하나를 관전하며 이닝마다 문제를 푼다"가 사용 단위라 조회 축도 계정이 아니라 경기이고, 순서 축이 페이지 번호가 아니라 이닝이다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**⚠ `/today`의 제공 가능 검증(오늘(KST)·요청자 응원 구단 참여·`IN_PROGRESS`)은 이 경로에 적용되지 않는다.** 이력은 끝난 경기를 보는 화면이라 그 관문을 두면 기능이 성립하지 않는다 — **어제 끝난 경기·응원하지 않은 구단의 경기·취소 경기도 200**이다(응답이 요청자 본인의 행만 담으므로 임의의 `gameId`를 넣어도 자기 기록 0건이 나올 뿐 아무것도 새지 않는다). 검증은 아래 둘뿐이며 순서가 고정이다: **404(경기 미존재) → 403(예정 경기, `SCHEDULED`) → 200(집계)**.

**쿼리 파라미터**

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| gameId | String | 없음(필수) | **내부 PK가 아니라 `games.naver_game_id` 문자열** — `/today`와 같은 값(`GameResponse.gameId`). 누락 또는 빈 문자열이면 400(이력 조회 쿼리 자체가 실행되지 않는다). 존재하지 않는 값이면 404 `GAME_NOT_FOUND` |

**이닝 열거 범위 — 경기 상태(`game_statuses.name`)가 1차 축이다.** id 리터럴이 아니라 이름 문자열로 판정한다(id는 환경마다 다를 수 있음). 두 이닝 컬럼(`current_inning`/`last_inning`)을 섞어 읽거나 한쪽이 비면 다른 쪽으로 대체하는 폴백은 없다.

| 경기 상태 | 읽는 컬럼 | 열거 범위 | 그 컬럼이 NULL이면 |
|---|---|---|---|
| `SCHEDULED` | **읽지 않음** | **없음 — 403 `GAME_NOT_STARTED`** | (해당 없음, 상태만으로 판정) |
| `IN_PROGRESS` | `current_inning` | `1 … current_inning-1`(**진행 중인 현재 이닝은 제외** — 완료된 이닝만 결산. `current_inning=1`이면 범위 없음) | 200 + `innings: []`(방어) |
| `FINISHED` / `DRAW` | `last_inning` | `1 … last_inning`(무승부는 정상 종료의 한 형태라 종료와 동일 처리) | 200 + `innings: []`(방어) |
| `CANCELED` | `last_inning` | 값이 있으면 `1 … last_inning`, 없으면 범위 없음(노게임은 취소돼도 진행된 이닝의 기록이 남을 수 있어 `SCHEDULED`처럼 접지 않는다) | 200 + `innings: []` |
| 위 표에 없는 상태 이름(예: 향후 `suspended`) | `last_inning` | `FINISHED`와 동일 처리(모르는 상태를 "시작 전"으로 단정해 이미 쌓인 기록을 감추지 않는다) | 200 + `innings: []` |

**그 위에 접히는 규칙이 하나 더 있다(범위가 계산돼도 끝이 아니다):**
- 그 경기에 요청자의 제출 행이 **하나도 없으면**, 위 범위가 몇 이닝으로 계산됐든 `innings`를 **통째로 빈 배열**로 반환한다(`0/0` 원소들을 만들지 않는다) — 그릴 축이 없는 화면이라 접는다.
- 기록이 **하나라도 있으면** 열거 범위의 모든 이닝이 원소로 내려간다 — 받은 문제가 없는 이닝도 `quizzes: []` + `{"correctCount":0,"total":0,"accuracy":0.0}`으로 남는다(빈 이닝만 골라 빼지 않는다 — 배열 길이가 곧 열거 이닝 수라야 FE가 1~N 슬롯을 고정 축으로 그릴 수 있다).

⚠ **`innings: []`는 서로 다른 세 사실을 한 모양으로 덮는다 — 응답만으로는 구별할 수 없다:** ① 그 경기에 내 기록이 0건 ② 열거에 쓸 이닝 값(`current_inning`/`last_inning`)이 NULL(수집 지연 등에 대한 방어 — 원천은 py-collector이고 이 앱은 통제하지 못한다, 값이 채워지면 재조회만으로 정상화됨) ③ `IN_PROGRESS`이고 `current_inning=1`이라 결산할 완료 이닝이 아직 없음(1회에 이미 세트를 받아 풀었어도 마찬가지 — "완료된 이닝만 결산한다"의 귀결). 지금은 화면 표시가 같아도 무방하다고 보고 **사유 필드를 두지 않는다**(감출 것이 있어서가 아니라 필요가 아직 없어서 — 구분 요구가 나오면 `emptyReason` 같은 필드를 더하는 별도 요구사항으로 다룬다).

**응답 200 OK** `ApiResponse<QuizSubmissionHistoryResponse>` — 페이징 없음.

| 필드 | 타입 | 설명 |
|---|---|---|
| data.summary.correctCount | long | 열거된 이닝 전체의 정답 수 합계(이닝 배열과 항상 일치 — 별도 count 쿼리가 아니라 이닝 합계로 유도) |
| data.summary.total | long | 열거된 이닝 전체의 받은 문항 수 합계. **열거 범위 밖(진행 중인 현재 이닝 등) 행은 포함하지 않는다** |
| data.summary.accuracy | double | `correctCount/total`(0.0~1.0), `total=0`이면 `0.0`(NaN 아님) |
| data.summary.earnedPoint | long(신규) | 정답 행(`is_answer=true`)의 `quizzes.score` 합. **적립 원장이 아니라 표시용 근사치**(`users_account.point`는 읽지 않는다) — 배점이 사후 수정되면 실제 적립액과 어긋날 수 있음을 계약으로 인정 |
| data.innings[].inning | int | 이닝 번호(1부터, 오름차순) |
| data.innings[].summary.correctCount / total / accuracy | | 그 이닝만의 같은 산식 — **분모(`total`)는 그 이닝에 실제로 받은 문항 수**(고정 20이 아니다) |
| data.innings[].quizzes[] | array | 그 이닝의 문제 목록. **행 `id` 오름차순(받은 순서)**. 기록 없는 이닝도 원소로 남고 `quizzes: []` |

**data.innings[].quizzes[] 항목 필드**(13개 — `quizDate`·`myOptionText`·`answerText`는 삭제됨):

| 필드 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK |
| question | String | 문제 본문 |
| type | String | `"객관식"` \| `"O/X"` |
| difficulty | String \| null | `EASY`/`MEDIUM`/`HARD`/`EXPERT`. null 가능 |
| options | array(신규) | `[{"no":0,"text":"O"},...]` — 0-기반, 번호 오름차순. 텍스트 두 필드(`myOptionText`·`answerText`)가 사라진 대신 클라이언트가 이 배열에서 번호로 찾는다. **투표 수(`voteCount`)는 없다** — 그건 `/today`(2026-08-19 신설) 전용이고 이 응답의 `options`는 여전히 `{no, text}`뿐이다(불변) |
| myOption | Integer \| null | 내가 고른 보기 번호. **답하지 않은 항목이면 null** |
| correct | boolean | `quiz_users_submit.is_answer` 그대로(재계산 안 함 — 정답이 사후 정정돼도 당시 판정을 보존). 미답이면 `false` |
| expired | boolean | 답 없이 시한(받은 시각+8분)을 넘겼는지. 답한 항목은 항상 `false` |
| answer | int | 정답 보기 번호. **미답 항목에도 실린다** — 이 목록의 항목은 정의상 전부 이미 받은 문제라 감출 대상이 아니다 |
| earnedPoint | long | 그 문제로 적립된 포인트(정답 아니면 0, `score` NULL이면 0) |
| submittedAt | LocalDateTime | 답을 낸 시각(그 행의 `updated_at`). **미답 항목은 낸 시각이 없어 받은 시각(`created_at`)이 그대로 남는다** |
| liked | boolean | 내 현재 좋아요 상태(항상 존재, 미답 항목도 포함 — 좋아요는 받은 문제 전체에 열려 있다) |
| likeCount | long | `liked=true` 행 수(취소한 좋아요는 세지 않음) |

**비용**: 이닝 수·문항 수와 무관하게 SQL이 상수다 — 보기는 `quiz_id IN` 조회 1건, 좋아요는 고정 2쿼리(집계 1 + 내 좋아요 1). 이닝마다 쿼리를 도는 구현(이닝 N+1)은 금지. 읽기 전용(`@Transactional(readOnly = true)`) — 조회 전후로 `quiz_users_submit`·`quizzes_like`·`users_account.point`가 변하지 않는다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | (검증) | `gameId` 쿼리 파라미터 누락 또는 빈 문자열 — 공통 래퍼로 응답, `data`는 `null`. 이력 조회 쿼리가 실행되지 않는다 |
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰, 2026-08-17부터 — 위 "이 도메인의 특이사항" 참고) |
| 403 | GAME_NOT_STARTED(신규) | 지목한 경기가 `SCHEDULED` — 이닝 컬럼을 읽지 않고 상태만으로 거절. `QUIZ_NOT_SERVABLE`(출제 거절 문구)을 재사용하지 않는다 |
| 404 | GAME_NOT_FOUND | `gameId`가 어떤 `games` 행과도 매칭되지 않음(기존 코드 재사용, 새 코드 아님) |

```bash
# 성공 — 진행 중인 경기, 1회는 결산됐고 2회는 기록이 있는 경기라 0/0으로 남는다
curl "http://localhost:8081/rt/quizzes/submissions?gameId=20260812SSHT02026" -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{
#   "summary":{"correctCount":9,"total":14,"accuracy":0.642857,"earnedPoint":450},
#   "innings":[
#     {"inning":1,"summary":{"correctCount":9,"total":14,"accuracy":0.642857},
#      "quizzes":[
#        {"quizId":30,"question":"...","type":"O/X","difficulty":"EASY",
#         "options":[{"no":0,"text":"O"},{"no":1,"text":"X"}],
#         "myOption":0,"correct":true,"expired":false,"answer":0,
#         "earnedPoint":50,"submittedAt":"2026-08-13T19:03:11","liked":false,"likeCount":2}]},
#     {"inning":2,"summary":{"correctCount":0,"total":0,"accuracy":0.0},"quizzes":[]}
#   ]},"message":null}

# 그 경기에서 문제를 한 번도 받지 않은 경우 — 열거 범위가 계산돼도 통째로 접힌다(빈 이닝 원소가 아니다)
curl "http://localhost:8081/rt/quizzes/submissions?gameId=20260812LTKT02026" -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"summary":{"correctCount":0,"total":0,"accuracy":0.0,"earnedPoint":0},
#   "innings":[]},"message":null}

# gameId 누락 (실측 규약)
curl http://localhost:8081/rt/quizzes/submissions -H 'Authorization: Bearer eyJ...'
# 400 {"success":false,"data":null,"message":"필수 요청 파라미터가 누락되었습니다: gameId"}

# 예정 경기
curl "http://localhost:8081/rt/quizzes/submissions?gameId=20260813LGHH02026" -H 'Authorization: Bearer eyJ...'
# 403 {"success":false,"data":null,"message":"아직 시작하지 않은 경기입니다."}

# 존재하지 않는 gameId
curl "http://localhost:8081/rt/quizzes/submissions?gameId=NOPE" -H 'Authorization: Bearer eyJ...'
# 404 {"success":false,"data":null,"message":"존재하지 않는 경기입니다."}
```

---

## POST /rt/quizzes/{quizId}/like
> 최종 변경: 2026-08-11 — 신규(2026-08-12 시점 코드 변경 없음)

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
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰, 2026-08-17부터 — 위 "이 도메인의 특이사항" 참고) |
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

## GET /rt/quizzes/{quizId}/vote-count
> 최종 변경: 2026-08-26 — 신규.

아직 답하지 않은 문제의 **보기별 투표 수**를 돌려준다. `QuizService.getQuizVoteCount(userAccountId, quizId)`.
`/today` 는 그 문제를 (경기, 이닝)당 딱 한 번만 내려주므로 분포도 그 순간 한 번만 전달되는데, 이 엔드포인트가 **그 분포를 다시 받을 수 있는 유일한 경로**다. 답을 고민하는 동안 클라이언트가 주기적으로 호출하는 용도다.

**백분율은 서버가 계산해 주지 않는다.** 분모를 서버가 정하면 같은 화면에 있는 `/today` 의 개수와 이 응답의 비율이 서로 어긋날 수 있어서다. 필요하면 클라이언트가 `voteCount` 를 더해 나눈다.

**인증 필요** — `Authorization: Bearer <accessToken>`

**경로 변수**

| 변수 | 타입 | 설명 |
|---|---|---|
| quizId | Long | 퀴즈 내부 PK. `/today` 응답의 `data[].id` 로 얻는다 |

**요청 본문 없음. 쿼리 파라미터 없음.**

**응답 200 OK** `ApiResponse<QuizVoteCountResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data | object \| null | 자격이 없으면 `null`(아래 참고) |
| data.quizId | Long | 요청한 문제 id |
| data.options | array | 보기 목록. **항목 타입이 `/today` 의 `options[]` 와 같다** |
| data.options[].no | int | 보기 번호. **0부터 시작**(`/today` 와 같은 축 — `+1` 하면 정답 표시가 한 칸 밀린다) |
| data.options[].text | String | 보기 지문 |
| data.options[].voteCount | long | 그 보기를 고른 표 수(0 이상, 항상 존재) |

총합·비율 필드는 없다. 정렬은 보기 번호 오름차순.

**⚠ 자격이 없으면 200 + `data:null` 이다(404·403 아님).** 다음 셋은 요청자 입장에서 완전히 같은 응답으로 합쳐진다:

1. 그 문제를 받은 적이 없다(`quiz_users_submit` 행 없음 — 존재하지 않는 `quizId`·미편성 풀 문제 포함)
2. **이미 제출했다**(행의 `submit_option_id IS NOT NULL`)
3. 그 문제에 보기가 하나도 없다(데이터 이상)

상태 코드를 가르지 않는 이유는 [좋아요](#post-rtquizzesquizidlike)의 단일 403 과 같은 계열의 은닉이다 — 404·403 으로 갈리면 **응답 코드만 보고 "저 사람이 그 문제를 받았는지"를 알아낼 수 있다.** 이미 제출한 사람에게 분포를 감추는 것이 이 API 의 목적인데, 감췄다는 사실 자체가 새어 나가면 목적이 반쯤 무너진다. 클라이언트는 `data === null` 이면 폴링을 멈추면 된다.

**시한(8분) 초과 미답 행은 그대로 값을 준다.** 제출은 403 이지만 분포를 못 볼 이유가 없고, 시각으로 갈리는 판정을 여기에 두면 화면이 폴링 도중 조용히 빈 응답으로 바뀐다.

**값의 출처와 신뢰도**: `/today` 와 같은 Redis 집계 키(`quiz:votes:{quizId}`)를 읽은 **근사 스냅샷**이다. 다만 `/today` 와 달리 이 경로는 **읽기만 한다** — 키를 만들지도, 없는 보기를 0 으로 채우지도, TTL 을 다시 걸지도 않는다(조회가 만료된 키를 되살리면 표가 없는 상태가 TTL 만큼 더 유지되기 때문). Redis 장애·키 부재·TTL 만료(기본 12h)·값 파싱 실패는 전부 해당 보기 `voteCount:0` 으로 채워 200 이 그대로 나간다 — **`voteCount:0` 은 "아무도 안 골랐다"와 "집계를 못 읽었다"를 응답만으로 구분하지 못한다**(구분은 서버 WARN 로그로만, 요청당 1건).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | 무토큰(또는 비밀번호 변경 이전에 발급된 토큰 — 위 "이 도메인의 특이사항" 참고) |
| 400 | (공통) | `quizId` 가 숫자가 아님(`/rt/quizzes/abc/vote-count`) |

**403·404·409 는 이 엔드포인트에서 발생하지 않는다.**

```bash
# 받았고 아직 안 푼 문제
curl http://localhost:8081/rt/quizzes/23/vote-count -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":{"quizId":23,"options":[{"no":0,"text":"안타","voteCount":37},{"no":1,"text":"삼진","voteCount":12}]},"message":null}

# 이미 제출했거나 · 받은 적 없거나 · 존재하지 않는 quizId (셋 다 같은 응답)
curl http://localhost:8081/rt/quizzes/999999/vote-count -H 'Authorization: Bearer eyJ...'
# {"success":true,"data":null,"message":null}
```

---

## 관련 문서

- [README.md](README.md) — 응답 래퍼·JWT 인증·401 정책은 quiz 모듈 공통. 2026-08-17부터 토큰 무효화(비밀번호 변경) 판정도 포함.
- [chat.md](chat.md) — 같은 quiz 모듈(포트 8081, `/rt`)의 채팅 도메인. `PageResponse` 규약의 원 출처.
- 요구사항: `docs/requirements/user/access-token-invalidation.md`(USER-ATI-12 — quiz(8081) 인증 경로에도 이 대조가 동일 적용된다는 계약의 출처. user 모듈 문서이지만 파급 범위에 quiz가 명시돼 있다)
