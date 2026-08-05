# 내 요약 프로필 조회 요구사항
> 상태: **확정** (2026-08-04) · 모듈: user (+ 선행 스키마 변경은 domain) · 최종 수정: 2026-08-04
> 선행 도메인: `users_account.point` 컬럼 추가 · `users_bq` 테이블 신설. **둘 다 아직 존재하지 않는다** — 이 문서가 그 스키마와 **회원가입 경로 변경·기존 계정 백필까지** 계약으로 포함한다.
> **2026-08-04 개정**: 사용자 답변 반영 — `users_bq`는 계정과 **1:1**, 행은 **회원가입 시 함께 생성**(초안의 lazy 생성 가정을 뒤집음), 응원 구단은 제품상 필수, 백필은 **운영자 수동 실행**(`infra/sql/users-bq-backfill.sql`). 이 결정으로 **기존 계정 백필이 범위 밖에서 범위 안으로 들어왔다**(USER-ME-26~29).
> **미해결 질문 없음 — 이 문서는 동결됐다.**

## 배경 / 목적
`/api/member/users/me`는 지금 `DELETE`(탈퇴) 하나뿐이고, `docs/api/account.md`가 "프로필 조회·수정 엔드포인트는 아직 없다 — 이 도메인에 생길 자리다"라고 적어 둔 자리를 채운다.

계약의 쟁점은 "네 값을 준다"가 아니라 **세 가지**다.

1. **노출 경계** — 이 엔드포인트는 계정 엔티티를 처음으로 응답에 싣는 자리다. `UserAccount`를 그대로 직렬화하면 `password`(bcrypt 해시)와 `uid`가 나간다. `docs/api/README.md`는 **"이 API 전체에서 어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다"**를 전역 사실로 적고 있으므로, 이 계약은 노출 키 집합을 닫아야 한다.
2. **"항상 있다"는 전제와 그 전제가 깨지는 창(window)** — `users_bq` 행은 가입 시 함께 만들고(USER-ME-23), 응원 구단은 제품상 필수다. **그러나 둘 다 스키마가 강제하지 않는다.** 배포~백필 사이, 그리고 가입 완료~구단 선택 사이에 전제가 성립하지 않는 계정이 실재한다. 정상 경로는 단순하고, 계약의 본체는 그 창에서 `/me`가 **500이 아니라 200**을 돌려준다는 안전망 쪽이다(USER-ME-16 · USER-ME-19).
3. **읽기가 쓰기를 하지 않을 것** — 위 안전망을 "없으면 만들어 준다"로 구현하면 GET이 상태를 바꾸고, `open-in-view: false`인 prod의 읽기 트랜잭션 경계와도 어긋난다. 안전망은 **응답 값으로만** 메운다(USER-ME-20).

## 범위
- 포함
  - `GET /api/member/users/me` 1개(access 토큰 필수) — 닉네임 · 응원 구단 · 보유 포인트 · 누적 획득 점수
  - 선행 스키마 2건: `users_account.point` 컬럼 추가, `users_bq` 테이블 신설(엔티티·리포지토리는 `:domain`)
  - **회원가입 경로 변경** — `POST /api/member/auth/signup` 트랜잭션이 `users_bq` 행을 함께 만든다(USER-ME-23~25·30). 이 문서는 조회 엔드포인트만의 계약이 아니다
  - **기존 계정 백필** — 배포 시 운영자가 1회 수동 실행 + 검증(USER-ME-26~29). 스크립트는 `infra/sql/users-bq-backfill.sql`에 둔다
  - 응답 DTO(`user.account.dto`)
- 제외
  - **`point`·`bq_score`를 증감시키는 주체·규칙** — 적립/차감/점수 획득 경로는 이번 범위가 아니다. 이 엔드포인트는 **읽기 전용**이며, 두 값을 누가 언제 바꾸는지는 별도 요구사항에서 정의한다. (그때까지 두 값은 0에서 움직이지 않는다)
  - **프로필 수정**(`PATCH`/`PUT /users/me`) — 닉네임 변경 등은 별도 요구사항
  - **응원 선수 목록·이메일·전화번호·가입일 노출** — 요청된 4개 항목 밖이다. 필요해지면 키를 추가하는 별도 개정
  - **포인트·점수 이력 조회**(적립 내역 리스트) — `users_bq`가 계정당 1행이라 이력 자체가 쌓이지 않는다
  - **랭킹·다른 사용자 프로필 조회** — 대상 계정은 항상 토큰 주체 본인이다
  - **캐시(HTTP 캐시 헤더·서버 캐시)** — 값이 자주 바뀌는 성격이라 캐시 전략은 별도 판단
  - **"구단 미선택 사용자의 다른 API 차단"** — 구단 선택을 서버가 강제하는 것은 `support-selection.md`가 이미 범위 밖으로 둔 별도 요구사항이다. 이 문서는 그 창에서 `/me`가 어떻게 응답하는지만 정한다(USER-ME-16)

## 엔드포인트

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| GET | `/api/member/users/me` | 필수(access) | 200 `ApiResponse<프로필>` |

**이 문서는 기존 엔드포인트 하나의 동작도 바꾼다**: `POST /api/member/auth/signup`(`docs/api/auth.md`)의 **요청·응답 계약은 그대로지만 트랜잭션이 하는 일이 늘어난다** — 계정 생성과 같은 트랜잭션에서 `users_bq` 행이 만들어진다(USER-ME-23~25·30). 가입 API 문서에도 이 부수 효과를 반영해야 한다.

**응답 래퍼는 `ApiResponse<T>`다.** 근거: `docs/api/README.md`의 래퍼 표에서 **raw인 것은 auth의 signup/login/refresh/logout 4개와 본문이 없는 탈퇴(204)뿐**이고, 도메인 데이터를 본문으로 돌려주는 엔드포인트(team·player·game·support·validate·email·chat)는 **예외 없이 전부 `ApiResponse`**다. 이 엔드포인트는 후자다. 같은 `account` 도메인의 탈퇴가 raw인 것은 "본문이 없어서"이지 도메인 규칙이 아니다.

## 요구사항 (EARS)

### 선행 스키마 — `:domain`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-1 | 유비쿼터스 | THE 시스템 SHALL `users_account` 테이블에 `point` 컬럼(BIGINT, NOT NULL, 기본값 0)을 보유한다 | `SHOW COLUMNS FROM users_account LIKE 'point'` → `Type=bigint`, `Null=NO`, `Default=0` |
| USER-ME-2 | 유비쿼터스 | THE 시스템 SHALL 컬럼 추가 이전부터 존재하던 계정의 `point`를 0으로 유지한다 | 컬럼 추가 후 `SELECT COUNT(*) FROM users_account WHERE point IS NULL OR point <> 0` = 0 |
| USER-ME-3 | 유비쿼터스 | THE 시스템 SHALL `users_bq` 테이블을 보유하며 그 컬럼은 `id`(BIGINT PK AUTO_INCREMENT) · `user_account_id`(BIGINT NOT NULL) · `bq_score`(BIGINT NOT NULL, 기본값 0) · `created_at`(DATETIME NOT NULL) · `updated_at`(DATETIME NOT NULL)이다 | `SHOW COLUMNS FROM users_bq` 결과가 위 5개와 일치 |
| USER-ME-4 | 유비쿼터스 | THE 시스템 SHALL `users_bq.user_account_id`에 `users_account.id`를 참조하는 FK를 두고, 참조된 계정 행이 삭제되면 `users_bq` 행도 함께 삭제한다(ON DELETE CASCADE) | `SHOW CREATE TABLE users_bq` 에 `FOREIGN KEY (user_account_id) REFERENCES users_account (id) ON DELETE CASCADE`. DB에서 계정 행을 직접 삭제하면 대응 `users_bq` 행이 0건이 됨 |
| USER-ME-5 | 유비쿼터스 | THE 시스템 SHALL `users_bq.user_account_id`에 UNIQUE 제약을 두어 계정당 행이 1개를 넘지 않도록 보장한다 | 같은 `user_account_id`로 2번째 행을 INSERT 하면 UNIQUE 제약 위반. `SELECT user_account_id, COUNT(*) FROM users_bq GROUP BY 1 HAVING COUNT(*) > 1` = 0건 |
| USER-ME-6 | 유비쿼터스 | THE 시스템 SHALL `users_bq` 행 생성 시 `created_at`을, `bq_score` 변경 시 `updated_at`을 갱신한다 | 신규 행에서 `created_at`·`updated_at` 둘 다 non-null. `bq_score`를 바꾼 뒤 `updated_at > created_at`, `created_at` 불변 |

### 회원가입 시 `users_bq` 행 생성 — `POST /api/member/auth/signup`

> 이 절은 `/me` 조회가 아니라 **가입 경로의 계약**이다. "모든 계정에 `users_bq` 행이 있다"는 전제를 만드는 주체가 여기다.

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-23 | 이벤트 | WHEN 회원가입이 성공하면, THE 시스템 SHALL 그 계정의 `users_bq` 행을 `bq_score = 0`으로 1개 생성한다 | 가입 직후 `SELECT bq_score FROM users_bq WHERE user_account_id = <신규 계정 id>` → 1행, 값 0. 가입 응답 계약(201, `Boolean`)은 변하지 않음 |
| USER-ME-24 | 유비쿼터스 | THE 시스템 SHALL `users_bq` 행 생성을 계정 생성과 **같은 트랜잭션**에서 수행한다 | 가입 요청 1건으로 `users_account`·`users_bq` 두 행이 함께 커밋됨. 별도 요청·별도 커밋·비동기 후처리가 필요하지 않음 |
| USER-ME-25 | 예외 | IF 가입 트랜잭션이 어느 단계에서든 실패하면, THEN THE 시스템 SHALL `users_bq` 행을 남기지 않는다 | 중복 이메일 등으로 가입이 409로 실패한 뒤 `users_bq` 행 수가 요청 전과 동일 |
| USER-ME-30 | 예외 | IF `users_bq` 행 생성이 실패하면, THEN THE 시스템 SHALL 계정도 생성하지 않고 가입을 실패로 처리한다 | `users_bq` 테이블이 없는 환경에서 가입 요청 → 실패 응답이며 `users_account`에 새 행이 남지 않음(계정만 있고 bq 행이 없는 상태가 만들어지지 않음) |

### 기존 계정 백필 (배포 1회)

> USER-ME-23은 **앞으로 가입할 계정**만 커버한다. 이미 적재된 계정에는 행이 없으므로, 전제를 성립시키려면 배포 시 백필이 반드시 함께 돌아야 한다.

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-26 | 유비쿼터스 | THE 시스템 SHALL 배포 시 `users_bq` 행이 없는 모든 기존 `users_account` 행(탈퇴 계정 포함)에 대해 `bq_score = 0` 행을 1개씩 생성한다 | 백필 후 `SELECT COUNT(*) FROM users_account ua WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id)` = 0. 그리고 `COUNT(users_bq)` = `COUNT(users_account)` |
| USER-ME-27 | 유비쿼터스 | THE 시스템 SHALL 백필을 여러 번 실행해도 이미 행이 있는 계정에 행을 추가하지 않는다(멱등) | 같은 백필을 2회 실행 → 2회차 affected rows = 0, `users_bq` 행 수 불변, UNIQUE 위반 오류 없음 |
| USER-ME-28 | 유비쿼터스 | THE 시스템 SHALL 백필로 만드는 행의 `bq_score`를 0으로 하고 기존 행의 `bq_score`는 변경하지 않는다 | 백필 전 `bq_score`가 0이 아닌 행이 있으면 그 값이 백필 후에도 동일 |
| USER-ME-29 | 유비쿼터스 | THE 배포 절차 SHALL 백필 실행 후 USER-ME-26의 검증 쿼리를 실행하고 그 결과가 0임을 확인하기 전까지 배포를 완료로 간주하지 않는다 | 배포 기록에 검증 쿼리와 그 결과(`0`)가 남아 있음. 0이 아니면 배포는 **미완료**이며 백필을 재실행한다(USER-ME-27로 재실행은 안전) |

**백필 스크립트 위치**: `infra/sql/users-bq-backfill.sql` — 기존 `infra/sql/teams-init.sql`·`chat-init.sql`과 같은 자리, 같은 `<대상>-<동작>.sql` 명명이다. 그 두 파일도 **운영에서는 사람이 순서대로 적용하는** 스크립트라 이번 백필과 성격이 같다. **이 문서는 파일을 만들지 않는다 — 구현 시 이 경로에 생성한다.**

**백필 SQL (확정 형태)**
```sql
INSERT INTO users_bq (user_account_id, bq_score, created_at, updated_at)
SELECT ua.id, 0, NOW(6), NOW(6)
FROM users_account ua
WHERE NOT EXISTS (SELECT 1 FROM users_bq b WHERE b.user_account_id = ua.id);
```
- **`NOT IN`이 아니라 `NOT EXISTS`다.** `NOT IN`은 서브쿼리 결과에 `NULL`이 하나라도 섞이면 전체가 0건이 되는 함정이 있다(지금은 `user_account_id`가 NOT NULL이라 결과가 같지만, 조건이 바뀌면 조용히 아무것도 안 넣는 쿼리가 된다). 이 형태가 USER-ME-27(멱등)을 SQL 자체로 보장한다.
- **시각은 `NOW(6)`이다.** Hibernate가 `LocalDateTime`을 `datetime(6)`으로 매핑하므로 `NOW()`(초 단위)를 쓰면 백필 행만 마이크로초가 0으로 잘린다.
- **탈퇴 계정도 포함한다.** `exit_at is not null`을 제외하면 "계정 1행 = bq 1행" 불변식이 깨져 USER-ME-26의 검증 쿼리가 복잡해지고, 탈퇴는 soft delete라 행이 그대로 남아 있어 제외할 실익이 없다.

### 인증 — `GET /api/member/users/me`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-7 | 유비쿼터스 | THE 시스템 SHALL 이 경로에 유효한 access 토큰을 요구한다 | `Authorization` 헤더 없이 호출 → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` |
| USER-ME-8 | 예외 | IF 토큰이 위조·만료되었으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | `Authorization: Bearer not-a-jwt` → 401, 본문은 USER-ME-7과 동일 |
| USER-ME-9 | 예외 | IF refresh 토큰으로 요청하면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 유효한 refresh 토큰을 `Bearer`로 실어 호출 → 401 |
| USER-ME-10 | 예외 | IF 탈퇴한 계정의 access 토큰으로 요청하면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 탈퇴 후 같은 access 토큰으로 호출 → 401 (프로필이 반환되지 않음) |
| USER-ME-11 | 유비쿼터스 | THE 시스템 SHALL 조회 대상 계정을 access 토큰에서만 식별하고 경로·쿼리·본문으로 받지 않는다 | 경로에 식별자가 없고 요청 파라미터가 0개. `?userId=`·`?uid=` 를 붙여도 응답이 토큰 주체 본인의 것과 동일 |

### 응답 본문

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-12 | 이벤트 | WHEN 인증된 사용자가 이 경로를 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 요약 프로필을 반환한다 | `{"success":true,"data":{...},"message":null}` |
| USER-ME-13 | 유비쿼터스 | THE 시스템 SHALL `data`의 키 집합을 정확히 `{nickname, supportTeam, point, bqScore}`로 한정한다 | `data`의 키가 위 4개뿐. `id`·`uid`·`password`·`email`·`tel`·`exitAt`·`createdAt`·`updatedAt` 키가 **응답 어디에도 없음** |
| USER-ME-14 | 유비쿼터스 | THE 시스템 SHALL `nickname`에 그 계정의 `users_account.nickname` 현재 값을 담는다 | DB의 `nickname`과 응답 값이 문자 그대로 일치 |
| USER-ME-15 | 유비쿼터스 | THE 시스템 SHALL `supportTeam`에 그 계정이 **현재 응원 중인**(`user_support_team.oppose is null`) 구단의 `{id, name}`을 담는다 | KIA 응원 중 → `"supportTeam":{"id":6,"name":"KIA"}`. 취소된(`oppose` 채워진) 구단은 반환되지 않음 |
| USER-ME-16 | 예외 | **[안전망]** IF 그 계정에 현재 응원 중인 구단 행이 없으면, THEN THE 시스템 SHALL `supportTeam`을 `null`로 담아 200을 반환한다 | 가입 직후(구단 선택 전) 계정 → 200, `"supportTeam":null`. 400·404·500이 아니며 빈 문자열·빈 객체도 아님 |
| USER-ME-17 | 유비쿼터스 | THE 시스템 SHALL `point`에 `users_account.point` 현재 값을 JSON 숫자로 담는다 | `"point":0` (문자열 `"0"` 아님). DB 값을 1200으로 바꾸면 `"point":1200` |
| USER-ME-18 | 유비쿼터스 | THE 시스템 SHALL `bqScore`에 그 계정의 `users_bq.bq_score` 현재 값을 JSON 숫자로 담는다 | 행의 `bq_score`가 340이면 `"bqScore":340` |
| USER-ME-19 | 예외 | **[안전망]** IF 그 계정의 `users_bq` 행이 없으면, THEN THE 시스템 SHALL `bqScore`를 `0`으로 담아 200을 반환한다 | `users_bq`에 행이 없는 계정 → 200, `"bqScore":0` (`null`·404·500 아님) |
| USER-ME-20 | 유비쿼터스 | THE 시스템 SHALL 이 요청을 처리하면서 어떤 행도 생성·수정·삭제하지 않는다 | 호출 전후로 `users_bq`·`users_account`·`user_support_team`의 행 수가 같고 `users_account.updated_at`이 불변. 특히 **USER-ME-19의 안전망이 작동한 뒤에도 `users_bq` 행이 생기지 않음**(조회가 데이터를 고치지 않는다) |

#### 두 개의 `[안전망]` 조항은 정상 경로가 아니다 (반드시 읽을 것)

**USER-ME-19 (bq 행 없음)** — 정상 상태에서는 **모든 계정에 `users_bq` 행이 존재한다**(USER-ME-23 가입 시 생성 + USER-ME-26 백필). 그럼에도 이 조항을 남기는 이유는 행이 없는 계정이 실제로 존재할 수 있는 창이 둘 있기 때문이다.
- **배포 직후 ~ 백필 실행 전.** prod는 `ddl-auto=update`로 앱이 기동하면서 테이블이 생기므로, 테이블 생성과 백필 사이에는 **기존 계정 전부**가 "행 없음" 상태다.
- **백필 누락·부분 실패.** Flyway가 없어 백필은 **운영자가 손으로 실행하는 절차**다(결정 기록 8). 빠뜨릴 수 있다는 것이 이 방식의 알려진 리스크이며, 그래서 배포 3단계의 검증 쿼리가 USER-ME-29로 계약화돼 있다.

이 창에서 `/me`가 500을 내면 배포 사고가 곧 장애가 된다. **`bqScore: 0`은 "행이 없다"의 표현이 아니라 "점수를 얻은 적 없다"와 같은 값**이므로 클라이언트가 두 상태를 구분할 필요도 없다. 다만 **조회가 행을 만들어 스스로 고치지는 않는다**(USER-ME-20) — 조용히 복구하면 백필 누락이 영원히 드러나지 않고, GET이 쓰기 트랜잭션을 갖게 된다.

**USER-ME-16 (응원 구단 없음)** — "응원 구단은 필수"는 **온보딩 제품 정책이지 스키마·코드 제약이 아니다.** 코드에서 직접 확인한 사실:
- `SignupRequest`(`user/src/main/java/com/skhynix/user/auth/dto/SignupRequest.java`)의 필드는 `name`·`tel`·`email`·`gender`·`nickname`·`password` **6개뿐이며 구단 필드가 없다.**
- 구단 선택은 별도 엔드포인트 `POST /api/member/support/team`(`support-selection.md`)이고, 그 문서는 "가입 직후 미선택 상태를 서버가 추적해 다른 경로를 막는 것은 별도 요구사항"이라며 강제를 이미 범위 밖으로 두었다.

즉 **"가입 완료 ~ 구단 선택 전" 윈도우가 스키마·코드상 실재한다.** 그 사이에 프로필 화면을 여는 것은 정상적인 앱 사용 흐름이며(온보딩 이탈 후 재진입 포함), 이때 `/me`가 400/404/500을 내면 사용자가 복구 화면조차 못 본다. **정상 경로에서는 `supportTeam`이 항상 non-null**이고, `null`은 이 윈도우와 데이터 이상에서만 나온다.

### 비기능

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-ME-21 | 유비쿼터스 | THE 시스템 SHALL 응답 조립에 필요한 모든 지연 로딩 연관(응원 구단명 포함)을 트랜잭션 경계 안에서 초기화한다 | `open-in-view: false`인 prod 프로파일 설정으로 호출 → 200이며 `LazyInitializationException`이 발생하지 않음 |
| USER-ME-22 | 유비쿼터스 | THE 시스템 SHALL 한 번의 요청에 대해 SELECT 를 4회 이하로, 그 계정의 응원 이력 행 수와 무관한 고정 횟수로 수행한다 | `show-sql`(또는 Hibernate statistics)로 세었을 때 SELECT ≤ 4. 응원 이력이 1건인 계정과 10건인 계정의 SELECT 횟수가 동일 |

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **`SecurityConfig`를 수정하지 않는다.** `/users/**`는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 그대로 걸린다(같은 경로의 `DELETE /users/me`가 이미 그렇다). `/teams`·`/players`·`/games` 때와 반대로, **이번엔 아무것도 추가하지 않는 것이 정답**이다 — 실수로 GET `permitAll`을 넣으면 USER-ME-7~10이 전부 깨진다.
- **principal은 `Long` 내부 PK다.** `JwtAuthenticationFilter`가 요청마다 `findActiveIdByUid`(`exit_at is null` 포함)로 uid→id를 해석한다. 그래서 USER-ME-10(탈퇴 계정 401)에 별도 검사가 필요 없고, 리포지토리 시그니처(`findByUserAccount_Id...`)와도 그대로 맞는다.
- **엔티티를 응답에 그대로 실으면 USER-ME-13이 깨진다.** `UserAccount`를 직렬화하면 `password`(bcrypt 해시)·`uid`·`id`·`exitAt`이 함께 나가고, 이는 `docs/api/README.md`의 전역 서술("어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다")을 무효화한다. 응답 DTO는 앱 모듈(`user.account.dto`)에 둔다 — `:domain`은 `user`·`quiz` 공유 모듈이라 API 계약을 두면 quiz까지 끌려간다(`player-list.md`·`support-selection.md`와 동일한 결정).
- **가입 트랜잭션은 이미 `@Transactional`이다.** `AuthService.signup`이 `userRepository.save` → `userAccountRepository.save` 순으로 한 트랜잭션 안에서 돈다 — USER-ME-24는 새 트랜잭션 전략을 요구하는 것이 아니라 **`users_bq` INSERT를 이 경계 밖(별도 커밋·비동기·after-commit 이벤트)으로 빼지 말라**는 뜻이다. 밖으로 빼면 USER-ME-25/30의 원자성이 깨져 "계정은 있는데 bq 행이 없는" 계정이 생기고, 그 계정은 USER-ME-19 안전망에 영구히 의존하게 된다.
- **가입 경로 변경은 기존 테스트를 건드린다.** `AuthServiceTest`·`AuthControllerSignupTest` 등 가입 경로 테스트는 현재 `UserRepository`/`UserAccountRepository` 목만 세운다 — `users_bq` 리포지토리 협력자가 늘면 이 테스트들이 함께 수정 대상이 된다(계약 변경이 아니라 파급 범위 고지).
- **`UserSupportTeam.team`은 LAZY다.** 구단명을 얻으려면 프록시가 초기화돼 조회가 1회 더 나간다(USER-ME-22의 "4회 이하"가 이 비용을 허용 범위로 못 박은 것이다). prod는 `open-in-view: false`(`user/src/main/resources/application-prod.yaml`)라 **컨트롤러가 DTO를 만들면 늦다** — USER-ME-21은 이 함정을 계약으로 고정한 것이다. dev에는 이 설정이 없어(기본 `true`) **dev에서만 우연히 통과하는 코드가 나올 수 있다.**
- **`findByUserAccount_IdAndOpposeIsNull`은 `Optional`이고, 정책이 깨진 데이터(활성 구단 행 2개 이상)에서는 예외를 던진다.** USER-ME-16은 "행이 0개"만 다루며, 2개 이상은 `support-selection.md` USER-SP-12가 쓰기 경로에서 막아야 하는 불변식이다 — 이 조회 엔드포인트가 조용히 첫 행을 고르는 식으로 덮지 않는다.
- **`users_bq`는 테이블명이 사용자 확정값이다.** `.claude/modules/domain.md`의 "클래스 단수 / 테이블 복수형" 컨벤션과 `user_support_team`·`user_support_player` 2건의 명시적 예외 목록에 이어지는 **세 번째 확정 이름**이다(임의로 `user_bqs` 등으로 바꾸지 말 것).
- **기존 데이터가 있는 테이블에 NOT NULL 컬럼을 붙이는 변경이다.** `users_account`에는 이미 계정 행이 적재돼 있다. MySQL은 `ALTER TABLE ... ADD COLUMN point BIGINT NOT NULL`에서 기존 행을 암묵 기본값(0)으로 채우므로 실패하지 않지만, **DDL에 `DEFAULT 0`을 남기려면 컬럼 정의에 기본값을 명시해야 한다**(USER-ME-1의 인수 기준이 `Default=0`까지 요구하는 이유). 사용자가 "기본값이 0이라 추가에 문제 없다"고 판단한 지점이며, USER-ME-2가 그 판단을 인수 기준으로 고정한다.
- **배포 순서가 계약의 일부다.** 저장소에 Flyway가 없고 prod 스키마는 **`user` 앱의 `ddl-auto=update`가 기동 시 만든다**(`.claude/modules/domain.md`). 따라서 순서는 다음 하나뿐이다.
  1. `user` 앱 재기동 → Hibernate가 `users_account.point` 컬럼과 `users_bq` 테이블을 생성한다. **이 시점 이후의 신규 가입은 USER-ME-23으로 이미 행을 갖는다.**
  2. **곧바로** 운영자가 `infra/sql/users-bq-backfill.sql`을 운영 DB에 실행한다(USER-ME-26) → 기존 계정의 행이 채워진다. 1과 2 사이에 가입한 계정은 `NOT EXISTS` 조건이 걸러내므로 중복이 생기지 않는다(USER-ME-27).
  3. **검증(생략 불가)**: USER-ME-26의 인수 기준 쿼리가 0을 반환하는지 확인한다. **0을 확인하기 전까지 배포는 완료가 아니다**(USER-ME-29). `user` 앱에는 `spring.sql.init` 설정이 없어 dev·prod 어디서도 이 스크립트가 자동 실행되지 않는다 — 로컬에서도 같은 파일을 직접 실행해야 한다.

  **1 이전에는 `/me`도 가입도 500이다**(`users_bq` 테이블 부재 — `user_support_team`이 겪은 것과 같은 상황). **1~2 사이에는 기존 계정의 `/me`가 USER-ME-19 안전망 덕에 200을 유지한다.** 이 순서를 뒤집을 수는 없다 — 테이블이 없으면 백필 SQL 자체가 실패한다.
- **날짜·시각을 직접 읽지 않는다.** 이 엔드포인트는 시각 로직이 없고, `users_bq`의 타임스탬프는 Hibernate(`@CreationTimestamp`/`@UpdateTimestamp`)가 채운다 — 엔티티가 `now()`를 직접 읽지 않는 domain 컨벤션 그대로다. 백필만 SQL의 `NOW(6)`을 쓰는데, 이는 애플리케이션 밖의 1회성 절차라 예외다.

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
1. **`users_bq`는 계정과 1:1**(UNIQUE `user_account_id`). 사용자 결정. 누적 획득 점수는 그 한 행의 `bq_score`를 그대로 읽으며 `SUM` 집계가 아니다. 1:N 이력 안은 폐기했다 — 획득 사유 컬럼이 없어 이력의 활용도가 낮고, `updated_at`(변경 시 갱신)이 붙은 것 자체가 domain 컨벤션상 "갱신되는 엔티티"의 표식이다(순수 기록성 엔티티는 `created_at`만 갖는다).
2. **행은 회원가입 시 함께 생성한다**(`bq_score = 0`). 사용자 결정. lazy 생성 안은 폐기했다 — 조회 경로가 "행이 없을 수도 있다"를 정상 상태로 다루면 이후 점수 증감 경로도 매번 "없으면 만들기"를 반복해야 한다. 대신 **기존 계정 백필이 배포 필수 절차가 됐다**(USER-ME-26).
3. **응원 구단은 제품상 필수다.** 사용자 결정. 다만 스키마·코드가 강제하지 않으므로 `/me`는 미선택 윈도우에서 `supportTeam: null` + 200으로 응답한다(USER-ME-16) — 이는 정책의 예외가 아니라 **정책이 아직 강제되지 않는 구간의 안전망**이다.
4. **`supportTeam`은 `{id, name}` 객체다.** 사용자 결정. 이 API의 모든 구단·선수 표현(`TeamResponse`/`PlayerResponse`)과 일관되고, 프론트가 구단 로고·색상을 이름 문자열이 아니라 id로 매핑할 수 있다.
5. **응답 래퍼는 `ApiResponse`다.** 위 "엔드포인트" 절의 근거 참조.
6. **응답 필드명은 `nickname` / `supportTeam` / `point` / `bqScore`로 고정한다**(USER-ME-13). 컬럼명 `bq_score`의 camelCase다.
7. **`point`·`bqScore`는 JSON 숫자다.** BIGINT이므로 자바스크립트 안전 정수 범위(2^53)를 넘으면 정밀도 문제가 생길 수 있으나, 포인트·점수 규모상 실사용 영향이 없다고 보고 문자열 직렬화는 하지 않는다.
8. **백필은 운영자가 `infra/sql/users-bq-backfill.sql`을 수동 실행한다.** 사용자 결정. 앱 코드 변경이 0이고, 같은 디렉터리의 `teams-init.sql`·`chat-init.sql`이 이미 "운영에서는 사람이 순서대로 적용한다"는 같은 방식으로 운영되고 있다.
   - **`ApplicationRunner` 등 앱 기동 시 1회 실행(B안)은 택하지 않았다** — 일회성 마이그레이션 코드를 앱에 영구히 남기지 않기 위함이다(언제 지울지가 또 다른 미결정으로 남는다).
   - **Flyway 도입(C안)은 택하지 않았다** — 현재 `ddl-auto=update`로 굴러가는 스키마 관리 체계 자체를 바꾸는 별도 작업이라 이번 범위 밖이다.
   - **알려진 리스크(감수하기로 한 것)**: 사람이 실행하는 절차라 **빠뜨릴 수 있다.** 그런데 백필을 빠뜨려도 `/me`는 USER-ME-19 안전망 덕에 `bqScore: 0`으로 200을 돌려주므로 **장애로 드러나지 않고 조용히 잘못된 상태가 유지된다**(이후 점수 증감 경로가 생기면 그때 "행이 없는 계정"에서 문제가 터진다). 이 리스크를 상쇄하는 유일한 장치가 배포 3단계의 **검증 쿼리이며, 그래서 그 실행을 선택이 아니라 계약(USER-ME-29)으로 못 박았다.**

---
**이 문서는 동결됐다.** 미해결 질문 없음 — 모든 항목이 위 "결정 기록"으로 확정됐다. 이후 변경은 새 개정 이력을 남길 것.
