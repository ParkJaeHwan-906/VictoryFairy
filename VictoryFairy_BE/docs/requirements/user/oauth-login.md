# 소셜 로그인(OAuth) · 자동 계정 통합 요구사항
> 상태: **승인됨 (2026-08-20)** · 모듈: user (+ domain) · 최종 수정: **2026-08-21** (3차 개정 — 구현이 드러낸 문서-실제 불일치 3건 정정 + 구현자 재량 2건 승격. **요구사항 2건 추가**: USER-OAU-100(인증번호 만료)·101(입력 티켓 이메일 누락). **`ErrorCode` 신규는 6종 → 7종** — 한때 지웠던 `OAUTH_EMAIL_REQUIRED`를 되살렸다, 제약 21)
> **1차 개정(같은 날) 요약**: ①미검증 이메일을 일괄 거절하지 않고 기존 계정과 합쳐지는 갈래에서만 코드 인증을 태운다(USER-OAU-68~80) + **선점 방지**(USER-OAU-73). ②소셜 가입에 `name`·`tel`·`gender`를 받지 않고 컬럼을 nullable로 완화(USER-OAU-86~89). ③`redirectUri`를 본문으로 받되 허용 목록과 완전 일치 대조(USER-OAU-63~66).
> **삭제된 요구사항 3건**: USER-OAU-23·24·39 (번호는 재사용하지 않는다).

## 배경 / 목적
요청의 핵심은 "소셜 로그인 3개 추가"가 아니라 **한 사람 = 한 계정으로 수렴시키는 자동 통합(auto-linking)** 이다. 그래서 이 기능의 무게는 토큰 교환이 아니라 **신원 해석(identity resolution)** — "지금 온 이 소셜 계정이 기존의 누구인가"를 무엇을 근거로 판정하느냐 — 에 실린다.

이 판정이 틀리면 결과가 로그인 실패가 아니라 **계정 탈취**다. 자동 통합의 유일한 현실적 후보 키가 이메일인데, provider가 주는 이메일은 **검증 여부가 서로 다르고, 애초에 안 주기도 한다.**

이 문서가 다루는 세 가지 결핍이 그래서 전부 이메일에 관한 것이다.

1. **이메일이 아예 안 오는 경우** — 카카오의 `account_email` 동의항목은 **비즈 앱 권한**이라, 일반 앱에서는 필수/선택 이전에 **동의항목 자체를 쓸 수 없다.** 2026-08-20 기준 우리는 비즈 앱이 아니고 전환도 하지 않기로 했다. 즉 **카카오에서 이메일이 오지 않는 것이 오류가 아니라 정상 경로**다. 네이버도 이메일이 선택 동의라 사용자가 거부할 수 있다. 이때는 **사용자에게 이메일을 입력받아 우리 인증번호로 소유를 확인**하고, 그 이메일을 신원 해석의 키로 쓴다(USER-OAU-90~99).
2. **이메일은 왔지만 미검증인 경우** — 아무 조건 없이 통합하면 공격자가 남의 이메일을 등록한 소셜 계정 하나로 그 사람의 계정에 그대로 들어온다. 다만 **전부 거절하는 것은 과잉**이다. 위험은 "이미 존재하는 계정에 붙는" 갈래에서만 성립하고, 그 이메일로 만들어진 계정이 아직 없다면 빼앗을 것이 없다. 그래서 **위험한 갈래에만 인증을 요구한다**(USER-OAU-68~80).
3. **선점** — 2번의 완화가 열어 놓는 구멍이며 USER-OAU-73이 그것을 닫는다. 공격자가 피해자의 이메일을 단 미검증 소셜 계정으로 **먼저** 가입한다(빼앗을 계정이 없었으므로 통과한다). 나중에 진짜 주인이 검증된 신원으로 들어와 이메일 코드를 통과하고 그 계정에 합류한다. 이때 아무 조치도 하지 않으면 **공격자의 연동이 그 계정에 그대로 남아 주인이 합류한 뒤에도 계속 로그인된다.** 그래서 "미검증이던 계정이 코드 인증으로 승격되는 순간, 그 계정에 이미 붙어 있던 연동을 전부 해제한다"가 필요하다.

세 갈래는 **provider 종류로 갈리지 않는다.** 판정 기준은 언제나 "이번 응답에 쓸 수 있는 이메일이 있는가 / 그 이메일이 검증됐는가"이며(USER-OAU-91), 그래서 카카오 비즈 앱 전환이 나중에 승인되면 **요구사항도 코드도 고치지 않은 채 이메일 입력 단계가 저절로 생략된다.**

두 번째 쟁점이던 스키마 충돌(`users.name`·`tel`·`gender`가 NOT NULL인데 소셜 3사가 이 셋을 안 준다)은 **컬럼을 nullable로 완화하는 것**으로 확정됐다. 근거는 실측이다: `tel`의 실사용은 가입 시 `existsByTel` 중복 검사와 시스템 계정 채움뿐이고 `/me` 응답은 `tel`을 일부러 제외한다(테스트가 `$.data.tel` 부재로 고정). `gender`도 가입 요청에서 저장까지가 전부다. **아무도 읽지 않는 값을 위해 소셜 가입에 폼을 강제하는 것**은 마찰 제거라는 목적과 정반대라는 판단이다.

## 용어
- **연동 행** — (provider, provider 사용자 식별자) 한 쌍을 계정에 묶은 행
- **가입 티켓** — 신규 계정을 만들 자격을 담은 1회용 티켓(provider·식별자·이메일·이메일 검증 상태)
- **이메일 인증 티켓** — 인증번호 절차를 태울 자격을 담은 1회용 티켓. 두 종류다.
  - **링크 티켓** — provider가 **이메일을 준** 경우(USER-OAU-68). 이메일이 티켓 안에 이미 있다
  - **입력 티켓** — provider가 **이메일을 주지 않은** 경우(USER-OAU-90). 이메일이 비어 있고 사용자가 채운다
  - 둘을 가르는 이유는 **본문 이메일을 받아도 되는가**가 정반대이기 때문이다. 입력 티켓은 본문 이메일이 필수고, 링크 티켓이 본문 이메일을 받아들이면 **남의 계정 이메일로 코드를 보내는 우회로**가 열려 USER-OAU-69의 보호가 무너진다. 티켓 종류는 늘었지만 **엔드포인트는 늘리지 않는다**(두 종류가 같은 `/link/send-code`·`/link/verify`를 쓴다)

## 범위
- 포함
  - 소셜 인증 엔드포인트 1개(`kakao`·`naver`·`google`) + 이메일 인증 2개 + 소셜 가입 완료 1개 — **총 4개**
  - 신원 해석 규칙과 자동 통합(연동 행 생성), 이메일 미제공 시 사용자 입력 + 코드 인증, 미검증 갈래의 코드 인증, 선점 연동 해제
  - 계정별 이메일 검증 상태 저장(`users.email_verified`)과 기존 행 백필
  - `users.name`·`tel`·`gender` nullable 완화(자체 가입 경로는 애플리케이션 검증으로 필수 유지)
  - 연동 정보를 담는 신규 엔티티(`:domain`) + UNIQUE 제약 2종
  - 소셜 전용 계정의 자체 로그인·비밀번호 변경 경로 응답
  - 탈퇴 계정·만료 데이터 정리 배치와의 상호작용
  - 신규 `ErrorCode`
- 제외
  - **카카오 비즈 앱 전환** — 이번 범위 밖의 별도 진행이며, 전환되면 이메일 입력 단계가 **요구사항 변경 없이** 생략된다(USER-OAU-91)
  - **연동 목록 조회·연동 해제(unlink) API** — `GET /api/users/me` 응답도 바뀌지 않는다(USER-OAU-62). ⚠ USER-OAU-73의 연동 해제는 **시스템이 수행하는 내부 동작**이며 사용자에게 열어 주는 기능이 아니다
  - **소셜 전용 계정의 비밀번호 설정·비밀번호 찾기** — 저장소에 비밀번호 찾기 기능 자체가 아직 없다
  - **provider 프로필 이미지·닉네임 가져오기** — 닉네임은 사용자가 직접 입력한다(USER-OAU-37)
  - **provider access·refresh 토큰 보관과 그 토큰으로 하는 후속 API 호출** — provider 토큰은 신원 확인 1회용이다
  - **Apple 로그인** — 요청된 3사만
  - **provider 앱 등록·리다이렉트 URI 화이트리스트 등록·클라이언트 시크릿 주입** — 아래 "선행 조건"
  - **소셜 세션과 자체 로그인 세션의 구분 표시** — 발급된 토큰은 발급 경로를 구분하지 않는다(USER-OAU-48)
  - **선점당한 이메일의 소유자가 자체 회원가입으로 계정을 되찾는 경로** — 되찾는 길은 소셜 인증 + 이메일 코드뿐이다(제약 3)

## 선행 조건 (이 문서의 범위 밖 — infra/운영 소관)
1. 카카오·네이버·구글 각각의 애플리케이션 등록과 **클라이언트 ID/시크릿** 환경변수 주입. 설정되지 않은 provider는 지원 목록에서 빠진다(USER-OAU-4)
2. **provider 콘솔의 리다이렉트 URI 화이트리스트**에 웹 주소와 앱 커스텀 스킴을 모두 등록. 서버의 허용 목록(USER-OAU-63)은 이 목록과 같은 값이어야 한다 — 한쪽만 늘리면 provider가 인가코드를 안 주거나(콘솔 누락) 서버가 400을 준다(서버 목록 누락)
3. **카카오 이메일은 현재 오지 않는다.** `account_email`이 비즈 앱 권한이고 우리는 비즈 앱이 아니다 — 동의항목을 켤 수 없으므로 필수/선택의 문제가 아니다. 그래서 카카오 로그인은 항상 USER-OAU-90(이메일 입력) 갈래를 탄다. **비즈 앱 전환이 승인되면 이메일이 오기 시작하고, 그 시점부터 입력 단계는 자동으로 생략된다**(USER-OAU-91 — 코드·요구사항 변경 없음).
4. 아래 "배포 전제"의 DDL 3건 — **앱 배포보다 먼저**

## 요구사항 (EARS)

### A. 진입과 응답 형태
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-1 | 이벤트 | WHEN 클라이언트가 지원 provider로 소셜 인증을 요청하면, THE 시스템 SHALL 요청에 실린 인가코드를 그 provider의 토큰 엔드포인트로 교환한다 | `POST /api/auth/oauth/kakao` `{"code":"<인가코드>","redirectUri":"<허용 주소>"}` → provider 토큰 교환 호출 1회(스텁 서버 관측) |
| USER-OAU-2 | 이벤트 | WHEN provider 토큰 교환이 성공하면, THE 시스템 SHALL provider 사용자 정보 조회로 provider 사용자 식별자를 획득한다 | 교환 성공 직후 사용자 정보 API 호출 1회, 응답의 식별자가 이후 판정에 쓰임 |
| USER-OAU-3 | 유비쿼터스 | THE 시스템 SHALL 소셜 인증 경로를 인증 없이 처리한다 | `Authorization` 헤더 없이 `POST /api/auth/oauth/google` → 401이 아님 |
| USER-OAU-4 | 선택 | WHERE provider별 클라이언트 자격증명이 설정된 경우, THE 시스템 SHALL 그 provider를 지원 목록에 포함한다 | google 자격증명을 비운 채 기동 → `POST /api/auth/oauth/google` → 400 `UNSUPPORTED_OAUTH_PROVIDER` |
| USER-OAU-5 | 예외 | IF 경로의 provider가 지원 목록에 없으면, THEN THE 시스템 SHALL 400과 `"지원하지 않는 소셜 로그인입니다."`를 반환한다 | `POST /api/auth/oauth/apple` → 400 `UNSUPPORTED_OAUTH_PROVIDER` |
| USER-OAU-6 | 예외 | IF 요청 본문의 인가코드가 없거나 공백이면, THEN THE 시스템 SHALL 400을 반환한다 | `{"code":"","redirectUri":"<허용 주소>"}` → 400, provider 호출 0회 |
| USER-OAU-7 | 예외 | IF provider가 인가코드를 거절하면, THEN THE 시스템 SHALL 401과 `"소셜 인증에 실패했습니다. 다시 시도해 주세요."`를 반환한다 | 만료·이미 사용된 코드 / provider가 판정한 redirect URI 불일치 → 401 `INVALID_OAUTH_CODE`(세 사유가 같은 응답) |
| USER-OAU-8 | 예외 | IF provider 호출이 타임아웃되거나 5xx로 실패하면, THEN THE 시스템 SHALL 502와 `"소셜 로그인 제공자와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요."`를 반환한다 | provider 스텁을 500으로 응답시킴 → 502 `OAUTH_PROVIDER_UNAVAILABLE`, 계정 행·연동 행 생성 0건 |
| USER-OAU-9 | 유비쿼터스 | THE 시스템 SHALL provider access·refresh 토큰을 저장하지 않는다 | 인증 성공 후 DB 어느 테이블·Redis 어느 키에도 provider 토큰 문자열이 없음 |
| USER-OAU-10 | 유비쿼터스 | THE 시스템 SHALL 소셜 인증 응답을 `status`/`accessToken`/`refreshToken`/`ticket`/`email` 다섯 키로 구성한다 | `status`는 `LOGIN`·`SIGNUP_REQUIRED`·`EMAIL_VERIFICATION_REQUIRED`·`EMAIL_INPUT_REQUIRED` 넷 중 하나. provider 토큰은 어느 키에도 없음 |
| USER-OAU-11 | 이벤트 | WHEN 소셜 인증이 활성 계정으로 해석되고 추가 확인이 필요 없으면, THE 시스템 SHALL 200과 `status:"LOGIN"` + 이 서비스의 토큰 쌍을 반환한다 | 200, `{"status":"LOGIN","accessToken":"...","refreshToken":"...","ticket":null}` |
| USER-OAU-12 | 이벤트 | WHEN 쓸 수 있는 이메일로 해석되는 계정이 없으면, THE 시스템 SHALL 200과 `status:"SIGNUP_REQUIRED"` + 가입 티켓을 반환한다 | 200, `{"status":"SIGNUP_REQUIRED","accessToken":null,"refreshToken":null,"ticket":"...","email":"<확정된 이메일>"}` |
| USER-OAU-63 | 유비쿼터스 | THE 시스템 SHALL 요청 본문의 `redirectUri`를 provider별 허용 목록과 **문자 그대로 완전 일치**로 대조한다 | 허용 목록이 `https://victoryfairy.com/oauth/kakao`일 때 `https://victoryfairy.com/oauth/kakao?x=1`·`https://victoryfairy.com.evil.com/oauth/kakao` 둘 다 400(접두 일치로 통과하지 않음) |
| USER-OAU-64 | 예외 | IF `redirectUri`가 허용 목록에 없으면, THEN THE 시스템 SHALL 400과 `"허용되지 않은 리다이렉트 주소입니다."`를 반환한다 | 임의 주소 → 400 `INVALID_OAUTH_REDIRECT_URI`, **provider 호출 0회** |
| USER-OAU-65 | 예외 | IF `redirectUri`가 없거나 공백이면, THEN THE 시스템 SHALL 400과 `"허용되지 않은 리다이렉트 주소입니다."`를 반환한다 | `{"code":"x"}` → 400 `INVALID_OAUTH_REDIRECT_URI`, provider 호출 0회 |
| USER-OAU-66 | 이벤트 | WHEN provider에 토큰 교환을 요청하면, THE 시스템 SHALL 요청 본문으로 받은 `redirectUri`를 그대로 실어 보낸다 | 앱 커스텀 스킴으로 받은 인가코드 → 같은 스킴이 교환 요청에 실림(서버 고정값으로 치환하지 않음) |

### B. 신원 해석과 자동 통합
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-13 | 유비쿼터스 | THE 시스템 SHALL 신원 해석을 ①(provider, provider 사용자 식별자) 일치 ②확정된 이메일과 `users.email` 일치 순서로 판정한다 | 두 조건이 서로 다른 계정을 가리키도록 데이터를 만든 뒤 인증 → ①이 가리키는 계정의 `uid`로 로그인(②는 조회되지 않음) |
| USER-OAU-14 | 이벤트 | WHEN (provider, provider 사용자 식별자)와 일치하는 연동 행이 있으면, THE 시스템 SHALL 그 연동 행의 계정으로 로그인시킨다 | provider 쪽 이메일을 바꾼 뒤 재인증 → 이메일 대조·검증 판정 없이 같은 계정 `uid`로 200. **이메일을 안 주는 provider도 이 경로는 그대로 성립**(두 번째 로그인부터는 이메일 입력이 없다) |
| USER-OAU-15 | 유비쿼터스 | THE 시스템 SHALL 신원 해석 대상을 `exit_at IS NULL`인 계정으로 한정한다 | 탈퇴 계정과 일치하는 연동 행·이메일이 있어도 그 계정으로 로그인되지 않음(USER-OAU-51·52) |
| USER-OAU-67 | 유비쿼터스 | THE 시스템 SHALL provider가 준 이메일의 검증 판정을 구글 `email_verified`, 카카오 `is_email_verified` **와** `is_email_valid`의 동시 만족, 네이버 무조건 검증됨으로 한다 | 카카오 응답이 `is_email_verified:true, is_email_valid:false` → 미검증으로 판정. 네이버 응답에 검증 필드가 없어도 검증됨으로 판정. ⚠ 카카오 갈래는 **비즈 앱 전환 전까지 도달하지 않는다**(이메일 자체가 오지 않아 USER-OAU-90으로 빠진다) — 전환 후를 위해 유지하는 규칙이다 |
| USER-OAU-16 | 이벤트 | WHEN 일치하는 연동 행이 없고 같은 이메일의 활성 계정이 있으며 **확정된 이메일과 그 계정의 이메일이 둘 다 검증됨**이면, THE 시스템 SHALL 그 계정에 (provider, 식별자) 연동 행 1건을 생성한다 | 자체 가입 계정(항상 검증됨)의 이메일로 구글(`email_verified:true`) 인증 → 코드 인증 없이 연동 1행 생성 |
| USER-OAU-17 | 이벤트 | WHEN USER-OAU-16의 조건이 성립하면, THE 시스템 SHALL 그 계정의 토큰 쌍을 반환한다 | 200 `status:"LOGIN"`, 받은 access로 `GET /api/users/me` → 기존 계정의 `nickname` |
| USER-OAU-68 | 이벤트 | WHEN 일치하는 연동 행이 없고 같은 이메일의 활성 계정이 있으며 **provider가 준 이메일과 그 계정의 이메일 중 하나라도 미검증**이면, THE 시스템 SHALL 200과 `status:"EMAIL_VERIFICATION_REQUIRED"` + 링크 티켓 + 그 이메일을 반환한다 | 미검증 이메일이 기존 계정과 일치 → 200, `ticket` 비어 있지 않음, `email`이 provider가 방금 준 값(어디로 코드가 갈지 화면에 필요하고, 요청자 자신의 값이라 신규 노출이 없다), **연동 행 생성 0건·토큰 0건** |
| USER-OAU-69 | 이벤트 | WHEN 클라이언트가 **링크 티켓**으로 인증번호 발송을 요청하면, THE 시스템 SHALL 그 티켓에 실린 이메일로만 인증번호를 발송한다 | `POST /api/auth/oauth/link/send-code` `{"ticket":"<링크 티켓>"}` → 티켓의 이메일로 발송. 본문에 다른 이메일을 실어도 발송 주소가 바뀌지 않음(남의 주소로 코드를 보내는 우회로 차단) |
| USER-OAU-70 | 이벤트 | WHEN 이메일 인증 티켓과 일치하는 인증번호가 제출되고 그 이메일의 활성 계정이 있으면, THE 시스템 SHALL 그 계정에 (provider, 식별자) 연동 행 1건을 생성한다 | `POST /api/auth/oauth/link/verify` `{"ticket":"...","code":"123456"}` → 연동 1행(링크 티켓·입력 티켓 모두 동일) |
| USER-OAU-71 | 이벤트 | WHEN USER-OAU-70의 조건이 성립하면, THE 시스템 SHALL 200과 `status:"LOGIN"` + 토큰 쌍을 반환한다 | 200, 그 access로 `GET /api/users/me` → 기존 계정의 `nickname` |
| USER-OAU-72 | 이벤트 | WHEN 이메일 인증 티켓과 일치하는 인증번호가 제출되고 그 이메일의 활성 계정이 있으면, THE 시스템 SHALL 그 계정의 이메일 검증 상태를 검증됨으로 승격시킨다 | 인증 전 `users.email_verified=false` → 인증 후 `true`. 이후 같은 이메일의 검증된 소셜 인증은 코드 없이 USER-OAU-16 경로로 통합됨 |
| USER-OAU-73 | 이벤트 | WHEN 이메일이 **미검증이던** 계정에서 인증번호 통과로 통합이 성립하면, THE 시스템 SHALL 그 계정에 이미 존재하던 연동 행을 모두 삭제한다 | 미검증 카카오로 만든 계정 + 진짜 주인이 검증된 구글로 합류(코드 통과) → 연동 테이블에 구글 1행만 남고 카카오 행 0건. 이후 그 카카오로 인증 → 자동 로그인 안 됨 |
| USER-OAU-74 | 유비쿼터스 | THE 시스템 SHALL 이미 검증됨이던 계정에서는 기존 연동 행을 삭제하지 않는다 | 검증된 계정에 다른 provider가 코드 통과로 합류 → 기존 연동 행 그대로 유지, 새 행만 1건 추가 |
| USER-OAU-75 | 이벤트 | WHEN 이메일 인증이 성공하면, THE 시스템 SHALL 그 이메일 인증 티켓을 소비해 재사용 불가하게 한다 | 같은 티켓으로 재요청 → 400 `INVALID_OAUTH_TICKET`, 연동 행 추가 0건 |
| USER-OAU-76 | 예외 | IF 이메일 인증 **티켓**이 없거나 만료·이미 소비됐으면, THEN THE 시스템 SHALL 400과 `"소셜 인증 정보가 만료되었습니다. 다시 로그인해 주세요."`를 반환한다 | 임의 문자열 티켓 → 400 `INVALID_OAUTH_TICKET`(발송·인증 두 경로 모두). ⚠ **인증번호 만료(USER-OAU-100)와는 다른 사건이며 코드도 다르다** — 이쪽은 소셜 로그인부터 다시 해야 하고, 저쪽은 인증번호만 다시 받으면 된다 |
| USER-OAU-77 | 예외 | IF 제출한 인증번호가 일치하지 않으면, THEN THE 시스템 SHALL 400과 `"인증번호가 일치하지 않습니다."`를 반환한다 | 오답 → 400 `INVALID_VERIFICATION_CODE`, 연동 행 생성 0건, 티켓은 아직 유효 |
| USER-OAU-78 | 예외 | IF 인증번호 시도 횟수가 한도를 초과하면, THEN THE 시스템 SHALL 400과 `"인증 시도 횟수를 초과했습니다. 인증번호를 다시 발송해 주세요."`를 반환한다 | 6번째 시도 → 400 `VERIFICATION_ATTEMPTS_EXCEEDED`, 정답이어도 거절(기존 이메일 인증과 동일한 5회 한도, 두 티켓 모두 동일) |
| USER-OAU-79 | 예외 | IF 발송 쿨다운 안에 인증번호 재발송을 요청하면, THEN THE 시스템 SHALL 429와 `"인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요."`를 반환한다 | 60초 내 재요청 → 429 `EMAIL_SEND_COOLDOWN`(기존 이메일 인증과 동일한 쿨다운, 두 티켓 모두 동일) |
| USER-OAU-100 | 예외 | IF 티켓은 유효하지만 그 티켓의 인증번호가 만료됐거나 발송된 적이 없으면, THEN THE 시스템 SHALL 400과 `"만료되었거나 유효하지 않은 인증번호입니다."`를 반환한다 | 발송 후 5분 경과(티켓은 10분이라 아직 살아 있음) → 400 `EXPIRED_VERIFICATION_CODE`. 발송 없이 곧바로 `/link/verify` → 같은 응답. **티켓은 소비되지 않아 재발송으로 이어서 진행할 수 있다**(USER-OAU-76처럼 소셜 로그인부터 다시 하지 않는다) |
| USER-OAU-80 | 유비쿼터스 | THE 시스템 SHALL 이메일 인증 성공 결과를 그 티켓에만 결부시키고 회원가입용 이메일 인증완료 상태를 만들지 않는다 | 인증 성공 후 Redis에 회원가입용 인증완료 키가 생기지 않음(자체 가입 경로와 상태가 섞이지 않는다) |
| USER-OAU-18 | 유비쿼터스 | THE 시스템 SHALL 계정과 provider 조합당 연동 행을 최대 1건만 보유한다 | 같은 계정에 같은 provider 행 2건을 직접 INSERT 시도 → DB UNIQUE 위반 |
| USER-OAU-19 | 유비쿼터스 | THE 시스템 SHALL provider와 provider 사용자 식별자 조합을 최대 1개 계정에만 연결한다 | 같은 (provider, 식별자)를 다른 계정에 INSERT 시도 → DB UNIQUE 위반 |
| USER-OAU-20 | 예외 | IF 이메일로 해석된 계정에 같은 provider의 다른 식별자 연동 행이 이미 있으면, THEN THE 시스템 SHALL 409와 `"이미 다른 소셜 계정이 연결되어 있습니다."`를 반환한다 | 계정 A에 kakao(식별자 X) 연동 후 같은 이메일로 확정된 kakao(식별자 Y)가 통합 시도 → 409 `OAUTH_PROVIDER_ALREADY_LINKED`, 연동 행 추가 0건 |
| USER-OAU-21 | 유비쿼터스 | THE 시스템 SHALL 자동 통합 시 기존 계정의 `users.email`을 새로 확정된 이메일로 갱신하지 않는다 | provider 이메일 변경 후 재인증 → `users.email` 값 불변 |
| USER-OAU-22 | 유비쿼터스 | THE 시스템 SHALL 자동 통합 시 기존 계정의 `nickname`·`name`·`tel`·`gender`·`profile_img_url`을 provider 값으로 덮어쓰지 않는다 | provider 프로필 이름·이미지가 달라도 `GET /api/users/me` 응답 불변 |
| USER-OAU-23 | — | (삭제됨 — 초안의 "이메일 미제공 400 거절". 카카오 비즈 앱 미전환 확정으로 **이메일 미제공이 정상 경로**가 되어 USER-OAU-90~99의 사용자 입력 갈래로 대체) | — |
| USER-OAU-24 | — | (삭제됨 — 초안의 "미검증 이메일 400 거절". USER-OAU-68~80의 코드 인증 갈래로 대체) | — |
| USER-OAU-25 | 상태 | WHILE 계정에 연동 행이 1건 이상 있는 동안, THE 시스템 SHALL 그 계정의 이메일·비밀번호 자체 로그인을 종전과 동일하게 처리한다 | 자체 가입 계정에 소셜 3사를 모두 연동한 뒤 `POST /api/auth/login` → 200 + 토큰 쌍 |
| USER-OAU-26 | 유비쿼터스 | THE 시스템 SHALL 한 계정이 kakao·naver·google 연동 행을 동시에 보유할 수 있게 한다 | 같은 이메일로 3사 순차 인증 → 연동 3행이 모두 같은 계정 소속, 세 번 모두 같은 `uid`로 로그인 |

### B-2. 이메일을 받지 못한 경우 (사용자 입력 + 코드 인증)
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-90 | 이벤트 | WHEN provider 사용자 정보에 쓸 수 있는 이메일이 없고 일치하는 연동 행도 없으면, THE 시스템 SHALL 200과 `status:"EMAIL_INPUT_REQUIRED"` + 입력 티켓을 반환한다 | 이메일 없는 카카오 응답 → 200, `ticket` 비어 있지 않음, `email:null`, **계정 행·연동 행 생성 0건**(400이 아니다) |
| USER-OAU-91 | 유비쿼터스 | THE 시스템 SHALL 이메일 입력 단계 진입 여부를 provider 종류가 아니라 **이번 응답에 쓸 수 있는 이메일이 있는지**로 판정한다 | 같은 카카오 스텁이 이메일을 실어 응답하도록 바꾸면 설정·코드 변경 없이 `EMAIL_INPUT_REQUIRED`가 사라지고 USER-OAU-16/68/12 갈래로 간다(비즈 앱 전환 시의 동작) |
| USER-OAU-92 | 이벤트 | WHEN 클라이언트가 **입력 티켓**과 이메일로 인증번호 발송을 요청하면, THE 시스템 SHALL 본문의 이메일로 인증번호를 발송한다 | `POST /api/auth/oauth/link/send-code` `{"ticket":"<입력 티켓>","email":"a@b.com"}` → 그 주소로 발송(링크 티켓과 달리 본문 이메일을 받는 유일한 경우) |
| USER-OAU-101 | 예외 | IF **입력 티켓** 요청에 이메일이 없거나 공백이면, THEN THE 시스템 SHALL 400과 `"이메일 주소를 입력해 주세요."`를 반환한다 | `{"ticket":"<입력 티켓>"}`(email 없음) 또는 `{"ticket":"...","email":""}` → 400 `OAUTH_EMAIL_REQUIRED`, 메일 발송 0건, 티켓은 소비되지 않음. 형식 오류(`"abc"`)는 여기까지 오지 않고 DTO `@Email`이 잡아 `data.email` 필드 메시지가 실린 일반 검증 400이 된다(⚠ `@NotBlank`는 걸 수 없다 — 링크 티켓 요청은 이 필드가 **없어야** 정상이다) |
| USER-OAU-93 | 유비쿼터스 | THE 시스템 SHALL 입력 티켓의 인증 대상 이메일을 **가장 최근 발송 요청의 이메일**로 고정한다 | 오타 후 다른 주소로 재발송 → 이전 주소의 인증번호로는 400, 최신 주소의 인증번호로만 성공 |
| USER-OAU-94 | 예외 | IF 같은 입력 티켓으로 발송 쿨다운 안에 재발송을 요청하면, THEN THE 시스템 SHALL 429와 `"인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요."`를 반환한다 | 60초 안에 **다른 이메일**로 재발송해도 429 `EMAIL_SEND_COOLDOWN`(티켓 하나를 대량 발송 통로로 쓰는 것을 막는다) |
| USER-OAU-95 | 유비쿼터스 | THE 시스템 SHALL 인증번호 발송 응답을 그 이메일의 계정 존재 여부와 무관하게 동일하게 반환한다 | 가입된 이메일·미가입 이메일 둘 다 200(기존 `POST /api/auth/email/send-code`의 409와 다르다 — 여기서 409를 주면 계정 열거가 된다) |
| USER-OAU-96 | 이벤트 | WHEN 입력 티켓의 인증번호가 일치하면, THE 시스템 SHALL 그 이메일을 **검증된 이메일**로 확정하고 USER-OAU-13의 ②단계를 그대로 수행한다 | 인증 통과 → 그 이메일로 계정이 없으면 `SIGNUP_REQUIRED`, 있으면 통합 후 `LOGIN` |
| USER-OAU-97 | 유비쿼터스 | THE 시스템 SHALL 입력 티켓의 인증번호 통과로 확정된 이메일에 대해 **추가 인증번호를 요구하지 않는다** | 그 이메일의 기존 계정이 미검증이어도 `EMAIL_VERIFICATION_REQUIRED`로 되돌아가지 않는다(이중 인증 금지 — 방금 통과한 인증이 곧 USER-OAU-68이 요구하는 증명이다) |
| USER-OAU-98 | 이벤트 | WHEN 입력 티켓의 인증번호가 일치하고 그 이메일의 활성 계정이 없으면, THE 시스템 SHALL 200과 `status:"SIGNUP_REQUIRED"` + 가입 티켓을 반환한다 | 200, `ticket` 비어 있지 않음, `email`이 방금 인증한 주소, 계정 행 생성 0건 |
| USER-OAU-99 | 유비쿼터스 | THE 시스템 SHALL 입력 티켓 경로로 확정된 이메일의 검증 상태를 검증됨으로 취급한다 | 그 티켓으로 만든 계정은 `users.email_verified=true`. 통합된 계정도 `true`로 승격(USER-OAU-72) |

### B-3. 이메일 검증 상태
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-81 | 유비쿼터스 | THE 시스템 SHALL 계정마다 이메일 검증 여부를 저장한다 | `users.email_verified`(NOT NULL) 컬럼이 존재하고 모든 행이 값을 가짐 |
| USER-OAU-82 | 유비쿼터스 | THE 시스템 SHALL 자체 회원가입으로 만들어진 계정의 이메일을 검증됨으로 저장한다 | `POST /api/auth/signup` 성공 → `email_verified=true`(가입 자체가 이메일 인증을 선행조건으로 이미 강제한다) |
| USER-OAU-83 | 유비쿼터스 | THE 시스템 SHALL 이 기능 배포 이전에 만들어진 계정을 전부 검증됨으로 본다 | 컬럼 추가 DDL이 `DEFAULT TRUE`라 기존 행이 전부 `true`. 별도 백필 스크립트 없음 |
| USER-OAU-84 | 이벤트 | WHEN 소셜 신규 가입이 성사되면, THE 시스템 SHALL 가입 티켓에 실린 이메일 검증 상태를 그 계정에 저장한다 | provider 미검증 이메일로 가입 → `false`. provider 검증 이메일 또는 입력 티켓 인증을 거친 이메일로 가입 → `true` |
| USER-OAU-85 | 유비쿼터스 | THE 시스템 SHALL 한 번 검증됨이 된 계정의 이메일 검증 상태를 미검증으로 되돌리지 않는다 | 승격 후 미검증 provider로 다시 인증해도 `email_verified`는 `true` 유지 |

### C. 소셜 신규 가입 (닉네임만 입력)
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-27 | 이벤트 | WHEN 해석되는 활성 계정이 없으면, THE 시스템 SHALL provider·식별자·확정된 이메일·이메일 검증 상태를 담은 1회용 가입 티켓을 발급한다 | 신규 소셜 사용자 인증 → 응답 `ticket`이 비어 있지 않음(provider가 이메일을 준 경우는 USER-OAU-12, 입력받은 경우는 USER-OAU-98) |
| USER-OAU-28 | 유비쿼터스 | THE 시스템 SHALL 가입 티켓과 이메일 인증 티켓의 유효기간을 10분으로 한다 | 발급 10분 경과 후 사용 → 400 `INVALID_OAUTH_TICKET`(세 종류 티켓 모두) |
| USER-OAU-29 | 유비쿼터스 | THE 시스템 SHALL 티켓 발급 시점에 `users`·`users_account` 행을 만들지 않는다 | `SIGNUP_REQUIRED`·`EMAIL_VERIFICATION_REQUIRED`·`EMAIL_INPUT_REQUIRED` 응답 전후로 두 테이블의 행 수가 동일 |
| USER-OAU-30 | 이벤트 | WHEN 유효한 가입 티켓과 `nickname`으로 소셜 가입을 요청하면, THE 시스템 SHALL `users`·`users_account` 행을 생성한다 | `POST /api/auth/oauth/signup` `{"ticket":"...","nickname":"승리요정"}` → 두 테이블에 각 1행(요청 필드는 이 둘뿐) |
| USER-OAU-31 | 이벤트 | WHEN 소셜 가입이 성공하면, THE 시스템 SHALL 같은 트랜잭션에서 `users_bq` 행을 `bq_score=0`으로 생성한다 | 가입 후 `users_bq`에 그 계정 행 1건, `bq_score=0`(자체 가입과 동일) |
| USER-OAU-32 | 이벤트 | WHEN 소셜 가입이 성공하면, THE 시스템 SHALL 티켓의 provider·식별자로 연동 행 1건을 생성한다 | 가입 후 연동 테이블 1행, 같은 소셜 계정 재인증 시 `status:"LOGIN"`(이메일 입력 없이) |
| USER-OAU-33 | 이벤트 | WHEN 소셜 가입이 성공하면, THE 시스템 SHALL 201과 토큰 쌍을 반환한다 | 201, `{"accessToken":"...","refreshToken":"..."}`(자체 가입이 `Boolean`을 주는 것과 다르다) |
| USER-OAU-34 | 이벤트 | WHEN 소셜 가입이 성공하면, THE 시스템 SHALL 그 가입 티켓을 소비해 재사용 불가하게 한다 | 같은 티켓으로 재요청 → 400 `INVALID_OAUTH_TICKET`, 계정 생성 0건 |
| USER-OAU-35 | 유비쿼터스 | THE 시스템 SHALL 생성 계정의 이메일을 가입 티켓에 실린 이메일로 고정한다 | 요청 본문에 `email`을 실어도 무시되고 `users.email`은 티켓의 이메일(입력 티켓 경로에서도 인증을 통과한 그 주소만 쓰인다) |
| USER-OAU-36 | 예외 | IF 가입 티켓이 없거나 만료·이미 소비됐으면, THEN THE 시스템 SHALL 400과 `"소셜 인증 정보가 만료되었습니다. 다시 로그인해 주세요."`를 반환한다 | 임의 문자열 티켓 → 400 `INVALID_OAUTH_TICKET` |
| USER-OAU-37 | 예외 | IF `nickname`이 닉네임 정책을 위반하면, THEN THE 시스템 SHALL 400과 자체 가입과 동일한 위반 메시지를 반환한다 | 11자 닉네임 → 400, `POST /api/auth/nickname/validate`가 같은 값에 돌려주는 메시지와 문자 그대로 동일. `(알수없음)` 같은 괄호 포함 값도 400 |
| USER-OAU-38 | 예외 | IF `nickname`이 이미 점유됐으면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 닉네임입니다."`를 반환한다 | 기존 닉네임으로 요청 → 409 `DUPLICATE_NICKNAME`(탈퇴 계정 점유분 포함) |
| USER-OAU-39 | — | (삭제됨 — 초안의 "`tel` 중복 409". 소셜 가입이 `tel`을 받지 않게 되어 성립하지 않는다) | — |
| USER-OAU-40 | 예외 | IF 가입 티켓의 이메일이 이미 점유됐으면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 이메일입니다."`를 반환한다 | 탈퇴 계정이 점유한 이메일로 소셜 가입 → 409 `DUPLICATE_EMAIL` |
| USER-OAU-41 | 유비쿼터스 | THE 시스템 SHALL 소셜 가입에 회원가입용 이메일 인증완료 상태를 요구하지 않는다 | Redis 인증완료 키 없이 소셜 가입 → 201 (자체 `signup`은 같은 상황에서 400 `EMAIL_NOT_VERIFIED`) |
| USER-OAU-42 | 유비쿼터스 | THE 시스템 SHALL 소셜 가입 요청에서 비밀번호를 받지 않는다 | 본문에 `password`를 실어 가입한 뒤 그 값으로 `POST /api/auth/login` → 401 `INVALID_CREDENTIALS` |
| USER-OAU-86 | 유비쿼터스 | THE 시스템 SHALL 소셜 가입으로 만들어진 계정의 `name`·`tel`·`gender`를 NULL로 저장한다 | 소셜 가입 직후 `users` 행의 세 컬럼이 전부 NULL |
| USER-OAU-87 | 유비쿼터스 | THE 시스템 SHALL `users.name`·`tel`·`gender`에 NULL을 허용한다 | `information_schema.COLUMNS`에서 세 컬럼의 `IS_NULLABLE`이 `YES` |
| USER-OAU-88 | 예외 | IF 자체 회원가입 요청에 `name`·`tel`·`gender` 중 하나라도 없으면, THEN THE 시스템 SHALL 400을 반환한다 | `POST /api/auth/signup`에서 `tel` 누락 → 400(컬럼이 nullable이 되어도 **자체 가입 요청 계약은 불변**) |
| USER-OAU-89 | 유비쿼터스 | THE 시스템 SHALL `users.tel`의 UNIQUE 제약을 유지한 채 NULL 다중 행을 허용한다 | 소셜 계정 2개 이상 생성 → `tel`이 NULL인 행이 여러 건 공존, 같은 실제 번호 2건은 여전히 UNIQUE 위반 |

### D. 소셜 계정과 자체 인증 경로의 관계
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-43 | 유비쿼터스 | THE 시스템 SHALL 소셜로만 만들어진 계정의 `password`에 어떤 평문과도 일치하지 않는 잠긴 값을 저장한다 | 저장값이 BCrypt 패턴이 아니며 어떤 문자열로 로그인해도 200이 나오지 않음 |
| USER-OAU-44 | 예외 | IF 소셜 전용 계정의 이메일로 자체 로그인을 요청하면, THEN THE 시스템 SHALL 401과 `"이메일 또는 비밀번호가 올바르지 않습니다."`를 반환한다 | 임의 비밀번호로 `POST /api/auth/login` → 401 `INVALID_CREDENTIALS`(미가입 이메일과 응답 동일) |
| USER-OAU-45 | 예외 | IF 소셜 전용 계정이 비밀번호 변경을 요청하면, THEN THE 시스템 SHALL 400과 `"현재 비밀번호가 올바르지 않습니다."`를 반환한다 | 어떤 `currentPassword`로도 `PATCH /api/users/me/password` → 400 `INVALID_CURRENT_PASSWORD` |
| USER-OAU-46 | 이벤트 | WHEN 자체 가입 계정에 소셜 연동이 추가되면, THE 시스템 SHALL 그 계정의 `password`를 변경하지 않는다 | 연동 후 기존 비밀번호로 `POST /api/auth/login` → 200 |
| USER-OAU-47 | 이벤트 | WHEN 소셜 인증으로 토큰을 발급하면, THE 시스템 SHALL 그 계정의 기존 유효 refresh 토큰을 모두 만료시킨다 | 자체 로그인으로 받은 refresh → 소셜 로그인 → 이전 refresh로 `POST /api/auth/refresh` → 401 `EXPIRED_REFRESH_TOKEN` |
| USER-OAU-48 | 유비쿼터스 | THE 시스템 SHALL 소셜 인증으로 발급한 토큰의 claim 구성을 자체 로그인과 동일하게 한다 | payload의 `sub`가 `UserAccount.uid`, `type`이 `access`/`refresh`. 소셜 access로 `GET /api/users/me` → 200 |
| USER-OAU-49 | 이벤트 | WHEN 소셜 인증으로 발급된 refresh 토큰으로 재발급을 요청하면, THE 시스템 SHALL 자체 로그인 토큰과 동일하게 처리한다 | `POST /api/auth/refresh` → 200 + 새 토큰 쌍 |
| USER-OAU-50 | 이벤트 | WHEN 비밀번호 변경 이전에 소셜 인증으로 발급된 access 토큰으로 인증이 필요한 요청이 들어오면, THE 시스템 SHALL 401과 `"인증이 필요합니다."`를 반환한다 | 소셜 access 발급 → `PATCH /api/users/me/password` → 이전 소셜 access로 `GET /api/users/me` → 401(USER-ATI 계약 그대로) |

### E. 탈퇴·만료 데이터 정리와의 상호작용
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-51 | 예외 | IF 연동 행이 가리키는 계정이 탈퇴 상태면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 이메일입니다."`를 반환한다 | 카카오 연동 계정 탈퇴 후 같은 카카오로 인증 → 409 `DUPLICATE_EMAIL`, 새 계정 생성 0건 |
| USER-OAU-52 | 예외 | IF 확정된 이메일이 탈퇴 계정의 이메일과 같으면, THEN THE 시스템 SHALL 409와 `"이미 사용 중인 이메일입니다."`를 반환한다 | 자체 가입 후 탈퇴한 이메일로 네이버 인증 → 409 `DUPLICATE_EMAIL`. 입력 티켓 경로에서는 인증번호 통과 **뒤** 같은 409(발송 응답으로는 존재를 알리지 않는다, USER-OAU-95) |
| USER-OAU-53 | 유비쿼터스 | THE 시스템 SHALL 회원 탈퇴 시 연동 행을 삭제하지 않는다 | `DELETE /api/users/me` 후 연동 테이블의 그 계정 행이 그대로 존재 |
| USER-OAU-54 | 유비쿼터스 | THE 시스템 SHALL 계정 하드 삭제 시 그 계정의 연동 행을 함께 삭제한다 | 만료 데이터 정리 배치 실행 후 연동 테이블에 그 계정 행 0건, 배치가 FK 위반 없이 성공 |
| USER-OAU-55 | 이벤트 | WHEN 하드 삭제 이후 같은 provider 식별자로 소셜 인증이 들어오면, THE 시스템 SHALL 신규 사용자로 처리한다 | 탈퇴 30일 경과 + 배치 실행 후 같은 카카오로 인증 → 200 `SIGNUP_REQUIRED` 또는 `EMAIL_INPUT_REQUIRED`(409가 아님) |
| USER-OAU-56 | 유비쿼터스 | THE 시스템 SHALL `(알수없음)` 더미 계정을 신원 해석 대상에서 제외하고 그 이메일을 신규 가입으로도 흘려보내지 않는다 | 그 계정의 이메일(`unknown@victoryfairy.internal`)을 입력 티켓에 적고 인증을 시도해도 그 계정으로 로그인되지 않으며, 신규 가입으로도 이어지지 않고 409 `DUPLICATE_EMAIL`(실제로 점유된 주소다) |

### F. 저장 구조·동시성·로그
| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-OAU-57 | 유비쿼터스 | THE 시스템 SHALL provider 사용자 식별자를 provider가 준 값 그대로 저장한다 | 카카오 숫자 id·구글 `sub` 문자열이 가공(해싱·접두 부착) 없이 저장됨 |
| USER-OAU-58 | 유비쿼터스 | THE 시스템 SHALL 연동 행에 연동 시각을 기록한다 | 연동 행의 `created_at`이 NULL이 아님 |
| USER-OAU-59 | 유비쿼터스 | THE 시스템 SHALL 같은 (provider, 식별자)에 대한 동시 연동 시도 중 한 건만 성공시킨다 | 동일 신원의 인증 요청 2건을 동시에 보냄 → 연동 행은 1건만 생성 |
| USER-OAU-60 | 예외 | IF 동시 요청이 연동 행 UNIQUE 제약에 걸려 실패하면, THEN THE 시스템 SHALL 500과 `"서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."`를 반환한다 | 실패한 쪽 응답이 `ApiResponse`로 감싼 500(`{"success":false,"data":null,"message":"서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}` = `INTERNAL_SERVER_ERROR`). 재시도하면 성공(그때는 USER-OAU-14 경로) |
| USER-OAU-61 | 유비쿼터스 | THE 시스템 SHALL 인가코드와 provider 토큰을 로그에 남기지 않는다 | 인증 1회 수행 후 애플리케이션 로그에서 해당 문자열 검색 결과 0건 |
| USER-OAU-62 | 유비쿼터스 | THE 시스템 SHALL `GET /api/users/me` 응답 계약을 변경하지 않는다 | 소셜 연동 계정으로 조회해도 응답 키가 기존 그대로(연동 목록·이메일 검증 상태 미노출) |

## 신규 `ErrorCode` (`:common`)
| 코드 | 상태 | 메시지 | 쓰이는 곳 |
|---|---|---|---|
| `UNSUPPORTED_OAUTH_PROVIDER` | 400 | 지원하지 않는 소셜 로그인입니다. | USER-OAU-4·5 |
| `INVALID_OAUTH_REDIRECT_URI` | 400 | 허용되지 않은 리다이렉트 주소입니다. | USER-OAU-64·65 |
| `OAUTH_EMAIL_REQUIRED` | 400 | 이메일 주소를 입력해 주세요. | USER-OAU-101 (**7번째 코드** — 입력 티켓에 이메일이 없을 때. `EMAIL_NOT_VERIFIED`를 빌려 쓰지 않는 이유는 사용자가 할 일이 "인증 완료"가 아니라 "입력"이라서다, 제약 21) |
| `INVALID_OAUTH_TICKET` | 400 | 소셜 인증 정보가 만료되었습니다. 다시 로그인해 주세요. | USER-OAU-34·36·75·76 (가입 티켓·링크 티켓·입력 티켓 **공용**. 티켓 종류를 문구로 가르지 않는 이유는 사용자가 할 조치가 "다시 로그인"으로 같기 때문이다) |
| `INVALID_OAUTH_CODE` | 401 | 소셜 인증에 실패했습니다. 다시 시도해 주세요. | USER-OAU-7 (만료·재사용·provider 쪽 redirect URI 불일치 세 사유 통합) |
| `OAUTH_PROVIDER_ALREADY_LINKED` | 409 | 이미 다른 소셜 계정이 연결되어 있습니다. | USER-OAU-20 |
| `OAUTH_PROVIDER_UNAVAILABLE` | **502** | 소셜 로그인 제공자와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요. | USER-OAU-8 — **이 저장소 최초의 502**. 500으로 묶지 않는 이유는 원인이 이 서버가 아니라 외부 의존이고 클라이언트의 올바른 행동(재시도)이 다르기 때문이다 |

**`OAUTH_EMAIL_REQUIRED`는 뜻이 한 번 바뀐 코드다.** 초안에서는 "provider가 이메일을 주지 않았다"는 **거절** 사유였으나, 2차 개정에서 이메일 미제공이 오류가 아니라 `status:"EMAIL_INPUT_REQUIRED"`라는 **정상 응답**이 되면서 그 뜻으로는 쓰이는 곳이 없어져 삭제됐다(정상 흐름의 한 단계를 4xx로 표현하면 프론트 인터셉터가 오류로 삼킨다). 3차 개정에서 **"사용자가 이메일을 입력하지 않았다"는 새 뜻으로 되살렸다** — 경위는 제약 21.

기존 코드 재사용(**신규는 위 7종뿐이다**): `DUPLICATE_EMAIL`(409, USER-OAU-40·51·52·56) · `DUPLICATE_NICKNAME`(409, USER-OAU-38) · `INVALID_VERIFICATION_CODE`(400, USER-OAU-77) · `EXPIRED_VERIFICATION_CODE`(400, **USER-OAU-100** — 티켓이 아니라 인증번호의 만료) · `VERIFICATION_ATTEMPTS_EXCEEDED`(400, USER-OAU-78) · `EMAIL_SEND_COOLDOWN`(429, USER-OAU-79·94) · `INVALID_CREDENTIALS`(401, USER-OAU-44) · `INVALID_CURRENT_PASSWORD`(400, USER-OAU-45) · `UNAUTHENTICATED`(401, USER-OAU-50) · `INTERNAL_SERVER_ERROR`(500, USER-OAU-60). ⚠ `EMAIL_NOT_VERIFIED`는 **이 기능이 쓰지 않는다** — USER-OAU-41이 자체 가입과의 대비로 언급할 뿐이다(3차 개정 때 USER-OAU-101이 잠시 빌려 썼다가 `OAUTH_EMAIL_REQUIRED`로 되돌렸다, 제약 21).

## 제약 (모듈 컨텍스트 대조 결과 — 구현 지시가 아니라 이미 참이거나 이 결정이 만든 사실)
1. **`POST /api/auth/email/send-code`를 이 기능에 재사용할 수 없다.** 그 엔드포인트는 **이미 가입된 이메일에 409를 주도록 계약돼 있고**(중복가입 사전차단 우선, `email-verification.md`), 이 기능의 인증 대상은 **계정이 이미 있을 수도 있는 이메일**이다(링크 티켓은 항상 그렇고, 입력 티켓도 그럴 수 있다). 그래서 `/api/auth/oauth/link/send-code`·`/link/verify` 2개가 전용 경로이며 **두 티켓 종류가 이 둘을 함께 쓴다**(엔드포인트를 더 늘리지 않는다). 판정 정책(60초 쿨다운·5회 시도·1회용 소비)은 기존 `EmailVerificationService`의 것을 그대로 따르되(USER-OAU-77~79), **결과는 전역 인증완료 상태가 아니라 티켓에 결부된다**(USER-OAU-80).
2. **사용자가 남의 이메일을 적을 수 있고, 그것을 막는 유일한 장치가 인증번호다**(USER-OAU-92~96). 입력 티켓은 본문 이메일을 그대로 받으므로 임의 주소를 적는 것 자체는 막지 않는다 — **막히는 지점은 그 주소의 메일함을 못 여는 것**이며, 그래서 시도 5회 한도(USER-OAU-78)와 발송 쿨다운(USER-OAU-79·94)이 이 경로에도 예외 없이 적용된다. **발송 응답이 계정 존재 여부로 갈리지 않는 것**(USER-OAU-95)도 같은 이유다 — 여기서 409를 주면 이 경로가 계정 열거기가 된다.
3. **남는 한계: 이 경로는 소량 메일 발송 통로로 쓰일 수 있다.** 티켓 하나로 60초에 한 번(USER-OAU-94), 티켓 수명 10분이므로 최대 10통이고, 티켓을 새로 받으려면 **매번 실제 소셜 로그인(1회용 인가코드)** 을 통과해야 한다. 대량 발송을 막는 실질 장치는 이 인가코드 비용이며, 그 이상의 전역 한도는 이번 범위에서 두지 않는다.
4. **선점 방지(USER-OAU-73)는 정상 사용자에게도 비용을 물린다.** 한 사람이 미검증 소셜로 가입한 뒤 다른 신원으로 합류하면 **자기 이전 연동도 함께 지워진다**(시스템은 그 연동이 본인 것인지 공격자 것인지 구분할 수단이 없다 — 구분할 수 있었다면 애초에 이 규칙이 필요 없다). 그 사용자는 다음에 이전 provider로 들어올 때 인증을 한 번 더 거치고, 그때는 계정이 이미 검증됨이라 기존 연동이 지워지지 않는다(USER-OAU-74). **최대 1회의 추가 인증**으로 수렴하는 비용이다.
5. **선점당한 이메일의 소유자는 자체 회원가입으로 계정을 되찾을 수 없다.** 공격자가 만든 미검증 계정이 `users.email` UNIQUE를 점유하므로 `POST /api/auth/signup`은 409 `DUPLICATE_EMAIL`이다. 되찾는 유일한 길은 **소셜 인증 + 코드 통과**다. ⚠ 2차 개정으로 이 길이 **넓어졌다** — 이제는 provider가 이메일을 주지 않아도 입력 티켓으로 그 이메일을 직접 적고 인증할 수 있어, 소셜 계정만 하나 있으면 provider 종류와 무관하게 되찾을 수 있다(1차 개정 시점의 "이메일을 주는 provider 계정이 필요하다"는 제약이 사라졌다).
6. **되찾은 계정은 공격자가 만든 상태를 그대로 물려받는다.** 닉네임·포인트·응원 설정·퀴즈 이력이 남고 지워지는 것은 연동 행뿐이다(USER-OAU-73). 닉네임은 `nickname_changed_at`이 NULL이라 즉시 1회 바꿀 수 있지만 그 뒤 30일 쿨다운에 걸린다.
7. **닉네임 문자 화이트리스트가 `(알수없음)` 예약 계정 사칭 방지의 유일한 근거다**(`nickname`에 DB UNIQUE가 없어 중복 검사가 아니라 문자 정책이 막는다). **provider가 준 닉네임을 그대로 저장하는 구현은 금지**다 — 카카오 닉네임에는 괄호·공백·이모지가 들어올 수 있다(그리고 카카오에서 우리가 받는 것은 지금 식별자와 닉네임뿐이라 그 값을 쓰고 싶은 유혹이 특히 크다). 소셜 가입 닉네임은 사용자 입력이며 USER-OAU-37이 같은 정책을 강제한다.
8. **`redirectUri`를 검증 없이 그대로 쓰면 인가코드를 임의 주소로 빼돌리는 경로가 열린다.** 서버 설정 고정은 불가능하다 — 웹(`https://…`)과 앱(커스텀 스킴)이 서로 다른 값을 쓰고, 토큰 교환 시 인가코드 발급 때와 **문자 그대로 일치**해야 한다. 그래서 본문으로 받되 허용 목록과 대조한다(USER-OAU-63). **접두 일치로 구현하면 안 된다** — `https://victoryfairy.com`을 접두로 검사하면 `https://victoryfairy.com.evil.com`이 통과한다.
9. **`users.email`·`tel`은 탈퇴해도 30일간 점유된다**(`existsBy*`가 탈퇴를 구분하지 않고 DB UNIQUE가 물리적으로 막는다 — 근거는 `withdraw.md` "결정 근거 1"). 자동 통합도 소셜 가입도 이 벽을 넘을 수 없어 USER-OAU-51·52가 409로 고정된다.
10. **탈퇴 계정 비노출 원칙(USER-WD-8)의 의도적 예외**가 USER-OAU-51·52다. 근거: 그 지점에서는 **provider 또는 우리 인증번호가 이미 그 이메일의 소유자임을 증명한 상태**라 응답이 알려주는 정보가 요청자 자신의 것이다. ⚠ 그래서 **입력 티켓 경로의 409는 반드시 인증번호 통과 뒤에 나가야 한다** — 발송 단계에서 409를 주면 아무나 이메일만 적어 가입·탈퇴 이력을 캐낼 수 있다(USER-OAU-95).
11. **네이버는 이메일 검증 여부를 알려주지 않아 무조건 검증됨으로 취급한다**(USER-OAU-67). **자동 통합의 가장 약한 고리**이며, 네이버 쪽에 미검증 이메일 계정이 존재할 수 있다면 그 계정은 코드 인증 없이 기존 계정에 붙는다. 네이버가 정책을 바꾸거나 사고가 보고되면 가장 먼저 재검토할 자리다.
12. **카카오 검증 판정 규칙(USER-OAU-67)은 지금 도달하지 않는 코드 경로다.** 비즈 앱이 아니라 이메일 자체가 오지 않기 때문이다. **삭제하지 않는 이유는 전환 승인 시 그대로 필요하기 때문**이며, 그때 `is_email_verified`만 보고 `is_email_valid`를 빠뜨리면 폐기된(회수·재사용될 수 있는) 주소가 검증됨으로 통과한다.
13. **`tel`이 NULL인 계정이 정상적으로 존재하게 된다.** 향후 SMS 발송·본인확인·전화번호 기반 조회 기능은 **NULL을 다뤄야 하고**, "전화번호는 항상 있다"를 전제로 짜면 소셜 계정에서 NPE 또는 조용한 누락이 된다. `name`·`gender`도 같다.
14. **refresh 토큰은 계정당 1개다.** 소셜 로그인은 기존 세션을 끊는다(USER-OAU-47) — 새 정책이 아니라 현행 계약의 귀결이다(사용자 고지 완료).
15. **`/api/auth/**`는 전부 permitAll**이라 소셜 경로 4개는 `SecurityConfig` 수정 없이 열린다. 반대로 연동 해제 같은 인증 필요 API를 나중에 추가할 때 `/api/auth` 아래 두면 인증이 걸리지 않는다.
16. **연동 테이블의 FK는 `ON DELETE CASCADE`여야 한다.** 만료 데이터 정리 배치의 삭제 순서는 고정돼 있고(취소 좋아요 → 소유권 이관 → `deleteUserById`), CASCADE가 아니면 `DELETE FROM users`가 FK 위반으로 실패해 **그 계정이 배치에서 통째로 스킵된다**. ⚠ **`ddl-auto=update`는 기존 FK의 `DELETE_RULE`을 바꾸지 않는다** — 나중에 정책을 고칠 일이 생기면 1회성 DDL이 필요하다(`QuizLikeDeleteRuleInspector`가 그 사고의 산물이다).
17. **1인 1계정을 실제로 보장하는 것은 DB 제약 두 개다**: UNIQUE (provider, provider_user_id) · UNIQUE (user_account_id, provider). 애플리케이션 판정만으로는 동시 요청에서 뚫린다(USER-OAU-59·60).
18. **`:domain` 컨벤션**: 테이블명은 `users_` + 한정어, provider는 `@Enumerated(ORDINAL)`+TINYINT이며 **선언 순서 변경 금지**, UNIQUE 제약은 이름을 명시한다.
19. **Redis는 이미 이 모듈에 있다**(이메일 인증·프로필 이미지 한도와 같은 인스턴스). 세 종류의 티켓 저장에 새 인프라 의존이 생기지 않는다.
20. **처리되지 않은 예외의 500은 `ApiResponse`로 감싸여 나간다**(`:web-support`의 `GlobalExceptionHandler.handleUnexpected` — `AccessDeniedException`·`AuthenticationException`·`ErrorResponse`·`AsyncRequestNotUsableException`만 다시 던지고 나머지는 전부 감싼다). 연동 UNIQUE 충돌(`DataIntegrityViolationException`)도 여기 걸리므로 USER-OAU-60의 응답은 **래퍼가 붙은 500**이다. ⚠ 이 핸들러를 이 기능 때문에 고치지 말 것 — **전 앱의 500 응답 계약을 바꾸는 일**이고, 락 획득 실패 등 기존 사례와 응답 형태가 갈린다.
21. **`OAUTH_EMAIL_REQUIRED`(USER-OAU-101)는 한 번 지웠다가 되살린 코드다 — 그 경위를 남긴다.** ①초안에는 "provider가 이메일을 안 줬다"는 뜻의 400으로 있었고, ②2차 개정에서 **이메일 미제공이 오류가 아니라 정상 경로**(`status:"EMAIL_INPUT_REQUIRED"`)가 되면서 쓰이는 곳이 없어져 삭제됐다. ③3차 개정에서 입력 티켓의 이메일 누락을 규정할 때 **총량을 6종으로 묶는 결정** 때문에 `EMAIL_NOT_VERIFIED`를 빌려 썼는데, 그 문구는 사용자가 이미 주소를 알고 있다는 전제를 깔아 **해야 할 일(입력)을 잘못 안내**했다. 기존 400 중 대안도 없었다 — `INVALID_OAUTH_TICKET`은 "다시 로그인하라"고 잘못 안내하고, `INVALID_APP_ID`·`PROFILE_IMAGE_REQUIRED` 같은 "~가 필요합니다" 계열은 도메인이 다르다. ④**2026-08-21 사용자가 7종을 허용해 이견이 채택**됐고, 같은 이름을 **새 뜻**으로 되살렸다. ⚠ 나중에 "코드가 왜 이렇게 많나"를 되묻는 사람을 위해: 6종으로 묶었던 이유는 **에러 코드 인플레이션 억제**였고, 그 원칙을 깬 기준은 "상태코드가 같아도 **사용자가 취할 조치가 다르면** 코드를 나눈다"였다.

## 배포 전제
1. **운영 DDL 3건 — 앱 배포보다 반드시 먼저.** `users.name`·`tel`·`gender`의 NOT NULL 해제. ⚠ **`ddl-auto=update`는 기존 컬럼의 NOT NULL을 풀어 주지 않는다** — 손으로 적용하지 않으면 소셜 가입이 전부 제약 위반으로 실패한다(엔티티만 nullable로 바꿔 놓으면 로컬·신규 DB에서만 멀쩡하고 운영에서만 깨진다).
2. **`users.email_verified` 컬럼 추가** — `NOT NULL DEFAULT TRUE`. 기본값이 곧 백필이다(USER-OAU-83). `ddl-auto=update`가 만들게 두어도 되지만, **손으로 적용할 경우 `DEFAULT TRUE`를 빠뜨리면 기존 계정 전원이 미검증이 되어 모든 소셜 통합이 코드 인증을 요구하게 된다.**
3. **연동 테이블 신규 생성** — `ddl-auto=update`가 만든다. UNIQUE 2종과 FK CASCADE가 실제로 걸렸는지 배포 후 `information_schema`로 확인할 것(제약 16·17).
4. provider 콘솔 3곳의 리다이렉트 URI 화이트리스트와 서버 허용 목록 값 일치(선행 조건 2)
5. **메일 발송 설정(`MAIL_*`)이 prod에 실제로 있어야 한다** — 이 기능은 카카오·네이버 로그인의 **정상 경로**에서 메일을 보낸다(USER-OAU-92). 자체 가입 인증번호만 쓰던 때보다 실패의 파급이 크다(메일이 안 나가면 카카오 로그인이 아예 불가능하다).

## 결정 기록 (2026-08-20, 사용자 확정)
1. **미검증 이메일 — 위험한 갈래에만 인증을 요구한다.** 초안의 "검증된 이메일만 통합, 나머지는 400"은 과잉이라 폐기했다. 빼앗을 계정이 없는 신규 가입은 검증 여부와 무관하게 통과시키고(USER-OAU-84가 그 사실을 계정에 기록한다), 기존 계정과 합쳐지는 갈래에서만 코드 인증을 태운다. **대신 선점 방지(USER-OAU-73)가 필수 조건으로 함께 확정됐다** — 이 규칙이 없으면 완화가 곧 취약점이 된다.
2. **provider별 검증 판정** — 구글 `email_verified` / 카카오 `is_email_verified` **AND** `is_email_valid` / 네이버는 필드 부재로 검증됨 취급(제약 11).
3. **이메일 미제공 — 사용자 입력 + 우리 코드 인증(2차 개정).** 카카오 비즈 앱 전환을 하지 않기로 확정되면서 `account_email` 동의항목 자체를 못 쓰게 됐고, **이메일 미제공이 오류가 아니라 정상 경로**가 됐다. 그래서 400 거절(USER-OAU-23)을 폐기하고 입력 갈래를 신설했다. **이 경로로 얻은 이메일은 `emailVerified=true`** 다 — provider가 주는 미검증 이메일과 달리 **우리가 직접 소유를 확인**했으므로 신뢰도가 provider 검증 이메일과 동급 이상이다. 따라서 인증 통과 후의 판정은 ②단계와 완전히 같고 **인증을 또 요구하지 않는다**(USER-OAU-97). 선점 방지는 이 경로에도 그대로 적용된다(USER-OAU-73).
4. **분기 기준은 provider가 아니라 "이번 응답에 이메일이 있는가"**(USER-OAU-91). provider를 하드코딩해 분기하면 비즈 앱 전환 시 요구사항과 코드를 다시 고쳐야 한다. 네이버도 이메일이 선택 동의라 거부당하면 같은 경로를 탄다.
5. **소셜 가입 필수 필드 — 컬럼 nullable 완화(초안 B안 채택).** 초안 A안(폼으로 `name`·`tel`·`gender`를 받기)은 **아무도 읽지 않는 값을 위해 마찰을 만드는 것**이라 폐기했다. `tel`은 `existsByTel`과 시스템 계정 채움 외에 소비처가 없고 `/me`는 일부러 제외한다. **자체 가입 요청 계약은 불변**(USER-OAU-88).
6. **닉네임은 사용자 직접 입력.** 소셜 가입 입력은 닉네임 하나다. **2단계 구조를 유지하는 이유**는 클라이언트가 첫 요청 시점에 이 신원이 신규인지 알 수 없고, **인가코드가 1회용이라 "닉네임을 붙여 다시 호출"이 불가능**하기 때문이다. 티켓이 그 1회용 인가코드의 자리를 대신한다.
7. **인증 플로우는 A(프론트가 인가코드 전달 → 서버가 교환).** 프론트가 React 웹 기반 RN이라 웹과 앱이 같은 서버 엔드포인트를 쓸 수 있는 방식이 이것뿐이다. `redirectUri`는 **서버 고정이 불가능**해 본문으로 받되 허용 목록과 완전 일치로 대조한다(제약 8).
8. **탈퇴 계정 충돌은 409 거절.** 30일 뒤 하드 삭제되면 신규 가입으로 열린다(USER-OAU-55).
9. **소셜 전용 계정의 비밀번호 설정 기능은 이번 범위 제외.**
10. **연동 목록 조회·해제(unlink) API는 이번 범위 제외.** USER-OAU-73의 연동 해제는 시스템 내부 동작이며 이것과 별개다.
11. **구현 후 정정 3건(2026-08-21).** 구현이 드러낸 문서-실제 불일치를 문서 쪽으로 맞췄다. ①USER-OAU-60의 500은 **래퍼가 붙는다**(제약 20). ②입력 티켓의 이메일 누락 응답을 USER-OAU-101로 명시했다(제약 21의 문구 한계 포함). ③**티켓 만료와 인증번호 만료는 다른 사건**이라 코드가 갈린다 — 티켓은 `INVALID_OAUTH_TICKET`(USER-OAU-76, 소셜 로그인부터 다시), 인증번호는 `EXPIRED_VERIFICATION_CODE`(USER-OAU-100, 재발송으로 이어서 진행). 같은 날 구현자 재량 2건을 계약으로 승격했다 — `EMAIL_VERIFICATION_REQUIRED` 응답의 `email` 동봉(USER-OAU-68), 예약 이메일의 409(USER-OAU-56).
12. **`ErrorCode` 6종 → 7종(2026-08-21, 같은 날 재결정).** 3차 개정 때 총량을 6종으로 묶은 탓에 USER-OAU-101이 `EMAIL_NOT_VERIFIED`를 빌려 썼는데, **사용자가 해야 할 일("이메일을 입력하라")과 문구("인증이 완료되지 않았습니다")가 어긋난다**는 이견이 제기돼 사용자가 7번째 코드를 허용했다. 초안에 있다가 2차 개정에서 지웠던 **`OAUTH_EMAIL_REQUIRED`를 되살리되 뜻을 바꿔 재정의**한다 — 옛 뜻은 "provider가 이메일 제공에 동의받지 못했다"(그래서 400 거절)였고, 지금 뜻은 "**사용자가 이메일을 입력하지 않았다**"다. 바뀐 것은 USER-OAU-101 한 줄과 코드 표뿐이다(제약 21).

## 미해결 질문
없음 — 초안의 8건과 2차 개정 논점은 2026-08-20 사용자 답변으로 전부 해소됐다(위 "결정 기록" 참조).
