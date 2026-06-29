# infra 모듈 (배포 · 인프라 학습)

> 이 파일은 infra/배포 작업 시에만 로드되는 슬림 컨텍스트다.
> EC2 → Docker → Kubernetes 단계적 학습 + 이 백엔드의 실제 배포(nginx, docker-compose.prod, deploy.yml)를 다룬다.
> 최종 업데이트: 2026-06-26

## 관련 위치 (이 레포)
- `nginx.conf`, `docker-compose.prod.yml`, `docker-compose.yml`, `Dockerfile`
- `infra/` 디렉터리
- `.github/workflows/deploy.yml` (CI/CD)
- `docs/deployment-strategy.md`, `docs/cicd-runbook.md`

---

## 현재 인프라 상태
- **인스턴스**: EC2 `t2.micro` (1 vCPU / 1GB RAM)
- **OS**: Amazon Linux 2023 (al2023)
- **리전**: ap-northeast-2 (서울)
- **사설 IP**: 10.0.0.5 / **VPC CIDR**: 10.0.0.0/24
- **인스턴스 ID**: i-0dba661111b28bfcf

### 보안 그룹 (launch-wizard-2) — 인바운드
- 22 (SSH) / TCP / 0.0.0.0/0
- 80 (HTTP) / TCP / 0.0.0.0/0
- 443 (HTTPS) / TCP / 0.0.0.0/0

### nginx
- 호스트 설치(`dnf install nginx`) → Docker 컨테이너 전환 작업 중

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

### STEP 1 — Docker로 nginx 띄우기 (다음 차례)
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
- **주의: t2.micro는 control plane 불가** (최소 2vCPU/2GB → `t3.small` 이상)
- kubeadm 직접 구축: 신규 EC2(t3.small+) → control plane(`kubeadm init`) / worker(`kubeadm join`)
- 또는 **EKS**(control plane을 AWS가 관리, 월 ~7-8만원)
- 노드 간 통신 포트(6443, 10250 등) 보안 그룹 개방 필요

### STEP 5 — HTTPS / 운영 (나중에)
- 실서비스는 443 + TLS 인증서 (보통 도메인 필요)
- 실무 패턴: **앞단(ALB/Ingress)에서 TLS 종료, 내부 컨테이너는 80**
  - AWS ALB + **ACM**(무료 인증서) / 쿠버네티스 **ingress-nginx** + cert-manager
- Helm, 모니터링(Prometheus/Grafana), CI/CD(GitHub Actions→ECR→EKS)

---

## 미결정 사항
- [ ] 쿠버네티스 목적: 학습용 vs 실서비스
- [ ] 클러스터 방식: kubeadm 직접 구축 vs EKS
- [ ] 도메인 보유 여부 (HTTPS 진행 시 필요)
- [ ] 앱 개수 / 예상 트래픽 (쿠버네티스가 오버스펙인지 판단)
