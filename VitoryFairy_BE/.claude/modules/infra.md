# infra 모듈 (배포 · 인프라 학습)

> 이 파일은 infra/배포 작업 시에만 로드되는 슬림 컨텍스트다.
> EC2 → Docker → Kubernetes 단계적 학습 + 이 백엔드의 실제 배포(nginx, docker-compose.prod, deploy.yml)를 다룬다.
> 최종 업데이트: 2026-07-15

## 관련 위치
- (이 레포 `VitoryFairy_BE/`) `nginx.conf`, `docker-compose.prod.yml`, `docker-compose.yml`, `Dockerfile`, `infra/` 디렉터리, `docs/deployment-strategy.md`, `docs/cicd-runbook.md`
- (**상위 레포** `VictoryFairy/.github/workflows/deploy.yml`, CI/CD) — 이 레포(`VitoryFairy_BE/`)에는 `.github`가 없다. 저장소 루트가 한 단계 위임에 주의 (deploy.yml 자신도 이를 경고).

---

## 현재 인프라 상태
- **인스턴스**: EC2 `t3.small` (2 vCPU / 2GB RAM) — **교체 예정** (시기·후신 스펙 미정, 아래 세부는 교체 시 무효화됨)
  - 이 스펙이 `docker-compose.prod.yml`의 `mem_limit` 예산(앱 N개 × 500m + nginx 128m + OS/도커 여유)의 근거다. 예산 총합이 호스트 RAM을 넘으면 호스트가 죽으므로, 서비스 추가/변경 시 이 값 기준으로 재계산할 것. **이 파일이 스펙의 유일한 출처**(compose-manager 등 에이전트 정의가 여기를 참조).
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
- 경로 라우팅(`nginx.conf`): `/api/auth`→`user:8080` · `/api/quiz`, `/api/chat`→`quiz:8081`. SSE 구독 경로(`~ ^/api/chat/rooms/[^/]+/subscribe$`)는 일반 `/api/chat` 블록보다 먼저 매치되는 별도 `location`으로 `proxy_buffering off`·`proxy_cache off`·`proxy_read_timeout 3600s`(앱 SSE 타임아웃 30분보다 여유)·`proxy_http_version 1.1`+keep-alive(`Connection ''`)를 준다. 이 블록에서 `proxy_set_header`를 하나라도 지정하면 서버 블록의 Host/X-Real-IP/X-Forwarded-For/X-Forwarded-Proto 상속이 통째로 끊기는 nginx 특성 때문에 4개 헤더를 전부 재선언한다. `/api/chat`이 배포되면 quiz의 첫 실동작 엔드포인트가 외부에 노출된다(이전엔 quiz에 컨트롤러가 없어 `/api/quiz`가 사실상 항상 404였음).

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
- **헬스체크 부재**: `docker-compose.prod.yml`에 healthcheck 없음. nginx의 `/healthz`는 nginx 자신이 200을 반환할 뿐 백엔드를 보지 않는다. user/quiz의 SecurityConfig에는 `GET /health` permit 규칙만 있고 이를 처리하는 컨트롤러/actuator가 **아예 없어** 실제 호출 시 404 — nginx 라우팅 노출 여부와 무관하게 **운영 앱이 실제로 살아있는지 확인할 수단 자체가 없다.** 선결 과제는 nginx 노출이 아니라 health 엔드포인트 구현.
- **롤백 전략 없음**: CI가 `:latest`와 `:${{ github.sha }}` 둘 다 push하지만 EC2 배포 스크립트는 `IMAGE_TAG=latest` 고정이라 sha 태그를 쓸 방법이 없고, 배포 스크립트의 `docker image prune -f`가 EC2에 남은 이전 이미지를 지워버려 롤백용 이미지도 안 남는다.

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
- [ ] 인스턴스 교체: 시기 / 후신 스펙 (현재 t3.small, 2vCPU/2GB)
- [ ] 쿠버네티스 목적: 학습용 vs 실서비스
- [ ] 클러스터 방식: kubeadm 직접 구축 vs EKS
- [ ] 도메인 보유 여부 (HTTPS 진행 시 필요)
- [ ] 앱 개수 / 예상 트래픽 (쿠버네티스가 오버스펙인지 판단)
