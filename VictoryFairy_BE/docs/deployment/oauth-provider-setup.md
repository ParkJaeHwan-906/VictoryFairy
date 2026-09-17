# 소셜 로그인(OAuth) provider 설정 가이드

최종 수정: 2026-08-21

카카오·네이버·구글 소셜 로그인에 필요한 콘솔 설정과 환경변수 등록 절차.
기능 계약은 `docs/requirements/user/oauth-login.md`, API 는 `docs/api/auth.md` 를 본다.

---

## 0. 한눈에

등록해야 할 환경변수는 **9개**다.

| provider | client id | client secret | redirect URI 목록 |
|---|---|---|---|
| 카카오 | `KAKAO_CLIENT_ID` | `KAKAO_CLIENT_SECRET` | `KAKAO_REDIRECT_URIS` |
| 네이버 | `NAVER_CLIENT_ID` | `NAVER_CLIENT_SECRET` | `NAVER_REDIRECT_URIS` |
| 구글 | `GOOGLE_CLIENT_ID` | `GOOGLE_CLIENT_SECRET` | `GOOGLE_REDIRECT_URIS` |

**셋을 한꺼번에 채울 필요는 없다.** `client-id` 가 비어 있으면 그 provider 는 지원 목록에서
자동으로 빠지고(USER-OAU-4) 나머지는 정상 동작한다. 준비된 것부터 넣어도 된다.

메일 발송(`MAIL_USERNAME`·`MAIL_PASSWORD`)은 **이미 운영에 등록돼 있다**(Secret `app-secret`).
`MAIL_HOST`·`MAIL_PORT` 는 앱 기본값(`in-v3.mailjet.com` / `587`)이라 넣지 않아도 된다.
다만 로컬 컨테이너로 검증하려면 `.env` 에도 두 값을 넣어야 한다 — 아래 §5 참고.

---

## 1. redirect URI 규칙 (세 provider 공통, 여기가 가장 많이 틀린다)

- `*_REDIRECT_URIS` 는 **콤마 구분 목록**이다. 웹과 앱이 서로 다른 주소를 쓰므로 **둘 다** 넣는다.
- 서버는 요청 본문의 `redirectUri` 가 이 목록과 **문자 그대로 일치**할 때만 provider 를 호출한다.
  부분 일치·접두사 일치는 통과하지 않는다. 이 검증이 없으면 인가코드를 임의 주소로 빼돌리는
  경로가 열린다(USER-OAU-63~66).
- 같은 값이 **각 사 콘솔에도 등록**돼 있어야 한다. 토큰 교환 시 provider 가 인가코드 발급 때 쓴
  값과 대조하기 때문이다. 한 글자(끝 슬래시 포함)만 달라도 교환이 거절된다.

### ⚠ 구글은 커스텀 스킴을 웹 클라이언트에 등록할 수 없다

구글의 **웹 애플리케이션** 클라이언트는 redirect URI 로 `https://…`(및 `http://localhost`)만 받는다.
`victoryfairy://…` 같은 앱 커스텀 스킴은 거절되고, 그런 주소를 쓰려면 iOS/Android 클라이언트를
따로 만들어야 하는데 **그 클라이언트는 client id 가 다르고 client secret 이 없다.**

지금 서버 설정은 provider 당 client id 하나를 전제한다. 따라서 다음 중 하나를 골라야 한다.

1. **(권장) 앱도 인앱 브라우저(Custom Tabs / SFSafariViewController)로 웹 redirect URI 를 쓴다.**
   client id 하나로 웹·앱이 모두 돌아가고 서버 설정을 바꿀 필요가 없다.
2. 앱 전용 클라이언트를 따로 만든다. 이 경우 **서버가 플랫폼별 client id 를 구분해야 하므로
   설정 구조 변경이 필요하다**(현재 미지원).

카카오·네이버는 커스텀 스킴 등록을 허용하므로 이 제약이 없다.

---

## 2. 구글

1. [Google Cloud Console](https://console.cloud.google.com/) → 프로젝트 선택/생성
2. **API 및 서비스 → OAuth 동의 화면** 을 먼저 구성한다(미구성이면 클라이언트를 만들어도 로그인이
   막힌다). 범위는 `openid`·`email`·`profile` 이면 충분하다.
3. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**
   - 애플리케이션 유형: **웹 애플리케이션**
   - 승인된 리디렉션 URI: 위 §1 의 웹 주소를 등록
4. 발급된 **클라이언트 ID / 클라이언트 보안 비밀번호** 를 `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET` 에 넣는다.

구글은 이메일을 항상 주고 `email_verified` 도 함께 준다. 추가 심사가 필요 없다.

---

## 3. 네이버

1. [네이버 개발자센터](https://developers.naver.com/) → **Application → 애플리케이션 등록**
2. 사용 API 에 **네이버 로그인** 을 선택하고, **제공 정보**에서 **이메일 주소**를 포함시킨다.
   (필수/선택 설정이 가능하다. 선택으로 두면 거부하는 사용자가 생기는데, 그 경우 서버가 이메일
   입력 + 인증번호 경로로 자연히 넘어가므로 로그인이 실패하지는 않는다.)
3. 서비스 URL 과 **Callback URL** 에 §1 의 주소를 등록한다.
4. 발급된 **Client ID / Client Secret** 을 `NAVER_CLIENT_ID`·`NAVER_CLIENT_SECRET` 에 넣는다.

> **⚠ 운영 공개 전 검수가 필요하다.** 네이버 로그인은 개발 상태에서 등록된 테스트 멤버만
> 로그인할 수 있다. 일반 사용자에게 열려면 개발자센터에서 검수를 신청해 통과해야 한다.
> 개발·검증 단계에서는 본인 계정으로 테스트하면 된다.

네이버는 이메일 검증 여부 필드를 주지 않아 **검증된 것으로 취급**한다(요구사항 제약에 기록됨).

---

## 4. 카카오

1. [카카오디벨로퍼스](https://developers.kakao.com/) → **내 애플리케이션 → 애플리케이션 추가**
2. **앱 키 → REST API 키** 가 `KAKAO_CLIENT_ID` 다. (JavaScript 키가 아니다.)
3. **카카오 로그인 → 활성화 설정 ON**, **Redirect URI** 에 §1 의 주소를 등록한다.
4. **카카오 로그인 → 동의항목**: 닉네임 등 필요한 항목을 설정한다.
   서버는 provider 닉네임을 저장하지 않으므로(USER-OAU-37) 최소 구성으로 충분하다.
5. **보안 → Client Secret**: 코드를 생성하고 **활성화 상태**로 둔 뒤 `KAKAO_CLIENT_SECRET` 에 넣는다.
   - ⚠ 활성화 여부와 환경변수를 **일치**시켜야 한다. 활성화해 두고 값을 비우거나, 비활성화 상태에서
     값을 넣으면 토큰 교환이 거절된다. 쓰지 않을 거면 비활성화 + 변수 공란으로 맞춘다.

### ⚠ 카카오는 이메일을 주지 않는다

`account_email` 동의항목은 **비즈 앱 권한**이라 일반 앱에서는 신청 자체가 막혀 있다.
따라서 카카오 사용자는 **최초 로그인 시 이메일을 직접 입력하고 인증번호로 확인**하는 경로를 탄다
(USER-OAU-90~99). 두 번째 로그인부터는 묻지 않는다.

이 때문에 **카카오 로그인은 메일 발송에 의존한다** — `MAIL_*` 이 죽으면 카카오만 로그인 불가가 된다.

나중에 비즈 앱 전환이 승인되어 이메일이 오기 시작하면, 서버 분기 기준이 "provider 종류"가 아니라
"이번 응답에 이메일이 있는가"라서 **코드·설정 변경 없이** 입력 단계가 자동으로 사라진다(USER-OAU-91).

---

## 5. 로컬 등록 (`.env`)

`.env.example` 의 해당 블록을 `.env` 로 복사해 값을 채운다.

로컬에서 **컨테이너로** 검증하려면 두 가지를 더 맞춰야 한다.

- `SPRING_PROFILES_ACTIVE=prod` — `docker-compose.yml` 의 `user`·`quiz` 서비스가 `profiles: ["prod"]`
  이고 `COMPOSE_PROFILES=${SPRING_PROFILES_ACTIVE}` 라, dev 로 두면 앱 컨테이너가 **아예 뜨지 않는다.**
- `MAIL_USERNAME`·`MAIL_PASSWORD` — prod 프로파일은 `SmtpEmailSender`(실발송)를 쓴다.
  값이 없으면 카카오 경로의 인증번호 발송이 실패한다. (`gradlew bootRun` 으로 dev 프로파일로 띄우면
  `LogEmailSender` 가 인증번호를 **로그에 출력**하므로 메일 설정 없이도 흐름을 확인할 수 있다.)

---

## 6. 운영 등록 (EKS)

네임스페이스는 `victoryfairy`, 파드는 ConfigMap `app-config` + Secret `app-secret` 을 `envFrom` 으로 읽는다.

**client id / secret 은 Secret 에** 넣는다.

```bash
kubectl -n victoryfairy patch secret app-secret --type merge -p "$(cat <<'JSON'
{"stringData":{
  "KAKAO_CLIENT_ID":"...",  "KAKAO_CLIENT_SECRET":"...",
  "NAVER_CLIENT_ID":"...",  "NAVER_CLIENT_SECRET":"...",
  "GOOGLE_CLIENT_ID":"...", "GOOGLE_CLIENT_SECRET":"..."
}}
JSON
)"
```

**redirect URI 목록은 비밀이 아니므로** ConfigMap 에 넣어도 된다.

```bash
kubectl -n victoryfairy patch configmap app-config --type merge -p "$(cat <<'JSON'
{"data":{
  "KAKAO_REDIRECT_URIS":"https://victoryfairy.com/oauth/callback/kakao",
  "NAVER_REDIRECT_URIS":"https://victoryfairy.com/oauth/callback/naver",
  "GOOGLE_REDIRECT_URIS":"https://victoryfairy.com/oauth/callback/google"
}}
JSON
)"
```

> **⚠ `envFrom` 은 핫리로드가 안 된다.** 반영하려면 파드를 새로 띄워야 한다.
> ```bash
> kubectl -n victoryfairy rollout restart deployment/user-app
> ```

> **⚠ ConfigMap 은 user·quiz 두 앱이 공유한다.** 여기에 키를 넣으면 양쪽에 모두 적용된다
> (quiz 는 이 값을 읽지 않으므로 무해하다).

---

## 7. 적용 확인

```bash
# 파드에 값이 들어갔는지 (이름만 확인, 값은 출력하지 않는다)
kubectl -n victoryfairy exec deploy/user-app -- printenv | grep -oE '^(KAKAO|NAVER|GOOGLE)_[A-Z_]+' | sort

# 지원 provider 목록이 열렸는지 — 미지원이면 400 UNSUPPORTED_OAUTH_PROVIDER 가 돌아온다
curl -s -X POST https://victoryfairy.com/api/auth/oauth/google \
  -H 'Content-Type: application/json' \
  -d '{"code":"dummy","redirectUri":"https://victoryfairy.com/oauth/callback/google"}'
```

- `UNSUPPORTED_OAUTH_PROVIDER` → `client-id` 가 비어 있다(변수 미등록 또는 rollout 미반영)
- `INVALID_OAUTH_REDIRECT_URI` → `*_REDIRECT_URIS` 목록에 그 값이 없다
- `INVALID_OAUTH_CODE`(401) → **여기까지 오면 설정은 정상이다.** 더미 인가코드가 거절된 것뿐이다
- `OAUTH_PROVIDER_UNAVAILABLE`(502) → provider 호출 자체가 실패했다(네트워크·자격증명 오류)
