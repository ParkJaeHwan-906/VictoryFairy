# 내 프로필 수정(닉네임·비밀번호) 요구사항
> 상태: **승인됨 (2026-08-17)** · 모듈: user · 최종 수정: 2026-08-17
> `docs/api/account.md`("프로필 **수정**(`PATCH`/`PUT /api/users/me` 류) 엔드포인트는 아직 없다")와 `me-profile.md`·`nickname-policy.md`가 각각 "별도 요구사항"으로 미뤄 둔 자리를 채운다.
> **2026-08-17 개정**: 초안의 미해결 질문 7건이 전부 확정됐다 — 경로 분리 유지 · 현재 비밀번호 재확인 요구 · **같은 값(비밀번호·닉네임)은 둘 다 거부** · 불일치·동일은 400 신규 `ErrorCode` 3종 · 비밀번호 변경 시 refresh 전량 만료. 그 결과 초안 USER-PE-17(같은 닉네임을 성공 처리)이 **반대로 뒤집혔고**, 함께 예고했던 "중복 판정에서 본인 제외" 요건이 사라졌다(아래 결정 근거 3).
> **2026-08-17 3차 개정(닉네임 변경 쿨다운 30일)**: 새 정책이 **덧붙는다** — 닉네임은 마지막 변경으로부터 **30일** 이내 재변경이 금지되고, 위반은 **429 + 다음 변경 가능 시각**이다(USER-PE-40~49). **비밀번호에는 쿨다운을 두지 않는다**(결정 근거 9 — "왜 닉네임만인가"는 반드시 다시 나올 질문이다). 기존 요구사항 중 손댄 것은 **USER-PE-8·15·33 세 건의 확장뿐**이며 나머지는 그대로다. 선행 스키마 1건(`users_account.nickname_changed_epoch_second`)이 생겨 **"배포 전제" 절을 신설**했다 — 이 문서가 계약으로 포함하는 DDL이다.
> **2026-08-17 2차 개정(성공 응답 비대칭)**: `PATCH /api/users/me/password`의 성공 응답이 **204에서 200 + `ApiResponse<TokenResponse>`로 바뀌었다** — 기존 유효 refresh를 만료시킨 뒤 **새 토큰 쌍을 발급해 응답에 담는다**(비밀번호를 바꾼 본인이 재로그인하지 않아도 되게 하려는 UX 결정). 이 개정으로 USER-PE-35가 **정반대로 뒤집혔고**(미발급 → 발급), 결정 근거 5·6도 함께 개정됐다. **닉네임 경로는 204 그대로다.** access 토큰 즉시 무효화는 이번에도 범위 밖이며 **별도 작업으로 분리**됐다(USER-PE-32·제약 4 유지).

## 배경 / 목적
닉네임·비밀번호 정책은 이미 `NicknamePolicy`·`PasswordPolicy` **단일 출처**로 분리돼 있고 회원가입·사전검사 API가 그 판정을 공유한다. 이 기능은 새 정책을 만드는 일이 아니라 **세 번째 소비처(수정 경로)를 같은 판정에 물리는 일**이다 — 여기서 규칙을 다시 적는 순간 단일 출처가 깨진다.

계약의 실제 쟁점은 정상 경로가 아니라 셋이다.

1. **"바뀌지 않는 수정"을 어떻게 다루는가** — 현재 값과 같은 값으로 오는 요청은 성공도 중복도 아니다. 둘 다 거부(400)로 확정했고, 그 결정이 닉네임 중복 판정의 모양까지 바꾼다(결정 근거 3).
2. **비밀번호 변경이 세션에 미치는 파급** — 탈퇴는 `expireValidTokens`로 refresh를 끊었지만 access는 필터의 활성 계정 조회가 막아 줬다. 비밀번호 변경은 **계정이 여전히 활성**이라 그 안전망이 없다 — access 토큰은 남은 유효기간(최대 3h) 동안 그대로 산다(USER-PE-32).
3. **위반 메시지의 결정성** — 한 요청에 형식 위반·현재 비밀번호 불일치·신구 동일·쿨다운이 동시에 성립할 수 있다. 판정 순서를 고정하지 않으면 응답 메시지가 호출마다 달라진다(`GlobalExceptionHandler`의 `Map#put` 순서 비보장 문제를 password가 이미 겪었다).
4. **(3차 개정) 거절에 데이터를 실어야 한다** — 닉네임 쿨다운은 "왜 막혔는지"만으로 부족하고 "언제 풀리는지"를 함께 줘야 한다. 그런데 이 저장소의 `BusinessException` 응답은 **예외 없이 `data: null`**이었다. 즉 이 요구사항은 기능 하나가 아니라 **실패 응답의 표현력을 처음으로 넓히는 일**이다(제약 9).

## 범위
- 포함
  - 닉네임 변경 1개 · 비밀번호 변경 1개 (둘 다 access 토큰 필수, 대상은 토큰 주체 본인)
  - 기존 `NicknamePolicy`·`PasswordPolicy` 재사용(형식 판정), 기존 `existsByNickname` 재사용(중복 판정)
  - 비밀번호 변경 시 현재 비밀번호 재확인 · 신·구 동일 거부 · 닉네임 동일 거부
  - 비밀번호 변경 시 **기존 refresh 토큰 전량 만료 → 새 토큰 쌍 발급**, 응답은 기존 `auth.dto.TokenResponse` 재사용(신규 DTO 없음)
  - **닉네임 변경 쿨다운 30일**(3차 개정) + 선행 스키마 1건(`users_account.nickname_changed_epoch_second`) + 그 컬럼의 수동 DDL 선투입(아래 "배포 전제")
  - 신규 `ErrorCode` 4종(`:common`): `INVALID_CURRENT_PASSWORD` · `SAME_AS_CURRENT_PASSWORD` · `SAME_AS_CURRENT_NICKNAME` · `NICKNAME_CHANGE_COOLDOWN`
- 제외
  - **비밀번호 변경 쿨다운** — 의식적으로 두지 않는다. 유출 대응 수단을 30일 잠그는 정책이며 후속 작업(토큰 무효화)의 목적과 정면으로 부딪친다(결정 근거 9). 이 줄이 "왜 닉네임만 쿨다운이 있나"에 대한 답이다
  - **비밀번호 변경 시각 컬럼(`password_changed_epoch_second`)** — 비밀번호 쿨다운이 빠지면서 이 브랜치에는 그 컬럼이 필요한 이유가 없어졌다. **후속 작업 `hwannee/be/feat-token-invalidation`(`access-token-invalidation.md`)이 자기 목적으로 별도 추가한다** — 이 문서가 만드는 컬럼은 닉네임 것 하나뿐이다
  - **쿨다운의 관리자 우회·예외 처리·잔여 기간 조회 전용 API** — 요청에 없었다. 남은 시간은 429 응답의 `nextChangeableAt`으로만 알 수 있다
  - **한 요청으로 닉네임과 비밀번호를 동시에 바꾸는 경로** — 사용자 확정: **한 번에 하나만 변경한다.** 단일 `PATCH /api/users/me`도, 두 필드를 함께 받는 본문도 만들지 않는다
  - **이메일·전화번호·이름·성별 수정** — 요청된 항목 밖(닉네임·비밀번호 2개). 이메일 변경은 소유 재검증(`email-verification.md`)까지 끌고 오므로 별도 요구사항이다
  - **비밀번호 찾기/재설정(비로그인 경로)** — 이 문서는 **로그인한 본인**의 변경만 다룬다. `email-verification.md`가 이미 "로그인·비밀번호 재설정용 이메일 인증"을 범위 밖으로 뒀다
  - **닉네임 변경 횟수 제한·쿨다운·변경 이력 보관** — 요청에 없었다. 필요해지면 별도 개정(현재는 무제한 변경)
  - **프로필 이미지·자기소개 등 신규 프로필 항목** — 저장할 컬럼 자체가 없다
  - **응원 구단·응원 선수·포인트 수정** — `support-selection.md`가 이미 계약을 갖고 있고, `point`·`bqScore`는 `me-profile.md`가 증감 주체를 범위 밖으로 뒀다
  - **닉네임 UNIQUE 제약 추가** — `users_account.nickname`에는 DB UNIQUE가 없다(`withdraw.md` 결정 근거 1). 동시 요청 레이스로 같은 닉네임 2개가 생길 수 있는 것은 signup에도 이미 있는 한계이며, 이번에 스키마를 바꾸지 않는다(아래 "알려진 한계")
  - **비밀번호 변경 시점의 access 토큰 즉시 무효화** — 사용자에게 "새 토큰 쌍을 발급해도 이전 access 토큰은 stateless 서명 검증을 그대로 통과해 최대 3h 살아 있다"를 설명한 뒤, **별도 작업으로 분리**하기로 확정했다. 이 문서는 그 사실을 계약으로 고정만 한다(USER-PE-32·제약 4)
  - **관리자의 타인 프로필 수정** — 대상 계정은 항상 토큰 주체 본인이다(USER-PE-5)

## 엔드포인트

| 메서드 | 경로 | 인증 | 요청 본문 | 성공 |
|---|---|---|---|---|
| PATCH | `/api/users/me/nickname` | 필수(access) | `{"nickname":"새닉"}` | 204 |
| PATCH | `/api/users/me/password` | 필수(access) | `{"currentPassword":"...","newPassword":"..."}` | 200 `ApiResponse<TokenResponse>` |

**성공 응답이 두 경로에서 다른 것은 의도된 비대칭이다.** 닉네임 변경은 세션에 아무 영향이 없어 돌려줄 것이 없지만(204), 비밀번호 변경은 **기존 refresh 토큰을 전부 만료시키므로** 그 자리에서 새 토큰 쌍을 주지 않으면 본인이 재로그인해야 한다. 응답 타입은 로그인·재발급과 **문자 그대로 같은 `auth.dto.TokenResponse`**(`{accessToken, refreshToken}`)를 재사용한다 — 응원 API가 `TeamResponse`/`PlayerResponse`를 재사용한 것과 같은 판단이다(자세한 근거는 결정 근거 5·6).

**두 값을 함께 받는 경로는 없다.** 사용자가 "한 번에 하나만 변경한다"로 확정했다. 이 분리는 저장소가 상태 전이마다 경로를 나눠 온 컨벤션(`/support/team`·`/support/players`·`/support/players/oppose`, `/auth/nickname/validate`·`/auth/password/validate`)과도 일치하며, 비밀번호에만 붙는 부수 효과(현재 비밀번호 재확인·refresh 토큰 만료)가 닉네임 계약을 오염시키지 않는다.

## 요구사항 (EARS)

### 공통 — 두 경로 모두

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PE-1 | 유비쿼터스 | THE 시스템 SHALL 두 수정 경로 모두에 유효한 access 토큰을 요구한다 | `Authorization` 헤더 없이 호출 → 401, `{"success":false,"data":null,"message":"인증이 필요합니다."}` (`UNAUTHENTICATED`) |
| USER-PE-2 | 예외 | IF 토큰이 위조·만료되었으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | `Authorization: Bearer not-a-jwt` → 401, 본문은 USER-PE-1과 동일 |
| USER-PE-3 | 예외 | IF refresh 토큰으로 요청하면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 유효한 refresh 토큰을 `Bearer`로 실어 호출 → 401 |
| USER-PE-4 | 예외 | IF 탈퇴한 계정의 access 토큰으로 요청하면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환하고 계정 값을 변경하지 않는다 | 탈퇴 후 같은 access 토큰으로 호출 → 401, `users_account.nickname`·`password`가 요청 전과 동일 |
| USER-PE-5 | 유비쿼터스 | THE 시스템 SHALL 수정 대상 계정을 access 토큰에서만 식별하고 경로·쿼리·본문으로 받지 않는다 | 경로에 식별자가 없다. 본문에 `userId`·`uid`·`email`을 추가로 넣어도 무시되고 토큰 주체 본인의 값만 바뀐다 |
| USER-PE-6 | 유비쿼터스 | THE 시스템 SHALL 응답 본문 어디에도 비밀번호(평문·bcrypt 해시)를 담지 않는다 | 두 경로의 성공·실패 응답 전부에 `password`·`currentPassword`·`newPassword` 키가 없다 |
| USER-PE-39 | 유비쿼터스 | THE 시스템 SHALL 응답 본문에 `uid`를 **키로** 담지 않는다 | 두 경로의 성공·실패 응답 전부에 `uid` 키가 없다. 비밀번호 변경 응답의 `accessToken`·`refreshToken`은 JWT라 그 payload를 base64 디코드하면 `sub`에서 uid를 읽을 수 있으나, 이는 로그인·재발급 응답이 이미 갖는 성질이며 **이 경로만의 새 노출이 아니다**(결정 근거 8) |
| USER-PE-7 | 예외 | IF 수정 요청이 어떤 사유로든 실패하면, THEN THE 시스템 SHALL 그 계정의 닉네임과 비밀번호를 모두 요청 전 값 그대로 유지한다 | 400·409 응답 후 `users_account.nickname`·`password`가 요청 전과 문자 그대로 동일(부분 반영 없음) |

### 닉네임 변경 — `PATCH /api/users/me/nickname`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PE-8 | 이벤트 | WHEN 인증된 사용자가 정책을 만족하고 아직 점유되지 않은 새 닉네임으로 **쿨다운이 풀린 상태에서** 변경을 요청하면, THE 시스템 SHALL `users_account.nickname`을 요청 값으로 교체한다 | `{"nickname":"길동gil9"}`(미점유, 현재 값과 다름, 마지막 변경 30일 경과 또는 변경 이력 없음) → DB `nickname`이 `길동gil9`. 쿨다운 중이면 교체되지 않는다(USER-PE-43) |
| USER-PE-9 | 이벤트 | WHEN 닉네임 변경이 성공하면, THE 시스템 SHALL 본문 없이 204를 반환한다 | `204 No Content`, 본문 없음(변경된 닉네임을 응답에 싣지 않는다 — 결정 근거 5) |
| USER-PE-10 | 이벤트 | WHEN 닉네임 변경이 성공하면, THE 시스템 SHALL 이후 `GET /api/users/me`의 `nickname`에 변경된 값을 반환한다 | 변경 직후 `GET /api/users/me` → `data.nickname`이 새 값. 응답의 다른 키(`supportTeam`·`supportPlayers`·`point`·`bqScore`)는 변경 전과 동일 |
| USER-PE-11 | 유비쿼터스 | THE 시스템 SHALL 닉네임 형식 판정에 회원가입과 동일한 `NicknamePolicy` 판정을 사용한다 | 임의 문자열 X에 대해 `POST /api/auth/nickname/validate`가 내는 메시지 M과 이 경로가 400으로 내는 메시지가 **문자 그대로 동일**하다 |
| USER-PE-12 | 예외 | IF 요청 닉네임이 길이(1~10자)를 위반하면, THEN THE 시스템 SHALL 400과 `"닉네임은 1~10자여야 합니다."`를 `data.nickname`에 담아 반환한다 | `{"nickname":"가나다라마바사아자차카"}`(11자) → 400, `{"success":false,"data":{"nickname":"닉네임은 1~10자여야 합니다."},"message":"입력값이 올바르지 않습니다."}` |
| USER-PE-13 | 예외 | IF 요청 닉네임에 허용 문자(한글·영문·숫자) 외의 문자가 포함되면, THEN THE 시스템 SHALL 400과 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."`를 `data.nickname`에 담아 반환한다 | `{"nickname":"a b"}` → 400, `data.nickname`이 문자 구성 메시지. `{"nickname":"굿🎉"}`도 동일 |
| USER-PE-14 | 예외 | IF 요청 본문에 `nickname`이 없거나 `null`이면, THEN THE 시스템 SHALL 400과 길이 위반 메시지를 반환한다 | `{}` 또는 `{"nickname":null}` → 400, `data.nickname`이 `"닉네임은 1~10자여야 합니다."`(`@NotBlank`의 `"공백일 수 없습니다"` 류가 아님) |
| USER-PE-15 | 예외 | IF 요청 닉네임이 형식을 위반하면, THEN THE 시스템 SHALL 동일 여부 판정·중복 조회·쿨다운 판정을 **모두** 수행하지 않는다 | 형식 위반이면서 이미 점유됐고 쿨다운 중이기까지 한 닉네임 → 응답이 409·429가 아니라 400이고 메시지가 형식 메시지다 |
| USER-PE-16 | 예외 | IF 요청 닉네임이 형식을 통과하고 **다른 계정**(탈퇴 계정 포함)이 점유한 값이면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 닉네임입니다."`를 반환한다 | 타 계정이 쓰는 닉네임으로 요청 → 409, `{"success":false,"data":null,"message":"이미 사용 중인 닉네임입니다."}` (`DUPLICATE_NICKNAME`). 탈퇴 계정이 점유한 닉네임도 동일하게 409 |
| USER-PE-17 | 예외 | IF 요청 닉네임이 자기 자신의 현재 닉네임과 완전히 같으면, THEN THE 시스템 SHALL 400과 `"현재 닉네임과 다른 닉네임을 사용해 주세요."`를 반환하고 닉네임을 교체하지 않는다 | 현재 닉네임 `길동`인 계정이 `{"nickname":"길동"}` 요청 → 400, `{"success":false,"data":null,"message":"현재 닉네임과 다른 닉네임을 사용해 주세요."}` (신규 `SAME_AS_CURRENT_NICKNAME`). **409 `"이미 사용 중인 닉네임입니다."`가 아니다** — 자기 닉네임에 대해 그 문구는 거짓이다(결정 근거 3) |
| USER-PE-18 | 유비쿼터스 | THE 시스템 SHALL 닉네임 변경 성공 후 이전 닉네임의 점유를 해제한다 | `길동` → `철수`로 변경한 뒤 `POST /api/auth/nickname/duplicate` `{"nickname":"길동"}` → `valid:true`. 다른 계정이 `길동`으로 가입·변경할 수 있다(탈퇴 계정의 영구 점유와 다른 사건이다) |
| USER-PE-19 | 유비쿼터스 | THE 시스템 SHALL 닉네임 변경으로 access·refresh 토큰을 무효화하지 않는다 | 변경 후 같은 access 토큰으로 `GET /api/users/me` → 200. 같은 refresh 토큰으로 `POST /api/auth/refresh` → 200 |
| USER-PE-33 | 유비쿼터스 | THE 시스템 SHALL 닉네임 변경의 판정 순서를 ①길이 → ②문자 구성 → ③현재 닉네임과 동일 → ④타 계정 점유 → **⑤쿨다운**으로 고정하고, 첫 위반 하나만 응답한다 | 11자이면서 특수문자를 포함하고 이미 점유됐으며 쿨다운 중이기까지 한 닉네임 → 400 + 길이 메시지 하나뿐. 쿨다운 중인 계정이 **이미 점유된** 닉네임을 요청하면 429가 아니라 409다(쿨다운이 마지막이므로). 응답에 위반이 2개 이상 담기지 않는다 |
| USER-PE-34 | 유비쿼터스 | THE 시스템 SHALL 닉네임 변경 요청에 비밀번호를 요구하지 않는다 | 요청 본문 키가 `nickname` 하나뿐이다. 비밀번호 없이 access 토큰만으로 200번대 응답(204)을 받는다 |

### 닉네임 변경 쿨다운 30일 (3차 개정) — 선행 스키마 + `PATCH /api/users/me/nickname`

> 이 절만 새 컬럼을 요구한다. 컬럼이 없으면 USER-PE-43이 성립할 수 없고, **quiz 앱이 500으로 죽는다**(아래 "배포 전제").

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PE-40 | 유비쿼터스 | THE 시스템 SHALL `users_account` 테이블에 `nickname_changed_epoch_second` 컬럼(BIGINT, NULL 허용, 기본값 없음, 인덱스 없음)을 보유한다 | `SHOW COLUMNS FROM users_account LIKE 'nickname_changed_epoch_second'` → `Type=bigint`, `Null=YES`, `Default=NULL`, `Key`가 비어 있음 |
| USER-PE-41 | 이벤트 | WHEN 닉네임 변경이 성공하면, THE 시스템 SHALL 그 계정의 `nickname_changed_epoch_second`에 변경 시각의 epoch 초를 기록한다 | 204를 받은 직후 그 계정의 컬럼 값이 요청 시각의 epoch 초(±수 초). 이전 값이 있었다면 덮어쓴다 |
| USER-PE-42 | 예외 | IF 닉네임 변경이 실패하면, THEN THE 시스템 SHALL `nickname_changed_epoch_second`를 갱신하지 않는다 | 400·409·429 응답 후 컬럼 값이 요청 전과 동일(NULL이었다면 NULL 그대로). USER-PE-7("실패 시 무변경")의 연장이다 |
| USER-PE-43 | 예외 | IF 마지막 닉네임 변경으로부터 30일이 지나지 않았으면, THEN THE 시스템 SHALL 429와 `"닉네임은 30일에 한 번만 변경할 수 있습니다."`를 반환하고 닉네임을 교체하지 않는다 | 닉네임을 바꾼 직후 다른 유효 닉네임으로 재요청 → 429, `{"success":false,"data":{"nextChangeableAt":"2026-09-16T14:03:21+09:00"},"message":"닉네임은 30일에 한 번만 변경할 수 있습니다."}` (신규 `NICKNAME_CHANGE_COOLDOWN`). DB `nickname` 불변 |
| USER-PE-44 | 유비쿼터스 | THE 시스템 SHALL 마지막 변경으로부터 **30일(2,592,000초)이 지난 시점부터** 재변경을 허용한다 | `nickname_changed_epoch_second`를 `현재 - 2,592,000`으로 맞추면 변경 성공(204). `현재 - 2,591,999`(1초 모자람)면 429. 경계 시점은 **허용** 쪽이다 |
| USER-PE-45 | 예외 | IF 그 계정의 `nickname_changed_epoch_second`가 NULL이면, THEN THE 시스템 SHALL 쿨다운을 적용하지 않는다 | 컬럼이 NULL인 계정(가입 후 한 번도 안 바꿨거나 컬럼 도입 이전 계정) → 429 없이 변경 성공. 429·500이 아니다 |
| USER-PE-46 | 유비쿼터스 | THE 시스템 SHALL 회원가입 시 `nickname_changed_epoch_second`를 채우지 않는다 | 가입 직후 `SELECT nickname_changed_epoch_second` → NULL. 가입 직후 곧바로 닉네임을 바꿔도 429가 나지 않는다(가입 요청·응답 계약도 변하지 않음) |
| USER-PE-47 | 유비쿼터스 | THE 시스템 SHALL 429 응답의 `data` 키 집합을 정확히 `{nextChangeableAt}`으로 한정하고, 그 값을 오프셋을 포함한 ISO-8601 시각 문자열로 담는다 | `data`의 키가 1개. 값이 `"2026-09-16T14:03:21+09:00"` 형태(오프셋 `+09:00` 포함, 남은 일수·epoch 숫자가 아님). 그 값은 `마지막 변경 시각 + 30일`과 일치한다 |
| USER-PE-48 | 유비쿼터스 | THE 시스템 SHALL 비밀번호 변경에는 쿨다운을 적용하지 않는다 | 비밀번호를 연속 2회(간격 제한 없이) 변경 → 두 번 다 200 + 새 토큰 쌍. 429가 나지 않는다(결정 근거 9) |
| USER-PE-49 | 유비쿼터스 | THE 시스템 SHALL 쿨다운 판정을 epoch 초 비교로 수행해 실행 환경의 시간대에 좌우되지 않게 한다 | 파드 시간대가 UTC든 KST든 같은 데이터에 대해 판정 결과가 같고, `nextChangeableAt`이 가리키는 **절대 시각**도 같다(표기 오프셋만 `Asia/Seoul` 기준으로 렌더링됨) |

**429 응답에 `data`를 싣는 것은 이 저장소에서 처음이다** — 기존 `BusinessException` 경로는 예외 없이 `data: null`이었다. 구조적 마찰은 제약 9에 사실로 적었다.

### 비밀번호 변경 — `PATCH /api/users/me/password`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PE-20 | 유비쿼터스 | THE 시스템 SHALL 비밀번호 변경 요청에 현재 비밀번호를 함께 요구한다 | 요청 본문 키가 `currentPassword`·`newPassword` 2개. `newPassword`만 보내면 성공하지 않는다(USER-PE-27) |
| USER-PE-21 | 이벤트 | WHEN 인증된 사용자가 올바른 현재 비밀번호와 정책을 만족하는 새 비밀번호로 변경을 요청하면, THE 시스템 SHALL `users_account.password`를 새 비밀번호의 bcrypt 해시로 교체한다 | 변경 후 DB `password` 값이 요청 전 해시와 다르고, 평문 `newPassword` 문자열과도 다르다(`$2` 로 시작하는 해시) |
| USER-PE-22 | 이벤트 | WHEN 비밀번호 변경이 성공하면, THE 시스템 SHALL 200과 새로 발급한 토큰 쌍을 담은 `ApiResponse<TokenResponse>`를 반환한다 | 200, `{"success":true,"data":{"accessToken":"eyJ...","refreshToken":"eyJ..."},"message":null}`. `data`의 키는 정확히 `{accessToken, refreshToken}` 2개이며 `POST /api/auth/login`·`/refresh` 응답의 `data`와 같은 형태다(신규 DTO 없이 `auth.dto.TokenResponse` 재사용) |
| USER-PE-23 | 이벤트 | WHEN 비밀번호 변경이 성공하면, THE 시스템 SHALL 이후 로그인에서 새 비밀번호만 받아들인다 | 새 비밀번호로 `POST /api/auth/login` → 200 + 토큰. 이전 비밀번호로 로그인 → 401 `"이메일 또는 비밀번호가 올바르지 않습니다."`(`INVALID_CREDENTIALS`) |
| USER-PE-24 | 유비쿼터스 | THE 시스템 SHALL 새 비밀번호 형식 판정에 회원가입과 동일한 `PasswordPolicy` 판정을 사용한다 | 임의 문자열 X에 대해 `POST /api/auth/password/validate`가 내는 메시지와 이 경로가 400으로 내는 메시지가 **문자 그대로 동일**하다 |
| USER-PE-25 | 예외 | IF 새 비밀번호가 길이(8~12자)를 위반하거나 `null`·누락이면, THEN THE 시스템 SHALL 400과 `"비밀번호는 8~12자여야 합니다."`를 `data.newPassword`에 담아 반환한다 | `{"currentPassword":"Old1234!","newPassword":"a1!"}` → 400, `{"success":false,"data":{"newPassword":"비밀번호는 8~12자여야 합니다."},"message":"입력값이 올바르지 않습니다."}` |
| USER-PE-26 | 예외 | IF 새 비밀번호가 영문·숫자·특수문자 중 하나라도 빠뜨리면, THEN THE 시스템 SHALL 400과 `"비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다."`를 `data.newPassword`에 담아 반환한다 | `{"newPassword":"abcdefgh"}` → 400, `data.newPassword`가 문자 구성 메시지(길이 메시지 아님) |
| USER-PE-27 | 예외 | IF 현재 비밀번호가 저장된 값과 일치하지 않거나 `null`·누락이면, THEN THE 시스템 SHALL 400과 `"현재 비밀번호가 올바르지 않습니다."`를 반환하고 비밀번호를 교체하지 않는다 | 틀린 `currentPassword` → 400, `{"success":false,"data":null,"message":"현재 비밀번호가 올바르지 않습니다."}` (신규 `INVALID_CURRENT_PASSWORD`). `currentPassword` 키를 생략해도 같은 응답. DB `password` 해시 불변 |
| USER-PE-28 | 예외 | IF 새 비밀번호가 현재 비밀번호와 동일하면, THEN THE 시스템 SHALL 400과 `"현재 비밀번호와 다른 비밀번호를 사용해 주세요."`를 반환하고 비밀번호를 교체하지 않는다 | `currentPassword`와 `newPassword`가 같은 값(형식은 만족) → 400, 신규 `SAME_AS_CURRENT_PASSWORD`. DB `password` 해시가 요청 전과 동일(재해싱조차 하지 않음) |
| USER-PE-29 | 유비쿼터스 | THE 시스템 SHALL 비밀번호 변경의 판정 순서를 ①새 비밀번호 형식 → ②현재 비밀번호 일치 → ③신·구 동일 여부로 고정하고, 첫 위반 하나만 응답한다 | 새 비밀번호 형식 위반 + 현재 비밀번호 오답을 동시에 보내면 → 400이며 응답은 `data.newPassword` 형식 메시지 하나뿐(`"현재 비밀번호가 올바르지 않습니다."`가 아니며, 두 메시지가 함께 나오지 않는다) |
| USER-PE-30 | 이벤트 | WHEN 비밀번호 변경이 성공하면, THE 시스템 SHALL 그 계정의 **기존** 유효 refresh 토큰을 모두 만료시킨다 | **변경 직전에 갖고 있던** refresh 토큰으로 `POST /api/auth/refresh` → 401 `"만료되었거나 이미 무효화된 리프레시 토큰입니다."`(`EXPIRED_REFRESH_TOKEN`) |
| USER-PE-31 | 예외 | IF 비밀번호 변경이 실패하면, THEN THE 시스템 SHALL refresh 토큰을 만료시키지 않는다 | 현재 비밀번호 오답으로 400을 받은 뒤 기존 refresh 토큰으로 `POST /api/auth/refresh` → 200 |
| ~~USER-PE-32~~ | — | ~~THE 시스템 SHALL 비밀번호 변경 전에 발급된 access 토큰을 무효화하지 않는다~~ | **폐기됨 — `access-token-invalidation.md`의 USER-ATI-4가 대체한다.** 이 요구사항의 인수 기준("변경 직후 변경 전 access 토큰으로 `GET /api/users/me` → 200")은 이제 **거짓**이다: 그 토큰은 401이다. 폐기는 사고가 아니라 후속 작업의 목적이며, 새 토큰 쌍이 즉시 인증된다는 계약(USER-PE-22·36)은 USER-ATI-7이 회귀 방지로 그대로 이어받는다 |
| USER-PE-35 | 유비쿼터스 | THE 시스템 SHALL 비밀번호 변경 시 기존 유효 refresh 토큰을 **먼저 만료시킨 뒤** 새 refresh 토큰 1건을 발급한다 | 변경 직후 그 계정의 `users_refreshtoken`에 만료되지 않은 행이 **정확히 1건**이고 그 값이 응답 `data.refreshToken`과 일치한다(계정당 유효 refresh 1개 정책 유지 — `AuthService`가 발급 직전 `expireValidTokens()`를 부르는 절차와 동일) |
| USER-PE-36 | 이벤트 | WHEN 비밀번호 변경이 성공하면, THE 시스템 SHALL 응답의 `accessToken`으로 인증이 필요한 요청이 즉시 통과하도록 한다 | 변경 응답의 `data.accessToken`으로 `GET /api/users/me` → 200 |
| USER-PE-37 | 이벤트 | WHEN 비밀번호 변경이 성공하면, THE 시스템 SHALL 응답의 `refreshToken`으로 재발급이 가능하도록 한다 | 변경 응답의 `data.refreshToken`으로 `POST /api/auth/refresh` → 200 + 새 토큰 쌍 |
| USER-PE-38 | 예외 | IF 비밀번호 변경이 실패하면, THEN THE 시스템 SHALL 새 토큰을 발급하지 않는다 | 400 응답 본문에 `accessToken`·`refreshToken` 키가 없고, `users_refreshtoken`의 행 수가 요청 전과 동일 |

## 제약 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)

1. **`/api/users/**`는 이미 `anyRequest().authenticated()`에 걸린다 — `SecurityConfig`를 건드리지 않는 것이 정답이다.** `/teams`·`/players`·`/games/lineup`처럼 GET 한정 `permitAll` 한 줄을 추가하면 USER-PE-1~4가 통째로 무너진다(`/games/support`가 같은 이유로 `SecurityConfig`를 그대로 둔 사례가 있다). 반대로 `/api/auth/**` 아래에 두면 전부 `permitAll`이라 인증이 걸리지 않는다(`withdraw.md` 제약 1과 같은 함정).
2. **닉네임 중복 판정에 "본인 제외" 로직을 넣지 않는다.** `existsByNickname`은 자기 자신을 걸러내지 않지만, USER-PE-17이 **중복 조회보다 먼저** 같은 값을 400으로 잘라내므로 중복 단계에 요청자 본인이 도달하는 경우가 없다(USER-PE-33의 순서가 이 사실을 보장한다). signup·`/nickname/validate`·`/nickname/duplicate`와 **문자 그대로 같은 판정**을 계속 공유하는 것이 단일 출처 원칙에 맞다. ⚠ USER-PE-17을 없애거나 순서를 뒤집으면 이 전제가 깨져 자기 닉네임 요청이 409가 된다.
3. **`UserAccount`에 `@Setter`가 없고 domain 컨벤션상 두지 않는다.** 닉네임·비밀번호 교체는 엔티티가 자신의 상태 전이를 책임지는 형태여야 한다(`withdraw(LocalDateTime)`가 이미 그 형태다). 서비스가 setter로 값을 밀어넣는 형태는 컨벤션 위반이다. **변경 시각 기록도 마찬가지다** — `nickname_changed_epoch_second`를 별도 setter로 밀어넣지 말고 **닉네임 전이 메서드(`changeNickname` 류)가 닉네임 교체와 시각 기록을 함께 책임져야** USER-PE-41("성공했을 때만 기록")이 구조적으로 보장된다. 둘을 분리하면 한쪽만 호출되는 경로가 언젠가 생긴다.
4. ~~**비밀번호 변경에는 탈퇴가 가진 안전망이 없다 — 새 토큰을 발급해도 마찬가지다.**~~ **이 제약은 더 이상 참이 아니다 — `access-token-invalidation.md`(USER-ATI-4·13·20)가 그 구멍을 닫았다.** 필터가 매 요청 "비밀번호 변경 시각보다 앞선 초에 발급된 토큰인가"를 대조하고(같은 조회에 기준 시각 컬럼을 함께 실어 요청당 조회는 늘지 않았다), `AuthService.reissue`가 refresh에도 같은 대조를 적용한다. 토큰 claim 계약(`sub`=uid만)은 결국 **바뀌지 않았다** — `iat`가 이미 실려 있었기 때문이다(USER-ATI-14). 남은 사실만 기록해 둔다: access 토큰 검증이 서명·만료만 보는 stateless 판정이라는 성질 자체는 그대로이고, 무효화는 그 판정 뒤에 계정 상태 대조를 한 겹 얹어 이룬 것이다.
5. **`newPassword`·`nickname`에 검증 애노테이션을 겹쳐 걸지 말 것.** 각각 `@ValidPassword`/`@ValidNickname` 하나만 건다. 겹치면 동시 위반 시 `GlobalExceptionHandler`의 `Map#put` 순서 비보장으로 응답 메시지가 호출마다 달라진다(모듈 컨텍스트의 `SignupRequest` 주의 그대로). USER-PE-27이 `currentPassword` 누락을 별도 `@NotBlank` 400이 아니라 "불일치"로 흡수하는 이유도 같다 — 위반이 항상 정확히 1개여야 USER-PE-29가 성립한다.
6. **신규 `ErrorCode` 4종이 `:common`에 필요하다**: `INVALID_CURRENT_PASSWORD`(400, `"현재 비밀번호가 올바르지 않습니다."`) · `SAME_AS_CURRENT_PASSWORD`(400, `"현재 비밀번호와 다른 비밀번호를 사용해 주세요."`) · `SAME_AS_CURRENT_NICKNAME`(400, `"현재 닉네임과 다른 닉네임을 사용해 주세요."`) · **`NICKNAME_CHANGE_COOLDOWN`(429, `"닉네임은 30일에 한 번만 변경할 수 있습니다."`)**. 앞 셋은 `BusinessException` 경로를 그대로 타지만 **넷째는 그렇지 않다**(제약 9). 명명은 기존 `EMAIL_SEND_COOLDOWN`(이 모듈 유일한 429)의 `<대상>_<동작>_COOLDOWN` 형태를 따랐다. `:common`은 user·quiz 공용이므로 추가는 두 앱에 함께 반영된다.
7. **현재 시각이 필요하면 `Clock` 빈을 쓴다.** USER-PE-30(refresh 토큰 만료)과 USER-PE-41·43(쿨다운 기록·판정) 둘 다 현재 시각을 필요로 한다. 운영 파드가 UTC라 `LocalDateTime.now()`를 직접 읽으면 KST 자정~오전 9시 사이에 날짜가 어긋난다(`ClockConfig`가 `Asia/Seoul` 고정 단일 출처). 기존 `AuthService`·`UserAccountService`가 아직 `LocalDateTime.now()`를 직접 쓰는 것은 알려진 미해결 지점이며, 새 경로가 그것을 따라갈 이유는 없다. **쿨다운은 저장·비교가 epoch 초라 시간대 자체에는 영향받지 않지만**(USER-PE-49), `nextChangeableAt`을 `+09:00` 오프셋으로 렌더링하는 데에는 이 `Clock`의 존이 그대로 쓰인다.
8. **30일이라는 기간과 429 메시지는 각각 한 곳에서만 정의돼야 한다.** 닉네임 정책의 단일 출처가 `NicknamePolicy`인 것과 같은 이유다 — 쿨다운 길이가 판정 코드·응답 메시지·`nextChangeableAt` 계산 세 군데에 각각 박히면 셋이 어긋난다(메시지에 `30`이라는 숫자가 들어 있어 특히 어긋나기 쉽다. `PasswordPolicy.LENGTH_MESSAGE`가 `MIN_LENGTH`·`MAX_LENGTH`로 조립되는 이유와 같다). 어느 클래스에 둘지는 구현 판단이다.
9. **⚠ `BusinessException`은 응답에 데이터를 실을 수 없다 — USER-PE-43이 이 구조와 정면으로 부딪친다.** 확인한 사실은 이렇다.
   - `ApiResponse`에는 이미 `fail(String message, T data)` 오버로드가 **있고**, `GlobalExceptionHandler.handleValidation`이 그것으로 400 응답에 `data`(필드별 메시지 맵)를 싣고 있다. 즉 **막힌 것은 `ApiResponse`가 아니다.**
   - 막힌 곳은 `BusinessException` 경로다: `BusinessException`은 `ErrorCode` 하나만 들고 있고 `ErrorCode`는 `status`·`message`만 갖는다. 그래서 `handleBusiness`가 `ApiResponse.fail(errorCode.getMessage())`밖에 부를 수 없고 **모든 `BusinessException` 응답의 `data`는 예외 없이 `null`이다.**
   - USER-PE-43은 이 저장소에서 **`BusinessException` 계열 실패 응답이 도메인 데이터를 싣는 첫 사례**다. 예외 객체·`ErrorCode`·핸들러 중 어디를 어떻게 여느냐는 `spring-dev` 판단이지만, **어느 길을 택하든 기존 `BusinessException` 응답들의 `data:null` 계약을 깨뜨리지 않아야 한다**(다른 예외의 응답 형태가 함께 바뀌면 그건 이 요구사항의 범위를 넘는 파급이다).

## 배포 전제 (3차 개정 — 컬럼 수동 선투입)

**사용자가 컬럼을 수동 DDL로 선투입하기로 확정했다.** 이 문서가 만드는 컬럼은 **하나뿐**이다.

```sql
ALTER TABLE users_account ADD COLUMN nickname_changed_epoch_second BIGINT NULL;
```

- **컬럼명은 후속 작업과 대칭이다.** 후속 작업 `hwannee/be/feat-token-invalidation`이 자기 목적으로 추가할 `password_changed_epoch_second`와 짝을 이루도록 `nickname_changed_epoch_second`로 정했다. **그 컬럼은 이 브랜치가 만들지 않는다**(비밀번호 쿨다운이 빠지면서 이 브랜치에 필요한 이유가 사라졌다).
- **왜 epoch 초 `BIGINT`인가**: 존 무관 저장이라 파드 TZ 전환·파드 간 TZ 불일치의 영향을 받지 않는다(USER-PE-49). `DATETIME`으로 두면 이 저장소가 이미 겪고 있는 "파드 UTC ↔ KST 9시간" 문제를 새 컬럼이 한 벌 더 떠안는다.
- **인덱스 없음**: 이 컬럼으로 조회하는 경로가 없다(항상 자기 계정 행을 읽은 뒤 그 안의 값을 본다). 기본값 없음·NULL 허용인 이유는 USER-PE-45·46(결정 근거 12)이다.

**적용 순서**
1. **DDL을 먼저 넣는다**(운영·dev 양쪽). 그 다음 앱을 배포한다.
2. 검증: `SHOW COLUMNS FROM users_account LIKE 'nickname_changed_epoch_second'`가 1행(USER-PE-40).

**⚠ 이 순서를 빠뜨렸을 때의 증상 — user만 보면 절대 못 잡는다**
- **user 앱은 멀쩡해 보인다.** prod `ddl-auto`가 앱마다 다르고 **user는 `update`**라, DDL을 빠뜨려도 user가 기동하면서 컬럼을 스스로 만들어 버린다. "배포했더니 잘 되네"가 곧 "빠뜨린 걸 몰랐다"가 된다.
- **터지는 쪽은 quiz다.** quiz는 `ddl-auto=none`이라 컬럼을 만들지 않는데, `UserAccount`는 `:domain`의 **공유 엔티티**이고 quiz가 `UserAccountRepository`를 **`QuizSubmitService`·`QuizLikeToggler`·`ChatService` 세 곳**에서 쓴다(코드 확인). 엔티티에 없는 컬럼을 SELECT 하게 되어 **quiz 쪽 요청이 500**으로 떨어진다.
- 즉 **user 배포가 quiz를 깨뜨리는 형태**이며, 증상과 원인이 다른 앱에 있다. 배포 검증은 user가 아니라 **quiz의 퀴즈 제출·좋아요·채팅 경로**에서 해야 한다.
- **운영 DB 접근 수단**: 로컬에 mysql 클라이언트가 없고 `.env`는 dev DB만 가리킨다 — 운영 SQL은 **클러스터 안 일회용 `mysql:8.0` 파드 + `app-secret`** 경유로 실행한다.

## 결정 근거 (해소된 질문 — 조사를 반복하지 않기 위해)

1. **경로를 나눈다(사용자 확정: 한 번에 하나만 변경).** 단일 `PATCH /api/users/me`로 두 필드를 선택적으로 받는 안은 폐기됐다. 그 안을 택했다면 "미전달 필드 = 변경 안 함", "둘 다 없을 때의 동작", "닉네임은 성공했는데 비밀번호가 실패했을 때의 롤백" 규칙이 추가로 필요했다. 지금 계약에는 그 세 규칙이 **존재하지 않아야 정상**이다.
2. **현재 비밀번호 불일치는 401이 아니라 400 + 신규 `INVALID_CURRENT_PASSWORD`다.** 기존 `INVALID_CREDENTIALS`(401)를 재사용하면 신규 코드는 아끼지만 두 가지가 걸린다. ①FE 인터셉터가 401을 "토큰 만료 → 재발급/로그아웃"으로 처리하는 것이 일반적이라, **비밀번호 오타 한 번에 로그아웃되는 사고**가 난다. ②메시지가 `"이메일 또는 비밀번호가 올바르지 않습니다."`라 이메일을 보내지도 않은 화면에서 거짓에 가깝다. 참고로 401이 "인증 실패"를 뜻하는 이 경로의 진짜 인증 수단은 **access 토큰**이고 그건 이미 통과한 상태다 — 여기서의 현재 비밀번호는 인증이 아니라 **요청 본문의 검증 대상**이므로 400이 의미상으로도 맞다.
3. **같은 값 거부는 409가 아니라 400 신규 코드 2종이다 — 그리고 이 결정이 중복 판정의 모양을 바꾼다.** 자기 닉네임을 409 `"이미 사용 중인 닉네임입니다."`로 흡수하면 구현은 한 줄도 안 늘지만(현행 `existsByNickname`이 자기 자신도 매칭한다) 그 문구가 **자기 닉네임에 대해서는 거짓**이다. 그래서 `SAME_AS_CURRENT_NICKNAME`(400)을 따로 둔다.
   - **귀결**: 같은 값이 중복 검사보다 먼저 걸러지므로(USER-PE-33), 중복 단계에서 `existsByNickname`이 요청자 자신과 매칭될 경우가 남지 않는다. 초안이 예고했던 "중복 판정에서 본인 계정을 제외해야 한다"는 요건은 **불필요해졌다**(제약 2). 구현은 signup과 문자 그대로 같은 메서드를 계속 쓴다.
   - 비밀번호도 같은 이유로 `SAME_AS_CURRENT_PASSWORD`(400)다. 허용하고 성공 처리하는 안은 "바꿨다고 안내받았지만 실제로는 그대로"인 상태를 만든다.
4. **비밀번호 경로의 판정 순서(형식 → 현재 비밀번호 → 신·구 동일)는 프레임워크가 강제하는 것이며, "현재 비밀번호가 맞아야 교체된다"와 충돌하지 않는다.** `@ValidPassword`는 빈 검증이라 **컨트롤러 진입 전**에 돌고, 실패는 `MethodArgumentNotValidException` → 400이다. 즉 형식 위반이면 서비스 코드가 실행조차 되지 않아 현재 비밀번호를 볼 기회 자체가 없다. 이것이 사용자의 요구를 어기지 않는 이유: **형식 위반 응답도 "교체하지 않음"**이기 때문이다. 사용자가 말한 것은 "현재 비밀번호가 틀렸는데 교체되는 일은 없다"이고, 그 명제는 순서와 무관하게 성립한다(USER-PE-7·27이 고정). 순서를 반대로 뒤집으면 형식이 틀린 요청에도 bcrypt `matches()`를 매번 돌리게 돼 얻는 것 없이 비용만 는다.
5. **성공 응답은 닉네임 204 / 비밀번호 200으로 비대칭이다(2026-08-17 2차 개정).** 처음에는 두 경로를 204로 대칭 고정했으나, 비밀번호 경로가 새 토큰 쌍을 돌려주기로 하면서(근거 6) 비대칭이 됐다. **비대칭의 근거는 "돌려줄 것이 있는가"다** — 닉네임 변경은 세션에 아무 영향이 없고 FE는 방금 보낸 값을 이미 알고 있어 본문이 필요 없지만, 비밀번호 변경은 **자기가 방금 무효화한 세션의 대체물**을 돌려줘야 한다. 닉네임을 200 + `{"nickname":"..."}`로 되돌리는 안, `/me`와 같은 전체 프로필을 돌려주는 안(SELECT 4회 추가 · `PlayerResponse` 소비처 증가)은 여전히 폐기 상태다 — 변경 후 최신 프로필이 필요하면 FE가 `GET /api/users/me`를 부른다.
6. **비밀번호 변경 시 기존 refresh를 전량 만료한 뒤 새 토큰 쌍을 발급해 응답에 담는다(2026-08-17 2차 개정 — 이전의 "새 토큰 쌍은 주지 않는다"를 뒤집음).** 만료는 탈퇴가 이미 쓰는 `expireValidTokens`와 같은 성격의 조치이고, 만료 → 발급 순서는 `AuthService.issueTokens`가 발급 직전 `expireValidTokens()`를 호출하는 **refresh 토큰 1개 정책의 절차 그대로**다(그래서 변경 후 유효 refresh 행은 새로 발급된 1건뿐이다, USER-PE-35).
   - **뒤집힌 이유**: 발급하지 않으면 비밀번호를 바꾼 본인도 access 3h가 지나면 새 비밀번호로 재로그인해야 한다. 사용자는 그 UX 비용을 받아들이지 않기로 했다.
   - **폐기된 반대 논거 2개는 틀려서 폐기된 것이 아니라 감수하기로 한 것이다.** ①응답이 204가 아니게 돼 두 경로의 대칭이 깨진다 ②변경 경로가 토큰 발급 책임까지 갖는다(`AuthService` 밖에 발급 지점이 하나 더 생긴다). 둘 다 여전히 사실이며, 사용자가 **본인이 재로그인하지 않아도 되는 UX를 우선해 의식적으로 감수했다.** ②의 실질적 대가는 "토큰 발급 절차가 바뀌면 고쳐야 할 자리가 두 곳"이라는 것이다 — 새 DTO를 만들지 않고 `TokenResponse`를 재사용하는 이유이기도 하다.
   - 참고로 이 서비스는 **계정당 유효 refresh 토큰이 1개**라 "다른 기기 세션"은 실질적으로 마지막 로그인 1개뿐이다.
7. **닉네임 변경에는 비밀번호를 요구하지 않는다(사용자 확정).** access 토큰 검증으로 충분하다는 판단이다. 비밀번호 변경에만 재확인이 붙는 비대칭의 근거는 피해 규모다 — 닉네임은 되돌릴 수 있지만, 비밀번호가 바뀌면 원래 소유자가 계정에서 밀려난다.
8. **"uid를 응답에 담지 않는다"와 토큰 발급의 형식적 충돌 — 얼버무리지 않고 정리한다.** `docs/api/README.md`는 **"어떤 엔드포인트도 응답 본문에 `uid`를 노출하지 않는다"**를 전역 사실로 적고 있고 `me-profile.md`(USER-ME-13)가 그것을 키 집합으로 닫았다. 그런데 비밀번호 변경 응답에는 JWT가 실리고, **JWT payload는 서명만 되고 암호화되지 않아 base64 디코드로 누구나 `sub`(=uid)를 읽을 수 있다**(모듈 컨벤션이 명시한 사실). 즉 "uid가 응답으로 나간다"는 형식적으로는 참이 된다.
   - 정리: 이 문서가 지키는 것은 **"uid를 응답 본문의 키로 노출하지 않는다"**이다(USER-PE-39). 토큰 안의 `sub`는 **`POST /api/auth/login`·`/refresh`가 이미 갖는 성질**이며, 이 경로가 새로 여는 노출이 아니다 — 같은 토큰을 이미 갖고 있는 요청자 본인에게 같은 값을 한 번 더 주는 것뿐이다.
   - uid를 감추는 목적은 **"토큰을 못 가진 제3자에게 계정 식별자를 흘리지 않는 것"**이지 토큰 소지자에게 감추는 것이 아니다(내부 PK `id`를 claim에 싣지 않는 정책은 그대로 유효하다). 따라서 이 응답으로 깨지는 정책은 없다.
   - 다만 `docs/api/README.md`의 전역 문구는 **토큰을 돌려주는 엔드포인트가 늘었다는 사실에 맞춰 다듬을 필요가 있다**(구현 후 `api-documenter`가 판단할 문서 정합성 문제이지, 이 계약의 미해결 항목이 아니다).
9. **왜 닉네임에만 쿨다운이 있고 비밀번호에는 없는가 — 반드시 다시 나올 질문이다(2026-08-17 3차 개정).** 초안 논의에서 비밀번호에도 같은 30일 제한을 거는 안이 나왔고, 사용자가 아래를 확인한 뒤 **비밀번호 제한을 빼기로 확정**했다.
   - **유출 대응 수단을 30일 잠근다.** 비밀번호가 새어 나갔다고 의심될 때 사용자가 쓸 수 있는 자구책은 사실상 "즉시 교체" 하나뿐인데, 쿨다운은 바로 그 수단을 막는다. "최근에 바꿨다"는 사실이 "지금 위험하다"보다 우선할 이유가 없다.
   - **후속 작업의 목적과 정면으로 부딪친다.** `hwannee/be/feat-token-invalidation`(access 토큰 무효화)의 취지는 "비밀번호를 바꾸면 탈취된 세션이 끊긴다"이다. 쿨다운은 그 트리거 자체를 30일 잠가, 세션을 끊을 유일한 스위치를 봉인한다.
   - **닉네임은 반대다.** 막으려는 것이 남용(닉네임 세탁·사칭 회피·표시명 도배)이고, 못 바꿔서 생기는 피해는 "불편"에 그친다. 즉 두 필드는 **보호하려는 대상이 다르다** — 닉네임 쿨다운은 다른 사용자를 보호하고, 비밀번호 무제한은 계정 주인을 보호한다. 이 비대칭은 일관성 결여가 아니라 각 필드의 위험 방향을 따른 결과다(USER-PE-48이 이를 계약으로 고정한다).
10. **왜 429인가(400·409가 아니라).** 이 모듈에는 이미 쿨다운 선례가 있다 — `EMAIL_SEND_COOLDOWN`(429)이 이 모듈 유일한 429이고, 성격이 정확히 같다("지금은 안 되고 시간이 지나면 된다"). 400으로 두면 **형식 오류와 구분되지 않는다** — 이 경로의 400은 `data.nickname`에 필드 메시지가 실리는 응답이라, FE가 "입력을 고치면 된다"로 오해하고 사용자는 멀쩡한 닉네임을 계속 고쳐 보게 된다. 409도 아니다 — 충돌하는 상대 자원(타인이 점유한 닉네임)이 없고, 막는 주체는 내 계정의 시간 제한이다. 같은 이유로 409(`DUPLICATE_NICKNAME`)와 429는 **동시에 성립할 수 있지만 429가 뒤**다(USER-PE-33).
11. **왜 `nextChangeableAt`이 오프셋 포함 ISO-8601 문자열인가(epoch 숫자·남은 일수가 아니라).** 기존 API 응답 관례를 조사한 결과다.
    - **응답 본문의 시각은 전부 문자열이다** — `GameResponse.gameDate`(`"2026-08-01T18:30:00"`), 채팅 `createdAt`, 퀴즈 `submittedAt` 모두 `LocalDateTime` 직렬화 문자열이다. **epoch 숫자를 쓰는 곳은 JWT claim(`iat`/`exp`)뿐**이고 그건 응답 본문이 아니라 토큰 내부다. 그래서 문자열을 택했다.
    - **단, 기존 문자열들이 쓰는 오프셋 없는 형태는 따라가지 않는다.** 오프셋이 없으면 그 시각이 어느 존인지 응답만 봐서는 알 수 없고, 파드가 UTC라 실제로 9시간 어긋나 보이는 문제가 이미 관측돼 있다(별도 보류 과제). **저장이 epoch라 오프셋을 붙여 렌더링하는 데 아무 손실이 없으므로**, 이 신규 필드는 처음부터 `+09:00`을 포함한다.
    - **남은 일수(`daysLeft`) 안은 폐기했다**: 반올림 규칙이 모호하고(12시간 남았을 때 0인지 1인지), 자정 경계에서 같은 값이 하루 종일 유지되다 갑자기 튀며, 무엇보다 사용자가 **"언제"를 알 수 없다**. 값의 형식이 사용자에게 주는 정보량이 가장 큰 것을 골랐다.
12. **왜 가입 시점에 값을 채우지 않는가(NULL로 시작).** 채우면 **가입 직후 30일간 닉네임을 못 바꾸게 된다** — 쿨다운이 막으려던 것은 "잦은 재변경"이지 "첫 변경"이 아니므로 부작용만 남는다. 컬럼 도입 이전 계정도 같은 이유로 NULL이며 첫 변경까지 제한이 없다(USER-PE-45).
    - **백필하지 않는다.** `me-profile.md`가 `users_bq`를 백필했던 것과 반대 결정인데, 그건 "모든 계정에 행이 있어야 한다"는 전제를 세우는 작업이었고 **여기서 NULL은 결핍이 아니라 "아직 바꾼 적 없음"이라는 의미 있는 상태**이기 때문이다. 채워 넣을 올바른 값 자체가 존재하지 않는다.
13. **왜 쿨다운이 판정 순서의 마지막인가.** 앞에 두면 **형식이 틀린 요청에도 쿨다운 메시지가 나간다** — 사용자는 닉네임 형식을 고쳐도 계속 같은 메시지에 막히는 것처럼 보이고, 실제 원인(형식 오류)을 끝내 못 본다. 부수적으로 쿨다운 판정은 계정 행의 값을 읽어야 하는데, 형식 위반은 그 전에 잘려 조회가 아예 일어나지 않는다(USER-PE-15).

## 알려진 한계 (이번 범위에서 고치지 않는 것)

- **닉네임 중복은 check-then-act라 원자적이지 않다.** `users_account.nickname`에 DB UNIQUE가 없어(`withdraw.md` 결정 근거 1) 두 계정이 동시에 같은 닉네임으로 변경하면 둘 다 통과할 수 있다. signup에 이미 있는 한계이며 스키마 변경 없이는 닫히지 않는다.
- **같은 계정에 대한 동시 수정 요청은 직렬화되지 않는다.** 응원 쓰기 경로가 쓰는 계정 행 비관적 락(`findWithLockById`)을 이 경로에 적용하지 않는다 — 지키는 불변식이 "집합의 크기"가 아니라 단일 컬럼 교체라 last-write-wins의 피해가 "나중 요청이 이긴다"에 그친다(탈퇴가 락 없이 남겨진 것과 같은 판단). 다만 같은 비밀번호 변경 요청 2건이 동시에 오면 한쪽이 USER-PE-27(현재 비밀번호 불일치)로 떨어질 수 있다.
- ~~**access 토큰은 비밀번호 변경으로 무효화되지 않는다**(USER-PE-32·제약 4). 새 토큰 쌍을 발급받아도 이전 access 토큰이 최대 3h 살아 있다.~~ **더 이상 한계가 아니다 — `access-token-invalidation.md`(USER-ATI-4·20)로 닫혔다.** 변경 이전에 발급된 access·refresh 토큰은 즉시 거절된다. 그쪽 문서가 대신 떠안은 한계는 두 가지다: 변경과 **같은 초**에 발급된 이전 토큰은 살아남고(≤1초 창, 자기 무효화를 막기 위한 대가), **로그아웃은 여전히 access 토큰을 죽이지 않는다**(최대 3h).
- **30일 이내에는 오타로 바꾼 닉네임도 되돌릴 수 없다.** `길동` → `길둥`으로 잘못 바꾸면 30일간 그대로 살아야 한다. 되돌리기 예외·유예 시간(예: 변경 직후 N분 내 취소)·관리자 우회 경로는 이번 범위에 없다(3차 개정으로 새로 생긴 한계).
- **컬럼이 NULL인 계정에는 첫 변경까지 제한이 없다**(USER-PE-45의 귀결). 컬럼 도입 이전에 이미 닉네임을 여러 번 바꿨던 계정이 있더라도 그 이력은 어디에도 없으므로 한 번은 그냥 통과한다 — 정책이 소급되지 않는다는 뜻이다.
- **`nextChangeableAt`은 항상 `Asia/Seoul` 오프셋으로 렌더링된다.** 클라이언트가 다른 지역에 있어도 서버는 `+09:00` 표기로 내보낸다(가리키는 절대 시각은 같으므로 클라이언트가 자기 존으로 변환하면 된다).

## 미해결 질문
없음 — 7건 전부 해소됨(2026-08-17 사용자 확정, 결정 근거 절 참고). 2차 개정(비밀번호 경로 성공 응답)과 3차 개정(닉네임 쿨다운 30일)으로 새로 생긴 미해결 항목도 없다. 3차 개정에서 판단이 필요했던 두 가지(429 응답 값의 형식 · 신규 `ErrorCode` 명명)는 기존 관례 조사로 확정했다(결정 근거 10·11, 제약 6).
