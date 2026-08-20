# VictoryFairy Infra

Terraform 으로 관리하는 VictoryFairy 의 AWS 인프라 코드입니다.
상세 규약은 리포지토리 루트의 `.claude/skills/terraform-infra/SKILL.md`,
확정 설계와 그 근거는 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 를 따릅니다.

## 아키텍처
- **네트워크**: VPC `10.0.0.0/16`. 서브넷은 2 AZ(2a/2c)에 선언하되 **노드·DB는 2a 집중, 2c는 예비**
  (2 AZ는 HA가 아니라 EKS 강제 요건 — DB가 단일 AZ라 앱만 벌리면 반쪽 HA. 자세한 근거는 ARCHITECTURE.md)
- **앱 컴퓨트**: EKS(관리형) 프라이빗 서브넷. **노드그룹 2개** — `app`(user+quiz 공용, 둘 다 HPA/Cluster Autoscaler 오토스케일), `batch`(Spot·min0, 야간 전용)
- **DB**: **단일 고정 EC2**(비 EKS)에 MySQL + Redis 컨테이너 + EBS 영속 볼륨 (RDS 미사용, 비용 사유)
  - Redis 는 채팅·퀴즈·인증 **서비스 브로커 전용**. 앱과 격리, 스케일아웃 없음(부족 시 수직 승급)
  - MySQL 데이터는 **하루 단위로 S3 백업** (+ EBS 스냅샷 병행 권장)
- **정제**: **서버리스**. 크롤(Lambda, 상시) → S3 이벤트 → 패턴 검열(Lambda) → SQS → LLM 검열(Lambda, AWS Bedrock).
  트리거가 인프라 부품이라 **폴링 컨트롤러가 없다**. 산출물은 **S3 에서 끝난다** — MySQL 을 쓰지 않는다 → ARCHITECTURE.md §4
- **batch 노드그룹**(Spot·min0): **정제에는 쓰이지 않는다.** 문제 생성 단계용으로 보류
- **배포**: BE 는 `deploy-eks.yml`(ECR → EKS 블루-그린), AI 정제 이미지는 `deploy-ai.yml`
  (ECR → Lambda `update-function-code`). 컨테이너 Lambda 는 태그를 digest 로 고정해서
  **push 만으로는 반영되지 않는다** → [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)
- **외부 접근**: EKS 노드 SSH·DB 셸은 SSM Session Manager(인바운드 22 개방 없음), EKS API는 퍼블릭 엔드포인트+IAM 인증(`kubectl`) — 절차는 [`scripts/README.md`](scripts/README.md)
  - ⚠ **운영 DB 는 2026-07-27부터 퍼블릭 서브넷 + EIP 로 직접 접속(3306·6379)도 열려 있다.** 인입은 지정 CIDR `/32` 하나뿐이며 **안정화 후 프라이빗 복귀 예정인 임시 구성**이다(되돌리면 인스턴스 재생성). SSM 경로는 그대로 유효 → ARCHITECTURE.md §3

## 디렉토리 구조

```
VictoryFairy_Infra/
├── docs/
│   └── ARCHITECTURE.md       # 확정 설계 + 결정 근거 (ADR 성격)
├── modules/                  # 재사용 가능한 빌딩 블록 (환경 독립적)
│   ├── network/              # VPC, 2 AZ 서브넷(2a 운영/2c 예비), NAT, 라우팅
│   ├── eks/                  # EKS 클러스터, 노드그룹 2개(app/batch), IRSA
│   ├── mysql-ec2/            # 운영 MySQL+Redis EC2 + EBS + SSM + 일 단위 S3 백업
│   ├── dev-db/               # 개발용 DB EC2(퍼블릭·/32) — 운영 백업을 매일 restore 하는 복제본
│   ├── alb/                  # AWS Load Balancer Controller 용 IRSA (컨트롤러 파드는 Helm)
│   ├── dns/                  # Route53 존 + ACM 인증서 + ExternalDNS IRSA
│   ├── cdn/                  # FE 정적 버킷(S3) + CloudFront (nginx 파드 대체, asset 오리진 배선 포함)
│   ├── asset/                # 사용자 업로드 버킷(프로필 이미지) — 퍼블릭 차단, CloudFront(OAC)만 읽음
│   ├── ecr/                  # 앱 이미지 저장소(IMMUTABLE·scan_on_push)
│   ├── refine-pipeline/      # 서버리스 정제 — Lambda 2개 + SQS/DLQ + DynamoDB + S3 이벤트
│   ├── quiz-irsa/            # quiz-app 파드용 IRSA (S3 quiz-candidates 읽기 전용)
│   ├── user-irsa/            # user-app 파드용 IRSA (asset 버킷 temp/·user-profile-img/ 읽기·쓰기·삭제)
│   ├── fe-watchdog/          # 헬스체크 Lambda + 알람 → FE 자동 롤백 + Slack 알림
│   └── security/             # 공용 IAM/보안그룹 (CI 배포 권한 포함)
├── collector-lambda/         # KBO 수집기(Lambda+ECR) 독립 스택 — 소스는 dev_ai py-collector
└── environments/             # 환경별 루트 (여기서 terraform 실행)
    ├── dev/
    └── prod/
```

## 시작하기

```bash
cd environments/dev

# 1. 변수 파일 준비
cp terraform.tfvars.example terraform.tfvars   # 값 채우기 (커밋 금지)

# 2. 초기화 → 미리보기 → 적용
#    state 는 S3 원격 백엔드(victoryfairy-tfstate)에 있다. init 이 "전체 신규 생성"
#    plan 을 내면 백엔드가 안 붙은 것이니 apply 하지 말 것 → docs/STATE.md
terraform init
terraform plan
terraform apply
```

## MySQL 접근 (SSM 포트포워딩)

> 팀원 온보딩·EKS 노드 SSH·kubectl 등 전체 접속 절차는
> [`scripts/README.md`](scripts/README.md)를 따라 하세요. 아래는 터널의 원리(수동 명령)입니다.

```bash
aws ssm start-session \
  --target <mysql_instance_id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3306"],"localPortNumber":["3306"]}'
# 이후 로컬 127.0.0.1:3306 으로 DB 클라이언트 접속
```

## 주의사항
- `*.tfstate`, `*.tfvars`(시크릿), `.terraform/` 는 커밋하지 않습니다 (`.gitignore` 처리됨).
- `.terraform.lock.hcl` 은 버전 고정을 위해 커밋합니다.
- MySQL EBS 데이터 볼륨은 실수 삭제 방지(`prevent_destroy`) 대상이며, 일 단위 S3 백업이 없으면
  인스턴스/AZ 장애 시 데이터가 유실됩니다.
- Kubernetes Dashboard는 2026-08-07 제거했습니다(미사용). cluster-admin 권한을 가진 학습용
  `admin-user` 계정도 함께 사라졌습니다 — 다시 들일 때는 그 권한 범위를 좁혀서 넣으세요.
- 커밋/적용 전 `terraform fmt -recursive && terraform validate && terraform plan` 을 실행합니다.
