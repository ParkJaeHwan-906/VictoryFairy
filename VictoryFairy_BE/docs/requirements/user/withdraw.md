# 회원탈퇴 요구사항
> 상태: 승인됨 · 모듈: user (+ domain) · 최종 수정: 2026-07-17

## 배경 / 목적
`UserAccount.exit_at` 컬럼은 선언만 되어 있고 저장소 어디서도 읽거나 쓰지 않아 **항상 NULL**이다. 즉 지금까지 "탈퇴한 계정"이라는 상태가 존재할 수 없었고, 이 기능이 그 상태를 처음으로 만든다.
그래서 이 작업의 본질은 엔드포인트 1개 추가가 아니라 **"탈퇴한 계정은 인증되지 않는다"는 규칙을 인증 경로 전체에 일관되게 심는 것**이다. access 토큰이 stateless(3h)라 서버가 폐기할 수 없다는 점이 요구사항 USER-WD-6의 이유다.

## 범위
- 포함: 탈퇴 엔드포인트(soft delete, `exit_at` 기록), 탈퇴 시 refresh 토큰 폐기, `login`·`refresh`·인증 필터 3개 지점의 탈퇴 계정 차단
- 제외:
  - **하드 딜리트 / 개인정보 파기 배치** — `exit_at`은 표식만 남기며 실제 행 삭제는 이번 범위 밖
  - **탈퇴 취소(복구) API** — 탈퇴는 **즉시 완료·유예 기간 없음·취소 불가**로 확정(사용자 결정). `exit_at`은 "탈퇴 예정 시각"이 아니라 "탈퇴 완료 시각"이다 — USER-WD-4가 이를 고정한다
  - **탈퇴 사유 수집** — 요청에 없었음
  - **`POST /api/auth/logout` 변경** — 탈퇴 시 refresh 토큰이 이미 만료되므로 기존 멱등 동작(항상 204)으로 충분
  - **`POST /api/auth/password/validate` 변경** — DB를 조회하지 않는 순수 정책 검사라 탈퇴와 무관

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-WD-1 | 이벤트 | WHEN 인증된 사용자가 탈퇴를 요청하면, THE 시스템 SHALL 해당 계정의 `exit_at`에 서버의 현재 시각을 기록한다 | `DELETE /api/users/me` + `Authorization: Bearer <유효 access>` → 해당 `users_account.exit_at`이 NULL이 아닌 값(요청 시각)으로 설정됨 |
| USER-WD-2 | 이벤트 | WHEN 인증된 사용자가 탈퇴를 요청하면, THE 시스템 SHALL 본문 없이 204를 반환한다 | `DELETE /api/users/me` + 유효 access → `204 No Content`, 본문 없음. 요청 본문도 없음(비밀번호 재확인 없음) |
| USER-WD-3 | 이벤트 | WHEN 인증된 사용자가 탈퇴를 요청하면, THE 시스템 SHALL 해당 계정의 유효한 refresh 토큰을 모두 만료시킨다 | 탈퇴 직전 발급받은 refreshToken으로 `POST /api/auth/refresh` → 401 (USER-WD-7과 동일) |
| USER-WD-4 | 유비쿼터스 | THE 시스템 SHALL 한 번 설정된 `exit_at` 값을 이후 변경하지 않는다 | 탈퇴한 계정의 `exit_at`은 이후 어떤 API 호출로도 NULL로 되돌아가거나 다른 시각으로 갱신되지 않음 |
| USER-WD-5 | 예외 | IF 인증되지 않은 요청이 탈퇴를 요청하면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | `DELETE /api/users/me` (Authorization 헤더 없음) → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (`UNAUTHENTICATED`) |
| USER-WD-6 | 예외 | IF 탈퇴한 계정의 access 토큰으로 인증이 필요한 요청이 들어오면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | 탈퇴 후 **탈퇴 전에 발급받은** access 토큰으로 `DELETE /api/users/me` → 401, `message`가 `"인증이 필요합니다."` (`UNAUTHENTICATED`) |
| USER-WD-7 | 예외 | IF 탈퇴한 계정의 refresh 토큰으로 재발급을 요청하면, THEN THE 시스템 SHALL 401과 `"만료되었거나 이미 무효화된 리프레시 토큰입니다."`를 반환한다 | 탈퇴 후 `POST /api/auth/refresh` `{"refreshToken":"<탈퇴 전 발급분>"}` → 401, `message`가 `"만료되었거나 이미 무효화된 리프레시 토큰입니다."` (`EXPIRED_REFRESH_TOKEN`) |
| USER-WD-8 | 예외 | IF 탈퇴한 계정의 이메일로 로그인을 요청하면, THEN THE 시스템 SHALL 401과 `"이메일 또는 비밀번호가 올바르지 않습니다."`를 반환한다 | 탈퇴한 계정의 이메일 + **올바른 비밀번호**로 `POST /api/auth/login` → 401, `message`가 `"이메일 또는 비밀번호가 올바르지 않습니다."` (`INVALID_CREDENTIALS`). 미가입 이메일로 로그인했을 때와 응답이 **완전히 동일** |
| USER-WD-9 | 예외 | IF 이미 탈퇴한 계정의 access 토큰으로 탈퇴를 재요청하면, THEN THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | `DELETE /api/users/me`를 같은 access 토큰으로 연속 2회 → 1회차 204, 2회차 401 (`UNAUTHENTICATED`). 2회차에 `exit_at`은 1회차 값에서 갱신되지 않음(USER-WD-4) |
| USER-WD-10 | 예외 | IF 탈퇴한 계정이 점유한 이메일로 회원가입을 요청하면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 이메일입니다."`를 반환한다 | 탈퇴한 계정의 이메일로 `POST /api/auth/signup` → 409, `message`가 `"이미 사용 중인 이메일입니다."` (`DUPLICATE_EMAIL`) |
| USER-WD-11 | 예외 | IF 탈퇴한 계정이 점유한 전화번호로 회원가입을 요청하면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 전화번호입니다."`를 반환한다 | 탈퇴한 계정의 `tel`로 `POST /api/auth/signup` → 409, `message`가 `"이미 사용 중인 전화번호입니다."` (`DUPLICATE_TEL`) |
| USER-WD-12 | 예외 | IF 탈퇴한 계정이 점유한 닉네임으로 회원가입을 요청하면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 닉네임입니다."`를 반환한다 | 탈퇴한 계정의 `nickname`으로 `POST /api/auth/signup` → 409, `message`가 `"이미 사용 중인 닉네임입니다."` (`DUPLICATE_NICKNAME`) |

**USER-WD-10~12은 "새로 만드는 규칙"이 아니라 현행 동작을 명문화한 것이다.** `existsByEmail`/`existsByTel`/`existsByNickname`이 탈퇴 여부를 구분하지 않으므로, 아무것도 하지 않으면 자동으로 이 동작이 된다. 이 3건이 왜 뒤집히지 않는지는 아래 "결정 근거 1"에 있다.

**새로 추가되는 `ErrorCode`는 없다.** 12건 전부 기존 코드(`UNAUTHENTICATED`/`INVALID_CREDENTIALS`/`EXPIRED_REFRESH_TOKEN`/`DUPLICATE_*`)로 충족된다.

## 결정 근거 (다시 묻힐 질문들 — 조사를 반복하지 않기 위해)

### 1. 왜 재가입이 안 되는가 (탈퇴한 email·tel·nickname 영구 점유)
"탈퇴했으면 같은 이메일로 다시 가입할 수 있어야 하지 않나"는 **반드시 다시 나올 질문**이다. 결론부터: **앱 코드만 고쳐서는 불가능하고, 스키마를 재설계해야 한다.** 사용자는 아래 비용을 확인한 뒤 **재가입 불가**를 선택했다.

- **`users.email`·`users.tel`에 DB UNIQUE 제약이 물리적으로 걸려 있다** (`User` 엔티티의 `@Column(unique = true)`, 코드 확인). `existsByEmail`/`existsByTel`에 "탈퇴 제외" 조건을 넣어봐야 **INSERT가 DB 레벨에서 막힌다.** 앱 검사 변경만으로는 소용없다.
- **MySQL은 partial unique index(조건부 unique)를 지원하지 않는다.** PostgreSQL의 `CREATE UNIQUE INDEX ... WHERE exit_at IS NULL` 같은 "활성 행만 unique" 표현이 애초에 불가능하다.
- **generated column 트릭도 불가하다.** 흔한 우회책은 `(email, exit_at)` 류의 생성 컬럼에 unique를 거는 것인데, `email`은 `users` 테이블이고 `exit_at`은 `users_account` 테이블이라 **서로 다른 테이블**이다. 생성 컬럼은 테이블을 넘지 못한다.
- **기존 `User` 행 재사용도 불가하다.** `UserAccount.user`가 `@OneToOne` + `@JoinColumn(name = "user_id", unique = true)`라, 한 `User`에 새 `UserAccount`를 다시 붙일 수 없다.

즉 재가입을 열려면 `users`의 unique 제약 해제 + 탈퇴 표식의 위치 이동(또는 테이블 병합) + `@OneToOne` 관계 재설계가 한꺼번에 필요하다. 이번 범위에서 감당할 변경이 아니다.

**nickname은 사정이 다르다 — 이 비대칭은 의도된 것이다.** `UserAccount.nickname`에는 **DB unique 제약이 없고**(앱의 `existsByNickname` 검사만 존재), `exit_at`과 **같은 테이블**에 있다. 즉 기술적으로는 `existsByNickname`에 `exit_at IS NULL` 조건 하나만 더하면 닉네임 해방이 **쉽게 가능했다.** 그럼에도 email·tel과 **정책을 일관되게 유지**하기 위해 점유 유지로 결정했다. 나중에 "닉네임만이라도 풀어달라"는 요구가 오면, 그건 **기술 제약이 아니라 정책 결정을 뒤집는 일**이므로 이 문단을 근거로 다시 판단하면 된다.

### 2. 왜 필터의 커버링 인덱스를 포기하는가
**직전 작업에서 의도적으로 만든 커버링 인덱스 최적화를 이번에 되돌린다.** 되돌리는 결정이므로 근거를 남긴다.

현재 필터의 uid→id 해석은 `select ua.id from UserAccount ua where ua.uid = :uid`이고, InnoDB secondary index가 PK를 품어 `uid` unique 인덱스가 물리적으로 `(uid, id)`이므로 **커버링 인덱스만으로 끝난다**(클러스터드 인덱스 북마크 조회 없음). 여기에 `exit_at IS NULL` 조건이 붙으면 `exit_at`이 인덱스에 없어 **클러스터드 인덱스 조회가 부활하고, 그 비용은 인증이 필요한 모든 요청에 붙는다.**

그럼에도 포기하는 이유:
- **커버링의 실이득이 원래 크지 않다.** 되살아나는 클러스터드 인덱스 페이지는 대개 **버퍼 풀에 상주**하므로 추가 디스크 I/O가 아니라 메모리 접근에 그친다.
- **탈퇴 차단의 정확성이 성능보다 우선한다.** access 토큰이 stateless(3h)라 이 조회가 탈퇴 즉시 차단의 **유일한 지점**이다.
- **`(uid, exit_at)` 복합 인덱스는 남는 게 없다.** `uid`에 이미 unique 인덱스가 있는데 거의 같은 인덱스를 하나 더 얹는 것이라, 쓰기 비용과 용량(100만 행 기준 **약 45MB**)에 비해 얻는 것이 버퍼 풀 히트 1회 절약뿐이다.

### 3. 왜 탈퇴 계정 로그인이 "탈퇴했습니다"가 아니라 "이메일 또는 비밀번호가 올바르지 않습니다"인가
전용 메시지(예: `"탈퇴한 계정입니다."`)는 **해당 이메일이 가입한 적 있다는 사실을 노출**한다. 기존 `login`은 이메일 미존재와 비밀번호 불일치를 동일한 `INVALID_CREDENTIALS`로 응답해 계정 존재 여부를 감추는 정책을 이미 갖고 있고(`docs/api/user.md`), 탈퇴 계정만 예외를 두면 그 정책이 깨진다.
**대가**: 탈퇴한 사용자는 자신이 탈퇴했다는 사실을 로그인 화면에서 알 수 없고 비밀번호를 틀린 줄 알고 재시도하게 된다. 이 UX 비용을 알고 받아들인 결정이다.

## 제약 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)

1. **`/api/auth/**`는 전부 `permitAll`이다.** 그래서 탈퇴는 `/api/users/me`에 둔다. `/api/auth/withdraw`에 두면 **인증이 걸리지 않아** USER-WD-5가 성립하지 않는다. `/api/users/**`는 기존 `anyRequest().authenticated()`에 그대로 걸리므로 **`SecurityConfig` 수정이 필요 없다.** 이 경로가 user 모듈의 첫 `/api/auth/**` 밖 엔드포인트이자 **`anyRequest().authenticated()`에 실제로 걸리는 첫 경로**가 된다.
2. **탈퇴 계정 차단 지점은 필터 1곳이 아니라 3곳이다.** 사용자 요청은 "필터"였으나 `login`은 `permitAll`이라 `JwtAuthenticationFilter`를 타지 않는다(USER-WD-8은 `login` 자체에서 판정해야 함). `refresh`(USER-WD-7)도 마찬가지다. **필터·`login`·`refresh` 각각**에 판정이 필요하다.
3. **uid 해석 계약이 바뀐다**: "uid로 계정 id를 조회한다" → **"uid로 *활성*(`exit_at IS NULL`) 계정의 id를 조회한다."** 기존 `findIdByUid`의 **유일한 소비처가 필터**이므로 이 메서드는 추가가 아니라 **대체**된다(메서드명은 구현 단계에서 결정). uid로 활성 계정을 못 찾으면 필터는 기존과 동일하게 `SecurityContext`를 비운 채 체인을 통과시키고, `anyRequest().authenticated()`에 걸려 `RestAuthenticationEntryPoint`가 401을 응답한다 — 그래서 USER-WD-6·USER-WD-9에 새 에러 코드가 필요 없다.
4. **`UserAccount`에 `@Setter`가 없고, domain 컨벤션상 두지 않는다.** 따라서 `exit_at` 기록은 **엔티티가 자신의 탈퇴 상태 전이를 책임지는** 형태여야 한다 — 의도를 드러내는 메서드(예: `withdraw(LocalDateTime)`)를 `UserAccount`에 추가하고, 그 메서드가 USER-WD-4(이미 설정된 `exit_at`을 덮어쓰지 않음)를 보장한다. 서비스가 setter로 상태를 밀어넣는 형태는 컨벤션 위반이다.
5. **배포 시 스키마 생성 수단이 필요하다.** 사용자가 **배포 환경을 초기화할 예정**이므로 기존 행에 대한 `exit_at` 백필은 **대상이 없다**(전부 신규 생성 → NULL = 활성, 의도한 초기 상태와 일치). 다만 prod가 `ddl-auto: none` + Flyway 없음이라 **초기화해도 스키마가 스스로 생기지 않는다** — `users_account`의 `uid`·`exit_at`을 포함한 스키마 반영 수단은 이 기능과 별개로 배포 전에 마련돼야 한다.

## 미해결 질문
없음 — 6건 전부 해소됨(결정 근거 절 참고).
