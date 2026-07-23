# 이메일 인증 요구사항
> 상태: 승인됨 · 모듈: user · 최종 수정: 2026-07-18

## 배경 / 목적
회원가입 시 사용자가 입력한 이메일이 **실제 본인 소유인지** 확인할 방법이 없다. 지금 signup은 `@Email` 형식 검사와 `existsByEmail` 중복 검사만 하므로, 남의 이메일이나 존재하지 않는 이메일로도 가입이 가능하다. 이 기능은 **발송(인증번호를 이메일로 보냄) → 검증(이메일+인증번호 대조)** 2단계로 소유를 확인하는 두 API를 추가하고, **검증에 성공한 이메일만 가입**을 허용하도록 signup을 연동한다.

이 문서가 확정하는 것은 **두 인증 API의 동작 계약 + signup 연동 계약**이다.

## 범위
- 포함:
  - 인증번호 **발송 API** — 이메일을 받아 인증번호를 생성·저장하고 해당 주소로 발송
  - 인증번호 **검증 API** — 이메일+인증번호를 받아 저장값과 대조하고 성공 시 "인증 완료" 상태를 저장
  - 인증번호 형식·유효시간(TTL)·재발송 쿨다운·시도 횟수 제한 정책
  - **signup 연동** — 인증 완료 상태가 아닌 이메일의 가입을 서버가 차단(기존 signup 계약 개정)
- 제외:
  - **로그인·비밀번호 재설정용 이메일 인증** — 목적이 회원가입 소유 검증으로 한정됨. 정책·저장소는 재사용 가능하게 두되 엔드포인트는 추가하지 않는다.
  - **실제 메일 템플릿(HTML/문구) 디자인** — 발송이 일어난다는 계약까지가 이 문서의 범위. 본문 문구는 구현 재량.
  - **인증 완료 상태를 signup 요청 본문의 토큰으로 전달하는 방식** — 서버가 Redis에 상태를 저장하고 signup이 이를 조회하는 방식으로 확정됐다(미해결 질문 6 답변 B). 단기 인증 토큰 발급(옵션 C)은 채택하지 않는다.

## 정책 요약 (확정)
| 항목 | 확정값 | 근거 |
|---|---|---|
| 인증번호 형식 | **6자리 숫자**(`000000`~`999999`) | 입력 편의 + 무차별 대입은 시도 제한으로 방어 |
| 유효시간(코드 TTL) | **발송 시점부터 5분** | 메일 지연 여유와 탈취 창 최소화의 균형 |
| 재발송 쿨다운 | **직전 발송으로부터 60초**, 재발송 시 이전 코드 무효화 | 스팸·발송비 방어 |
| 검증 시도 제한 | **인증번호 1건당 5회** 초과 시 차단 | 무차별 대입 방어 |
| 인증번호 저장소 | **Redis** — 인증번호·시도횟수·쿨다운·인증완료 상태 전부 TTL 기반 저장 | TTL/쿨다운/시도수/인증완료 만료를 자연스럽게 관리 |
| 인증 완료 상태 TTL | **검증 성공 시점부터 30분** | 인증 후 가입까지의 현실적 소요 시간 여유 |
| 발송 수단 | **SMTP**(`spring-boot-starter-mail`) 운영 실발송 + dev/test 프로파일은 로그/mock 대체 | 외부 서비스 계정 없이 도입, dev는 실발송 없이 코드 확인 |
| 중복 이메일 발송 검사 | **이미 가입된 이메일이면 send-code에서 409** | 사용자가 계정 비노출보다 **중복가입 사전차단**을 우선하기로 결정 |

**엔드포인트 (확정, 기존 `/api/auth/*` 컨벤션 따름):**
- 발송: `POST /api/auth/email/send-code`
- 검증: `POST /api/auth/email/verify`

아래 인수 기준은 이 경로를 전제로 쓰였다. 클래스 배치·라이브러리 세부는 `spring-dev` 재량.

## 요구사항 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-EMV-1 | 이벤트 | WHEN 형식이 유효하고 미가입인 이메일로 인증번호 발송을 요청하면, THE 시스템 SHALL 그 이메일 주소로 인증번호를 발송하고 200을 반환한다 | `POST /api/auth/email/send-code` `{"email":"user@example.com"}`(미가입) → 200. 해당 주소로 인증번호가 담긴 메일 발송(운영 SMTP, dev/test는 로그/mock) |
| USER-EMV-2 | 유비쿼터스 | THE 시스템 SHALL 인증번호를 6자리 숫자로 생성한다 | 발송된 인증번호가 `^\d{6}$` 를 만족한다 |
| USER-EMV-3 | 유비쿼터스 | THE 시스템 SHALL 발송한 인증번호를 발송 시점부터 5분 동안만 유효한 값으로 Redis에 저장한다 | 발송 후 5분 이내 검증은 유효, 5분 경과 후 검증은 USER-EMV-11 만료 실패로 떨어진다(Redis TTL 5분) |
| USER-EMV-4 | 예외 | IF 발송 요청의 이메일이 빈값이거나 `@Email` 형식을 위반하면, THEN THE 시스템 SHALL 400과 검증 메시지를 반환한다 | `POST /api/auth/email/send-code` `{"email":"not-an-email"}` → 400, `{"success":false,"data":{"email":"<메시지>"},"message":"입력값이 올바르지 않습니다."}` (Bean Validation 형식, 기존 signup과 동일) |
| USER-EMV-5 | 예외 | IF 직전 발송으로부터 쿨다운(60초) 이내에 같은 이메일로 재발송을 요청하면, THEN THE 시스템 SHALL 발송하지 않고 429와 재시도 안내 메시지를 반환한다 | 같은 이메일로 60초 내 2회 `send-code` → 2번째 응답 429, `message`에 "잠시 후 다시 시도" 취지. 신규 메일 미발송 |
| USER-EMV-6 | 이벤트 | WHEN 쿨다운이 지난 뒤 같은 이메일로 재발송을 요청하면, THE 시스템 SHALL 이전 인증번호를 무효화하고 새 인증번호를 발송한다 | 발송 → 쿨다운 경과 후 재발송 → 이전 인증번호로 검증 시 실패(무효화됨), 새 인증번호로 검증 시 성공 |
| USER-EMV-7 | 유비쿼터스 | THE 시스템 SHALL 발송·검증 두 API를 인증 없이 접근 가능하게 한다 | 두 엔드포인트를 `Authorization` 헤더 없이 호출 → 401이 아님(`/api/auth/**`가 이미 `permitAll`이라 `SecurityConfig` 수정 불필요) |
| USER-EMV-8 | 이벤트 | WHEN 이메일과 유효기간 내 올바른 인증번호로 검증을 요청하면, THE 시스템 SHALL 200과 성공 결과를 반환한다 | `POST /api/auth/email/verify` `{"email":"user@example.com","code":"<발송된 코드>"}`(TTL 내) → 200, `{"success":true,...}` |
| USER-EMV-9 | 이벤트 | WHEN 인증번호 검증에 성공하면, THE 시스템 SHALL 그 인증번호를 즉시 무효화한다(1회용) | 같은 이메일+코드로 검증을 2회 호출 → 1번째 성공, 2번째는 만료/미발송 실패(USER-EMV-11) |
| USER-EMV-10 | 예외 | IF 검증 요청의 인증번호가 저장된 값과 불일치하면, THEN THE 시스템 SHALL 400과 불일치 실패를 반환한다 | `{"email":"user@example.com","code":"999999"}`(틀린 코드) → 400, `{"success":false,"data":null,"message":"<인증번호 불일치 메시지>"}`(BusinessException). 인증 완료 상태 저장 안 됨 |
| USER-EMV-11 | 예외 | IF 검증 요청의 이메일에 유효한(만료 전·미소진) 인증번호가 없으면(TTL 경과·발송 이력 없음·이미 사용됨), THEN THE 시스템 SHALL 400과 만료/무효 실패를 반환한다 | 발송 이력 없는 이메일 또는 5분 경과 후 `verify` → 400, `message`에 "만료되었거나 유효하지 않은 인증번호" 취지(BusinessException). 미발송과 만료를 동일 응답으로(발송 이력 비노출) |
| USER-EMV-12 | 예외 | IF 같은 인증번호에 대한 검증 실패가 5회를 초과하면, THEN THE 시스템 SHALL 이후 시도를 차단하고 인증번호를 무효화하며 재발송을 요구한다 | 틀린 코드로 5회 실패 후 6번째 시도(정답이어도) → 400 차단 응답(BusinessException), 재발송 없이는 성공 불가. Redis 시도횟수 카운터로 판정 |
| USER-EMV-13 | 예외 | IF 검증 요청의 email 또는 code가 빈값/형식 위반이면, THEN THE 시스템 SHALL 400과 검증 메시지를 반환한다 | `POST /api/auth/email/verify` `{"email":"","code":""}` → 400, Bean Validation 형식(`data`에 위반 필드만) |
| USER-EMV-14 | 예외 | IF 발송 대상 이메일이 이미 가입된 계정이면, THEN THE 시스템 SHALL 인증번호를 발송하지 않고 409와 중복 안내를 반환한다 | 이미 가입된 이메일로 `send-code` → 409, `{"success":false,"data":null,"message":"<중복 이메일 메시지>"}`(`DUPLICATE_EMAIL`). 신규 메일 미발송. **사용자 결정: 계정 비노출보다 중복가입 사전차단 우선** |
| USER-EMV-15 | 이벤트 | WHEN 인증번호 검증에 성공하면, THE 시스템 SHALL 해당 이메일의 "인증 완료" 상태를 Redis에 30분 TTL로 저장한다 | 검증 성공 후 30분 이내 signup은 통과(USER-EMV-16 미발동), 30분 경과 후 signup은 USER-EMV-17로 거부 |
| USER-EMV-16 | 예외 | IF signup 요청의 이메일이 인증 완료 상태(USER-EMV-15)가 아니면, THEN THE 시스템 SHALL 가입을 거부하고 오류를 반환한다 | `POST /api/auth/signup` — 인증 완료 상태 없는 이메일 → 가입 거부, `{"success":false,"data":null,"message":"<이메일 미인증 메시지>"}`(신규 ErrorCode `EMAIL_NOT_VERIFIED`) |
| USER-EMV-17 | 예외 | IF signup 요청의 이메일 인증 완료 상태가 만료(30분 경과)됐으면, THEN THE 시스템 SHALL 가입을 거부하고 재인증을 요구한다 | 검증 성공 후 30분 경과한 이메일로 `signup` → USER-EMV-16과 동일 응답(미인증과 만료를 동일 처리, Redis 키 부재로 흡수) |
| USER-EMV-18 | 이벤트 | WHEN 인증 완료 이메일로 signup이 성공하면, THE 시스템 SHALL 해당 이메일의 인증 완료 상태를 제거한다 | 인증 완료 이메일로 `signup` 성공(201) → 같은 이메일로 재차 signup 시도 시 인증 완료 상태 부재로 USER-EMV-16 거부(1회용, 재가입 불가 정책과도 정합) |

## signup 연동 (기존 signup 계약 개정)
이 기능으로 **기존 signup 요구사항이 개정된다.** 종전 signup은 형식 검증 + `existsBy*` 중복 검사만 통과하면 가입됐으나, 이제 **이메일 인증 완료(USER-EMV-15) 상태가 선행 조건**이 된다.
- 개정 전: `POST /api/auth/signup` → 형식·중복 통과 시 201.
- 개정 후: `POST /api/auth/signup` → **이메일이 Redis 인증 완료 상태일 때만** 형식·중복 검사로 진행. 미인증/만료면 USER-EMV-16/17로 거부(신규 `EMAIL_NOT_VERIFIED`). 가입 성공 시 인증 완료 상태 소비·제거(USER-EMV-18).
- 검사 순서(제약): 형식(Bean Validation, 400) → **이메일 인증 완료 여부(USER-EMV-16, `EMAIL_NOT_VERIFIED`)** → 중복(`existsBy*`, 409). 인증 완료 검사가 중복 검사보다 먼저인지 여부는 구현 재량이나, 세 단계가 모두 통과해야 가입된다. `SignupRequest` 본문 스키마는 그대로다(인증 상태는 서버 Redis 조회, 요청 필드 추가 없음).
- 이 개정은 `docs/api/user.md`의 signup 명세에도 반영돼야 한다(`api-documenter` 소관).

## 구현 제약 (구현이 지켜야 할 사실 — 구현 방법 지시가 아님)
1. **Redis + spring-data-redis 도입이 전제다 — 현재 모듈 의존성에 없다.** `.claude/modules/user.md` 기준 현재 라이브러리는 Security/JPA/Validation/JJWT/BCrypt/MySQL/dotenv뿐이다. 인증번호·시도횟수·쿨다운·인증완료 상태를 전부 TTL 기반으로 보관할 **Redis**가 도입돼야 하고, `spring-data-redis` 의존성 추가와 `docker-compose`에 Redis 서비스 추가가 전제된다. 이는 확정 결정이 강제하는 **제약**이다(연결·직렬화 세부는 `spring-dev`).
2. **메일 발송은 SMTP + dev mock 전제.** 운영 프로파일은 `spring-boot-starter-mail`(SMTP) 실발송, 개발/테스트 프로파일은 실제 발송 없이 로그/mock으로 대체한다. dev에서 실메일을 보내지 않는 것이 확정 사항이다(발송 계약 검증은 mock 경계에서). SMTP 접속 정보는 환경변수로 주입(기존 `DB_*` 컨벤션과 동일 방식).
3. **새 ErrorCode가 필요하다(`:common`).** 현재 `ErrorCode` enum에는 이메일 인증용 코드가 없다(`DUPLICATE_*`, `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `INVALID/EXPIRED_REFRESH_TOKEN`뿐). 쿨다운(429, USER-EMV-5)·불일치(400, USER-EMV-10)·만료무효(400, USER-EMV-11)·시도초과(400, USER-EMV-12)·**이메일 미인증(USER-EMV-16/17, `EMAIL_NOT_VERIFIED`)** 전용 코드 추가가 전제된다. 중복 이메일 발송(USER-EMV-14, 409)은 기존 `DUPLICATE_EMAIL`을 재사용할 수 있다. 429는 `status`가 int라 값 자체는 문제없으나 모듈에 **429 응답 선례가 아직 없다**(현재 400/401/409/204만).
4. **에러 응답 포맷은 기존 모듈 계약을 따른다.** Bean Validation 실패(USER-EMV-4/13)는 `{"success":false,"data":{"필드":"메시지"},"message":"입력값이 올바르지 않습니다."}`(400), `BusinessException`(USER-EMV-5/10/11/12/14/16/17)은 `{"success":false,"data":null,"message":"<ErrorCode 메시지>"}` + `ErrorCode.getStatus()`. `GlobalExceptionHandler`가 그대로 처리한다.
5. **DTO는 record.** 이 모듈의 요청/응답 DTO는 전부 record 컨벤션(`SignupRequest`, `LoginRequest`, `PasswordValidationRequest` 등). 새 발송/검증 요청 DTO도 동일하게.
6. **인증번호 대조는 형식 검증과 분리한다.** email/code의 `@NotBlank`/형식(400, Bean Validation)과 저장값 대조(BusinessException, USER-EMV-10/11)는 별개 단계다 — 형식 통과 후에만 대조를 수행한다.
7. **`/api/auth/**`는 이미 `permitAll`이라 `SecurityConfig` 수정 불필요**(USER-EMV-7). signup 연동도 같은 컨트롤러(`AuthController`)·같은 경로 prefix라 보안 규칙 변경 없음.

## 미해결 질문
미해결 없음 — 확정 결정으로 모든 항목이 해소됨.

## 기존 정책과의 충돌
- **계정 존재 비노출 철학과의 의도적 예외(USER-EMV-14).** 모듈은 login(`INVALID_CREDENTIALS`로 미가입/탈퇴/오답 통합)·validate에서 계정·가입 이력을 노출하지 않는다. send-code에서 이미 가입된 이메일에 409 `DUPLICATE_EMAIL`을 주면 **가입 이력이 노출**돼 이 원칙과 어긋난다. **사용자가 계정 비노출보다 중복가입 사전차단을 우선하기로 명시적으로 결정**했으므로, 이 지점은 비노출 원칙의 **의도적 예외**로 기록한다(signup은 종전대로 이 예외를 따르지 않고 `existsBy*` 중복 시 409를 이미 반환하므로, send-code 409는 signup의 기존 노출 수준과 정합).
- **stateless 아키텍처와 Redis.** 모듈은 `SecurityConfig`가 stateless + JWT다. 인증번호·인증완료 상태를 HTTP 세션이 아니라 **Redis에 애플리케이션 상태로** 보관하므로 stateless 요청 처리와 상충하지 않는다(세션 저장소를 도입하지 않음).
- 그 외 signup의 이메일 형식 제약(`@NotBlank @Email @Size(max=100)`)·에러 응답 포맷과는 정합(USER-EMV-4/13이 그대로 재사용). signup 흐름 자체는 USER-EMV-16~18로 개정되며, 이는 "signup 연동" 절에 명시.
