# 구단(team) API 명세

> **도메인** `team` — KBO 구단 참조 데이터.
> **모듈** user (포트 8080) · **경로 접두사** `/api/member/teams` · **엔드포인트** 1개
> **컨트롤러** `user/src/main/java/com/skhynix/user/team/controller/TeamController.java` (`@RequestMapping("/teams")`)
> **최종 갱신** 2026-08-04 — 모듈별(`user.md`) 문서를 도메인별로 분리. 계약 변경 없음.
> 공통 규약(응답 래퍼·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/member/teams](#get-apimemberteams) | 200 | 구단 전체 목록 |

## 이 도메인의 특이사항

[선수(player)](player.md)·[경기(game)](game.md)와 함께 **공개 참조 데이터 3형제**를 이룬다. 셋 다 (a) `HttpMethod.GET`으로 좁힌 `permitAll`, (b) 페이징 없는 단일 배열, (c) 결과 없음은 404가 아니라 200 + 빈 배열, (d) 소스 자연키 미노출이라는 같은 계약을 따른다.

여기서 반환하는 `data[].id`가 [응원(support)](support.md)의 `teamId`와 [선수](player.md)의 `?teamId=` 입력값이다 — **구단 PK의 유일한 정당한 출처**다.

---

## GET /api/member/teams
> 최종 변경: 2026-07-28 (추정) — 도메인 분리 이전 이력이 없어 `TeamController` 마지막 커밋 기준

KBO 구단(팀) 전체 목록 조회. `TeamController` → `TeamService.getTeams()` → `TeamRepository.findAllByOrderByNameAsc()`.

**인증 불필요 — user 모듈에서 [`/api/member/auth/**`](auth.md) 밖으로 처음 열린 무인증 경로.** 회원가입 화면 등 로그인 이전 화면에서 구단 선택 목록으로 쓰이기 위해 `SecurityConfig`가 이 경로만 `permitAll`로 새로 열었다(`.requestMatchers(HttpMethod.GET, "/teams").permitAll()`). [회원탈퇴](account.md)는 여전히 인증이 필요하며 이 변경으로 영향받지 않는다.

**단, `permitAll`은 `HttpMethod.GET`으로 좁혀져 있다 — GET만 인증 없이 열려 있고, 그 밖의 모든 메서드는 `anyRequest().authenticated()`에 걸린다.** 즉 `POST /api/member/teams`처럼 GET이 아닌 요청은 **405 Method Not Allowed가 아니라 401**이다(컨트롤러에 도달하지 못하고 인증 단계에서 걸림 — 405를 기대하지 말 것).

**요청**: 없음(경로/쿼리 파라미터 없음). `?page=`/`?size=` 등을 붙여도 서버가 해석하지 않으며 **페이징이 없다** — 항상 전체 구단을 단일 배열로 반환한다.

**응답 200 OK** `ApiResponse<List<TeamResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 항상 `true` |
| data | array | 구단 배열. 페이지 필드(`content`/`totalElements` 등) 없이 배열 자체가 최상위 데이터 |
| data[].id | Long | 구단 PK |
| data[].name | String | 구단 이름 |
| message | null | 사용되지 않음 |

**`code`/`createdAt`/`updatedAt`는 의도적으로 응답에 없다.** `Team.code`는 py-collector가 upsert 키로 소유하는 소스 자연키라, 클라이언트가 이 값으로 구단을 지칭하기 시작하면 수집기 쪽 코드 체계가 외부(프론트) 계약이 되어버리기 때문에 `TeamResponse`가 엔티티를 그대로 직렬화하지 않고 `id`+`name`만 골라 변환한다.

**정렬: `name` 오름차순, DB(`ORDER BY name ASC`)가 단독 수행하며 애플리케이션에서 재정렬하지 않는다.** 정렬 기준이 한국어 로케일이 아니라 MySQL 콜레이션이라, 영문 구단명이 전부 한글 구단명보다 앞에 온다(대문자 영문과 한글이 유니코드 정렬 가중치상 그렇게 갈린다). 시드(`infra/sql/teams-init.sql`) 10개 구단 적용 시 실제 기대 순서:
```
["KIA", "KT", "LG", "NC", "SSG", "두산", "롯데", "삼성", "키움", "한화"]
```
완전한 한글 가나다순(영문이 사이사이 섞이는 형태)이 아니므로, 프론트에서 이 순서를 "정렬이 깨졌다"로 오인하지 말 것.

**`teams` 테이블에 행이 없으면 200 + 빈 배열**을 반환한다(404·500이 아님):
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | `GET` 이외의 메서드로 이 경로 요청(`permitAll`이 GET으로만 좁혀져 있어 비-GET은 `anyRequest().authenticated()`로 떨어짐 — 405 아님) |

Authorization 헤더가 있어도(만료됐거나 서명이 무효한 access 토큰이어도) 이 경로는 `permitAll`이라 검증 자체를 거치지 않고 그대로 200 + 구단 목록을 반환한다.

**예시**
```bash
curl -i -X GET http://localhost:8080/api/member/teams
```
응답(`id`는 `infra/sql/teams-init.sql`의 `INSERT` 순서를 auto-increment가 그대로 따른다고 가정한 예시일 뿐 — PK 채번은 계약이 아니고 정렬 계약은 오직 `name`에만 있다):
```json
{"success":true,"data":[{"id":6,"name":"KIA"},{"id":4,"name":"KT"},{"id":2,"name":"LG"},{"id":8,"name":"NC"},{"id":10,"name":"SSG"},{"id":1,"name":"두산"},{"id":9,"name":"롯데"},{"id":3,"name":"삼성"},{"id":5,"name":"키움"},{"id":7,"name":"한화"}],"message":null}
```

페이징 파라미터를 붙여도 무시됨(항상 전체 10개 반환):
```bash
curl -i -X GET "http://localhost:8080/api/member/teams?page=1&size=5"
```

비-GET 예시(405가 아니라 401):
```bash
curl -i -X POST http://localhost:8080/api/member/teams
```
```json
{"success":false,"data":null,"message":"인증이 필요합니다."}
```

---

## 확인 필요 / 코드 미확인

- 위 예시 응답의 `id` 값은 `infra/sql/teams-init.sql`의 `INSERT` 나열 순서를 MySQL auto-increment가 그대로 이어받는다고 가정해 역산한 값이며, **실제 DB에서 직접 조회해 확인한 값이 아니다.** `teams` 테이블 DDL이 코드(엔티티/시드 SQL)에 명시돼 있지 않아(Hibernate `ddl-auto`가 생성) 채번 규칙 자체를 코드만으로 100% 보증할 수 없다. `id` 자체는 API 계약이 아니므로(정렬 계약은 `name`에만 있음) 문서화 목적상 문제는 없으나, 정확한 실측이 필요하면 시드 적용된 DB에 `SELECT id, name FROM teams ORDER BY name`을 직접 실행해 대조할 것.
- 구단 상세 조회(`GET /api/member/teams/{id}`)·엠블럼/색상 등 표시용 메타데이터 엔드포인트는 없다.

## 관련 문서

- [선수(player)](player.md) — `?teamId=`로 이 문서의 `data[].id`를 받는다.
- [응원(support)](support.md) — 응원 구단 선택에 이 `id`를 쓴다.
