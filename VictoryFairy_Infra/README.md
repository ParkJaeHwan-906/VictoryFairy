# VictoryFairy Infra

Terraform 으로 관리하는 VictoryFairy 의 AWS 인프라 코드입니다.
상세 규약은 리포지토리 루트의 `.claude/skills/terraform-infra/SKILL.md`,
확정 설계와 그 근거는 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 를 따릅니다.

## 아키텍처
- **네트워크**: VPC `10.0.0.0/16`. 서브넷은 2 AZ(2a/2c)에 선언하되 **노드·DB는 2a 집중, 2c는 예비**
  (2 AZ는 HA가 아니라 EKS 강제 요건 — DB가 단일 AZ라 앱만 벌리면 반쪽 HA. 자세한 근거는 ARCHITECTURE.md)
- **앱 컴퓨트**: EKS(관리형) 프라이빗 서브넷. **노드그룹 3개로 분리** — `user`(안정), `quiz`(taint 격리 + HPA/Cluster Autoscaler 오토스케일), `batch`(Spot·min0, 야간 전용)
- **DB**: **단일 고정 EC2**(비 EKS)에 MySQL + Redis 컨테이너 + EBS 영속 볼륨 (RDS 미사용, 비용 사유)
  - Redis 는 채팅·퀴즈·인증 **서비스 브로커 전용**. 앱과 격리, 스케일아웃 없음(부족 시 수직 승급)
  - MySQL 데이터는 **하루 단위로 S3 백업** (+ EBS 스냅샷 병행 권장)
- **배치**: 매일 03:15 KST, **Spot xlarge 노드그룹 0→N→0**. 크롤→정제→생성 스트리밍 파이프라인
  (S3 개수 트리거, 배치 전용 임시 Redis 로 상태 관리 → ARCHITECTURE.md §4)
- **외부 접근**: SSM Session Manager 포트포워딩 (인바운드 22/3306 개방 없음)

## 디렉토리 구조

```
VictoryFairy_Infra/
├── docs/
│   └── ARCHITECTURE.md       # 확정 설계 + 결정 근거 (ADR 성격)
├── modules/                  # 재사용 가능한 빌딩 블록 (환경 독립적)
│   ├── network/              # VPC, 2 AZ 서브넷(2a 운영/2c 예비), NAT, 라우팅
│   ├── eks/                  # EKS 클러스터, 노드그룹 3개(user/quiz/batch), IRSA
│   ├── mysql-ec2/            # MySQL+Redis EC2 + EBS + SSM + 일 단위 S3 백업
│   └── security/             # 공용 IAM/보안그룹
└── environments/             # 환경별 루트 (여기서 terraform 실행)
    ├── dev/
    └── prod/
```

## 시작하기

```bash
cd environments/dev

# 1. 변수 파일 준비
cp terraform.tfvars.example terraform.tfvars   # 값 채우기 (커밋 금지)

# 2. (최초 1회) 백엔드용 S3 버킷 + DynamoDB 락 테이블 생성 후
#    terraform.tf 의 backend "s3" 주석 해제

# 3. 초기화 → 미리보기 → 적용
terraform init
terraform plan
terraform apply
```

## MySQL 접근 (SSM 포트포워딩)

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
- 커밋/적용 전 `terraform fmt -recursive && terraform validate && terraform plan` 을 실행합니다.
