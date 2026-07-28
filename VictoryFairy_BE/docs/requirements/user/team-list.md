# KBO 구단 목록 조회 요구사항
> 상태: 승인됨 (2026-07-28) · 모듈: user · 최종 수정: 2026-07-28

## 배경 / 목적
`TeamController`/`TeamService`는 본문이 비어 있어 **현재 컴파일조차 되지 않는 상태**로 방치돼 있다. 이 문서는 그 두 파일을 완성하기 전에 계약을 먼저 고정한다.
핵심 쟁점은 "목록을 준다"가 아니라 **두 가지 경계**다 — (1) 회원가입 화면에서 쓰이므로 **로그인 전에도 열려 있어야** 하고, 그러려면 `user` 모듈에서 `/api/member/auth/**` 밖의 경로가 처음으로 `permitAll`이 된다. (2) `Team.code`는 py-collector 의 소스 자연키라 **외부에 나가면 안 된다** — 클라이언트가 code 로 구단을 지칭하기 시작하면 수집기 쪽 코드 체계가 프론트 계약이 되어 버린다.

## 범위
- 포함: 구단 목록 조회 엔드포인트 1개(`GET /api/member/teams`), 응답 DTO(`id`+`name`), 정렬 순서 고정, `SecurityConfig`에 이 경로를 `permitAll`로 여는 변경
- 제외:
  - **구단 단건 조회 / 생성 / 수정 / 삭제** — 데이터는 py-collector 와 시드 SQL 이 소유한다. 앱에서 쓰기 경로를 열지 않는다
  - **`code` 필드 노출** — 위 배경 참조. 어떤 응답에도 넣지 않는다
  - **페이징 / 검색 / 필터** — 10개 고정 데이터라 불필요(USER-TM-5 가 이를 계약으로 못 박는다)
  - **애플리케이션 레벨 재정렬(`Collator` 등 한국어 로케일 정렬)** — 정렬은 DB 가 단독으로 수행한다(아래 "제약" 참조). "완전한 가나다순"이 필요해지면 그때 별도 요구사항으로 다룬다
  - **HTTP 캐시 헤더(`Cache-Control`/`ETag`)·서버 캐시** — 거의 불변인 데이터라 후보이긴 하나 이번 요청 범위 밖
  - **구단 로고·색상 등 표시용 메타** — 엔티티에 컬럼이 없다
  - **`quiz` 모듈 쪽 노출** — 채팅방 목록(`GET /api/quiz/chat/rooms`)이 이미 `team`(이름)을 내려주고 있고, 이번 엔드포인트는 `user` 모듈 전용이다

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-TM-1 | 이벤트 | WHEN 클라이언트가 구단 목록을 요청하면, THE 시스템 SHALL 200과 `ApiResponse` 래퍼에 담긴 구단 배열을 반환한다 | `GET /api/member/teams` → 200, 본문 `{"success":true,"data":[...],"message":null}` |
| USER-TM-2 | 유비쿼터스 | THE 시스템 SHALL 구단 항목에 `id`와 `name` 두 필드만 포함한다 | `data[0]`의 키 집합이 정확히 `{"id","name"}`. `code`·`createdAt`·`updatedAt` 키가 **응답 어디에도 없음**. `id`는 JSON 숫자, `name`은 문자열 |
| USER-TM-3 | 유비쿼터스 | THE 시스템 SHALL 구단 목록을 `name` 오름차순(DB 콜레이션 기준)으로 정렬해 반환한다 | 시드가 적용된 DB → `data`의 `name`이 정확히 `["KIA","KT","LG","NC","SSG","두산","롯데","삼성","키움","한화"]` 순서. 동일 DB 상태에서 2회 연속 호출 시 순서 동일 |
| USER-TM-4 | 유비쿼터스 | THE 시스템 SHALL `teams` 테이블의 모든 행을 조건 없이 반환한다 | 시드(`infra/sql/teams-init.sql`)가 적용된 DB → `data` 길이 10, `name` 집합이 `두산·LG·삼성·KT·키움·KIA·한화·NC·롯데·SSG` |
| USER-TM-5 | 유비쿼터스 | THE 시스템 SHALL 페이징 파라미터를 해석하지 않고 전체 구단을 단일 응답으로 반환한다 | `GET /api/member/teams?page=1&size=5` → 200, `data` 길이는 전체 구단 수(시드 기준 10). `data`는 배열이며 `content`/`totalElements` 같은 페이지 필드가 없음 |
| USER-TM-6 | 이벤트 | WHEN `Authorization` 헤더 없이 구단 목록 요청이 들어오면, THE 시스템 SHALL 200과 구단 목록을 반환한다 | 헤더 없이 `GET /api/member/teams` → 200 (401 `"인증이 필요합니다."` 가 아님) |
| USER-TM-7 | 예외 | IF 만료되었거나 위조된 access 토큰이 `Authorization` 헤더에 담겨 오면, THEN THE 시스템 SHALL 200과 구단 목록을 반환한다 | `Authorization: Bearer <만료 토큰>` 및 `Bearer not-a-jwt` → 둘 다 200, 본문은 헤더 없을 때와 동일 |
| USER-TM-8 | 예외 | IF `teams` 테이블에 행이 없으면, THEN THE 시스템 SHALL 200과 빈 배열을 반환한다 | 빈 `teams`에 대해 `GET /api/member/teams` → 200, `{"success":true,"data":[],"message":null}` (404·500 아님) |
| USER-TM-9 | 예외 | IF 구단 목록 경로에 GET 이외의 메서드로 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | `POST /api/member/teams` (헤더 없음) → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (`UNAUTHENTICATED`) |

### 표기 근거 (요구사항 아님 — 위 문장을 읽는 데 필요한 사실)
- **USER-TM-1 의 `ApiResponse` 래퍼는 추측이 아니라 코드 확인 결과다.** `user` 모듈은 래퍼 사용이 혼재하지만(`login`/`signup`/`logout`은 원시 타입 반환), **데이터를 돌려주는 최근 엔드포인트는 전부 래퍼를 쓴다**(`/password/validate`, `/nickname/validate`, `/nickname/duplicate`, `/email/*` — 모두 `ResponseEntity<ApiResponse<T>>`). 같은 성격의 목록 조회 선례인 `quiz`의 `GET /chat/rooms`도 `ApiResponse<List<RoomResponse>>`다. 래퍼 없는 쪽이 예외이며 신규 엔드포인트가 따라갈 대상이 아니다.
- **USER-TM-9 의 401(405 아님)은 `permitAll`을 GET 으로만 여는 것의 귀결이다.** `SecurityConfig`의 actuator 헬스체크가 `requestMatchers(HttpMethod.GET, ...)`로 메서드를 좁힌 선례를 따른 것. 메서드를 좁히지 않으면 `POST`는 컨트롤러까지 도달해 405가 된다.

### USER-TM-3 의 한계 — "가나다순"이 아니다 (반드시 읽을 것)
정렬 기준은 `name` 오름차순이지만, **정렬을 수행하는 주체가 MySQL 이므로 결과는 한국어 로케일 정렬이 아니라 콜레이션 순서**다. KBO 10개 구단명은 한글(두산·롯데·삼성·키움·한화)과 영문(KIA·KT·LG·NC·SSG)이 섞여 있어 **영문 5개가 먼저 오고 한글 5개가 뒤에 온다.** 사용자가 화면에서 기대할 법한 "ㄱㄴㄷ 순에 영문이 섞인 형태"와 다르므로 프론트에서 "정렬이 깨졌다"는 리포트가 올 수 있다. 이건 버그가 아니라 아래 계약이다.

- **기대 순서**: `KIA, KT, LG, NC, SSG, 두산, 롯데, 삼성, 키움, 한화`
  - 영문 구간: `KIA < KT`(두 번째 글자 `I < T`) `< LG < NC < SSG`
  - 한글 구간: `두 < 롯 < 삼 < 키 < 한` — 한글끼리는 결과적으로 가나다순과 일치한다
- **콜레이션 근거(추측 아님, 확인 범위 명시)**: `teams` 테이블은 명시적 DDL 없이 **Hibernate `ddl-auto`가 생성**하며(`infra/sql/teams-init.sql`은 `INSERT` 만 한다), 엔티티 `Team`·`application-*.yaml`·`docker-compose.yml` 어디에도 charset/collation 지정이 없다. JDBC URL 의 `characterEncoding=UTF-8`은 **클라이언트 전송 인코딩**일 뿐 정렬 규칙이 아니다. 따라서 `teams.name`의 콜레이션은 **DB 기본값에 의존**하며, `mysql:8.0` 이미지가 옵션 없이 만든 스키마의 기본값은 `utf8mb4_0900_ai_ci`다. **즉 이 순서는 저장소 어디에도 고정돼 있지 않고 서버 기본값에 기대고 있다는 점 자체가 한계다.**
  - 참고: 같은 저장소의 `infra/sql/chat-init.sql`은 자기 테이블에 `utf8mb4_unicode_ci`를 명시하고 있어 **`teams`와 콜레이션이 다를 수 있다**. 다만 두 콜레이션 모두 유니코드 정렬 가중치상 라틴 문자를 한글보다 앞에 두므로 **위 기대 순서는 둘 중 어느 쪽이어도 동일**하다.
  - 구단명은 전부 대문자 영문 또는 한글이라 대소문자·악센트 규칙(`ai_ci`)이 결과에 관여하지 않는다.

## 제약 (기존 코드·정책과의 접점 — 구현 지시가 아니라 지켜야 할 사실)
- **정렬은 DB 단독 수행이다.** `ORDER BY name ASC`(예: `TeamRepository.findAllByOrderByNameAsc()`)로 조회하고, **애플리케이션에서 재정렬하지 않는다.** 10행짜리 목록에 자바 정렬 계층을 얹을 이유가 없고, 무엇보다 정렬 기준이 DB 와 앱 두 곳으로 갈라지면 USER-TM-3 의 기대 순서가 어느 쪽 규칙인지 모호해진다. 사용자가 내린 결정이므로 구현 재량이 아니다.
- **`SecurityConfig`의 `requestMatchers` 경로에는 context-path 를 붙이지 않는다.** `/api/member`는 컨테이너가 필터 체인 이전에 떼므로, 외부 경로가 `/api/member/teams`여도 매처는 접두사 없는 경로(`/teams`)로 써야 한다 — `.requestMatchers(HttpMethod.GET, "/teams").permitAll()` 형태. 접두사를 붙이면 매칭이 안 돼 `anyRequest().authenticated()`로 떨어지고 **USER-TM-6 이 401 로 실패**한다. (`user` 모듈의 기존 주석에 같은 함정이 명시돼 있음)
- **새 `permitAll` 규칙이 `/teams` 범위를 넘어 넓어지면 안 된다.** 특히 `/api/member/users/me`(탈퇴)는 계속 인증이 필요하다.
- **`Team.code` 를 노출하지 않는 것은 엔티티 Javadoc 이 명시한 소유권 경계다** — code 는 py-collector 가 upsert 키로 소유한다. 응답 DTO 는 엔티티를 그대로 직렬화하지 않아야 USER-TM-2 가 성립한다.
- 이 문서는 기존 `user` 모듈 정책과 **충돌하지 않는다**. `permitAll` 확대는 규칙 변경이 아니라 규칙 **추가**이며, 인증이 필요한 기존 경로의 동작을 바꾸지 않는다.

## 결정 기록 (해결됨 — 다시 논의하지 않기 위해)
1. **경로: `GET /api/member/teams`** (복수형 컬렉션). 컨트롤러 매핑은 `@RequestMapping("/teams")` + `@GetMapping`(context-path 는 매핑에 쓰지 않는다). **초안의 `/team/list` 는 폐기** — 동사형 `/list`가 컬렉션 조회 관례와 어긋나고, 이후 단건 조회를 붙일 때 `/team/list`·`/team/{id}`가 섞이기 때문. 패키지 경로(`com.skhynix.user.team.*`)는 그대로 둔다(URL 과 패키지명이 달라도 무방).
2. **정렬: `name` 오름차순, DB 에서 수행.** 초안의 `id` 오름차순(시드 INSERT 순) 가정은 폐기. select 박스에서 사용자가 찾기 쉬운 쪽을 택했고, 영문이 앞에 몰리는 부작용은 위 "USER-TM-3 의 한계"로 수용한다.
3. **GET 외 메서드: GET 만 `permitAll` → 비-GET 은 401.** 읽기 전용 의도가 보안 설정에 드러나고, 이후 이 경로에 쓰기 엔드포인트가 **인증 없이 열린 채 추가되는 사고**를 구조적으로 막는다. 405 대신 401 이 나오는 것은 이 선택의 의도된 결과다(USER-TM-9).
4. **데이터 0행: 200 + `[]`.** 조회는 성공했고 결과가 비었을 뿐이다. 다만 **prod 는 시드가 수동 적용**이라 `teams-init.sql` 미적용 상태에서도 프론트에 빈 select 가 조용히 뜬다 — 이 감지는 API 계약이 아니라 **배포 체크리스트**에서 다룬다(USER-TM-4 의 인수 기준이 그 확인 쿼리 역할을 겸한다).
