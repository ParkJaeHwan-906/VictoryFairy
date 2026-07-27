# infra 모듈 (배포 · 인프라 학습)

> 이 파일은 infra/배포 작업 시에만 로드되는 슬림 컨텍스트다.
> EC2 → Docker → Kubernetes 단계적 학습 이력 + 이 백엔드의 배포를 다룬다.
> ⚠️ 방향 전환: EKS 기반 인프라가 이미 Terraform 으로 프로비저닝됨 — 아래 "인프라 방향" 섹션 먼저 읽을 것.
> 최종 업데이트: 2026-07-27

---

## ⚠️ 인프라 방향: EKS 로 이행 중 (2026-07)
이 문서의 본문(아래 "현재 인프라 상태"~"로드맵")은 **EC2 단일 인스턴스 + docker-compose** 기준의
초기 배포·학습 이력이다. 그와 **별개로 EKS 기반 인프라가 Terraform 으로 프로비저닝**되어 있다.

**도메인(`https://victoryfairy.com`)은 EKS 가 서빙한다** — 2026-07-27 종단 확인.
apex A(ALIAS) → ALB → `user-app`/`quiz-app` 파드로 붙고, 두 타깃 그룹 모두 healthy 다.
다만 EC2+compose 파이프라인(`.github/workflows/deploy.yml`)도 **여전히 main push 에서 함께 돈다**
(EKS 용은 `deploy-eks.yml`). EC2 쪽을 정리할지는 미결이므로, "EC2 는 죽었다"고 단정하지 말 것.

- **코드 위치**: 인프라 Terraform·k8s 매니페스트는 이 BE 트리가 아니라 **상위 레포의
  `VictoryFairy_Infra/`** 에 있다. `main` 에는 `VictoryFairy_BE/`·`VictoryFairy_Infra/`·`VictoryFairy_AI/`
  가 함께 있지만 `dev_*` 브랜치는 각자 담당 디렉터리만 가진 **분리된 트리**라, `dev_be` 트리에서는
  `VictoryFairy_Infra/` 가 보이지 않는다. 인프라 파일을 봐야 하면 `main` 기준으로 볼 것.
  Terraform 규약은 `.claude/skills/terraform-infra`.
  상태는 S3 백엔드(`victoryfairy-tfstate`, key `dev/terraform.tfstate`).
- **EKS**: 클러스터 `victoryfairy-dev`, k8s **1.30**(⚠ EKS 표준 지원 종료 → 연장 지원 과금 구간, 버전
  업그레이드 필요). 노드그룹 2개 — `app`(user·quiz 공용 t3.medium On-Demand, HPA+Cluster Autoscaler),
  `batch`(Spot, 평소 0대, CronJob 시각에만 0→N→0). 워커는 프라이빗 서브넷, 파드 권한은 IRSA(OIDC).
- **앱 설정 주입**: user·quiz 파드가 `envFrom` 으로 ConfigMap `app-config` + Secret `app-secret` 을 함께 읽는다
  (`DB_HOST/PORT/NAME`, `REDIS_HOST/PORT`, `SPRING_PROFILES_ACTIVE`, `JAVA_TOOL_OPTIONS` 등).
  - ⚠ **두 앱이 같은 ConfigMap 을 공유**한다. 여기에 키를 넣으면 한쪽만 바꿀 수 없고 양쪽에 적용된다.
  - ⚠ `envFrom` 은 **핫리로드가 안 된다.** ConfigMap 을 고쳤으면 `kubectl rollout restart` 로 파드를 새로 띄워야 반영된다.
  - `ddl-auto` 는 ConfigMap 으로 덮어쓰지 않는다(2026-07-27 `SPRING_JPA_HIBERNATE_DDLAUTO` 키 제거).
    각 앱의 `application-prod.yaml` 이 출처다 — user `update`, quiz `none`.
    종전에는 ConfigMap 이 `update` 를 양쪽에 강제해 quiz 의 yaml 값이 무시되고 있었다.
- **DB**: RDS 미사용 — **EC2 자체 호스팅 MySQL+Redis 컨테이너**(`modules/mysql-ec2`), EBS 영속 볼륨,
  SSM 포트포워딩 접근(22/3306 인입 미개방), mysqldump→S3 백업 크론.
- **레지스트리/CI**: ECR(`user`,`quiz`) + GitHub Actions keyless(OIDC) 배포.
- **도메인 + HTTPS** (**적용 완료 2026-07-27**): 루트 `victoryfairy.com` 이 Route53(신규 존)+ACM
  (DNS 검증, ISSUED) 인증서로 ALB 에 연결돼 HTTPS 로 서비스 중. AWS Load Balancer Controller +
  ExternalDNS(`k8s/23-external-dns.yaml`, apex A(ALIAS)→ALB 자동 레코드).
  모듈: `VictoryFairy_Infra/modules/{dns,alb}`, eks 모듈 `oidc_provider_url` 출력 참조.
  runbook: `VictoryFairy_Infra/docs/domain-https-setup.md`.
  - **Ingress 는 2개다**(`k8s/22-ingress.yaml`): `victoryfairy-user`(`/api/member`) /
    `victoryfairy-quiz`(`/api/game`). `group.name: victoryfairy` 를 같게 줘서 **ALB 는 하나**로 묶인다.
    쪼갠 이유는 헬스체크 경로가 앱마다 다르기 때문 — `healthcheck-path` 는 Ingress 단위 어노테이션이라
    하나로는 두 값을 담을 수 없다.
  - ALB 헬스체크: `/api/member/actuator/health/readiness`, `/api/game/actuator/health/readiness`.
    ⚠ `/actuator/health` 전체가 아니라 **readiness 그룹**이다(전체는 db·redis 인디케이터를 합산해
    DOWN 을 내므로 MySQL EC2 가 흔들리면 멀쩡한 파드까지 타깃에서 빠진다).
  - ⚠ 레지스트라 NS: 도메인 재등록·이전 시 가비아 네임서버가 기본값으로 초기화된다. Route53 존 NS 4개로
    다시 등록해야 하며, 그동안 도메인 전체가 SERVFAIL 이 된다(2026-07-27 실제 발생).
  - ⚠ ALB 는 forward 시 경로 rewrite 를 못 한다(redirect 만 가능). Ingress path 와 앱의
    `server.servlet.context-path` 가 문자 그대로 일치해야 한다.

---

## 관련 위치
- (이 레포 `VictoryFairy_BE/`) `nginx.conf`, `docker-compose.prod.yml`, `docker-compose.yml`, `Dockerfile`, `infra/` 디렉터리, `docs/deployment-strategy.md`, `docs/cicd-runbook.md`
- (**상위 레포** `VictoryFairy/.github/workflows/deploy.yml`, CI/CD) — 이 레포(`VictoryFairy_BE/`)에는 `.github`가 없다. 저장소 루트가 한 단계 위임에 주의 (deploy.yml 자신도 이를 경고).

---

## 현재 인프라 상태
- **인스턴스**: EC2 `t3.small` (2 vCPU / 2GB RAM) — **교체 예정** (시기·후신 스펙 미정, 아래 세부는 교체 시 무효화됨)
  - 이 스펙이 `docker-compose.prod.yml`의 `mem_limit` 예산(user 500m + quiz 500m + **redis 64m** + nginx 128m = 1192m + OS/도커 여유, create는 주석 처리라 예산 미포함)의 근거다. 예산 총합이 호스트 RAM을 넘으면 호스트가 죽으므로, 서비스 추가/변경 시 이 값 기준으로 재계산할 것. **이 파일이 스펙의 유일한 출처**(compose-manager 등 에이전트 정의가 여기를 참조).
- **OS**: Amazon Linux 2023 (al2023)
- **리전**: ap-northeast-2 (서울)
- **사설 IP**: 10.0.0.5 / **VPC CIDR**: 10.0.0.0/24 (교체 시 변경될 수 있음)
- **인스턴스 ID**: i-0dba661111b28bfcf (교체 시 변경될 수 있음)

### 보안 그룹 (launch-wizard-2) — 인바운드
- 22 (SSH) / TCP / 0.0.0.0/0
- 80 (HTTP) / TCP / 0.0.0.0/0
- 443 (HTTPS) / TCP / 0.0.0.0/0

### nginx
- **컨테이너 전환 완료** (호스트 설치 → `docker-compose.prod.yml`의 `nginx` 서비스, `nginx:1.27-alpine`)
- 설정 파일 2개가 존재하며 용도가 갈린다:
  - `nginx.conf` (루트) — **현재 사용 중**. compose 컨테이너에 마운트되고, CI(`deploy.yml`)가 EC2로 scp하는 것도 이 파일뿐.
  - `infra/nginx/victoryfairy.conf` — **레거시**. EC2 호스트 nginx 시절 설정으로, 어떤 파이프라인에서도 참조하지 않음 (삭제 후보).
- 경로 라우팅(`nginx.conf`): `/api/member`→`user:8080` · `/api/game`→`quiz:8081`. 모듈 접두사 단위라 컨트롤러가 늘어도 location 은 그대로다(접두사는 각 앱의 `server.servlet.context-path` 가 붙인다). SSE 구독 경로(`~ ^/api/game/chat/rooms/[^/]+/subscribe$`)는 일반 `/api/game/chat` 블록보다 먼저 매치되는 별도 `location`으로 `proxy_buffering off`·`proxy_cache off`·`proxy_read_timeout 3600s`(앱 SSE 타임아웃 30분보다 여유)·`proxy_http_version 1.1`+keep-alive(`Connection ''`)를 준다. 이 블록에서 `proxy_set_header`를 하나라도 지정하면 서버 블록의 Host/X-Real-IP/X-Forwarded-For/X-Forwarded-Proto 상속이 통째로 끊기는 nginx 특성 때문에 4개 헤더를 전부 재선언한다. `/api/game/chat`이 배포되면 quiz의 첫 실동작 엔드포인트가 외부에 노출된다(이전엔 quiz에 컨트롤러가 없어 `/api/quiz`가 사실상 항상 404였음).

---

## 해결한 핵심 이슈: nginx·SSH 모두 접속 불가 (timeout)
**진짜 원인 (하나였음): 라우팅 테이블에 인터넷 게이트웨이(IGW) 경로가 없었음.**
- 서브넷이 사실상 프라이빗 상태 (`local` 경로만 존재)
- 부팅 시 EC2가 패키지 저장소(S3)에 접속 못 해 `dnf install nginx`가 30초 타임아웃 → 설치 실패
  (`No match for argument: nginx`는 "패키지 없음"이 아니라 "repo 메타데이터 다운로드 실패")
- 동시에 외부에서 SSH/HTTP 접근도 불가

**해결**
1. VPC에 **인터넷 게이트웨이(IGW)** 연결 확인/생성
2. 라우팅 테이블에 **`0.0.0.0/0 → igw-xxxxx`** 경로 추가
3. 이후 SSH 접속 성공 → nginx 정상 동작

**교훈**
- EC2 외부 접속 불가(timeout) 점검 순서: **보안그룹 22포트 → 퍼블릭 IP 유무 → 라우팅 테이블 IGW 경로 → 상태검사**
- 새 인스턴스는 **퍼블릭 서브넷(IGW 경로 보유) + 퍼블릭 IP 자동할당**으로 두면 user-data 스크립트가 그대로 동작

---

## 배포 파이프라인 알려진 갭
- **앱 health 엔드포인트** — *해소됨(2026-07-27)*. user/quiz 양쪽에 `spring-boot-starter-actuator` 를
  넣고 health 만 노출한다(`management.endpoints.web.exposure.include: health`,
  `management.endpoint.health.probes.enabled: true`). context-path 아래로 들어가므로 외부 경로는
  앱마다 갈린다 — `/api/member/actuator/health/readiness`, `/api/game/actuator/health/readiness`.
  SecurityConfig 의 죽은 `GET /health` permit 규칙(핸들러가 없어 항상 404였다)도 `/actuator/health/**` 로 교체했다.
  경위: 이 404 때문에 ALB 타깃 2개가 모두 `Target.ResponseCodeMismatch` 로 Unhealthy → 전면 503 이었다.
- **compose healthcheck 부재(redis 포함)** — *EC2+compose 경로에 한해 여전히 갭*. 앱에 health
  엔드포인트가 생겼으므로 이제 붙일 수 있게 됐지만, `docker-compose.prod.yml` 에는 아직 healthcheck 가
  없다. nginx 의 `/healthz` 는 nginx 자신이 200 을 반환할 뿐 백엔드를 보지 않는다. `redis` 서비스(이메일
  인증 상태 저장, `user` 가 의존)도 prod 에서는 healthcheck·조건부 `depends_on` 이 없다(로컬
  `docker-compose.yml` 은 `healthcheck` + `depends_on: redis: condition: service_healthy` 구성이라 prod 와 다름).
- **롤백 전략 없음**: CI가 `:latest`와 `:${{ github.sha }}` 둘 다 push하지만 EC2 배포 스크립트는 `IMAGE_TAG=latest` 고정이라 sha 태그를 쓸 방법이 없고, 배포 스크립트의 `docker image prune -f`가 EC2에 남은 이전 이미지를 지워버려 롤백용 이미지도 안 남는다.

## redis 서비스 (이메일 인증 상태 저장, user 전용)
- `redis:7.2-alpine`. 로컬(`docker-compose.yml`): `6379` 노출 + healthcheck(`redis-cli ping`), `user`가 `condition: service_healthy`로 대기. prod(`docker-compose.prod.yml`): 외부 미노출(포트 매핑 없음), `mem_limit: 64m`, healthcheck 없음(위 갭 참고), 영속 볼륨 없음(TTL 기반 휘발성 데이터라 재기동 시 초기화돼도 무방).
- `user` 서비스에 `SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT`(둘 다 `redis`/`6379`) 주입 — `quiz`는 redis 미의존.

## 이메일 발송 (Mailjet SMTP, user 전용, prod만)
- `docker-compose.prod.yml`의 `user` 서비스에 `MAIL_HOST=in-v3.mailjet.com`(하드코딩) / `MAIL_PORT=587`(STARTTLS, 하드코딩) / `MAIL_USERNAME`(Mailjet API Key)·`MAIL_PASSWORD`(Mailjet Secret Key, 시크릿, `.env`) / `MAIL_FROM`(기본값 `no-reply@victoryfairy.com`) 주입 — `SmtpEmailSender`(`@Profile("prod")`)가 이 값으로 이메일 인증번호를 실발송. dev/test는 `LogEmailSender`(mock)라 이 변수들 불필요. 발신 도메인 `victoryfairy.com`은 Mailjet에서 SPF/DKIM 인증이 필요(미인증 시 도달률 저하). 저장소 루트 `.env.example`에 Mailjet `MAIL_*` 템플릿 있음. 배포 가이드: `docs/deployment/email-verification-mailjet.md`.

---

## 비용 메모
| 동작 | 컴퓨팅 | EBS(디스크) | 데이터 |
|------|:---:|:---:|:---:|
| 실행 중 | 청구 | 청구 | 유지 |
| 중지(Stop) | 0원 | 청구(소액) | 유지 |
| 종료(Terminate) | 0원 | 0원 | 삭제 |

- 중지해도 **EBS 요금은 계속 나감**. 완전 0원은 종료(Terminate).
- 미사용 **탄력적 IP(EIP)는 과금**됨. EIP 없이 stop/start 하면 **퍼블릭 IP가 바뀜**.

---

## 로드맵

### STEP 1 — Docker로 nginx 띄우기 (완료)
운영에는 아래 단발 `docker run`이 아니라 `docker-compose.prod.yml`의 `nginx` 서비스로 정착함 (nginx 섹션 참고). 명령어는 학습 이력으로 남김.
```bash
# 호스트 nginx 제거
sudo systemctl stop nginx && sudo systemctl disable nginx
sudo dnf remove -y nginx && sudo rm -rf /etc/nginx /var/log/nginx /usr/share/nginx
sudo ss -tlnp | grep :80          # 80포트 비었는지 확인

# Docker 설치
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user  # 재로그인 후 sudo 없이 docker

# nginx 컨테이너 실행 + 확인
sudo docker run -d -p 80:80 --name web nginx
sudo docker ps && curl localhost
```

### STEP 2 — 컨테이너 개념 다지기
- 직접 `Dockerfile` 작성해 커스텀 이미지 빌드
- 자주 쓰는 명령: `docker ps -a`, `stop`, `start`, `rm -f`, `logs`

### STEP 3 — 쿠버네티스 개념 (로컬, 비용 0)
- **minikube** 또는 **kind**로 로컬 클러스터
- 핵심 오브젝트 4개: **Pod / Deployment / Service / ConfigMap·Secret**
- nginx Deployment 띄우고 kubectl 익히기

### STEP 4 — 멀티노드 클러스터 (EC2)
- **주의: control plane 최소 2vCPU/2GB 필요.** 현재 인스턴스(t3.small, 2vCPU/2GB)가 이 최소치를 충족하지만 교체 예정이므로, 후신 인스턴스도 최소 2vCPU/2GB는 유지해야 함
- kubeadm 직접 구축: control plane(`kubeadm init`) / worker(`kubeadm join`)용 EC2 준비 (최소 2vCPU/2GB)
- 또는 **EKS**(control plane을 AWS가 관리, 월 ~7-8만원)
- 노드 간 통신 포트(6443, 10250 등) 보안 그룹 개방 필요

### STEP 5 — HTTPS / 운영 (나중에)
- 실서비스는 443 + TLS 인증서 (보통 도메인 필요)
- 실무 패턴: **앞단(ALB/Ingress)에서 TLS 종료, 내부 컨테이너는 80**
  - AWS ALB + **ACM**(무료 인증서) / 쿠버네티스 **ingress-nginx** + cert-manager
- Helm, 모니터링(Prometheus/Grafana), CI/CD(GitHub Actions→ECR→EKS)

---

## 미결정 사항
- [x] 클러스터 방식: **EKS 채택** (managed control plane, `victoryfairy-dev`)
- [x] 도메인 보유: **`victoryfairy.com` 보유 확정** (Route53+ACM 으로 HTTPS 진행 — 위 "인프라 방향")
- [ ] EKS k8s **1.30 → 상위 버전 업그레이드** (연장 지원 과금 중 — 도메인/HTTPS 작업과 별개 선결 과제)
- [x] EC2+compose → EKS **컷오버 상태 확정**: 도메인(`https://victoryfairy.com`)은 **EKS 서빙**(2026-07-27 종단 확인)
- [ ] EC2+compose 파이프라인(`deploy.yml`) 정리 여부 — main push 마다 EKS 배포와 **함께** 돌고 있다
- [x] 앱 헬스 엔드포인트 구현 — **actuator readiness 도입 완료(2026-07-27)**, ALB 타깃 healthy 확인
- [ ] compose(EC2 경로) healthcheck 배선 — 앱 엔드포인트는 생겼으나 `docker-compose.prod.yml` 미반영
- [ ] 앱 개수 / 예상 트래픽 (스케일 상한 재산정)
