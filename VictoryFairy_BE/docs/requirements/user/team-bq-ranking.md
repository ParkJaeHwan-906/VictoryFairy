# 응원 구단 내 BQ 순위 조회 요구사항
> 상태: **승인됨 (2026-09-04, 사용자 승인)** · 모듈: user · 최종 수정: 2026-09-04
> 신규 엔드포인트 3개(`GET /api/rankings/bq/top` · `GET /api/rankings/bq` · `GET /api/rankings/bq/me`) + 기존 `GET /api/users/me` 응답에 키 1개(`bqRank`) 추가.
> ID 규칙: `USER-RK-<n>`. 기존 user 계열(`USER-ME-*`·`USER-GSP-*` 등)과 번호 공간이 다르다.
> 선행 계약: `docs/requirements/user/me-profile.md`(`users_bq` 스키마·`bqScore` 안전망, USER-ME-3~6·18~20) · `docs/requirements/quiz/quiz-point-bq-split.md`(`bq_score`가 증가하는 유일한 경로, QUIZ-PBQ-16·25) · `docs/requirements/user/support-selection.md`(활성 응원 구단 1개 정책). **이 문서는 그 셋의 스키마·쓰기 경로를 한 줄도 바꾸지 않고 읽기만 한다.**
> **2026-09-04 승인 시점 개정**: 초안의 미해결 질문 9건이 전부 확정됐다(하단 "결정 기록"). 8건은 초안의 가정(A안) 그대로이고, **탈퇴 계정 처리(Q4)만 뒤집혔다 — 탈퇴 계정을 모집단에 포함한다**(USER-RK-12 개정, "제약"의 `exit_at` 항목 정정). `(가정)` 표시는 이 문서에 남아 있지 않다. `me-profile.md`의 USER-ME-13·22·44는 같은 날 키 10개·SELECT 9회로 함께 정정됐다.

## 배경 / 목적
`users_bq.bq_score`는 2026-09-03 배점 분리로 처음 증가 경로가 생겼지만, 그 값을 **남과 비교해 보여 주는 화면은 아직 없다**(`GET /api/users/me`의 `bqScore`는 내 값 하나뿐이다). 이 문서는 그 값을 "같은 구단을 응원하는 사람들 사이의 순위"로 노출하는 첫 소비처를 정의한다. 순위 축은 **점수(bq)** 하나이고 재화 축(`point`)은 순위 대상이 아니다 — 캐릭터 구매로 줄어드는 값이라 순위 축으로 쓰면 "많이 쓴 사람이 떨어지는" 순위가 된다.

## 용어
| 용어 | 뜻 |
|---|---|
| **BQ 점수** | `users_bq.bq_score`(BIGINT, 계정당 1행). 사용자 요청 원문의 "BQ rate"는 **이 값이다(결정 1)**. 이름이 "rate"지만 비율이 아니라 **적립 누적치**다 — `GET /api/users/me`의 `quizAccuracy`(정답률, 비율)와 다른 값이다 |
| **활성 응원 구단** | 그 계정의 `user_support_team` 중 `oppose IS NULL`인 행의 구단. 정책상 계정당 최대 1개(`support-selection.md`) |
| **순위 모집단** | 요청자와 **같은 활성 응원 구단**을 가진 계정 집합. 세부 포함 조건은 USER-RK-10~15. **탈퇴 여부는 조건이 아니다**(결정 4) |
| **순위 항목** | 순위표의 한 줄. 키는 `{rank, profileImgUrl, nickname, bqScore}` 4개(USER-RK-20) |

## 엔드포인트
| 사용자 요청의 이름 | 경로 | 응답 `data` | 내용 |
|---|---|---|---|
| `topRanking` | `GET /api/rankings/bq/top` | 순위 항목 **배열** | 1~3위 |
| `ranking` | `GET /api/rankings/bq` | 순위 항목 **배열** | 1~10위 |
| `myRanking` | `GET /api/rankings/bq/me` | 순위 항목 **객체 1개** 또는 `null` | 요청자 본인 |
| (EXTRA) | `GET /api/users/me` | 기존 응답에 `bqRank` 키 추가 | 요청자 본인의 순위 숫자 |

**응답 래퍼는 세 경로 모두 `ApiResponse<T>`다** — `docs/api/README.md` 공통 규약 1에서 도메인 데이터를 본문으로 돌려주는 엔드포인트는 예외 없이 래퍼를 탄다. 세 경로 모두 **파라미터가 없다**(경로·쿼리·본문 어디에도 구단 id·사용자 id를 받지 않는다 — 구단은 토큰 주체의 활성 응원 구단으로만 정해진다).

## 범위
- 포함
  - 위 신규 엔드포인트 3개의 인증·응답 형태·순위 산정 규칙·안전망(응원 구단 없음·`users_bq` 행 없음)
  - `GET /api/users/me` 응답에 본인 순위 키 1개 추가와 그에 따른 `me-profile.md` 조항(USER-ME-13·22·44) 정정(완료)
- 제외
  - **`point`(재화 축) 순위** · **`quizAccuracy`(정답률) 순위** — 순위 축은 `bq_score` 하나다
  - **전체(구단 무관) 순위** · **구단 간 순위**(구단별 합산 비교) — 모집단은 항상 "내 응원 구단 안"이다
  - **기간별 순위**(주간·월간·시즌) — `users_bq`가 누적 1행이라 기간 산정의 원장이 없다
  - **페이지네이션·11위 이하 조회** — 항목 수는 3·10 고정이며 `?page=`·`?size=` 파라미터를 두지 않는다
  - **순위 변동(전일 대비 상승/하락) 표시** — 이력이 없어 계산할 근거가 없다
  - **다른 사용자의 순위 조회**(`/rankings/bq/{userId}` 류) — 본인 순위만 낸다
  - **목록 안의 본인 식별 키**(`isMe`·`uid`) — 넣지 않는다(결정 9)
  - **캐싱·스냅샷** — 매 요청 현재 값으로 계산한다. 캐싱이 필요해지면 별도 개정
  - **`bq_score` 적립 규칙 변경** · **`users_bq` 스키마 변경** — 선행 계약 그대로
  - **`docs/api/*.md` 갱신** — 구현 후 `api-documenter` 소관(도메인이 새로 생기므로 `docs/api/ranking.md` 신설 + `account.md`·`README.md` 인덱스 갱신이 필요해진다)

## 요구사항 (EARS)

### 인증 — 세 경로 공통
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-1 | 유비쿼터스 | THE 시스템 SHALL 세 경로(`/api/rankings/bq/top`·`/api/rankings/bq`·`/api/rankings/bq/me`) 모두에 유효한 access 토큰을 요구한다 | 각 경로를 `Authorization` 헤더 없이 호출 → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` |
| USER-RK-2 | 예외 | IF 토큰이 위조·만료되었거나 refresh 토큰이면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | `Authorization: Bearer not-a-jwt` → 401. 유효한 refresh 토큰을 `Bearer`로 실어 호출 → 401. 본문은 USER-RK-1과 동일 |
| USER-RK-3 | 예외 | IF 탈퇴한 계정의 access 토큰으로 요청하면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 탈퇴 후 같은 access 토큰으로 세 경로 각각 호출 → 401(순위가 반환되지 않음). **요청자**가 탈퇴자이면 막히는 것이고, 탈퇴자가 **모집단**에 남는 것(USER-RK-12)과는 별개다 |
| USER-RK-4 | 유비쿼터스 | THE 시스템 SHALL 순위 모집단의 구단과 `myRanking`의 대상 계정을 access 토큰에서만 식별하고 경로·쿼리·본문으로 받지 않는다 | 세 경로 모두 경로 변수·필수 쿼리 파라미터가 0개. `?teamId=`·`?userId=`를 붙여도 응답이 파라미터 없이 호출한 것과 동일 |

### 순위 모집단 — 누가 순위에 들어가는가
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-10 | 유비쿼터스 | THE 시스템 SHALL 순위 모집단을 **요청자의 활성 응원 구단과 같은 구단을 활성 응원 중인 계정**으로 한정한다 | 구단 6 응원 계정 A·B, 구단 1 응원 계정 C가 있을 때 A의 `GET /api/rankings/bq` 응답에 B는 있고 C는 없다. C의 응답에는 A·B가 없다 |
| USER-RK-11 | 유비쿼터스 | THE 시스템 SHALL 응원을 취소한(`oppose IS NOT NULL`) 구단 행을 모집단 판정에 쓰지 않는다 | 구단 6을 응원하다 취소하고 구단 1로 바꾼 계정 D → 구단 6 응원자의 순위표에 D가 없고, 구단 1 응원자의 순위표에 D가 있다 |
| USER-RK-12 | 유비쿼터스 | THE 시스템 SHALL 모집단 판정에 계정의 탈퇴 여부(`users_account.exit_at`)를 조건으로 쓰지 않는다 — 탈퇴(soft delete) 계정도 활성 응원 행이 있으면 모집단에 **포함**한다 | 구단 6 1위 계정이 `DELETE /api/users/me`로 탈퇴 → 직후 `GET /api/rankings/bq/top`에 그 계정이 **여전히 `rank: 1`**로 있다(닉네임·점수 그대로). 30일 뒤 만료 데이터 정리(`expired-data-cleanup.md`)로 `users` 행이 하드 삭제되면 CASCADE로 `users_bq`·`user_support_team` 행이 함께 사라져 그때부터 순위표에서 빠진다 |
| USER-RK-13 | 유비쿼터스 | THE 시스템 SHALL `bq_score`가 0인 계정도 모집단에 포함한다 | 구단 6 응원자가 점수 30·0·0 세 명뿐이면 `GET /api/rankings/bq` 응답 항목이 **3건**(`rank` 1·2·2). 0점 계정이 빠져 1건이 되지 않는다 |
| USER-RK-14 | 예외 | IF 모집단 후보 계정에 `users_bq` 행이 없으면, THEN THE 시스템 SHALL 그 계정의 점수를 0으로 간주해 모집단에 포함한다 | `users_bq` 행이 없는 구단 6 응원 계정 E → 순위표에 `bqScore: 0`으로 나타난다(빠지지도, 500도 아니다). `GET /api/users/me`의 USER-ME-19 안전망(행 없음 → `bqScore: 0`)과 같은 값 |
| USER-RK-15 | 유비쿼터스 | THE 시스템 SHALL 요청자 본인을 모집단에서 빼지 않는다 | 구단 6 응원자가 요청자 한 명뿐이면 `GET /api/rankings/bq` → 항목 1건이며 그 항목의 `nickname`이 본인 닉네임, `rank`가 1 |

### 순위 산정 규칙
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-16 | 유비쿼터스 | THE 시스템 SHALL 순위를 `bq_score` **내림차순**으로 매긴다 | 점수 50·30·10 세 계정 → `rank` 1·2·3이 각각 50·30·10 |
| USER-RK-17 | 유비쿼터스 | THE 시스템 SHALL 같은 `bq_score`인 계정에 **같은 순위**를 부여하고, 다음 순위는 동점자 수만큼 건너뛴다(1·1·3 방식) | 점수 50·50·30 세 계정 → `rank`가 1·1·3. `1·2·3`(순차)도 `1·1·2`(밀집)도 아니다 |
| USER-RK-18 | 유비쿼터스 | THE 시스템 SHALL 동점 계정의 **목록 내 배치 순서**를 `users_account.id` 오름차순(가입이 빠른 계정 먼저)으로 고정한다 | 점수가 같은 계정 id 7·3·12 → 응답 배열에서 3·7·12 순. 같은 데이터로 두 번 호출해도 순서가 같다 |
| USER-RK-19 | 유비쿼터스 | THE 시스템 SHALL 본인 순위(`myRanking`·`bqRank`)를 목록의 순위와 **같은 규칙**(USER-RK-16~18)으로 계산한다 | 본인이 `GET /api/rankings/bq` 항목에 있으면 그 항목의 `rank`와 `GET /api/rankings/bq/me`의 `rank`가 같다. 본인이 점수 50 동점 3명 중 하나이면 `rank`는 1이다(배치 순서와 무관) |

### 순위 항목 — 응답 형태
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-20 | 유비쿼터스 | THE 시스템 SHALL 순위 항목의 키 집합을 정확히 `{rank, profileImgUrl, nickname, bqScore}` 4개로 한정한다 | `data[0]`의 키가 위 4개뿐(`length() == 4`). `id`·`uid`·`userAccountId`·`isMe`·`email`·`point`·`quizAccuracy`·`exitAt` 키가 응답 어디에도 없다 |
| USER-RK-21 | 유비쿼터스 | THE 시스템 SHALL `rank`를 1 이상의 JSON 정수로 담는다 | `"rank":1`(문자열 `"1"`·`"1위"` 아님). 0·음수·소수가 나오지 않는다 |
| USER-RK-22 | 유비쿼터스 | THE 시스템 SHALL `nickname`에 그 계정의 `users_account.nickname` **현재 값**을 담는다 | 순위표에 오른 계정이 `PATCH /api/users/me/nickname`으로 닉네임을 바꾼 직후 다시 조회 → 새 닉네임. `nickname`엔 DB UNIQUE가 없으므로 같은 닉네임 항목이 둘 이상 있을 수 있다(오류 아님). 탈퇴 계정도 탈퇴 시점 닉네임 그대로다(탈퇴는 닉네임을 바꾸지 않는다) |
| USER-RK-23 | 유비쿼터스 | THE 시스템 SHALL `profileImgUrl`에 그 계정의 `users_account.profile_img_url` 값을 **BaseURL을 뺀 EP** 그대로 담고, 객체의 실존 여부를 확인하지 않는다 | 값이 `user-profile-img/9f1c….jpg`인 계정 → 응답도 문자 그대로 동일(선행 슬래시·`https://`·버킷명 없음). `GET /api/users/me`·채팅 `MessageResponse`의 `profileImgUrl`과 같은 규칙. 스토리지 조회(S3 `exists`)가 요청당 0회 |
| USER-RK-24 | 예외 | IF 그 계정의 `profile_img_url`이 NULL이면, THEN THE 시스템 SHALL `profileImgUrl`을 `null`로 담는다 | 이미지 미등록 계정 항목 → `"profileImgUrl":null`. 빈 문자열·기본 이미지 EP·키 생략이 아니다 |
| USER-RK-25 | 유비쿼터스 | THE 시스템 SHALL `bqScore`에 그 계정의 `users_bq.bq_score` 현재 값을 JSON 숫자로 담는다 | 행의 `bq_score`가 340이면 `"bqScore":340`. `GET /api/users/me`의 `bqScore`와 같은 계정에서 같은 값 |

### `topRanking` — `GET /api/rankings/bq/top`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-30 | 이벤트 | WHEN 활성 응원 구단이 있는 인증 사용자가 이 경로를 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 순위 항목 배열을 반환한다 | `{"success":true,"data":[…],"message":null}`, `data`는 배열 |
| USER-RK-31 | 유비쿼터스 | THE 시스템 SHALL 배열 항목 수를 **최대 3건**으로 하고 `rank` 오름차순(같은 `rank`끼리는 USER-RK-18 순)으로 정렬한다 | 모집단 20명 → 항목 3건, `data[0].rank <= data[1].rank <= data[2].rank` |
| USER-RK-32 | 유비쿼터스 | THE 시스템 SHALL 이 응답을 같은 시점 `GET /api/rankings/bq` 응답의 **앞 3건과 동일**하게 반환한다 | 두 경로를 연속 호출 → `top.data`가 `ranking.data.subList(0, min(3, size))`와 항목·순서·값까지 일치 |
| USER-RK-33 | 예외 | IF 모집단이 3명 미만이면, THEN THE 시스템 SHALL 있는 만큼만 담아 200을 반환한다 | 구단 6 응원자가 2명 → 항목 2건. `null`로 채우거나 400을 내지 않는다 |
| USER-RK-34 | 예외 | IF 3위 자리에 동점자가 여럿이면, THEN THE 시스템 SHALL USER-RK-18 배치 순서상 앞선 계정까지만 담아 **항목 수 3건을 넘기지 않는다** | 점수 50·40·30·30·30(id 순) → 항목 3건이며 마지막 항목은 30점 중 id가 가장 작은 계정, `rank`는 `1·2·3`. 동점을 모두 실어 5건이 되지 않는다 |

### `ranking` — `GET /api/rankings/bq`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-40 | 이벤트 | WHEN 활성 응원 구단이 있는 인증 사용자가 이 경로를 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 순위 항목 배열을 반환한다 | `{"success":true,"data":[…],"message":null}`, `data`는 배열 |
| USER-RK-41 | 유비쿼터스 | THE 시스템 SHALL 배열 항목 수를 **최대 10건**으로 하고 `rank` 오름차순(같은 `rank`끼리는 USER-RK-18 순)으로 정렬한다 | 모집단 20명 → 항목 10건, `rank`가 단조 증가(같은 값 허용) |
| USER-RK-42 | 예외 | IF 모집단이 10명 미만이면, THEN THE 시스템 SHALL 있는 만큼만 담아 200을 반환한다 | 구단 6 응원자가 4명 → 항목 4건 |
| USER-RK-43 | 예외 | IF 10위 자리에 동점자가 여럿이면, THEN THE 시스템 SHALL USER-RK-18 배치 순서상 앞선 계정까지만 담아 **항목 수 10건을 넘기지 않는다** | 모집단 12명 중 10·11·12번째가 동점 → 항목 10건, 10번째 항목은 동점자 중 id 최소 계정 |
| USER-RK-44 | 유비쿼터스 | THE 시스템 SHALL 요청자 본인이 10위 안이면 본인 항목을 **다른 항목과 같은 형태로** 배열에 포함한다 | 본인이 2위 → `data[1]`이 본인 항목이며 키·형태가 다른 항목과 동일(`isMe` 같은 추가 키 없음 — 결정 9) |

### `myRanking` — `GET /api/rankings/bq/me`
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-50 | 이벤트 | WHEN 활성 응원 구단이 있는 인증 사용자가 이 경로를 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 **순위 항목 객체 1개**를 반환한다 | `{"success":true,"data":{"rank":7,"profileImgUrl":…,"nickname":"gildong","bqScore":120},"message":null}`. `data`가 배열이 아니라 객체 |
| USER-RK-51 | 유비쿼터스 | THE 시스템 SHALL `data`의 `nickname`·`profileImgUrl`·`bqScore`를 요청자 본인의 값으로 담는다 | `data.nickname`이 같은 토큰으로 호출한 `GET /api/users/me`의 `nickname`과 같고, `data.bqScore`가 그 응답의 `bqScore`와 같다 |
| USER-RK-52 | 유비쿼터스 | THE 시스템 SHALL 본인이 10위 안에 있더라도 이 경로에서 본인 항목을 반환한다 | 본인이 1위 → `GET /api/rankings/bq/me` → 200, `data.rank` = 1(`null`·204·"목록을 보라"가 아니다) |
| USER-RK-53 | 유비쿼터스 | THE 시스템 SHALL `data.rank`를 11 이상으로도 반환한다 | 모집단 300명 중 본인이 187위 → `"rank":187`. 10위 밖이라고 `null`·상한값으로 뭉개지 않는다 |
| USER-RK-54 | 예외 | IF 요청자 본인에게 `users_bq` 행이 없으면, THEN THE 시스템 SHALL 본인을 `bqScore: 0`으로 순위 산정(USER-RK-14)해 200을 반환한다 | `users_bq` 행이 없는 계정 → 200, `data.bqScore` = 0, `data.rank`는 0점 계정들의 순위 |

### 응원 구단이 없을 때 — 세 경로 공통 안전망
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-60 | 예외 | **[안전망]** IF 요청자에게 활성 응원 구단이 없으면, THEN THE 시스템 SHALL `GET /api/rankings/bq/top`·`GET /api/rankings/bq`에 200과 **빈 배열**을 반환한다 | 가입 직후(구단 선택 전) 계정 → 두 경로 모두 200, `"data":[]`. 400 `SUPPORT_TEAM_REQUIRED`·404·500이 아니다. `GET /api/games/support`(USER-GSP-16, 응원 구단 없음 → 200 + `[]`)와 같은 기조 |
| USER-RK-61 | 예외 | **[안전망]** IF 요청자에게 활성 응원 구단이 없으면, THEN THE 시스템 SHALL `GET /api/rankings/bq/me`에 200과 `data: null`을 반환한다 | 같은 계정 → 200, `{"success":true,"data":null,"message":null}`. 빈 객체 `{}`·`rank: 0`이 아니다 |
| USER-RK-62 | 유비쿼터스 | THE 시스템 SHALL 활성 응원 구단이 없는 요청자에 대해 다른 구단의 순위를 **어떤 것도** 반환하지 않는다 | 구단 미선택 계정의 세 경로 응답에 어떤 닉네임도 없다(전체 순위·기본 구단으로 폴백하지 않는다) |

**두 `[안전망]` 조항은 정상 경로가 아니다.** 사용자 확인(결정 2)대로 제품상 응원 구단 없는 사용자는 존재하지 않는다 — 온보딩이 구단 선택을 강제한다. 그럼에도 서버가 거절(400)이 아니라 빈 값을 주는 이유는 `/me`(USER-ME-16)·`/games/support`(USER-GSP-16)가 같은 상황을 그렇게 다루고 있어 세 경로만 다르면 프론트의 "구단 없음" 처리가 갈리기 때문이다.

### `GET /api/users/me` 확장 — `bqRank` (EXTRA)
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-70 | 유비쿼터스 | THE 시스템 SHALL `GET /api/users/me` 응답 `data`에 `bqRank` 키를 **항상** 포함한다 | 응답 `data`의 키가 정확히 `{nickname, supportTeam, supportPlayers, point, bqScore, profileImgUrl, characterImgUrl, characterItems, quizAccuracy, bqRank}` **10개**(USER-ME-13, 2026-09-04 정정). 구단 미선택 계정에서도 키가 존재한다 |
| USER-RK-71 | 유비쿼터스 | THE 시스템 SHALL `bqRank`에 `GET /api/rankings/bq/me`의 `data.rank`와 **같은 값**을 JSON 정수로 담는다 | 같은 토큰으로 두 경로를 연속 호출 → `/me`의 `bqRank`와 `/rankings/bq/me`의 `data.rank`가 같다. 순위 객체를 통째로 넣지 않는다(`bqRank`는 숫자 하나) |
| USER-RK-72 | 예외 | **[안전망]** IF 요청자에게 활성 응원 구단이 없으면, THEN THE 시스템 SHALL `bqRank`를 `null`로 담아 200을 반환한다 | `"supportTeam":null`인 응답에서 `"bqRank":null`. `0`·키 생략·400이 아니다 |
| USER-RK-73 | 유비쿼터스 | THE 시스템 SHALL 기존 9개 키의 이름·타입·값을 이번 변경 전후로 동일하게 유지한다 | 같은 데이터에 대해 변경 전후 `/me` 응답에서 `bqRank`를 제외한 부분이 문자 단위로 같다 |
| USER-RK-74 | 유비쿼터스 | THE 시스템 SHALL 이 값을 얻기 위해 `/me`의 SELECT를 **1회만** 추가하고, 그 횟수를 모집단 크기와 무관하게 유지한다 | `show-sql` 기준 `/me` 전체 SELECT가 **9회**(USER-ME-22, 2026-09-04 정정 — 내역표 9번). 모집단 3명인 구단과 3,000명인 구단에서 횟수가 같다 |

### 비기능 — 세 경로 공통
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-RK-80 | 유비쿼터스 | THE 시스템 SHALL 세 경로의 요청을 처리하면서 어떤 행도 생성·수정·삭제하지 않는다 | 호출 전후로 `users_bq`·`users_account`·`user_support_team` 행 수가 같고 `users_bq.updated_at`·`users_account.updated_at`이 불변. 특히 USER-RK-14·54의 안전망이 작동한 뒤에도 `users_bq` 행이 생기지 않는다(`GET /api/users/me` USER-ME-20과 같은 계약) |
| USER-RK-81 | 유비쿼터스 | THE 시스템 SHALL 세 경로 각각의 SELECT 횟수를 **모집단 크기와 무관한 고정 횟수**로 유지한다 | 모집단 3명인 구단과 3,000명인 구단에서 각 경로의 SELECT 횟수가 같다. 모집단 전체를 애플리케이션으로 끌어와 정렬·세면 이 조항이 깨진다 |
| USER-RK-82 | 유비쿼터스 | THE 시스템 SHALL 응답 조립에 필요한 지연 로딩 연관을 트랜잭션 경계 안에서 초기화한다 | `open-in-view: false`인 prod 프로파일 설정으로 호출 → 200이며 `LazyInitializationException`이 나지 않는다 |
| USER-RK-83 | 유비쿼터스 | THE 시스템 SHALL 순위 산정에 `bq_score`의 **요청 시점 현재 값**을 쓰고 별도 스냅샷·캐시를 두지 않는다 | 어떤 계정이 `POST /rt/quizzes/{quizId}/submit`으로 bq를 적립한 직후 순위를 조회 → 새 점수가 반영된 순위(적립 트랜잭션 커밋 이후). 세 경로를 따로 호출하는 사이 점수가 바뀌면 서로 다른 시점의 값이 나올 수 있다(USER-RK-32·71의 "같은 시점"은 그 사이 변경이 없을 때의 진술이다) |
| USER-RK-84 | 예외 | IF 유효한 토큰으로 세 경로에 GET이 아닌 메서드를 요청하면, THEN THE 시스템 SHALL 405와 `ApiResponse` 래퍼를 반환한다 | `POST /api/rankings/bq` + 유효 access → 405, `{"success":false,"data":null,"message":"지원하지 않는 요청 메서드입니다."}`(`web-support` 공통 핸들러). 토큰 없이 비-GET → 401(USER-RK-1이 먼저) |

## 알려진 결과 (설계상 받아들인 것)
1. **탈퇴자가 최대 30일간 순위표에 남는다**(결정 4). 닉네임·점수는 탈퇴 시점 그대로다. 탈퇴자를 "이긴" 사람의 순위는 하드 삭제 시점에 한 칸 올라간다 — 그 사이 순위가 낮게 보이는 것은 버그가 아니다.
2. **탈퇴자의 `profileImgUrl`은 가리키는 객체가 이미 없을 수 있다.** `WithdrawnProfileImageListener`가 탈퇴 커밋 직후 S3 객체를 지우지만 `profile_img_url` 컬럼은 비우지 않는다(`profile-image.md`, 30일 뒤 행 삭제로 함께 사라짐). USER-RK-23이 실존 확인을 하지 않으므로 그 EP는 그대로 나가고, 프론트가 그 값으로 이미지를 요청하면 404를 받는다. 프론트는 이미지 로드 실패를 기본 이미지로 대체하는 처리(이미 `profileImgUrl: null`에 하고 있는 것과 같은 폴백)를 이 경우에도 적용해야 한다. 서버가 존재 확인을 하지 않는 이유: 항목마다 스토리지 호출이 붙으면 USER-RK-81(고정 횟수)이 깨지고, 탈퇴가 아니어도 객체는 언제든 사라질 수 있어 프론트 폴백은 어차피 필요하다.
3. **탈퇴자의 항목은 `(알수없음)`으로 바뀌지 않는다.** `(알수없음)` 더미 계정으로의 소유권 이관은 채팅방·메시지에만 적용되고(`expired-data-cleanup.md`), 그것도 하드 삭제 시점이다. 순위표는 그 전까지 실제 닉네임을 그대로 낸다.
4. **세 경로는 각각 별개 스냅샷이다**(USER-RK-83). 프론트가 `top`·`ranking`·`me`를 연달아 부르는 사이 누군가 적립하면 `top`이 `ranking`의 앞 3건과 어긋날 수 있다. 하나의 응답에 셋을 합쳐 주지 않는 것은 사용자 요청("각각의 EP로 분리")이다.

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **`SecurityConfig`를 수정하지 않는 것이 정답이다.** `/api/rankings/**`는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 자연히 걸린다(`/api/users/**`·`/api/support/**`·`/api/characters/**`·`/games/support`와 같은 성격). `permitAll` 줄을 추가하면 USER-RK-1~3이 한꺼번에 깨진다. 매처·MockMvc 내부 경로에는 context-path(`/api`)를 붙이지 않는다.
- **principal은 내부 PK `Long` id다.** `JwtAuthenticationFilter`가 `findActiveAuthByUid`(`exit_at is null` 포함)로 해석하므로 **요청자**의 탈퇴 차단(USER-RK-3)에 별도 검사가 필요 없다 — `me-profile.md` USER-ME-10과 같은 지점이 같은 일을 한다. 인증 필수 경로라 `@AuthenticationPrincipal Long`이 `null`이 될 수 없다.
- **모집단에는 `exit_at` 조건을 의도적으로 걸지 않는다**(USER-RK-12, 결정 4). 탈퇴는 `exit_at`만 채우는 soft delete이고 응원 행·`users_bq` 행은 그대로라, `users_bq` + `user_support_team`(+ 닉네임·이미지용 `users_account`)만 조인하면 **탈퇴자가 자연히 포함된다** — 그것이 계약이다. "탈퇴자를 걸러야 하지 않나"는 이미 논의돼 포함으로 결정됐으니 `exit_at IS NULL`을 습관적으로 붙이지 말 것. 요청자 쪽(필터)은 활성만, 모집단 쪽은 전부 — **비대칭이 의도**다.
- **`UserBqRepository`에는 조회 메서드가 계정 단건 2종(`findByUserAccount_Id`·`findWithLockByUserAccountId`)뿐이다.** 구단 단위 순위 조회 경로가 없다 — USER-RK-10·16·81을 만족하려면 조회 경로가 필요하다(어떻게 만들지는 구현 판단). ⚠ **`findWithLockByUserAccountId`(비관적 쓰기 락)를 읽기 경로에 쓰지 말 것** — 조회끼리 서로 막고, 퀴즈 적립 트랜잭션(QUIZ-PBQ-24)과 경합한다. `UserAccountRepository.findWithLockById` Javadoc이 같은 경고를 이미 적어 두었다.
- **`users_bq` 행은 세 주체가 만든다**(가입 트랜잭션 · 소셜 가입 `OauthAccountWriter` · 정답 적립 QUIZ-PBQ-25) + 배포 시 백필(USER-ME-26). 정상 데이터라면 계정마다 행이 있지만 **없을 수 있다는 전제로 안전망(USER-RK-14·54)을 둔다** — `/me`의 USER-ME-19가 그렇게 한 것과 같은 이유(배포~백필 사이 창)다.
- **예약 계정 2종(`(알수없음)` 더미 계정 · 채팅 SYSTEM 계정)은 `users_bq` 행을 백필로 갖고 있다.** 현재는 `user_support_team` 행이 없어 USER-RK-10 조건으로 자연히 빠진다. 누군가 이 계정에 응원 구단을 넣으면 순위표에 `(알수없음)`이 등장한다 — 이 문서는 별도 제외 조건을 두지 않는다(그 계정에 응원 행이 생기는 것 자체가 `cleanup.policy.UnknownAccountPolicy` 위반이다).
- **`nickname`에는 DB UNIQUE가 없다.** 동점 정렬의 타이브레이커로 닉네임을 쓰면 결정적이지 않고, 순위표에 같은 닉네임이 둘 이상 실릴 수 있다(USER-RK-22). 프론트가 목록에서 "나"를 찾을 때 닉네임만으로 매칭하면 틀릴 수 있다 — 결정 9(식별 키 없음) 참고.
- **`profileImgUrl` 규칙은 이미 세 곳(`/me`·프로필 업로드 응답·채팅 `MessageResponse`)이 공유한다.** 네 번째 소비처다. 값 가공(BaseURL 결합·기본 이미지 대체·실존 확인)을 서버가 하지 않는다는 규칙을 여기서 깨면 프론트가 경로별로 다른 처리를 하게 된다.
- **`GET /api/users/me`는 `UserProfileService`(클래스 레벨 `readOnly`, 쓰기 경로 없음)가 조립한다.** `bqRank`가 붙으면 협력자가 하나 늘고 SELECT가 9회가 된다(USER-ME-22 정정 완료) — `UserProfileServiceTest`의 `@InjectMocks`와 `UserAccountControllerMeTest`의 `@WebMvcTest` 슬라이스에 새 협력자를 등록하지 않으면 각각 런타임 NPE·컨텍스트 로딩 실패로 깨진다(모듈 컨텍스트에 기록된 재발 함정).
- **새 도메인 패키지가 생긴다.** `docs/api/README.md` 규칙("도메인 이름은 `com.skhynix.user.<domain>` 패키지와 1:1, 새 패키지가 생기면 같은 이름의 문서가 하나 생긴다")에 따라 `ranking` 도메인 문서·인덱스 행(엔드포인트 39→42)이 필요해진다 — `api-documenter` 소관이며 요구사항 위반은 아니다.
- **`:common`의 `ErrorCode` 추가는 이 계약에 없다.** USER-RK-60~61이 200으로 확정돼 신규 상수가 필요 없다. `SUPPORT_TEAM_REQUIRED` 류를 이 경로에 쓰려 들지 말 것.

## 기존 정책과의 충돌 (모듈 컨텍스트 대조)
| 대상 | 현재 기록 | 이 문서의 변경 |
|---|---|---|
| `me-profile.md` USER-ME-13 | `data` 키 집합 **9개** | **10개로 정정 완료**(2026-09-04, `bqRank`). 계약 원본은 이 문서 USER-RK-70~72 |
| `me-profile.md` USER-ME-22 · USER-ME-44 · 내역표 | `/me` SELECT **8회** | **9회로 정정 완료**(내역표 9번 추가). 계약 원본은 USER-RK-74 |
| `me-profile.md` USER-ME-20 | `/me`는 어떤 행도 만들지 않는다 | 충돌 없음 — 순위 조회도 읽기뿐(USER-RK-80) |
| `me-profile.md` USER-ME-10 · 모듈 컨텍스트 "탈퇴 계정 차단 지점은 3곳" | 탈퇴 계정의 토큰은 필터에서 401 | 충돌 없음 — 요청자 차단은 그대로(USER-RK-3). **모집단**에 탈퇴자를 포함하는 것(USER-RK-12)은 인증과 무관한 별개 결정이며, 기존 문서 어디에도 "탈퇴자를 목록에서 숨긴다"는 계약은 없다(채팅은 하드 삭제 시점에야 `(알수없음)`으로 바뀐다) |
| `.claude/modules/user.md`(`/me` SELECT 8회 고정 · 키 9개) | 현행 사실 | 구현 후 `context-keeper`가 갱신 |
| `docs/api/account.md`(`/me` 키 9개 · SELECT 8회) · `docs/api/README.md`(총 39개) | 현행 사실 | 구현 후 `api-documenter` 소관 |
| `quiz-point-bq-split.md` "제외: bq를 소비하는 화면·랭킹·레이팅 산식" | 당시 범위 밖 | 이 문서가 그 첫 소비처다. 그쪽 계약(적립 규칙·스키마)은 바꾸지 않는다 — 충돌 없음 |

## 결정 기록 (2026-09-04 사용자 확정 — 다시 논의하지 않기 위해)
1. **"BQ rate" = `users_bq.bq_score`(누적 적립 점수).** 정답률(`quizAccuracy`)도 별도 산식도 아니다. 응답 키는 `/me`와 같은 `bqScore`.
2. **응원 구단 없는 요청자 → 200 + 빈 배열 / `data: null`.** 제품상 그런 사용자는 없다고 확인됐으나 방어적으로 유지한다. `SUPPORT_TEAM_REQUIRED`(400)를 쓰지 않는 이유는 `/me`·`/games/support`의 같은 상황 처리와 갈리지 않기 위해서다.
3. **동점은 공동 순위(1·1·3), 배치는 `users_account.id` 오름차순, 항목 수 상한(3·10) 고정.** 순차 순위(1·2·3)는 "누가 앞서는가"를 설명할 근거가 없고, `users_bq.updated_at`은 0점 동점자에게는 가입 순과 다르지 않아 이점이 없었다.
4. **탈퇴 계정을 모집단에 포함한다(초안 가정을 뒤집음).** `exit_at` 필터를 걸지 않는다. 하드 삭제(30일 경과)로 행이 사라지면 자연히 빠진다. 요청자 자신은 인증 필터가 탈퇴자를 이미 걸러내므로(USER-ME-10) "탈퇴자가 자기 순위를 본다"는 경우는 생기지 않는다 — 비대칭이지만 모순은 아니다. 귀결은 "알려진 결과" 1~3.
5. **경로 `/api/rankings/bq/top` · `/api/rankings/bq` · `/api/rankings/bq/me`, 항목 키 `{rank, profileImgUrl, nickname, bqScore}`.** 축 이름(`bq`)을 경로에 두어 다른 축이 생겨도 같은 접두 아래 둘 수 있게 했다. `bqRate`(원문 표기)는 같은 값의 세 번째 이름(`bqScore`·`totalBq`)이 되므로 버렸다.
6. **`myRanking`은 객체 1개이며 10위 안이어도 항상 반환한다.** 프론트가 두 응답을 합쳐야만 내 순위를 알 수 있는 형태(10위 안이면 `null`)는 버렸다.
7. **`/me` 키는 `bqRank`, 구단 없으면 `null`.** 기존 키의 "출처 접두 + 의미어" 형태(`bqScore`·`quizAccuracy`)와 같다. `me-profile.md` USER-ME-13·22·44를 같은 날 정정했다.
8. **0점 계정·`users_bq` 행 없는 계정은 0점으로 포함한다.** `/me`의 `bqScore: 0` 안전망과 같은 값을 쓰고, "구단 없음"과 "0점"을 프론트가 구분할 필요를 만들지 않는다.
9. **목록 항목에 `isMe`·`uid`를 넣지 않는다.** `myRanking`이 따로 있으므로 강조 표시는 `rank`+`nickname`+`bqScore` 세 값 일치로 근사한다(닉네임 비유일이라 완전하지 않음 — 알고 택한 것).

## 미해결 질문
없음. (초안의 9건이 2026-09-04 사용자 확정으로 전부 결정 1~9로 옮겨졌다.)
