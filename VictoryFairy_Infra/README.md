# VictoryFairy Infra

Terraform 으로 관리하는 VictoryFairy 의 AWS 인프라 코드입니다.
상세 규약은 리포지토리 루트의 `.claude/skills/terraform-infra/SKILL.md` 를 따릅니다.

## 아키텍처
- **컴퓨트**: EKS(관리형) + 프라이빗 서브넷 워커 노드, HPA / Cluster Autoscaler, CronJob
- **DB**: 별도 EC2(Small)에 MySQL 컨테이너 + EBS 영속 볼륨 (RDS 미사용, 비용 사유)
  - MySQL 데이터는 **하루 단위로 S3 백업** (+ EBS 스냅샷 병행 권장)
- **외부 접근**: SSM Session Manager 포트포워딩 (인바운드 22/3306 개방 없음)

## 디렉토리 구조

```
VictoryFairy_Infra/
├── modules/                  # 재사용 가능한 빌딩 블록 (환경 독립적)
│   ├── network/              # VPC, 다중 AZ 서브넷, NAT, 라우팅
│   ├── eks/                  # EKS 클러스터, 노드그룹, IRSA
│   ├── mysql-ec2/            # MySQL EC2 + EBS + SSM + 일 단위 S3 백업
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
