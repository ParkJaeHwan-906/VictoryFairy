# 이메일 인증 발송 설정 가이드 (Brevo SMTP · victoryfairy.com)

> 대상: user 모듈의 이메일 인증(`/api/auth/email/send-code`) 실발송을 운영에 붙이는 배포 담당자
> 발송 수단: **Brevo**(무료 트랜잭션 메일) SMTP · 발신 도메인 **victoryfairy.com**

## 0. 배포 진행 상태 — 다음에 이어서 할 일

> 마지막 갱신 2026-07-18. 코드/설정(준비 작업)은 커밋 완료. 아래는 **운영 배포 시 남은 실작업**이며, 지금은 **가비아 도메인 승인 대기 중**이라 보류.

- [x] 코드·config·docker-compose(Redis + Brevo MAIL_*)·문서 — 커밋됨 (dev/test는 mock으로 이미 동작)
- [ ] **가비아 도메인(`victoryfairy.com`) 승인 완료** ← 현재 대기. 승인돼야 DNS 관리 가능
- [ ] Brevo 가입 → 도메인 등록 → 발급된 DNS 레코드 확인 (3·5단계)
- [ ] **가비아 DNS 관리**에 SPF·DKIM·brevo-code(TXT) 등록 → Brevo에서 인증 초록불 (4단계)
      - 가비아 호스트칸: 루트는 `@`, 서브는 접두사만(`mail._domainkey`). 도메인명 붙이지 말 것
      - SPF는 도메인당 1개 — 기존 SPF 있으면 `include:spf.brevo.com`만 추가
- [ ] Brevo SMTP key 발급 (6단계)
- [ ] **EC2 `.env`** 에 `MAIL_USERNAME`/`MAIL_PASSWORD` 채우기 (7단계)
- [ ] `docker compose -f docker-compose.prod.yml up -d`로 user 컨테이너 재생성 → 실발송 확인 (8단계)

> 참고: **도메인을 EC2에 연결(A 레코드)하는 것과 이메일 발송은 무관**하다. 메일 발송은 가비아 DNS에 TXT 레코드만 있으면 되고, 실제 발송은 Brevo가 대신 한다.

## 1. 언제 실제로 메일이 나가는가

발송 구현은 프로파일로 갈린다.

| 프로파일 | 구현체 | 동작 |
|---|---|---|
| `prod` | `SmtpEmailSender` | Brevo SMTP로 **실제 발송** |
| `prod` 이외 (dev/test) | `LogEmailSender` | 발송 없이 **로그로 코드만 출력** (`[MOCK-EMAIL] ... code=xxxxxx`) |

즉 로컬/개발/테스트는 Brevo 없이도 그대로 동작하며, **실발송은 운영 배포에서만** 일어난다. 코드는 표준 `spring.mail.*`(SMTP)를 쓰므로, 발송 서비스를 바꾸더라도 **환경변수만 교체**하면 되고 코드 수정은 없다.

## 2. 설정 표면 (코드가 읽는 값)

| 프로퍼티 | 환경변수 | 값 | 정의 위치 |
|---|---|---|---|
| `spring.mail.host` | `MAIL_HOST` | `smtp-relay.brevo.com` | `application-prod.yaml` |
| `spring.mail.port` | `MAIL_PORT` | `587` (STARTTLS) | `application-prod.yaml` |
| `spring.mail.username` | `MAIL_USERNAME` | Brevo SMTP 로그인 | `application-prod.yaml` |
| `spring.mail.password` | `MAIL_PASSWORD` | Brevo SMTP key | `application-prod.yaml` |
| `app.mail.from` | `MAIL_FROM` | `no-reply@victoryfairy.com` | `application.yaml` (기본값 동일) |

`docker-compose.prod.yml`의 `user` 서비스가 위 환경변수를 주입한다. `MAIL_HOST`/`MAIL_PORT`는 고정값, `MAIL_USERNAME`/`MAIL_PASSWORD`는 **시크릿이라 `.env`에서 주입**, `MAIL_FROM`은 `.env`로 덮어쓸 수 있고 없으면 `no-reply@victoryfairy.com`을 쓴다.

## 3. Brevo 가입 & 도메인 인증

1. **brevo.com 무료 가입** (신용카드 불필요).
2. **Senders, Domains & Dedicated IPs → Domains → Add a domain** 에서 `victoryfairy.com` 등록.
3. Brevo가 아래 **DNS 레코드**를 발급한다(값은 계정마다 고유):
   - 소유 인증 TXT — `brevo-code:xxxxxxxx` 형태
   - **DKIM** TXT — 호스트 `mail._domainkey`
   - **SPF** TXT — `v=spf1 include:spf.brevo.com ~all`
   - **DMARC** TXT(권장) — 호스트 `_dmarc`, 예: `v=DMARC1; p=none;`

## 4. 도메인 등록처 DNS에 레코드 추가

`victoryfairy.com` 구입처(가비아/Cloudflare/GoDaddy 등)의 DNS 관리에서 위 레코드를 **Brevo가 준 값 그대로** 추가한다.

- ⚠️ **SPF는 도메인당 하나**여야 한다. 이미 `v=spf1 ...` TXT가 있으면 새로 만들지 말고 기존 레코드에 `include:spf.brevo.com`만 추가한다.
- 등록 후 Brevo 콘솔에서 **Authenticate/Verify**를 눌러 초록불 확인(DNS 전파에 수 분~수 시간).

## 5. SMTP 자격증명 발급

Brevo 콘솔 **SMTP & API → SMTP** 에서:

- **Login** = 표시된 SMTP 사용자(보통 가입 이메일) → `MAIL_USERNAME`
- **Generate a new SMTP key** 로 SMTP key 생성 → `MAIL_PASSWORD` (로그인 비밀번호가 **아님**)

## 6. EC2 `.env` 채우기

운영 서버의 `.env`(배포 compose가 읽는 파일)에 시크릿을 추가한다. 저장소에는 `.env` 예시 파일이 없으므로(gitignore 대상) 아래 키를 직접 채운다.

```dotenv
MAIL_USERNAME=<Brevo SMTP 로그인>
MAIL_PASSWORD=<Brevo SMTP key>
# 선택: 기본값(no-reply@victoryfairy.com)과 다르게 쓸 때만
# MAIL_FROM=no-reply@victoryfairy.com
```

`MAIL_HOST`/`MAIL_PORT`/`MAIL_FROM`은 compose에 고정값·기본값으로 들어있어 `.env`에 없어도 된다. 로컬 `.env`(docker-compose.yml)에는 이 키들이 필요 없다(dev는 mock).

값을 채운 뒤 다음 배포 또는 `docker compose -f docker-compose.prod.yml up -d`로 `user` 컨테이너를 재생성해야 반영된다.

## 7. 배포 후 확인 체크리스트

- [ ] Brevo 콘솔에서 `victoryfairy.com` 도메인 인증이 **초록불**인지 (인증 전에는 발송 거부/스팸 처리됨)
- [ ] EC2 `.env`에 `MAIL_USERNAME`/`MAIL_PASSWORD`가 채워졌는지 (비면 `${MAIL_USERNAME}`이 빈 값→ Brevo 인증 실패)
- [ ] `user` 컨테이너가 `prod` 프로파일(`SPRING_PROFILES_ACTIVE=prod`)로 뜨는지 (아니면 mock sender가 로딩됨)
- [ ] `POST /api/auth/email/send-code` 호출 → 실제 수신함에 `[VictoryFairy] 이메일 인증번호 안내` 도착
- [ ] 첫 발송이 스팸함에 가면 DKIM/SPF/DMARC 정렬 재확인

## 8. 자주 겪는 문제

| 증상 | 원인 | 조치 |
|---|---|---|
| 앱은 뜨는데 발송이 로그로만 찍힘 | 프로파일이 `prod`가 아님 | `SPRING_PROFILES_ACTIVE=prod` 확인 |
| SMTP 인증 실패(535 등) | `MAIL_PASSWORD`에 로그인 비밀번호를 넣음 | Brevo **SMTP key**로 교체 |
| 메일이 스팸함으로 | 도메인 미인증 / SPF·DKIM 미정렬 | 3~4단계 도메인 인증 완료 |
| `${MAIL_USERNAME}` 빈 값으로 기동 | EC2 `.env` 미갱신 | 6단계대로 `.env` 채우고 컨테이너 재생성 |

---

관련 코드: `user/.../auth/email/SmtpEmailSender.java`, `LogEmailSender.java` · 설정: `user/src/main/resources/application-prod.yaml`, `application.yaml` · compose: `docker-compose.prod.yml`
