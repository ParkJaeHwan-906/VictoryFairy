# collector-lambda — KBO 수집기(Lambda) 스택

py-collector(dev_ai, `VictoryFairy_AI/py-collector`)를 운영하는 서버리스 수집 스택.
`VictoryFairy_AI/py-collector/deploy/lambda/terraform` 에 있던 것을 이 리포(dev_infra)로
이관했다 — **크롤러 코드는 dev_ai, 인프라 정의는 dev_infra** 로 소유를 나누기 위함.

ECR 리포 하나에 이미지 하나를 빌드·푸시하고, 같은 이미지로 Lambda 함수 두 개를 돌린다:

| 함수 | 위치 | 잡 (EventBridge) |
|---|---|---|
| `kbo-collector` | VPC 밖 | community `rate(10 minutes)` · game 03:00 KST → S3 적재 |
| `kbo-collector-db` | VPC 안(프라이빗 서브넷) | records 03:30 KST · registrations 11:00 KST → 운영 MySQL 적재 |

함수를 나눈 이유, 잡별 상세·수동 실행·백필 방법은
[`VictoryFairy_AI/py-collector/deploy/lambda/README.md`](../../VictoryFairy_AI/py-collector/deploy/lambda/README.md)(dev_ai) 참고.

## ⚠ 소스 경로 (`collector_src`) — 이 스택의 유일한 함정

이미지는 **apply 시점에 로컬 py-collector 소스를 docker build** 해서 만든다
(`ecr.tf` 의 `null_resource.image` → `build_and_push.sh`). 그런데 dev_infra 브랜치에는
`VictoryFairy_AI` 가 없다. 따라서:

- **main 처럼 두 디렉토리가 나란히 있는 체크아웃**에서 apply 하면 기본 경로
  (`../../VictoryFairy_AI/py-collector`)가 그대로 동작한다. ← 권장
- **dev_infra 단독 체크아웃**이면 tfvars 에 `collector_src` 로 py-collector **절대경로**를
  지정해야 plan/apply 가 된다.
- 어느 쪽이든 **그 경로에 있는 코드가 그대로 배포된다.** 오래된 브랜치를 가리키면 옛
  코드가 이미지로 구워진다 — 배포 전 해당 체크아웃이 최신인지 확인할 것.
  (추후 CI 에서 이미지 빌드를 분리하면 없어질 제약)

## 배포

```bash
cd VictoryFairy_Infra/collector-lambda
cp terraform.tfvars.example terraform.tfvars   # 값 채우기 (커밋 금지)
terraform init
terraform apply    # ECR 생성 → 이미지 빌드/푸시(Docker 필요) → Lambda + 스케줄
```

- 코드 변경 후 재배포도 `terraform apply` 한 번 — 소스 해시가 바뀌면 이미지를 재빌드하고
  다이제스트 핀으로 Lambda 가 같은 apply 안에서 갱신된다(`ecr.tf` 주석 참고).
- 배포 자격증명에 필요한 권한 목록: `deployer-iam-policy.json`.

## 상태·비밀 (로컬 관리 중)

`terraform.tfstate` / `terraform.tfvars` 는 gitignore 이며 현재 **로컬 파일**로 관리한다
(운영 스택은 소태호 로컬). 디렉토리 이관 시 이 두 파일을 새 경로로 같이 옮기면 된다 —
state 는 리소스 주소 기반이라 디렉토리가 바뀌어도 그대로 인식한다.
tfstate/tfvars 에는 DB 비밀번호·PII salt 가 평문으로 들어 있으니 백업은 비밀번호
관리자에. (추후: S3 backend + SSM Parameter Store 로 전환 예정)

## 다른 스택과의 접점

- **environments/dev 소유 값 입력**: 프라이빗 서브넷 id, VPC id, 데이터 EC2(MySQL) SG id·
  프라이빗 IP 를 tfvars 로 받는다(`terraform.tfvars.example` 주석 참고). 데이터 EC2 재생성
  시 `db_host` 와 k8s Endpoints(`k8s/30-external-data.yaml`) 둘 다 갱신.
- **MySQL SG 에 규칙 추가**: 이 스택이 데이터 EC2 SG 에 "collector Lambda SG → 3306"
  인바운드 규칙 하나를 단다(`lambda_db.tf` 의 `mysql_from_db_lambda`). environments/dev
  쪽에서 같은 규칙을 중복 선언하지 말 것.
- **버킷은 안 만든다**: `data_bucket_name` S3 버킷은 선행 존재해야 한다.
