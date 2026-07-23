# 닉네임 정책 요구사항
> 상태: 승인됨 (2026-07-18, validate 2단 파이프라인 개정) · 모듈: user · 최종 수정: 2026-07-18

## 배경 / 목적
현재 `SignupRequest.nickname`은 `@NotBlank @Size(max=100)`뿐이라 공백·특수문자·이모지가 그대로 통과하고, "회원가입 전 닉네임을 미리 확인"할 방법이 없다. 이 기능은 **PasswordPolicy 아키텍처를 그대로 미러링**해 닉네임 규칙을 `NicknamePolicy` 단일 출처로 빼고, 회원가입 검증과 새 사전 검사 API가 **문자 그대로 같은 판정 함수**를 공유하게 한다. 두 경로가 각자 규칙을 하드코딩해 어긋나는 것(과거 password에서 겪은 비결정 메시지 문제)을 원천 차단하는 것이 목적이다.

## 범위
- 포함:
  - `NicknamePolicy` 단일 출처 정책(허용 문자·길이·위반 메시지·`findViolation`) — `PasswordPolicy` 미러
  - 커스텀 제약(`@ValidNickname` + Validator) — `@ValidPassword`/`PasswordValidator` 미러
  - `SignupRequest.nickname`의 검증을 새 제약 하나로 교체(기존 `@NotBlank @Size(max=100)` 제거)
  - 사전 검사 API `POST /api/auth/nickname/validate` — 임의 문자열을 받아 **정책 검사 → 중복 검사 2단 파이프라인**을 거쳐 **항상 200 + valid 결과** 반환(아래 "validate 2단 파이프라인" 참고)
  - **닉네임 중복(DB) 검사를 validate에 포함** — `userAccountRepository.existsByNickname` 재사용(정책 통과 시에만 수행)
- 제외:
  - **기존 닉네임 데이터 백필/마이그레이션** — max 100 → 10 축소지만 배포 환경 초기화 예정이라 대상 행이 없다(`docs/requirements/user/withdraw.md` 제약 5와 동일 전제).
  - **닉네임 변경 API** — 이번 요청 밖. 정책은 재사용 가능하게 만들되 엔드포인트는 추가하지 않는다.

## 정책 요약 (사용자 승인 완료)
| 항목 | 규칙 |
|---|---|
| 허용 문자 | 화이트리스트 `[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]` — 한글 완성형(가–힣)·호환 자모 낱자(ㄱ–ㅎ, ㅏ–ㅣ)·영문·숫자만. 공백·특수문자·이모지 전부 거부 |
| 길이 | 1~10자(포함), `String.length()`(UTF-16 code unit) 기준. 허용 문자는 전부 BMP라 code unit 1개 = 인식 1자 |
| 위반 메시지 | 항상 **1개만**, 우선순위: **① 길이 → ② 문자 구성 → ③ 중복(validate 한정)**. 정책(길이·문자)은 password와 동일 규칙, 중복은 validate에서만 3순위로 추가 |
| null/빈 문자열 | 정책 판정 함수가 예외 없이 길이 위반으로 처리(`@NotBlank` 미사용) |

**메시지 문구(확정 — PasswordPolicy 간결형 스타일):**
- 길이 위반: `닉네임은 1~10자여야 합니다.`
- 문자 구성 위반: `닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.`
- 중복(validate 한정): `이미 사용 중인 닉네임입니다.` (`ErrorCode.DUPLICATE_NICKNAME` 문구 그대로)
- 통과: `사용 가능한 닉네임입니다.` (password의 `사용 가능한 비밀번호입니다.` 컨벤션을 그대로 따름)

## validate 2단 파이프라인 (`POST /api/auth/nickname/validate`)
validate는 **정책 검사 → 중복 검사** 두 단계를 순서대로 수행한다. 두 단계는 각각 독립된 판정이며(구현 제약 참고 — 추후 분리 가능하도록 별도 메서드), validate 오케스트레이션이 순서대로 호출한다.

| 단계 | 내용 | DB | 위반 시 |
|---|---|---|---|
| 1. 정책 | 문자 화이트리스트 + 길이(NicknamePolicy 순수 함수) | 미조회 | **즉시 반환**(2단계 미실행), 정책 위반 메시지 |
| 2. 중복 | `existsByNickname(nickname)` | 조회 | valid:false + 중복 메시지. **1을 통과했을 때만 수행** |

동작 규칙:
- **1 위반**: 정책 메시지(길이 또는 문자), `valid:false`, 2단계 실행 안 함.
- **1 통과 & 2 위반**: `이미 사용 중인 닉네임입니다.`, `valid:false`.
- **둘 다 통과**: `사용 가능한 닉네임입니다.`, `valid:true`.
- 응답 shape은 `valid` + `message`만(reason 코드 필드 없음, PasswordPolicy 미러). **세 실패 종류는 메시지 문구로만 구분**한다.
- **HTTP는 세 경우 모두 200.** 정책 위반도 중복도 "검사가 정상 수행된 결과"이므로 409가 아니다. 반면 **signup의 중복은 기존대로 409 `DUPLICATE_NICKNAME`을 유지**한다 — validate(사전검사)와 signup의 중복 상태코드가 다른 것은 의도된 설계다.

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-NICK-1 | 유비쿼터스 | THE 시스템 SHALL 닉네임 정책(허용 문자·길이·메시지)을 `NicknamePolicy` 한 곳에서만 정의하고, 회원가입 검증과 사전 검사 API가 그 판정을 공유한다 | 같은 닉네임 X에 대해 `POST /api/auth/nickname/validate`가 `valid:false`+메시지 M을 내면, 같은 X로 `POST /api/auth/signup` 시에도 400 응답의 `data.nickname`이 정확히 M이다(문자 그대로 동일) |
| USER-NICK-2 | 유비쿼터스 | THE 시스템 SHALL 사전 검사 API를 인증 없이 접근 가능하게 한다 | `POST /api/auth/nickname/validate`를 `Authorization` 헤더 없이 호출 → 200(401 아님). `/api/auth/**`가 이미 `permitAll`이라 `SecurityConfig` 수정 불필요 |
| USER-NICK-3 | 이벤트 | WHEN 사전 검사 API가 정책을 통과하고 아직 점유되지 않은 닉네임을 받으면, THE 시스템 SHALL 200과 `{valid:true, message:"사용 가능한 닉네임입니다."}`를 반환한다 | `POST /api/auth/nickname/validate` `{"nickname":"길동gil9"}`(미점유) → 200, `{"success":true,"data":{"valid":true,"message":"사용 가능한 닉네임입니다."},"message":null}` |
| USER-NICK-4 | 이벤트 | WHEN 회원가입 요청의 nickname이 정책을 만족하면, THE 시스템 SHALL nickname 사유로 400을 반환하지 않는다 | `POST /api/auth/signup`에 정책 만족 nickname + 나머지 유효 값 → 400 응답의 `data`에 `nickname` 키가 없다(중복이 아니면 201) |
| USER-NICK-5 | 예외 | IF 사전 검사 API에 정책 위반 닉네임이 들어오면, THEN THE 시스템 SHALL 200과 `valid:false` 및 위반 메시지 1개를 반환한다(위반이어도 400이 아님) | `POST /api/auth/nickname/validate` `{"nickname":"hi!"}` → 200, `{"success":true,"data":{"valid":false,"message":"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."},"message":null}` |
| USER-NICK-6 | 예외 | IF 회원가입 요청의 nickname이 정책을 위반하면, THEN THE 시스템 SHALL 400과 위반 메시지를 `data.nickname`에 담아 반환한다 | `POST /api/auth/signup` (nickname `"a b"`, 나머지 유효) → 400, `{"success":false,"data":{"nickname":"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."},"message":"입력값이 올바르지 않습니다."}` |
| USER-NICK-7 | 예외 | IF 닉네임 길이가 1자 미만 또는 10자 초과이면, THEN THE 시스템 SHALL 길이 위반 메시지 `"닉네임은 1~10자여야 합니다."`를 반환한다 | `{"nickname":"가나다라마바사아자차카"}`(11자) → `valid:false`, `message`=`"닉네임은 1~10자여야 합니다."`. 경계: 10자 통과, 1자 통과 |
| USER-NICK-8 | 예외 | IF 닉네임에 허용 문자(한글 완성형·낱자, 영문, 숫자) 외의 문자가 하나라도 포함되면, THEN THE 시스템 SHALL 문자 구성 위반 메시지 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."`를 반환한다 | `{"nickname":"굿🎉"}` → `valid:false`, `message`=문자 구성 메시지. `{"nickname":"ㄱㅏ힣aZ9"}`(한글 낱자+완성형+영문+숫자) → `valid:true` |
| USER-NICK-9 | 예외 | IF 닉네임이 길이와 문자 구성을 동시에 위반하면, THEN THE 시스템 SHALL 길이 위반 메시지 1개만 반환한다(길이 우선) | `{"nickname":"!@#$%^&*()!"}`(11자, 특수문자) → `valid:false`, `message`=`"닉네임은 1~10자여야 합니다."`(문자 구성 메시지 아님). 위반이 정확히 1개만 뜬다 |
| USER-NICK-10 | 예외 | IF 닉네임이 `null`이거나 빈 문자열이면, THEN THE 시스템 SHALL 예외를 던지지 않고 길이 위반으로 처리한다 | `{"nickname":""}` → 200, `valid:false`, `message`=`"닉네임은 1~10자여야 합니다."`. signup에서 nickname 생략/`null` → 400 `data.nickname`=길이 메시지(`@NotBlank`의 `"공백일 수 없습니다"` 류 아님) |
| USER-NICK-11 | 예외 | IF 닉네임이 공백 문자로만 이루어지면, THEN THE 시스템 SHALL 이를 거부한다 | `{"nickname":"   "}`(공백 3칸) → `valid:false`. 공백은 화이트리스트 밖이라 **문자 구성 위반**으로 걸린다(길이는 통과). `{"nickname":" 홍길동"}`(선행 공백) → `valid:false`, 문자 구성 메시지 |
| USER-NICK-12 | 예외 | IF 사전 검사 API가 정책은 통과하나 이미 사용 중인 닉네임을 받으면, THEN THE 시스템 SHALL 200과 `valid:false` 및 `"이미 사용 중인 닉네임입니다."`를 반환한다 | 기존 가입/탈퇴 계정이 점유한 닉네임으로 `POST /api/auth/nickname/validate` → 200, `{"success":true,"data":{"valid":false,"message":"이미 사용 중인 닉네임입니다."},"message":null}` |
| USER-NICK-13 | 예외 | IF 사전 검사 요청 닉네임이 정책(길이·문자)을 위반하면, THEN THE 시스템 SHALL 중복(DB) 검사를 수행하지 않고 정책 위반 메시지를 반환한다 | 정책 위반 닉네임(예 `"hi!"`)으로 validate → 응답 `message`가 정책 메시지(문자/길이)이며 `"이미 사용 중인 닉네임입니다."`가 아니다. 판정 우선순위: 길이 → 문자 → 중복 |
| USER-NICK-14 | 유비쿼터스 | THE 시스템 SHALL validate의 중복 판정에 signup과 동일한 `existsByNickname`을 사용한다 | 탈퇴 계정이 점유한 닉네임으로 validate → `valid:false`+중복 메시지, 같은 닉네임으로 `POST /api/auth/signup` → 409 `DUPLICATE_NICKNAME`. `existsByNickname`이 `exit_at`을 거르지 않아 두 경로 모두 탈퇴 닉네임을 점유로 판정(재가입 불가 현행 동작과 일치) |
| USER-NICK-15 | 유비쿼터스 | THE 시스템 SHALL validate가 중복 닉네임에도 HTTP 200을 반환한다(signup의 중복은 409 유지) | 중복 닉네임으로 validate → 200(409 아님), 같은 닉네임으로 signup → 409 `DUPLICATE_NICKNAME`. 두 엔드포인트의 중복 상태코드 차이는 의도됨 |

### 메시지 결정성에 관한 주의 (모듈 컨텍스트 반영)
- USER-NICK-9가 성립하려면 위반이 **항상 정확히 1개**여야 한다. 모듈 컨텍스트(`.claude/modules/user.md`, `SignupRequest` 주의)에 따라 `SignupRequest.nickname`에는 새 제약 **하나만** 걸고 `@NotBlank`·`@Size`·`@Pattern`을 겹쳐 걸지 않는다. 겹치면 동시 위반 시 `GlobalExceptionHandler`의 `Map#put` 순서 비보장으로 응답 메시지가 호출마다 달라진다(password가 이미 겪은 문제).
- 위 표의 400 응답 형태(`data`에 위반 필드만, `message:"입력값이 올바르지 않습니다."`)와 validate의 항상-200 계약은 기존 `docs/api/user.md`의 signup/password-validate 계약과 동일하다.
- **signup에서도 정책(400) → 중복(409) 순서가 이미 성립한다.** `@Valid` 정책 검증이 `AuthService`의 중복 검사보다 먼저 실행되므로, 정책 위반이면서 중복이기도 한 닉네임은 400(정책 위반)으로 응답된다. 즉 이번 변경의 실질은 **validate 엔드포인트에 signup과 같은 2단 순서를 도입하는 것**이고, signup의 흐름은 바뀌지 않는다.

## 구현 제약 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)
1. **정책 검사와 중복 검사는 각각 별도 메서드로 분리한다.** 사용자 요구: "추후 분리 가능하도록 메서드를 각각 생성". 정책 판정 메서드(순수·DB 미조회)와 중복 판정 메서드(`existsByNickname` 위임)를 독립적으로 두고, validate 오케스트레이션이 순서대로 호출한다. 클래스/메서드명은 `spring-dev` 판단.
2. **validate는 DB를 보므로 순수 static 컨트롤러 호출이 아니라 서비스 계층(리포지토리 주입)을 거친다.** password validate가 `PasswordPolicy`를 컨트롤러에서 직접 static 호출하던 것과 달라지는 지점 — 미러링은 "정책 단일 출처·항상 200·shape(valid+message)"까지이고, DB 접근이 붙는 중복 단계는 서비스로 내려간다.
3. **중복 판정은 `userAccountRepository.existsByNickname(nickname)` 재사용.** signup과 문자 그대로 같은 메서드를 써서 두 경로의 중복 판정이 어긋나지 않게 한다. 이 메서드는 `exit_at`을 거르지 않아 탈퇴 닉네임도 점유로 잡는다(재가입 불가 현행 동작과 일치, USER-NICK-14).

## 미해결 질문
없음 — 2건 전부 해소됨(2026-07-18 사용자 확정, validate 설계는 이후 2단 파이프라인으로 개정).

## 결정 근거 (해소된 질문 — 조사를 반복하지 않기 위해)
1. **위반 메시지 문구 = PasswordPolicy 간결형으로 확정.** 길이 `"닉네임은 1~10자여야 합니다."`, 문자 구성 `"닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."`, 통과 `"사용 가능한 닉네임입니다."`. "특수문자·공백·이모지는 쓸 수 없습니다"처럼 금지 대상을 나열하는 안은 문구가 길고 password 스타일과 어긋나 채택하지 않음.
2. **사전 검사 API는 정책 검사 → 중복 검사 2단 파이프라인으로 확정(2026-07-18 개정, 이전 "정책만" 결정을 뒤집음).** password는 중복 개념이 없어 정책만 봤지만, 닉네임은 중복이 실제 가입 실패 사유라 사전검사가 중복까지 확인하는 편이 UX상 낫다는 판단. 정책 단계는 여전히 순수·DB 미조회이고, 중복 단계는 정책 통과 시에만 `existsByNickname`으로 조회한다. 응답 shape·항상-200 계약·정책 단일 출처는 password 미러 그대로 유지.
   - **validate 통과가 가입 성공을 보장하는가**: 정상적으로는 그렇다(정책·중복 둘 다 확인하므로). 단 validate와 signup 사이의 **레이스**로 그 틈에 같은 닉네임이 선점되면 signup에서 409가 날 수 있다 — 이는 모든 사전검사(check-then-act)의 본질적 한계이며 signup의 `existsByNickname` 최종 판정이 유일한 원자적 관문이다.
