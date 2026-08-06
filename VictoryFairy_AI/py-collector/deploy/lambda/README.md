# Lambda 배포 (EventBridge 스케줄 크롤링 + DB 적재)

호출한 만큼만 과금되는 서버리스 자동 수집. EventBridge 스케줄이 **같은 이미지를 쓰는
두 개의 Lambda 함수**를 호출합니다.

`kbo-collector` (S3 잡, VPC 밖):
- **community** — `rate(10 minutes)` → `{"job":"community"}` : 새 글만 증분 수집
- **game** — `cron(0 18 * * ? *)`(03:00 KST) → `{"job":"game"}` : schedule→result→relay
  - 03:00 KST에 돌면 전날 저녁(KST) 끝난 경기가 대상 — 그 시각 UTC 날짜가 곧 경기 KST 날짜라 자동 정합.

`kbo-collector-db` (DB 잡, VPC 안 — `db_subnet_ids` 설정 시에만 생성):
- **records** — `cron(30 18 * * ? *)`(03:30 KST) → `{"job":"records"}` : 완료 경기 → games/game_lineups
- **registrations** — `cron(0 2 * * ? *)`(11:00 KST) → `{"job":"registrations"}` : KBO 1군 등록명단 → players
- **games_sync** — `{"job":"games_sync"}` : 당일 KBO 경기 전부의 상태(SCHEDULED/LIVE/종료/취소) →
  games 동기화. 스케줄 제안(**테라폼 미적용 — 아래 두 줄은 문서 기록용, infra 반영 보류**):
  - 아침 동기화: `cron(0 23 * * ? *)`(08:00 KST) — 당일 SCHEDULED 선반영
  - 경기 시간대: `cron(0/10 8-14 * * ? *)`(17:00~23:50 KST, 10분 간격) — LIVE/종료/취소 반영

함수를 나눈 이유: 운영 MySQL(데이터 EC2)은 VPC 안에서만 접근되므로 DB 잡 함수만
프라이빗 서브넷에 배치하고(외부 API는 기존 NAT로 아웃바운드), S3 잡 함수는 VPC 밖에
그대로 둬서 10분 주기 커뮤니티 트래픽이 NAT 요금을 물지 않게 + DB 자격증명을 격리.

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
>
> 이건 **최초 부트스트랩** 경로입니다. 이후 이미지 갱신은 CI 소관이니
> 아래 "코드 바꾼 뒤 재배포" 절을 보세요.

## 즉시 한 번 돌려보기 / 확인
```bash
# 수동 1회 실행
terraform output -raw invoke_community_now | bash
terraform output -raw invoke_game_now | bash        # 오늘(UTC) 경기; 백필은 payload에 "date" 추가

# DB 잡
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"registrations"}' --cli-binary-format raw-in-base64-out /dev/stdout
# 시즌 백필 (구간이 길면 타임아웃 840s — 보름~한 달 단위로 나눠 호출)
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records","from":"2026-03-28","to":"2026-04-15"}' \
  --cli-binary-format raw-in-base64-out --cli-read-timeout 900 /dev/stdout
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"games_sync"}' --cli-binary-format raw-in-base64-out /dev/stdout  # 백필은 "date" 추가

# 적재 확인
aws s3 ls s3://victoryfairy-crawl-local/community/ --recursive | tail
# 로그
aws logs tail /aws/lambda/kbo-collector --follow
```
스케줄은 apply 직후부터 동작합니다(community 10분마다, game 매일 03:00 KST).

## DB 잡 (records / registrations) 켜기

`terraform.tfvars` 에 DB 블록을 채우면 `kbo-collector-db` 함수 + 스케줄 + SG가 같이
생깁니다(비워두면 아무것도 안 만듦). 값 출처는 `terraform.tfvars.example` 주석 참고 —
서브넷/VPC/데이터 EC2 SG·프라이빗 IP는 **VictoryFairy_Infra(dev_infra)** 스택 소유라
infra 팀에서 받습니다. 데이터 EC2 SG에 "Lambda SG → 3306" 인바운드 규칙 하나를 이
스택이 추가한다는 점도 공유할 것(`lambda_db.tf` 주석).

**최초 1회 (배포 전):**

1. **레거시 스키마 마이그레이션** — 구 수집기 테이블(teams 가 team_code PK 면 구 스키마)이
   있으면 운영 MySQL 에 `../sql/migrate-legacy-collector.sql` 을 1회 실행.
   서비스 테이블(teams/players/games/game_lineups 등) 생성은 **dev_be 소관**
   (domain JPA 엔티티 + `VictoryFairy_BE/infra/sql/` 선행 SQL) — py-collector 는 DDL 사본을 갖지 않는다.
2. **KBO 사이트 접근 확인** — registrations 는 KBO 공식 사이트를 긁는다. AWS IP 차단 여부를
   먼저 확인하고, 차단이면 registrations 스케줄만 끄고 로컬에서 돌린다:
   ```bash
   terraform output -raw invoke_registrations_now | bash   # 응답의 registrations 가 [] 이 아니면 OK
   ```

**수동 실행 / 백필:**
```bash
terraform output -raw invoke_records_now | bash          # 오늘(UTC=KST 경기일) 완료 경기
terraform output -raw invoke_registrations_now | bash    # 현재 등록명단
# 시즌 백필 (예: 개막일부터)
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records","from":"2026-03-28","to":"2026-07-27"}' \
  --cli-binary-format raw-in-base64-out --cli-read-timeout 900 /dev/stdout
# 적재 확인
mysql ... -e 'SELECT COUNT(*) FROM games; SELECT COUNT(*) FROM game_lineups;'
```
> 백필 구간이 길면 Lambda 타임아웃(기본 840s)을 넘을 수 있다 — 한 달 단위 정도로 나눠 호출.
> 적재는 자연키 upsert 라 재실행·중복 호출이 무해하다.

## 코드 바꾼 뒤 재배포 — **CI가 합니다 (2026-08-05~)**

`dev_ai`가 main에 머지되면 `.github/workflows/deploy-collector.yml`이 이미지를 굽고
`kbo-collector`·`kbo-collector-db`를 함께 갱신합니다. **손으로 할 일은 없습니다.**

트리거 경로는 이미지에 실제로 구워지는 것만입니다 — `kbo_collector/**`, `config/**`,
`deploy/lambda/`의 `Dockerfile`·`handler.py`·`requirements.txt`. `tests/`·`docs/`·
`terraform/`만 고쳤다면 워크플로는 돌지 않습니다(맞는 동작입니다).

```bash
# 지금 도는 이미지가 어느 커밋인지
aws lambda get-function --function-name kbo-collector \
  --query 'Code.{tag:ImageUri,digest:ResolvedImageUri}'
```

### ⚠ apply는 최신 체크아웃에서만

`terraform apply`는 이제 **함수 설정(VPC/SG·환경변수·스케줄)** 담당이지만,
`null_resource.image`는 그대로 남아 있어 소스 해시가 바뀌었으면 **여전히 로컬에서
이미지를 굽고 `:latest`에 덮어씁니다.** 옛 체크아웃에서 apply하면 옛 코드가 `:latest`가
되고, `image_uri`가 그 다이제스트에 핀돼 있으므로 **함수가 뒤로 감깁니다.**

apply 전에 `git pull` 하세요. 되감겼다면 GitHub Actions에서 `deploy-collector.yml`을
`workflow_dispatch`로 한 번 돌리면 복구됩니다.

> `image_uri`를 `:latest` 태그가 아니라 그 태그가 가리키는 **다이제스트**
> (`@sha256:...`, `data.aws_ecr_image`로 조회)에 고정합니다. `:latest` 문자열로 고정하면
> 값이 안 바뀌어 Terraform이 갱신을 건너뛰기 때문입니다. CI가 SHA 태그와 함께 `:latest`도
> 갱신하는 이유가 이 핀을 맞춰 두기 위함입니다.

## 내리기 (과금 중단)
```bash
terraform destroy
```
> Lambda/EventBridge는 **호출당 과금**이라 유휴 비용이 거의 없지만, 완전히 정리하려면 destroy.
> ECR은 `force_delete=true`라 이미지가 있어도 삭제됩니다.

## 참고
- 크롤링 플로우: [`../../docs/crawl-flow.md`](../../docs/crawl-flow.md)
- Lambda/EventBridge는 **호출한 만큼만** 과금이라 상시 운영에 가장 저렴합니다.
