# Lambda 배포 (EventBridge 스케줄 크롤링)

호출한 만큼만 과금되는 서버리스 자동 크롤링. 두 개의 EventBridge 스케줄이 하나의
컨테이너-이미지 Lambda를 호출합니다.

- **community** — `rate(10 minutes)` → `{"job":"community"}` : 새 글만 증분 수집
- **game** — `cron(0 18 * * ? *)`(03:00 KST) → `{"job":"game"}` : schedule→result→relay
  - 03:00 KST에 돌면 전날 저녁(KST) 끝난 경기가 대상 — 그 시각 UTC 날짜가 곧 경기 KST 날짜라 자동 정합.

핸들러(`handler.py`)는 코어(`kbo_collector.run.land_*`)를 그대로 호출하는 얇은 어댑터입니다.
`lxml` 네이티브 의존성 때문에 **컨테이너 이미지**(ECR)로 배포합니다.

## 사전 준비
- Docker, AWS CLI, Terraform, 그리고 **ECR/Lambda/EventBridge/IAM 생성 권한**이 있는 자격증명
  (권한 목록: `terraform/deployer-iam-policy.json`).
- 적재 대상 S3 버킷(`data_bucket_name`)은 이미 존재해야 함.

## 배포
```bash
cd deploy/lambda/terraform
cp terraform.tfvars.example terraform.tfvars     # data_bucket_name 등 확인
terraform init
terraform apply       # ECR 생성 -> 이미지 빌드/푸시(local-exec) -> Lambda + 스케줄
```
> `terraform apply`가 `build_and_push.sh`를 자동 실행해 이미지를 빌드·푸시합니다(Docker 필요).
> Apple Silicon이면 `architecture = "arm64"`(기본)로 네이티브 빌드 + 저렴.

## 즉시 한 번 돌려보기 / 확인
```bash
# 수동 1회 실행
terraform output -raw invoke_community_now | bash
terraform output -raw invoke_game_now | bash        # 오늘(UTC) 경기; 백필은 payload에 "date" 추가

# 적재 확인
aws s3 ls s3://victoryfairy-crawl-local/community/ --recursive | tail
# 로그
aws logs tail /aws/lambda/kbo-collector --follow
```
스케줄은 apply 직후부터 동작합니다(community 10분마다, game 매일 03:00 KST).

> **DB 잡은 여기서 안 돎**: `records`·`registrations`·`export`는 MySQL(SSH 터널 너머)에 써서
> Lambda에서 접근 불가. 이 스케줄은 **S3에만 쓰는** community·game 전용이며, DB 잡은 DB가
> 있는 서버의 cron에 별도로 거는 게 맞습니다.

## 코드 바꾼 뒤 재배포
```bash
terraform apply    # 핸들러/코어/의존성 변경 감지 -> 이미지 재빌드·푸시 -> Lambda 갱신
```
> `apply` **한 번**으로 끝납니다. `image_uri`를 `:latest` 태그가 아니라 방금 푸시한
> 이미지의 **다이제스트**(`@sha256:...`, `data.aws_ecr_image`로 조회)에 고정하므로,
> 코드가 바뀌면 다이제스트가 바뀌어 같은 apply 안에서 Lambda가 새 이미지로 갱신됩니다.
> (`:latest`로 고정하면 문자열이 안 바뀌어 Terraform이 갱신을 건너뛰고 옛 이미지가
> 계속 돕니다 — 그래서 다이제스트 핀을 씁니다. 수동 `update-function-code` 불필요.)

## 내리기 (과금 중단)
```bash
terraform destroy
```
> Lambda/EventBridge는 **호출당 과금**이라 유휴 비용이 거의 없지만, 완전히 정리하려면 destroy.
> ECR은 `force_delete=true`라 이미지가 있어도 삭제됩니다.

## 참고
- 크롤링 플로우: [`../../docs/crawl-flow.md`](../../docs/crawl-flow.md)
- Lambda/EventBridge는 **호출한 만큼만** 과금이라 상시 운영에 가장 저렴합니다.
