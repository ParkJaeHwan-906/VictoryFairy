# infra 모듈 (배포 · 인프라)

> 이 파일은 infra/배포 작업 시에만 로드되는 슬림 컨텍스트다.
> 최종 업데이트: 2026-08-01
>
> **서빙은 EKS다.** EC2+docker-compose 배포 경로는 2026-07-27 폐기됐다 — 대상 인스턴스가 이미
> 사라져 있었고 워크플로도 실패만 하고 있었다. `deploy.yml`·`docker-compose.prod.yml`·`nginx.conf`를
> 삭제했다. 되살리자는 제안을 먼저 하지 말 것(필요하면 git 히스토리에 있다).

---

## 서빙 구조

```
victoryfairy.com ──HTTPS──► ALB ──► user-app(8080) / quiz-app(8081) 파드
                                          │
                                          └──► EC2 자체 호스팅 MySQL (victoryfairy-mysql-dev, 10.0.0.14)
```

- **코드 위치**: 인프라 Terraform·k8s 매니페스트는 이 BE 트리가 아니라 상위 레포의 `VictoryFairy_Infra/`에 있다.
  `main`에는 `VictoryFairy_BE/`·`VictoryFairy_Infra/`·`VictoryFairy_AI/`가 함께 있지만 `dev_*` 브랜치는 각자
  담당 디렉터리만 가진 **분리된 트리**라 `dev_be`에서는 `VictoryFairy_Infra/`가 보이지 않는다.
  인프라 파일을 봐야 하면 **`main` 기준**으로 볼 것. Terraform 규약은 루트 `.claude/skills/terraform-infra`.
  상태는 S3 백엔드(`victoryfairy-tfstate`, key `dev/terraform.tfstate`).
- **EKS**: 클러스터 `victoryfairy-dev`, k8s **1.30**(⚠ 표준 지원 종료 → 연장 지원 과금 구간, 업그레이드 필요).
  노드그룹 2개 — `app`(user·quiz 공용 t3.medium On-Demand, HPA+Cluster Autoscaler),
  `batch`(Spot, 평소 0대, CronJob 시각에만 0→N→0). 워커는 프라이빗 서브넷, 파드 권한은 IRSA(OIDC).
- **DB**: RDS 미사용 — **EC2 자체 호스팅 MySQL+Redis 컨테이너**(`modules/mysql-ec2`), EBS 영속 볼륨,
  SSM 포트포워딩 접근(22/3306 인입 미개방), mysqldump→S3 백업 크론.
  ⚠ **MySQL EC2가 두 대다**(2026-08-01 AWS 실측, 헷갈리기 쉬우니 "운영 DB 확인했다"고 말할 때 어느 쪽인지 구분할 것):
  - `victoryfairy-mysql-dev` — private `10.0.0.14` / public `43.200.82.148`. **EKS 파드가 쓰는 쪽**
    (클러스터의 헤드리스 Service `mysql`, k8s `30-external-data.yaml`의 Endpoints가 이 IP).
  - `victoryfairy-devdb-dev` — private `10.0.0.163` / public `52.78.153.242`. BE 레포 `.env`의 `DB_HOST`가
    가리키는 **로컬 개발용**(매일 새벽 프로덕션 덤프를 복원).
  - 두 DB는 데이터가 다르다.
- **레지스트리/CI**: ECR(`victoryfairy-user`, `victoryfairy-quiz`) + GitHub Actions keyless(OIDC).

## 앱 설정 주입

user·quiz 파드가 `envFrom`으로 ConfigMap `app-config` + Secret `app-secret`을 함께 읽는다
(`DB_HOST/PORT/NAME`, `REDIS_HOST/PORT`, `SPRING_PROFILES_ACTIVE`, `JAVA_TOOL_OPTIONS` 등).

- ⚠ **두 앱이 같은 ConfigMap을 공유**한다. 여기에 키를 넣으면 한쪽만 바꿀 수 없고 양쪽에 적용된다.
- ⚠ `envFrom`은 **핫리로드가 안 된다.** ConfigMap을 고쳤으면 `kubectl rollout restart`로 파드를 새로 띄워야 반영된다.
- `ddl-auto`는 ConfigMap으로 덮어쓰지 않는다(2026-07-27 `SPRING_JPA_HIBERNATE_DDLAUTO` 키 제거).
  각 앱의 `application-prod.yaml`이 출처다 — **user `update`, quiz `none`**.
  종전에는 ConfigMap이 `update`를 양쪽에 강제해 quiz의 yaml 값이 무시되고 있었다.
- ⚠ **prod 스키마는 `user` 앱이 만든다.** `UserApplication`이 `@EntityScan("com.skhynix")`로 domain 엔티티를
  전부 스캔하는데 `user`가 `ddl-auto=update`라, quiz가 `none`이어도 채팅 테이블까지 user 기동 시 생성된다.
  뒤집어 말하면 **user를 `none`으로 되돌리는 순간 새 엔티티는 아무도 만들지 않는다.** Flyway는 없다.

## 도메인 + HTTPS (적용 완료 2026-07-27)

루트 `victoryfairy.com`이 Route53(신규 존)+ACM(DNS 검증, ISSUED) 인증서로 ALB에 연결돼 HTTPS로 서비스 중.
AWS Load Balancer Controller + ExternalDNS(`k8s/23-external-dns.yaml`, apex A(ALIAS)→ALB 자동 레코드).
모듈: `VictoryFairy_Infra/modules/{dns,alb}`. runbook: `VictoryFairy_Infra/docs/domain-https-setup.md`.

- **Ingress는 2개다**(`k8s/22-ingress.yaml`): `victoryfairy-user`(`/api`) / `victoryfairy-quiz`(`/rt`).
  `group.name: victoryfairy`를 같게 줘서 **ALB는 하나**로 묶인다. 쪼갠 이유는 헬스체크 경로가 앱마다 다르기
  때문 — `healthcheck-path`는 Ingress 단위 어노테이션이라 하나로는 두 값을 담을 수 없다.
- ALB 헬스체크: `/api/actuator/health/readiness`, `/rt/actuator/health/readiness`.
  ⚠ `/actuator/health` 전체가 아니라 **readiness 그룹**이다(전체는 db·redis 인디케이터를 합산해 DOWN을 내므로
  MySQL EC2가 흔들리면 멀쩡한 파드까지 타깃에서 빠진다).
- ⚠ **ALB는 forward 시 경로 rewrite를 못 한다**(redirect만 가능). Ingress path와 앱의
  `server.servlet.context-path`가 문자 그대로 일치해야 한다.
- ⚠ 레지스트라 NS: 도메인 재등록·이전 시 가비아 네임서버가 기본값으로 초기화된다. Route53 존 NS 4개로
  다시 등록해야 하며, 그동안 도메인 전체가 SERVFAIL이 된다(2026-07-27 실제 발생).

## CI/CD

**`deploy-eks.yml` 하나뿐이다**(저장소 루트 `.github/workflows/`).
push(main) + `workflow_dispatch` → 변경 모듈 감지 → Docker 빌드 → ECR push(**커밋 SHA 7자리**, 태그 IMMUTABLE)
→ `kubectl set image` → 블루-그린 롤아웃(`maxSurge 100%/maxUnavailable 0`) → **실패 시 자동 `rollout undo`**.
인증은 OIDC AssumeRole(`victoryfairy-dev-github-actions`)이라 액세스 키 시크릿이 없다.

## 로컬 개발

- `docker-compose.yml` — `mysql:8.0`(3306, `mysql-data` 볼륨) + `redis:7.2-alpine`(6379). 둘 다 healthcheck 있음.
  `user`·`quiz`는 `profiles: ["prod"]` 뒤에 숨어 있어 **기본 실행은 DB만 뜬다.** 앱까지 띄우려면 `--profile prod`.
- `Dockerfile` — **EKS CI도 이 파일로 빌드한다**(`docker build --build-arg MODULE=...`). 로컬 전용이 아니므로
  함부로 바꾸면 운영 빌드가 깨진다.
- ⚠ `docker compose down -v`는 `mysql-data` 볼륨을 지운다. 사용자가 로컬 개발 DB로 쓰고 있다.

## redis (이메일 인증 상태 저장, user 전용)

TTL 기반 휘발성 데이터라 영속 볼륨이 필요 없다. `user`에 `SPRING_DATA_REDIS_HOST`/`PORT`가 주입되고
`quiz`는 redis를 쓰지 않는다.

## 이메일 발송 (Mailjet SMTP, user 전용, prod 프로파일)

`SmtpEmailSender`(`@Profile("prod")`)가 인증번호를 실발송한다. dev/test는 `LogEmailSender`(mock)라 설정 불필요.
필요한 값: `MAIL_HOST`(`in-v3.mailjet.com`)·`MAIL_PORT`(587, STARTTLS)·`MAIL_USERNAME`(API Key)·
`MAIL_PASSWORD`(Secret Key)·`MAIL_FROM`(`no-reply@victoryfairy.com`).
**EKS에서는 ConfigMap `app-config`/Secret `app-secret`으로 주입한다.** 가이드: `docs/deployment/email-verification-mailjet.md`.

## 알려진 갭

- **Mailjet SPF 미등록** — DKIM·도메인 검증은 통과했으나 SPF가 없다. apex TXT를 ExternalDNS 소유권 레코드가
  점유하고 있어 SPF를 넣어도 1분 안에 덮어써진다. `--txt-prefix=_externaldns.`로 소유권 TXT를 하위 도메인으로
  내리면 함께 풀린다. 도달률(Gmail·네이버)에 영향.
- **ExternalDNS가 1분마다 같은 레코드를 UPSERT한다** — v0.15는 소유권 TXT를 구형/신형 두 개 쓰는데, apex
  레코드라 신형 이름이 `cname-victoryfairy.com`이 되어 **존 밖으로 나간다**(`no hosted zone matching record
  DNS Name`). 영영 못 만들어 매 루프 재시도한다. DNS는 정상이라 실질 피해는 로그 노이즈뿐. 위 SPF 건과 같은 방법으로 해소된다.
- **k8s probe가 전부 `tcpSocket`이다** — 포트가 열렸는지만 보고 앱이 건강한지는 안 본다. actuator readiness가
  생겼으니 `httpGet`으로 올릴 수 있다(`VictoryFairy_Infra/k8s/20-user-app.yaml`·`21-quiz-app.yaml`).
- ~~**`game_statuses` 시드 0행**~~ — 해소(2026-08-05). `infra/sql/game-statuses-init.sql`을 `user` 앱이 dev·prod 모두
  기동 시 실행한다(`spring.sql.init`). 배포 전 수동 SQL 불필요. 상세는 `.claude/modules/domain.md`.
- **CI에 테스트 단계가 없다** — 빌드만 하고 배포한다. 테스트는 32개 있으므로 넣을 명분이 있다.
- **EKS 1.30 연장 지원 과금 구간** — 버전 업그레이드 필요.
- **`docker-compose.yml`의 `user`/`quiz` `environment:`에 `JWT_SECRET`이 없다**(2026-08-06 실측) — `.env`에 값이 있어도 컨테이너로 전달되지 않고 `application.yaml`의 하드코드 기본값(65바이트, jjwt가 HS512 선택)으로 조용히 폴백한다. 그 결과 로컬 `docker compose --profile prod`에서 발급된 refresh 토큰이 `users_refreshtoken.refreshtoken`(`length=255`)를 넘겨 `POST /auth/login`이 항상 500(`Data too long for column`)이 난다. EKS는 Secret 주입 경로가 달라 이 증상이 있는지 미확인.

## 미결정 사항
- [x] 클러스터 방식: **EKS 채택** (managed control plane, `victoryfairy-dev`)
- [x] 도메인·HTTPS: **적용 완료**, `https://victoryfairy.com` EKS 서빙 (2026-07-27 종단 확인)
- [x] 앱 헬스 엔드포인트: **actuator readiness 도입 완료**, ALB 타깃 healthy 확인
- [x] EC2+compose 경로 정리: **폐기 완료** (2026-07-27)
- [ ] EKS k8s **1.30 → 상위 버전 업그레이드**
- [ ] Mailjet SPF 등록 (ExternalDNS txt-prefix 조정과 함께)
- [ ] 앱 개수 / 예상 트래픽 (스케일 상한 재산정)
