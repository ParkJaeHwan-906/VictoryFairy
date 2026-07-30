# collector-lambda — KBO 수집기(Lambda) 스택

py-collector(dev_ai, `VictoryFairy_AI/py-collector`)를 운영하는 서버리스 수집 스택.
`VictoryFairy_AI/py-collector/deploy/lambda/terraform` 에서 이관 — **크롤러 코드는
dev_ai, 인프라 정의는 dev_infra** 로 소유를 나눴다.

ECR 리포 하나의 이미지를 Lambda 함수 두 개가 공유한다:

| 함수 | 위치 | 잡 (EventBridge) |
|---|---|---|
| `kbo-collector` | VPC 밖 | community `rate(10 minutes)` · game 03:00 KST → S3 적재 |
| `kbo-collector-db` | VPC 안(프라이빗 서브넷) | records 03:30 KST · registrations 11:00 KST → 운영 MySQL 적재 |

함수를 나눈 이유, 잡별 상세·수동 실행·백필은
[`VictoryFairy_AI/py-collector/deploy/lambda/README.md`](../../VictoryFairy_AI/py-collector/deploy/lambda/README.md)(dev_ai) 참고.

## 배포 = 머지 (CI)

파이프라인 두 개가 브랜치별로 분리돼 있고, **ECR 이미지가 유일한 접점**이다:

```
dev_ai 머지 (py-collector/** 변경)
  └→ collector-image.yml     : docker build(arm64) → ECR :latest 푸시 → 두 함수 코드 갱신
dev_infra 머지 (collector-lambda/** 변경)
  └→ collector-terraform.yml : terraform plan → apply   (PR 단계는 fmt/validate 만)
```

- 이 스택은 이미지를 **빌드하지 않는다.** `data.aws_ecr_image` 로 그 시점 `:latest`
  다이제스트를 읽어 함수에 핀할 뿐이라, py-collector 소스 없이(dev_infra 단독
  체크아웃·CI 러너) plan/apply 가 된다.
- CI 의 AWS 로그인은 OIDC 롤(`ci.tf`) — 장기 액세스키 없음, 브랜치 단위 신뢰:
  `kbo-collector-image-ci`(dev_ai) / `kbo-collector-terraform-ci`(dev_infra).

## 설정·비밀 관리

| 무엇 | 어디 | 비고 |
|---|---|---|
| state | S3 `victoryfairy-tfstate/collector-lambda/` + DynamoDB 락 | environments/dev 와 같은 버킷 관례 |
| 비밀 아닌 설정 | `config.auto.tfvars` (커밋됨) | 서브넷/SG/db_host 등 — CI·로컬 공용 |
| 비밀 2개 | `db_password`, `pii_salt` | CI: GitHub Secrets `TF_VAR_db_password`·`TF_VAR_pii_salt` / 로컬: gitignore 된 `terraform.tfvars` |

⚠ `pii_salt` 는 커뮤니티 작성자 마스킹의 연속성을 좌우하는 **복구 불가능한 비밀** —
비밀번호 관리자에 백업해 둘 것. state 에도 평문으로 들어가므로 state 버킷 접근 권한이
곧 비밀 접근 권한이다.

## 수동 실행 (부트스트랩·비상용)

평상시엔 필요 없다. 로컬에서 돌릴 일이 있으면:

```bash
cd VictoryFairy_Infra/collector-lambda
cat > terraform.tfvars <<'EOF'   # 비밀 2개만 (나머지는 config.auto.tfvars)
db_password = "..."
pii_salt    = "..."
EOF
terraform init     # 원격 state 자동 연결 (로컬 state 에서 전환 시 -migrate-state)
terraform plan -out=tfplan && terraform apply tfplan
```

새 환경 부트스트랩 순서(이미지가 없으면 `data.aws_ecr_image` 조회가 실패하므로):
ECR 리포만 targeted apply → 이미지 CI 1회(workflow_dispatch) → 전체 apply.

## 다른 스택과의 접점

- **environments/dev 소유 값 입력**: 프라이빗 서브넷 id, VPC id, 데이터 EC2(MySQL) SG id·
  프라이빗 IP 를 `config.auto.tfvars` 로 받는다. 데이터 EC2 재생성 시 `db_host` 와
  k8s Endpoints(`k8s/30-external-data.yaml`) 둘 다 갱신.
- **MySQL SG 에 규칙 추가**: 이 스택이 데이터 EC2 SG 에 "collector Lambda SG → 3306"
  인바운드 규칙 하나를 단다(`lambda_db.tf` 의 `mysql_from_db_lambda`). environments/dev
  쪽에서 같은 규칙을 중복 선언하지 말 것.
- **버킷은 안 만든다**: `data_bucket_name` S3 버킷은 선행 존재해야 한다.

> CI 실행 이력은 GitHub Actions 의 `collector-terraform` 워크플로에서 확인한다.
