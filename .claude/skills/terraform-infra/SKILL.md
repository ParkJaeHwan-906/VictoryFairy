---
name: terraform-infra
description: >-
  VictoryFairy의 AWS 인프라를 Terraform으로 작성·수정할 때 사용합니다. EKS 기반 쿠버네티스
  컨테이너 워크로드(HPA 오토스케일·CronJob), 비용 절감을 위한 EC2 자체 호스팅 MySQL,
  SSM Session Manager 외부 접근, 모듈화, S3 원격 상태 관리, environments/modules 구조,
  다중 AZ, 최소 권한, 태깅 전략 등 이 프로젝트의 인프라 규약을 강제합니다. `.tf` 파일을
  만들거나 고치기 전, VPC/EKS/EC2/MySQL/ALB/IAM 리소스를 다루기 전, terraform plan/apply
  전에 읽으세요.
---

# VictoryFairy Terraform 인프라 스킬

VictoryFairy의 AWS 인프라는 **Terraform**으로 관리하며, **EKS 기반 쿠버네티스**
워크로드를 중심으로 구성됩니다. DB는 비용 절감을 위해 **EC2에 자체 호스팅한 MySQL 컨테이너**를
사용합니다. 인프라 코드를 작성/수정할 때 아래 규약을 반드시 따르세요.

이 규약은 HashiCorp 공식 [agent-skills](https://github.com/hashicorp/agent-skills)의
Terraform Style Guide / Module 모범 사례를 이 프로젝트에 맞게 추린 것입니다.

## 확정 아키텍처 요약
- **컴퓨트**: EKS(관리형 컨트롤플레인) + 프라이빗 서브넷 워커 노드, HPA/Cluster Autoscaler, CronJob
- **DB**: 별도 EC2(Small)에 MySQL 컨테이너 + EBS 영속 볼륨 (RDS 미사용, 비용 사유)
- **외부 접근**: SSM Session Manager 포트포워딩 (인바운드 22/3306 개방 없음)
- ⚠️ EKS 컨트롤플레인 월 ~$73 고정비 인지됨. EC2 MySQL은 자동 백업이 없어 백업 크론 필수.

---

## 1. 코드 구조: `environments/` 와 `modules/` 분리

재사용 로직은 `modules/`에, 환경별 조립·값은 `environments/`에 둡니다.
루트에서 리소스를 직접 선언하지 말고, 환경이 모듈을 조합하게 하세요.

```
VictoryFairy_Infra/
├── modules/                  # 재사용 가능한 빌딩 블록 (환경 독립적)
│   ├── network/              # VPC, 다중 AZ 퍼블릭/프라이빗 서브넷, NAT, 라우팅
│   ├── eks/                  # EKS 클러스터, 노드그룹, IRSA, 애드온
│   ├── mysql-ec2/            # MySQL용 EC2, EBS 볼륨, SSM 역할, 보안그룹
│   ├── alb/                  # Application Load Balancer / Ingress 관련
│   └── security/             # 공용 IAM 역할·정책, 보안그룹 정의
└── environments/             # 환경별 루트 (여기서 terraform 실행)
    ├── dev/
    │   ├── terraform.tf      # 버전·S3 백엔드 설정
    │   ├── providers.tf      # AWS 프로바이더 + default_tags
    │   ├── main.tf           # module 블록으로 조립
    │   ├── variables.tf
    │   ├── outputs.tf
    │   └── terraform.tfvars  # 환경 값 (커밋 금지)
    └── prod/
```

**규칙**
- `terraform init/plan/apply`는 **항상 `environments/<env>/` 안에서** 실행합니다.
- 모듈끼리 직접 참조하지 마세요. 의존성은 루트에서 output → input으로 연결합니다.
  (예: `module.network.private_subnet_ids` → `module.eks` / `module.mysql_ec2` 입력)
- 환경 간 차이는 **코드가 아니라 변수 값(tfvars)**으로 표현합니다.

### 파일 분리 규약 (모듈·환경 공통)
| 파일 | 내용 |
|------|------|
| `terraform.tf` | `required_version`, `required_providers`, `backend` |
| `providers.tf` | 프로바이더 설정, `default_tags` |
| `main.tf` | 리소스 / data source / module 블록 |
| `variables.tf` | 입력 변수 (알파벳 순) |
| `outputs.tf` | 출력 (알파벳 순) |
| `locals.tf` | 로컬 값 |

---

## 2. 상태 관리 (S3 원격 백엔드)

State는 **절대 Git에 커밋하지 않고**, S3 백엔드 + DynamoDB 락으로 관리합니다.
환경마다 `key`를 분리해 상태를 격리하세요.

```hcl
# environments/dev/terraform.tf
terraform {
  backend "s3" {
    bucket         = "victoryfairy-tfstate"
    key            = "dev/terraform.tfstate"   # 환경마다 key 분리 (prod/ 등)
    region         = "ap-northeast-2"
    dynamodb_table = "victoryfairy-tflock"     # 동시 apply 방지 락
    encrypt        = true                       # 저장 시 암호화
  }
}
```

**커밋 금지** (`.gitignore` 처리됨): `*.tfstate*`, `.terraform/`, `*.tfplan`, 시크릿 `*.tfvars`
**반드시 커밋**: 모든 `.tf`, `.terraform.lock.hcl`(버전 고정 → 재현성)

---

## 3. 변수 분리 & 정의 규약

- 모든 `variable`에는 **`description`과 `type`이 필수**입니다.
- 제약이 있는 입력은 `validation` 블록으로 방어합니다.
- 비밀값은 `sensitive = true`. 시크릿(DB 비밀번호 등)은 코드/기본값에 하드코딩하지 말고
  tfvars(커밋 금지)나 AWS Secrets Manager / SSM Parameter Store로 주입합니다.
- 모든 `output`에도 `description` 필수, 민감 출력은 `sensitive = true`.

```hcl
variable "environment" {
  description = "배포 환경"
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}
```

---

## 4. EKS 쿠버네티스 워크로드 규약

- **워커 노드는 프라이빗 서브넷**에만 배치합니다. 외부 트래픽은 ALB(또는 ALB Ingress
  Controller)를 통해서만 유입시킵니다.
- **오토스케일링 2계층**을 구분합니다.
  - **Pod 수평 확장**: HPA(Horizontal Pod Autoscaler) — CPU/메모리/커스텀 메트릭 기반.
  - **노드 확장**: Cluster Autoscaler(또는 Karpenter) — Pending 파드에 맞춰 노드 증감.
    노드그룹의 `min/desired/max` 크기를 변수로 노출합니다.
- **CronJob**은 쿠버네티스 `CronJob` 리소스로 정의합니다. Terraform은 EKS와 노드그룹까지
  프로비저닝하고, 워크로드(Deployment/CronJob/HPA) 매니페스트는 k8s 매니페스트/Helm으로
  관리하는 것을 기본으로 합니다. (인프라와 앱 배포 경계 분리)
- **IAM은 IRSA(IAM Roles for Service Accounts)**로 파드 단위 최소 권한을 부여합니다.
  노드 인스턴스 롤에 광범위한 권한을 몰아주지 마세요.
- 노드그룹 IAM은 필수 관리형 정책만: `AmazonEKSWorkerNodePolicy`,
  `AmazonEKS_CNI_Policy`, `AmazonEC2ContainerRegistryReadOnly`.
- 컨테이너 이미지 태그는 `latest` 금지 — 커밋 SHA 등 **불변 태그**를 사용합니다.

> **Spring 프로필**: Spring 컨테이너는 **`SPRING_PROFILES_ACTIVE=prod`** 로 동작해야
> 정상 기동합니다. 이는 **앱 런타임 설정**으로, k8s Deployment 매니페스트의 env(또는
> Helm values)에 지정합니다. Terraform 인프라 변수 `environment`(dev/prod, 상태·태그
> 구분용)와는 **다른 축**이며, 인프라가 `dev` 여도 컨테이너는 `prod` 프로필로 뜹니다.
> 두 값을 혼동해 인프라 env 를 바꾸지 마세요.

---

## 5. EC2 자체 호스팅 MySQL 규약 (RDS 미사용)

비용 절감을 위해 RDS 대신 EC2에 MySQL 컨테이너를 운영합니다. RDS가 해주던 것을 **직접
책임져야** 하므로 아래를 반드시 지킵니다.

- **데이터 영속성**: 컨테이너 임시 스토리지 금지. **별도 EBS 볼륨을 마운트**하고 MySQL
  데이터 디렉토리를 그 위에 둡니다. EBS는 `gp3`, 인스턴스와 라이프사이클 분리
  (`prevent_destroy` 등으로 실수 삭제 방지 고려).
- **백업 (필수)**: 자동 백업이 없습니다.
  - `mysqldump`(또는 Percona XtraBackup) **cron → S3 업로드**를 초기 구성합니다.
  - EBS 스냅샷(DLM 라이프사이클)을 병행합니다.
  - 이 백업 없이는 인스턴스/AZ 장애 = 데이터 유실입니다. 초기 필수 작업으로 취급하세요.
- **메모리**: t3.small(2GB)은 빠듯합니다. `innodb_buffer_pool_size` 튜닝, 스왑 대비.
- **가용성**: Multi-AZ 자동 failover 없음. 단일 AZ 다운을 감수하되 복구 절차(스냅샷
  복원)를 문서화합니다.

---

## 6. 외부/내부 접근 규약 (SSM Session Manager)

**raw SSH(22 포트 개방)를 사용하지 않습니다.** 개발자 접근은 **AWS Systems Manager
Session Manager**로 통일합니다.

- **인바운드 포트를 열지 않습니다** — 보안그룹에 22번(SSH) 및 3306(MySQL) 인입 규칙을
  개발자용으로 추가하지 마세요. 공격 표면을 만들지 않습니다.
- EC2에는 **`AmazonSSMManagedInstanceCore` IAM 역할**을 부여하고 SSM Agent를 사용합니다.
  (최신 Amazon Linux/Ubuntu AMI는 기본 탑재)
- 접근 권한은 **IAM으로 통제**하고, 세션은 CloudTrail로 감사합니다. 개발자 입퇴사 시
  SSH 키가 아니라 IAM 권한만 정리합니다.
- **MySQL은 포트 포워딩으로 접근**합니다:
  ```bash
  aws ssm start-session \
    --target i-0abc123... \
    --document-name AWS-StartPortForwardingSession \
    --parameters '{"portNumber":["3306"],"localPortNumber":["3306"]}'
  # 로컬 127.0.0.1:3306 으로 DB 클라이언트 접속
  ```
- **클러스터 내부 접근**: MySQL 보안그룹은 **EKS 노드 보안그룹에서 오는 3306만** 허용하고
  그 외 인입은 모두 차단합니다.

---

## 7. AWS 모범 사례

### 다중 AZ (고가용성)
- VPC 서브넷은 **최소 2개 이상의 AZ**에 걸쳐 생성합니다 (퍼블릭/프라이빗 각각).
- EKS 노드그룹은 여러 AZ의 프라이빗 서브넷에 노드를 분산합니다.
- ALB는 여러 AZ의 퍼블릭 서브넷에 걸칩니다.
- (MySQL은 비용상 단일 AZ. 위 §5의 백업으로 데이터 유실만이라도 방지)

### 최소 권한 원칙 (Least Privilege)
- IAM 정책은 필요한 **action과 resource ARN만** 명시합니다. `"*"` 와일드카드 지양.
- EKS는 IRSA(§4), EC2는 SSM 역할(§6)로 범위를 좁힙니다.
- 보안그룹은 필요한 포트/소스만 개방: ALB SG → 노드 SG로만 인입, MySQL SG는 노드
  SG에서 오는 3306만 허용. `0.0.0.0/0` 인입은 ALB의 80/443로 한정.

### 태깅 전략
- 프로바이더 `default_tags`로 **공통 태그를 전역 부여**하고, 리소스별 태그는 모듈 안에서
  `merge(var.tags, { Name = "..." })`로 병합합니다.
- 필수 태그: `Project=VictoryFairy`, `Environment`, `ManagedBy=Terraform`.
- EKS는 Cluster Autoscaler 발견용 태그(`k8s.io/cluster-autoscaler/<cluster>`,
  `k8s.io/cluster-autoscaler/enabled`)를 노드그룹/ASG에 부여해야 함에 유의합니다.

```hcl
provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "VictoryFairy"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}
```

---

## 8. 모듈 설계 원칙

- **단일 책임**: 한 모듈은 하나의 관심사만. 라이프사이클을 공유하는 리소스만 묶습니다.
- **명확한 인터페이스**: 입력은 타입·검증이 있는 변수로, 출력은 소비자에게 필요한 값만
  노출(내부 구현 누수 금지). 리스트보다 **맵**으로 반환하면 명확합니다.
- **과도한 추상화 금지**: `map(map(any))` 같은 범용 타입 대신 구체적인 `object({...})`.
- 유사 리소스 반복은 `count`가 아니라 **`for_each`**를 우선 사용합니다.
  (`count`는 `enable_x ? 1 : 0` 같은 조건부 생성에만)
- 상태 리팩터링 시 **`moved` 블록**을 사용하고, plan에 변경이 0으로 나오는지 확인합니다.

---

## 9. 커밋 전 검증 (필수)

`environments/<env>/`에서 apply/커밋 전에 실행:

```bash
terraform fmt -recursive      # 포맷 통일 (2칸 들여쓰기, = 정렬)
terraform validate            # 문법·참조 검증
terraform plan                # 변경 사항 리뷰 (특히 파괴적 변경 확인)
# 선택: tflint, checkov, tfsec 로 정적 보안 스캔
```

**변경은 반드시 `plan`으로 리뷰**한 뒤 apply하며, 리소스 삭제/교체(destroy/replace)가
포함되면 그 영향을 사용자에게 먼저 알립니다. 특히 MySQL EBS 볼륨은 절대 실수로 삭제되지
않도록 주의합니다.

---

## 요약 체크리스트
- [ ] `environments/`에서 실행, 리소스는 `modules/`에 캡슐화했는가
- [ ] 백엔드는 S3 + DynamoDB 락, `key`를 환경별로 분리했는가
- [ ] state/시크릿 tfvars가 `.gitignore`로 제외되는가
- [ ] 변수/출력에 `description`·`type`, 민감값에 `sensitive`가 있는가
- [ ] EKS 워커는 프라이빗 서브넷, IRSA로 파드 최소 권한인가
- [ ] HPA + Cluster Autoscaler 2계층 오토스케일이 반영됐는가
- [ ] MySQL EBS 영속 볼륨 + S3 백업 크론이 있는가
- [ ] 접근은 SSM 뿐인가 (22/3306 인입 개방 없음)
- [ ] 서브넷/노드그룹/ALB가 다중 AZ인가
- [ ] `default_tags` + Cluster Autoscaler 태그가 붙는가
- [ ] `fmt` / `validate` / `plan`을 돌렸는가
