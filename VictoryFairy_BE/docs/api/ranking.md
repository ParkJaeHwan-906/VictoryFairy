# 순위(ranking) API 명세

> **도메인** `ranking` — 응원 구단 안에서의 BQ 점수 순위(신설).
> **모듈** user (포트 8080) · **경로 접두사** `/api/rankings/bq` · **엔드포인트** 3개
> **컨트롤러** `user/src/main/java/com/skhynix/user/ranking/controller/BqRankingController.java` (`@RequestMapping("/rankings/bq")`)
> **최종 갱신** 2026-09-04 — 도메인 신설(`GET /rankings/bq/top`·`GET /rankings/bq`·`GET /rankings/bq/me`). 계약 원본 `docs/requirements/user/team-bq-ranking.md`(승인됨 2026-09-04, USER-RK-1~84).
> 공통 규약(응답 래퍼·JWT payload·401 4종·시스템 예외 래핑)은 [README.md](README.md)를 먼저 볼 것.
> `GET /api/users/me` 응답에 추가된 `bqRank` 필드는 이 도메인의 순위 규칙을 그대로 재사용한다 — 상세는 [account.md](account.md#get-apiusersme)에 있고, 여기서는 반복하지 않는다.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/rankings/bq/top](#get-apirankingsbqtop) | 200 | 내 응원 구단 안에서 1~3위 |
| GET | [/api/rankings/bq](#get-apirankingsbq) | 200 | 내 응원 구단 안에서 1~10위 |
| GET | [/api/rankings/bq/me](#get-apirankingsbqme) | 200 | 내 응원 구단 안에서 본인 순위(객체 1개) |

## 이 도메인의 특이사항

**순위 축은 `users_bq.bq_score`(적립 누적치) 하나뿐이다.** `point`(재화, 캐릭터 구매로 줄어듦)와 `quizAccuracy`(정답률)는 이 도메인의 순위 대상이 아니다 — 사용자 요청 원문의 "BQ rate"가 가리키는 값은 `bq_score`이지 비율이 아니다.

**세 경로 모두 파라미터가 0개다.** 구단·대상 계정은 access 토큰의 principal(활성 응원 구단)로만 정해진다 — `?teamId=`·`?userId=`를 붙여도 무시된다. 다른 사용자의 순위 조회(`/rankings/bq/{userId}` 류), 전체(구단 무관) 순위, 구단 간 순위, 기간별(주간·월간) 순위, 페이지네이션(11위 이하 조회), 순위 변동 표시는 전부 범위 밖이다.

**`SecurityConfig`를 건드리지 않는 것이 정답이다** — `/api/rankings/**`는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 자연히 걸린다(`/api/users/**`·`/api/support/**`·`/api/characters/**`와 같은 성격). `/teams`·`/players`·`/games`처럼 한 줄을 추가하면 그게 버그다.

**순위 모집단은 요청자의 활성 응원 구단(`user_support_team.oppose IS NULL`)과 같은 구단을 활성 응원 중인 계정 전체다.** ⚠ **탈퇴 여부(`users_account.exit_at`)는 모집단 조건이 아니다** — 탈퇴(soft delete) 계정도 활성 응원 행이 있으면 그대로 포함된다(30일 뒤 하드 삭제로 CASCADE가 응원 행·`users_bq` 행을 지우면 그때 자연히 빠진다). 이는 **요청자 자신의 인증 차단**(탈퇴자는 필터 단계에서 401)과는 별개의, 의도적으로 비대칭인 결정이다.

**동점 처리는 공동 순위(1·1·3 방식)다.** `bq_score` 내림차순으로 매기고, 같은 점수는 같은 `rank`를 받으며 다음 순위는 동점자 수만큼 건너뛴다. 목록 안 배치 순서(동점자끼리의 나열 순서)는 `users_account.id` 오름차순(가입이 빠른 계정 먼저)으로 고정된다. `top`(최대 3건)·`ranking`(최대 10건)은 이 상한에서 잘릴 때도 항목 수 자체를 상한 밖으로 넘기지 않는다 — 동점자를 전부 싣지 않고 배치 순서상 앞선 계정까지만 담는다.

**`users_bq` 행이 없는 계정은 0점으로 모집단에 포함된다**(`GET /api/users/me`의 `bqScore: 0` 안전망과 같은 값). 응답 항목 키는 정확히 `{rank, profileImgUrl, nickname, bqScore}` 4개로 닫혀 있다 — `id`·`uid`·`isMe`·`email`·`point`·`quizAccuracy`·`exitAt` 키는 어디에도 없다.

**SELECT 횟수는 요청마다 모집단 크기와 무관하게 고정된다** — 모집단을 애플리케이션으로 끌어와 정렬·세지 않고 DB가 `ORDER BY ... LIMIT`(목록)·`COUNT`(본인 순위)로 직접 계산한다. 어떤 경로도 행을 만들지 않는다(조회 전용).

---

## GET /api/rankings/bq/top
> 최종 변경: 2026-09-04 — 신규 추가

내 응원 구단 안에서 1~3위. `BqRankingController.getTopRanking()` → `BqRankingService.getTopRanking()`(클래스 레벨 `@Transactional(readOnly = true)`).

**인증 필요** — `Authorization: Bearer <accessToken>`. `/api/rankings/**`는 `SecurityConfig`에 별도 `permitAll` 줄이 없어 `anyRequest().authenticated()`에 자연히 걸린다.

**대상 구단은 access 토큰의 활성 응원 구단에서만 정해진다.** 경로·쿼리·본문 어디에도 파라미터가 없다.

**요청**: 없음. 파라미터·본문 없음.

**응답 200 OK** `ApiResponse<List<BqRankingResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data | array | 순위 항목 배열. **최대 3건**, `rank` 오름차순(같은 `rank`끼리는 `users_account.id` 오름차순) |
| data[].rank | int | 1 이상의 JSON 정수. 동점은 같은 값(1·1·3 방식) |
| data[].profileImgUrl | String \| null | `users_account.profile_img_url`을 BaseURL을 뺀 EP 그대로. 객체 실존 여부는 확인하지 않는다(`/me`·채팅과 같은 규칙) — 이미지가 없으면 `null` |
| data[].nickname | String | `users_account.nickname` 현재 값. UNIQUE가 없어 같은 닉네임이 둘 이상일 수 있다 |
| data[].bqScore | long(JSON 숫자) | `users_bq.bq_score`. 그 계정에 행이 없으면 `0` |

**이 응답은 같은 시점 `GET /api/rankings/bq` 응답의 앞 3건과 동일하다**(별개 요청이라 완전히 같은 시점은 아니다 — 그 사이 점수가 바뀌면 어긋날 수 있음, 캐시·스냅샷 없음). 모집단이 3명 미만이면 있는 만큼만(0~2건) 담아 200을 반환한다 — `null`로 채우거나 400을 내지 않는다.

**활성 응원 구단이 없으면 200 + 빈 배열**을 반환한다 — 400 `SUPPORT_TEAM_REQUIRED`·404·500이 아니다(`GET /api/games/support`·`GET /api/users/me`의 같은 상황 처리와 같은 기조). 제품상 구단 미선택 사용자는 없다고 확인됐으나(온보딩이 강제) 방어적으로 유지한다.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰 |
| 405 | (`ApiResponse` 래퍼, ErrorCode 없음) | 유효한 토큰으로 GET 이외의 메서드 요청(`web-support` 공통 핸들러, 토큰이 없으면 405보다 401이 먼저 걸린다) |

**예시**
```bash
curl -i http://localhost:8080/api/rankings/bq/top \
  -H 'Authorization: Bearer eyJ...'
```
```json
{"success":true,"data":[{"rank":1,"profileImgUrl":"user-profile-img/9f1c….jpg","nickname":"gildong","bqScore":340},{"rank":2,"profileImgUrl":null,"nickname":"chulsoo","bqScore":210},{"rank":2,"profileImgUrl":null,"nickname":"younghee","bqScore":210}],"message":null}
```

응원 구단 없음:
```json
{"success":true,"data":[],"message":null}
```

---

## GET /api/rankings/bq
> 최종 변경: 2026-09-04 — 신규 추가

내 응원 구단 안에서 1~10위. `BqRankingController.getRanking()` → `BqRankingService.getRanking()`(클래스 레벨 `@Transactional(readOnly = true)`).

**인증 필요** — `Authorization: Bearer <accessToken>`. `/api/rankings/bq/top`과 같은 이유로 `SecurityConfig` 무수정이 정답이다.

**대상 구단은 access 토큰의 활성 응원 구단에서만 정해진다.** 파라미터 없음.

**요청**: 없음.

**응답 200 OK** `ApiResponse<List<BqRankingResponse>>` — 필드는 [`/top`](#get-apirankingsbqtop)과 완전히 동일한 `BqRankingResponse`. 차이는 상한뿐이다: **최대 10건**, `rank` 오름차순.

모집단이 10명 미만이면 있는 만큼만 담아 200. 10위 자리에 동점자가 여럿이면 배치 순서(`users_account.id` 오름차순)상 앞선 계정까지만 담아 항목 수 10건을 넘기지 않는다. **요청자 본인이 10위 안이면 본인 항목도 다른 항목과 완전히 같은 형태로 포함된다**(`isMe` 같은 강조 키 없음).

**활성 응원 구단이 없으면 200 + 빈 배열**(`/top`과 같은 안전망).

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰 |
| 405 | (`ApiResponse` 래퍼, ErrorCode 없음) | 유효한 토큰으로 GET 이외의 메서드 요청 |

**예시**
```bash
curl -i http://localhost:8080/api/rankings/bq \
  -H 'Authorization: Bearer eyJ...'
```
```json
{"success":true,"data":[{"rank":1,"profileImgUrl":"user-profile-img/9f1c….jpg","nickname":"gildong","bqScore":340},{"rank":2,"profileImgUrl":null,"nickname":"chulsoo","bqScore":210},{"rank":2,"profileImgUrl":null,"nickname":"younghee","bqScore":210},{"rank":4,"profileImgUrl":null,"nickname":"minsu","bqScore":90}],"message":null}
```

---

## GET /api/rankings/bq/me
> 최종 변경: 2026-09-04 — 신규 추가

내 응원 구단 안에서 본인 순위. `BqRankingController.getMyRanking()` → `BqRankingService.getMyRanking()`(클래스 레벨 `@Transactional(readOnly = true)`).

**인증 필요** — `Authorization: Bearer <accessToken>`. 위 두 경로와 같은 이유로 `SecurityConfig` 무수정이 정답이다.

**대상 계정·구단은 access 토큰에서만 정해진다.** 파라미터 없음.

**요청**: 없음.

**응답 200 OK** `ApiResponse<BqRankingResponse>` — **`data`가 배열이 아니라 객체 1개다**(`/top`·`/`와 다름). 필드는 동일한 `BqRankingResponse` 키 4개(`rank`·`profileImgUrl`·`nickname`·`bqScore`), 값은 요청자 본인 것.

**요청자 본인이 10위 안에 있어도, 심지어 300위 밖이어도 이 경로는 항상 본인 항목을 반환한다** — `rank`가 11 이상이어도 `null`·상한값으로 뭉개지 않고 실제 순위(예: `187`)를 그대로 낸다. 목록에 있으면 그 항목의 `rank`와 이 경로의 `rank`가 항상 같다(같은 산정 규칙 재사용).

**요청자 본인에게 `users_bq` 행이 없으면 `bqScore: 0`으로 순위를 산정해 200을 반환한다**(행을 만들지 않는다).

**활성 응원 구단이 없으면 200 + `data: null`을 반환한다** — 빈 객체 `{}`도 `rank: 0`도 아니다. `/top`·`/`의 "빈 배열" 안전망과 짝을 이루는, 목록 없음 대신 객체 없음으로 표현한 같은 계약이다.

**내부 동작**: 활성 응원 구단 조회 1 + 본인 순위 재료 조회(`UserBqRepository.findRankingEntry`, 닉네임·이미지·점수를 한 쿼리로) 1 + 순위 계산(`countHigherInTeam`, `COUNT` 1회) = 총 3회. 모집단 크기와 무관하게 고정.

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰. **필터를 통과한 뒤에도 principal의 계정이 그 사이 사라졌다면 서비스가 방어적으로 같은 코드를 던진다**(정상 경로에서는 발생하지 않음 — `/me`의 같은 패턴과 동일) |
| 405 | (`ApiResponse` 래퍼, ErrorCode 없음) | 유효한 토큰으로 GET 이외의 메서드 요청 |

**예시**
```bash
curl -i http://localhost:8080/api/rankings/bq/me \
  -H 'Authorization: Bearer eyJ...'
```
```json
{"success":true,"data":{"rank":7,"profileImgUrl":"user-profile-img/1a2b….jpg","nickname":"gildong","bqScore":120},"message":null}
```

응원 구단 없음:
```json
{"success":true,"data":null,"message":null}
```

---

## 알려진 결과 (설계상 받아들인 것, 코드 확인됨)

- **탈퇴자가 최대 30일간 순위표에 남는다.** 닉네임·점수는 탈퇴 시점 그대로다. 탈퇴자를 "이긴" 사람의 순위는 하드 삭제 시점(30일 경과, `expired-data-cleanup.md`)에야 한 칸 올라간다.
- **탈퇴자의 `profileImgUrl`은 이미 없는 객체를 가리킬 수 있다** — 탈퇴 커밋 직후 S3 객체는 지워지지만(`WithdrawnProfileImageListener`) `profile_img_url` 컬럼 값 자체는 비우지 않는다. 서버는 실존 확인을 하지 않으므로(항목마다 스토리지 호출을 붙이면 고정 SELECT 횟수 계약이 깨진다) **프론트가 이미지 로드 실패 시 기본 이미지로 대체하는 폴백을 여기서도 적용해야 한다**(`profileImgUrl: null`에 이미 하고 있는 처리와 같은 방식).
- **탈퇴자의 항목은 `(알수없음)` 더미 계정으로 바뀌지 않는다** — 그 소유권 이관은 채팅방·메시지에만, 그것도 하드 삭제 시점에만 적용된다. 순위표는 그 전까지 실제 닉네임을 그대로 낸다.
- **세 경로는 각각 별개 스냅샷이다.** `top`·`ranking`·`me`를 연달아 호출하는 사이 누군가 적립하면 서로 어긋날 수 있다 — 하나의 응답에 셋을 합쳐 주지 않는다.

## 확인 필요 / 코드 미확인

- 없음(요구사항 `docs/requirements/user/team-bq-ranking.md`의 미해결 질문도 0건 — 2026-09-04 전부 확정됨).

## 관련 문서

- [계정(account)](account.md) — `GET /api/users/me` 응답의 `bqRank` 필드가 이 도메인(`BqRankingService.rankOf`)의 순위 규칙을 재사용한다.
- [응원(support)](support.md) — 순위 모집단을 가르는 활성 응원 구단(`oppose IS NULL`)의 출처.
- 요구사항: `docs/requirements/user/team-bq-ranking.md`(USER-RK-1~84, 승인됨 2026-09-04) · `docs/requirements/user/me-profile.md`(`bqScore` 안전망의 선행 계약) · `docs/requirements/quiz/quiz-point-bq-split.md`(`bq_score`가 증가하는 유일한 경로)
